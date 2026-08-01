package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestTraktCollectionSyncPostsOnlyWhatIsMissing(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()

	for _, item := range []media.Item{
		{LibraryID: "movies", Kind: "movie", Path: "/movies/have.mkv", Title: "Already Collected", SortTitle: "already", Year: 2001, IMDbID: "tt0001", Height: 1080, AudioCodec: "ac3"},
		{LibraryID: "movies", Kind: "movie", Path: "/movies/new.mkv", Title: "Newly Ripped", SortTitle: "newly", Year: 2020, IMDbID: "tt0002", Height: 2160, AudioCodec: "truehd"},
		{LibraryID: "movies", Kind: "movie", Path: "/movies/noid.mkv", Title: "No External Id", SortTitle: "no external", Year: 1999},
		{LibraryID: "tv", Kind: "episode", Path: "/tv/show/s01e01.mkv", Title: "Show S01E01", SortTitle: "show 01 01", ShowTitle: "Night Watch", SeasonNumber: 1, EpisodeNumber: 1, Year: 2019},
		{LibraryID: "tv", Kind: "episode", Path: "/tv/show/s01e02.mkv", Title: "Show S01E02", SortTitle: "show 01 02", ShowTitle: "Night Watch", SeasonNumber: 1, EpisodeNumber: 2, Year: 2019},
	} {
		if err := store.UpsertItem(ctx, item); err != nil {
			t.Fatal(err)
		}
	}

	var posted []map[string]any
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sync/collection/movies":
			_, _ = w.Write([]byte(`[{"movie":{"title":"Already Collected","year":2001,"ids":{"imdb":"tt0001","tmdb":11}}}]`))
		case "/sync/collection/shows":
			_, _ = w.Write([]byte(`[{"show":{"title":"Night Watch"},"seasons":[{"number":1,"episodes":[{"number":1}]}]}]`))
		case "/sync/collection":
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("decode collection body: %v", err)
			}
			posted = append(posted, body)
			_, _ = w.Write([]byte(`{"added":{"movies":1}}`))
		default:
			t.Errorf("unexpected trakt path %s", r.URL.Path)
		}
	}))
	defer upstream.Close()

	app := New(Options{
		Config: config.Config{TraktClientID: "id", TraktClientSecret: "secret", TraktAPIURL: upstream.URL},
		Store:  store,
		Log:    slog.New(slog.DiscardHandler),
	})
	t.Cleanup(app.Close)

	result, err := app.traktSyncCollection(ctx, "token")
	if err != nil {
		t.Fatalf("sync: %v", err)
	}
	if result.Movies != 2 || result.AlreadyThere != 2 || result.Shows != 1 || result.Episodes != 1 {
		t.Fatalf("result = %#v, want the two uncollected movies and the one new episode", result)
	}

	movies := postedEntries(t, posted, "movies")
	if len(movies) != 2 {
		t.Fatalf("posted %d movies, want 2", len(movies))
	}
	byTitle := map[string]map[string]any{}
	for _, movie := range movies {
		title, _ := movie["title"].(string)
		if ids, ok := movie["ids"].(map[string]any); ok {
			if imdb, ok := ids["imdb"].(string); ok && imdb == "tt0002" {
				title = "Newly Ripped"
			}
		}
		byTitle[title] = movie
	}
	newly := byTitle["Newly Ripped"]
	if newly["resolution"] != "uhd_4k" || newly["audio"] != "dolby_truehd" || newly["media_type"] != "digital" {
		t.Fatalf("4K entry = %#v, want the copy described", newly)
	}
	// A movie with no external id still syncs, matched by title and year.
	noID := byTitle["No External Id"]
	if noID == nil || noID["year"].(float64) != 1999 {
		t.Fatalf("id-less entry = %#v, want title and year", noID)
	}

	shows := postedEntries(t, posted, "shows")
	if len(shows) != 1 {
		t.Fatalf("posted %d shows, want 1", len(shows))
	}
	seasons := shows[0]["seasons"].([]any)
	episodes := seasons[0].(map[string]any)["episodes"].([]any)
	if len(episodes) != 1 || episodes[0].(map[string]any)["number"].(float64) != 2 {
		t.Fatalf("posted episodes = %#v, want only the one Trakt lacks", episodes)
	}
}

func postedEntries(t *testing.T, posted []map[string]any, key string) []map[string]any {
	t.Helper()
	out := []map[string]any{}
	for _, body := range posted {
		rows, ok := body[key].([]any)
		if !ok {
			continue
		}
		for _, row := range rows {
			out = append(out, row.(map[string]any))
		}
	}
	return out
}
