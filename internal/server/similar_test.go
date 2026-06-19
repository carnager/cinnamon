package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

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
