package server

import (
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

// The pacing exists for the real API's one-write-per-second rule; tests should
// not sit through it.
func withoutTraktPacing(t *testing.T) {
	t.Helper()
	previous := traktCollectionWriteDelay
	traktCollectionWriteDelay = 0
	t.Cleanup(func() { traktCollectionWriteDelay = previous })
}

func TestTraktCollectionSyncPostsOnlyWhatIsMissing(t *testing.T) {
	withoutTraktPacing(t)
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()
	res, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := res.LastInsertId()

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

	result, err := app.traktSyncCollection(ctx, userID, "token", false)
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

func TestTraktCollectionPruneOnlyTakesBackWhatPopcornSent(t *testing.T) {
	withoutTraktPacing(t)
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()
	res, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := res.LastInsertId()

	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/kept.mkv", Title: "Still Here", SortTitle: "still", Year: 2001, IMDbID: "tt0001", Height: 1080,
	}); err != nil {
		t.Fatal(err)
	}
	// Two entries popcorn sent earlier: one whose file is gone, and one that is
	// still in the library. Plus a title the user collected on Trakt themselves,
	// which popcorn never recorded and must never touch.
	if err := store.SaveTraktCollectionEntries(ctx, userID, map[string]string{
		"imdb:tt0001":     `{"kind":"movie","ids":{"imdb":"tt0001"},"title":"Still Here","year":2001}`,
		"imdb:tt0099":     `{"kind":"movie","ids":{"imdb":"tt0099"},"title":"Deleted Locally","year":1994}`,
		"night watch:1:1": `{"kind":"episode","show":"Night Watch","season":1,"episode":1}`,
	}, map[string]string{"imdb:tt0001": "movie", "imdb:tt0099": "movie", "night watch:1:1": "episode"}); err != nil {
		t.Fatal(err)
	}

	var removals []map[string]any
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sync/collection/movies":
			_, _ = w.Write([]byte(`[{"movie":{"title":"Still Here","year":2001,"ids":{"imdb":"tt0001"}}},{"movie":{"title":"Collected By Hand","year":1980,"ids":{"imdb":"tt0777"}}}]`))
		case "/sync/collection/shows":
			_, _ = w.Write([]byte(`[]`))
		case "/sync/collection":
			_, _ = w.Write([]byte(`{}`))
		case "/sync/collection/remove":
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("decode removal body: %v", err)
			}
			removals = append(removals, body)
			_, _ = w.Write([]byte(`{"deleted":{"movies":1}}`))
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

	result, err := app.traktSyncCollection(ctx, userID, "token", true)
	if err != nil {
		t.Fatalf("sync: %v", err)
	}
	if result.Removed != 2 {
		t.Fatalf("removed %d, want the deleted movie and the deleted episode", result.Removed)
	}
	movies := postedEntries(t, removals, "movies")
	if len(movies) != 1 {
		t.Fatalf("removed %d movies, want only the one popcorn sent that is gone", len(movies))
	}
	if ids := movies[0]["ids"].(map[string]any); ids["imdb"] != "tt0099" {
		t.Fatalf("removed the wrong movie: %#v", ids)
	}
	shows := postedEntries(t, removals, "shows")
	if len(shows) != 1 || shows[0]["title"] != "Night Watch" {
		t.Fatalf("removed shows = %#v", shows)
	}

	// The record now holds only what is still in the library.
	entries, err := store.TraktCollectionEntries(ctx, userID)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("record = %#v, want just the surviving movie", entries)
	}
	if _, ok := entries["imdb:tt0001"]; !ok {
		t.Fatalf("record lost the surviving movie: %#v", entries)
	}
}

func TestTraktCollectionSyncWaitsOutARateLimit(t *testing.T) {
	withoutTraktPacing(t)
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()
	res, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := res.LastInsertId()
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/one.mkv", Title: "One", SortTitle: "one", Year: 2001, IMDbID: "tt0001",
	}); err != nil {
		t.Fatal(err)
	}

	attempts := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sync/collection/movies", "/sync/collection/shows":
			_, _ = w.Write([]byte(`[]`))
		case "/sync/collection":
			attempts++
			if attempts == 1 {
				// Trakt answers a rate limit with an empty body, which is why
				// the first version of this reported nothing useful.
				w.Header().Set("Retry-After", "1")
				w.WriteHeader(http.StatusTooManyRequests)
				return
			}
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

	result, err := app.traktSyncCollection(ctx, userID, "token", false)
	if err != nil {
		t.Fatalf("sync gave up on a rate limit: %v", err)
	}
	if attempts != 2 {
		t.Fatalf("posted %d times, want a retry after the rate limit", attempts)
	}
	if result.Movies != 1 {
		t.Fatalf("result = %#v", result)
	}
}

func TestTraktCollectionSyncReportsTheStatus(t *testing.T) {
	withoutTraktPacing(t)
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()
	res, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := res.LastInsertId()
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/one.mkv", Title: "One", SortTitle: "one", IMDbID: "tt0001",
	}); err != nil {
		t.Fatal(err)
	}

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sync/collection/movies", "/sync/collection/shows":
			_, _ = w.Write([]byte(`[]`))
		default:
			w.WriteHeader(http.StatusUnauthorized)
		}
	}))
	defer upstream.Close()

	app := New(Options{
		Config: config.Config{TraktClientID: "id", TraktClientSecret: "secret", TraktAPIURL: upstream.URL},
		Store:  store,
		Log:    slog.New(slog.DiscardHandler),
	})
	t.Cleanup(app.Close)

	_, err = app.traktSyncCollection(ctx, userID, "token", false)
	if err == nil {
		t.Fatal("expected an error")
	}
	if !strings.Contains(err.Error(), "401") || !strings.Contains(err.Error(), "Unauthorized") {
		t.Fatalf("error = %q, want the status in it", err)
	}
}

func TestTraktCollectionStopsAtTheAccountLimit(t *testing.T) {
	withoutTraktPacing(t)
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()
	res, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := res.LastInsertId()
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/one.mkv", Title: "One", SortTitle: "one", IMDbID: "tt0001",
	}); err != nil {
		t.Fatal(err)
	}

	attempts := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sync/collection/movies", "/sync/collection/shows":
			_, _ = w.Write([]byte(`[]`))
		case "/sync/collection":
			attempts++
			// Trakt answers an account limit with 420 and an upgrade link, not
			// with a rate-limit response — retrying it can never work.
			w.Header().Set("X-Upgrade-URL", "https://trakt.tv/vip")
			w.WriteHeader(420)
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

	result, err := app.traktSyncCollection(ctx, userID, "token", false)
	if err != nil {
		t.Fatalf("account limit should be reported, not returned as an error: %v", err)
	}
	if !result.LimitReached || result.UpgradeURL != "https://trakt.tv/vip" {
		t.Fatalf("result = %#v, want the limit reported with its upgrade link", result)
	}
	if attempts != 1 {
		t.Fatalf("posted %d times, want no retry of an account limit", attempts)
	}
	if result.Movies != 0 {
		t.Fatalf("counted %d movies as collected, want none", result.Movies)
	}
	// Nothing landed, so nothing may be recorded — a later prune must not take
	// back entries that were never accepted.
	entries, err := store.TraktCollectionEntries(ctx, userID)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("recorded %#v despite the write failing", entries)
	}
}
