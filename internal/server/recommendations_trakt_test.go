package server

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestTraktRecommendationExclusionUsesHiddenRecommendationsSection(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	result, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := result.LastInsertId()
	if err := store.SaveTraktAccount(context.Background(), media.TraktAccount{
		UserID: userID, AccessToken: "token", RefreshToken: "refresh", ExpiresAt: "2099-01-01T00:00:00Z",
	}); err != nil {
		t.Fatal(err)
	}

	type request struct {
		path string
		body map[string]any
	}
	requests := make(chan request, 2)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode request: %v", err)
		}
		requests <- request{path: r.URL.Path, body: body}
		_ = json.NewEncoder(w).Encode(map[string]any{"added": map[string]any{"shows": 1}})
	}))
	defer upstream.Close()

	app := New(Options{
		Config: config.Config{
			TraktClientID:     "client",
			TraktClientSecret: "secret",
			TraktAPIURL:       upstream.URL,
		},
		Store: store,
		Log:   slog.New(slog.DiscardHandler),
	})
	t.Cleanup(app.Close)
	show := media.ShowSummary{LibraryID: "tv", Title: "Night Watch", Year: 2024}
	app.traktSyncRecommendationExclusionShows(userID, []media.ShowSummary{show}, false)
	app.traktSyncRecommendationExclusionShows(userID, []media.ShowSummary{show}, true)

	add := <-requests
	remove := <-requests
	if add.path != "/users/hidden/recommendations" {
		t.Fatalf("add path = %q", add.path)
	}
	if remove.path != "/users/hidden/recommendations/remove" {
		t.Fatalf("remove path = %q", remove.path)
	}
	shows, ok := add.body["shows"].([]any)
	if !ok || len(shows) != 1 {
		t.Fatalf("add body = %#v", add.body)
	}
}

// A show whose local title is a translation must go to Trakt by the IDs in
// its tvshow.nfo; Trakt answers a title miss with a 2xx and not_found.
func TestTraktShowEntryUsesTVShowNFOIDs(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	nfo := filepath.Join(t.TempDir(), "tvshow.nfo")
	writeTestFile(t, nfo, `<?xml version="1.0" encoding="UTF-8"?>
<tvshow>
  <title>Haus des Geldes</title>
  <uniqueid type="tmdb" default="true">71446</uniqueid>
  <uniqueid type="imdb">tt6468322</uniqueid>
  <uniqueid type="tvdb">327417</uniqueid>
</tvshow>`)
	if _, err := store.DB().Exec(`INSERT INTO media_shows(library_id, show_title, sort_title, year, nfo_path) VALUES ('tv', 'Haus des Geldes', 'haus des geldes', 2017, ?)`, nfo); err != nil {
		t.Fatal(err)
	}
	app := New(Options{Store: store, Log: slog.New(slog.DiscardHandler)})
	t.Cleanup(app.Close)

	entry := app.traktShowEntry(context.Background(), media.ShowSummary{LibraryID: "tv", Title: "Haus des Geldes", Year: 2017})
	ids, ok := entry["ids"].(map[string]any)
	if !ok || ids["tmdb"] != 71446 || ids["imdb"] != "tt6468322" || ids["tvdb"] != 327417 {
		t.Fatalf("expected NFO ids, got %#v", entry)
	}
	if _, hasTitle := entry["title"]; hasTitle {
		t.Fatalf("title must not be sent alongside ids: %#v", entry)
	}

	fallback := app.traktShowEntry(context.Background(), media.ShowSummary{LibraryID: "tv", Title: "No NFO Show", Year: 2020})
	if fallback["title"] != "No NFO Show" || fallback["year"] != 2020 {
		t.Fatalf("expected title fallback, got %#v", fallback)
	}
}

func TestWarnTraktNotFoundLogsMisses(t *testing.T) {
	var buf bytes.Buffer
	app := &App{log: slog.New(slog.NewTextHandler(&buf, nil))}
	app.warnTraktNotFound("watchlist sync", "add", []byte(`{"added":{"movies":0},"not_found":{"movies":[],"shows":[{"title":"Haus des Geldes"}]}}`))
	if !strings.Contains(buf.String(), "level=WARN") || !strings.Contains(buf.String(), "Haus des Geldes") {
		t.Fatalf("expected a warning naming the miss, got %q", buf.String())
	}
	buf.Reset()
	app.warnTraktNotFound("watchlist sync", "add", []byte(`{"added":{"movies":1},"not_found":{"movies":[],"shows":[]}}`))
	if buf.Len() != 0 {
		t.Fatalf("expected no log for a clean response, got %q", buf.String())
	}
}

// Episodes without IDs of their own must be nested under their show (named by
// tvshow.nfo IDs) - the sync API ignores show+number inside "episodes".
func TestTraktItemsBodyNestsIDlessEpisodesUnderShowIDs(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	nfo := filepath.Join(t.TempDir(), "tvshow.nfo")
	writeTestFile(t, nfo, `<tvshow><title>Stilles Wasser</title><uniqueid type="tvdb">123456</uniqueid></tvshow>`)
	if _, err := store.DB().Exec(`INSERT INTO media_shows(library_id, show_title, sort_title, nfo_path) VALUES ('tv', 'Stilles Wasser', 'stilles wasser', ?)`, nfo); err != nil {
		t.Fatal(err)
	}
	app := New(Options{Store: store, Log: slog.New(slog.DiscardHandler)})
	t.Cleanup(app.Close)

	items := []media.Item{
		{ID: 1, Kind: "movie", Title: "Film", IMDbID: "tt0000001"},
		{ID: 2, Kind: "episode", LibraryID: "tv", ShowTitle: "Other", SeasonNumber: 1, EpisodeNumber: 1, TVDbID: "999"},
		{ID: 3, Kind: "episode", LibraryID: "tv", ShowTitle: "Stilles Wasser", SeasonNumber: 1, EpisodeNumber: 2},
		{ID: 4, Kind: "episode", LibraryID: "tv", ShowTitle: "Stilles Wasser", SeasonNumber: 1, EpisodeNumber: 3},
		{ID: 5, Kind: "episode", LibraryID: "tv", ShowTitle: "Stilles Wasser", SeasonNumber: 2, EpisodeNumber: 1},
	}
	body := app.traktItemsBody(context.Background(), items, map[string]any{"rating": 8}, "test")
	raw, _ := json.Marshal(body)
	var got struct {
		Movies   []map[string]any `json:"movies"`
		Episodes []map[string]any `json:"episodes"`
		Shows    []struct {
			IDs     map[string]any `json:"ids"`
			Title   string         `json:"title"`
			Seasons []struct {
				Number   int              `json:"number"`
				Episodes []map[string]any `json:"episodes"`
			} `json:"seasons"`
		} `json:"shows"`
	}
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Movies) != 1 || got.Movies[0]["rating"] != float64(8) {
		t.Fatalf("movies = %#v", got.Movies)
	}
	if len(got.Episodes) != 1 || got.Episodes[0]["show"] != nil {
		t.Fatalf("episodes with ids = %#v", got.Episodes)
	}
	if len(got.Shows) != 1 || got.Shows[0].IDs["tvdb"] != float64(123456) || got.Shows[0].Title != "" {
		t.Fatalf("shows = %s", raw)
	}
	seasons := got.Shows[0].Seasons
	if len(seasons) != 2 || seasons[0].Number != 1 || len(seasons[0].Episodes) != 2 || seasons[1].Number != 2 || seasons[0].Episodes[1]["number"] != float64(3) || seasons[0].Episodes[1]["rating"] != float64(8) {
		t.Fatalf("seasons = %s", raw)
	}
}
