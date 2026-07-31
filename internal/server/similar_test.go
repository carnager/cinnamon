package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestSimilarItemsIntersectsLibraryInRankedOrder(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()

	upsert := func(title, tmdbID string) {
		if err := store.UpsertItem(ctx, media.Item{
			LibraryID: "movies",
			Path:      "/m/" + tmdbID + ".mkv",
			Kind:      "movie",
			Title:     title,
			SortTitle: title,
			TMDbID:    tmdbID,
		}); err != nil {
			t.Fatal(err)
		}
	}
	upsert("Source", "603")
	upsert("Related A", "604")
	upsert("Related B", "605")
	// 10000 and 700 intentionally NOT in the library.

	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var source media.Item
	for _, it := range items {
		if it.TMDbID == "603" {
			source = it
		}
	}
	if source.ID == 0 {
		t.Fatal("source item not found")
	}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/3/movie/603/recommendations":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"results": []map[string]any{{"id": 604}, {"id": 10000}, {"id": 605}, {"id": 603}},
			})
		case "/3/movie/603/similar":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"results": []map[string]any{{"id": 605}, {"id": 700}},
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer ts.Close()
	withTMDbBaseURL(t, ts.URL)

	app := New(Options{Config: config.Config{TMDbReadToken: "test"}, Store: store})
	got := app.similarItems(ctx, source)

	gotTMDb := make([]string, len(got))
	for i, it := range got {
		gotTMDb[i] = it.TMDbID
	}
	want := []string{"604", "605"} // ranked, in-library, excluding the source itself
	if len(gotTMDb) != len(want) {
		t.Fatalf("similar = %v, want %v", gotTMDb, want)
	}
	for i := range want {
		if gotTMDb[i] != want[i] {
			t.Fatalf("similar = %v, want %v", gotTMDb, want)
		}
	}
}

func TestHomeRecommendationsDoNotWaitForTMDb(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	ctx := context.Background()
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies",
		Path:      "/m/source.mkv",
		Kind:      "movie",
		Title:     "Source",
		SortTitle: "Source",
		TMDbID:    "603",
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies",
		Path:      "/m/related.mkv",
		Kind:      "movie",
		Title:     "Related",
		SortTitle: "Related",
		TMDbID:    "604",
	}); err != nil {
		t.Fatal(err)
	}
	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("items = %v, error = %v", items, err)
	}
	var source media.Item
	for _, item := range items {
		if item.TMDbID == "603" {
			source = item
		}
	}
	if source.ID == 0 {
		t.Fatal("source item not found")
	}

	started := make(chan struct{})
	release := make(chan struct{})
	var startedOnce sync.Once
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startedOnce.Do(func() { close(started) })
		select {
		case <-release:
			_ = json.NewEncoder(w).Encode(map[string]any{
				"page":        1,
				"total_pages": 1,
				"results":     []map[string]any{{"id": 604}},
			})
		case <-r.Context().Done():
		}
	}))
	defer ts.Close()
	withTMDbBaseURL(t, ts.URL)

	app := New(Options{Config: config.Config{TMDbReadToken: "test"}, Store: store})
	defer app.Close()
	start := time.Now()
	_, err = app.homeRecommendations(ctx, 1, nil, nil, homePayload{
		Progress: []media.PlaybackProgress{{
			ItemID:     source.ID,
			PositionMS: 7_200_000,
			DurationMS: 7_200_000,
			Completed:  true,
		}},
	})
	if err != nil {
		close(release)
		t.Fatal(err)
	}
	if elapsed := time.Since(start); elapsed > 250*time.Millisecond {
		close(release)
		t.Fatalf("home recommendations waited %s for TMDb", elapsed)
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("TMDb refresh was not queued")
	}
	close(release)
	deadline := time.Now().Add(time.Second)
	for {
		if _, ready := app.readySimilarItems(source.ID); ready {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("queued TMDb result was not stored")
		}
		time.Sleep(5 * time.Millisecond)
	}
}
