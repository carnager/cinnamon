package media

import (
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
)

func writeTestPoster(t *testing.T, path string, c color.RGBA) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	img := image.NewRGBA(image.Rect(0, 0, 60, 90))
	for y := 0; y < 90; y++ {
		for x := 0; x < 60; x++ {
			img.Set(x, y, c)
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := jpeg.Encode(f, img, nil); err != nil {
		t.Fatal(err)
	}
}

// TestArtworkHashLifecycle walks the full loop the background pass runs:
// pending -> hash -> save -> no longer pending, and confirms the saved hash
// reaches callers through the normal item read path.
func TestArtworkHashLifecycle(t *testing.T) {
	store, ctx := newTestStore(t)
	dir := t.TempDir()
	poster := filepath.Join(dir, "movie-poster.jpg")
	writeTestPoster(t, poster, color.RGBA{R: 200, G: 40, B: 40, A: 255})

	item := upsertTestItem(t, ctx, store, Item{
		LibraryID:       "movies",
		Kind:            "movie",
		Title:           "Hashable",
		SortTitle:       "hashable",
		Path:            filepath.Join(dir, "movie.mkv"),
		PosterPath:      poster,
		PosterMTimeUnix: 1000,
	})

	pending, err := store.PendingArtworkHashes(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 || pending[0].Kind != "poster" || pending[0].Path != poster {
		t.Fatalf("unexpected pending set: %+v", pending)
	}

	results := HashArtworkTargets(ctx, pending, 2)
	if len(results) != 1 || results[0].Hash == "" {
		t.Fatalf("expected one non-empty hash, got %+v", results)
	}
	if err := store.SaveArtworkHashes(ctx, results); err != nil {
		t.Fatal(err)
	}

	pending, err = store.PendingArtworkHashes(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("still pending after save: %+v", pending)
	}

	got, err := store.GetItem(ctx, item.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.PosterThumbhash != results[0].Hash {
		t.Fatalf("item poster thumbhash = %q, want %q", got.PosterThumbhash, results[0].Hash)
	}
}

// TestArtworkHashRecomputesWhenArtworkChanges covers the staleness rule: the
// hash is tied to the artwork mtime it came from, so replacing a poster must
// put the item back in the pending set instead of leaving the old placeholder
// in place forever.
func TestArtworkHashRecomputesWhenArtworkChanges(t *testing.T) {
	store, ctx := newTestStore(t)
	dir := t.TempDir()
	poster := filepath.Join(dir, "poster.jpg")
	writeTestPoster(t, poster, color.RGBA{R: 200, G: 40, B: 40, A: 255})

	base := Item{
		LibraryID:       "movies",
		Kind:            "movie",
		Title:           "Replaced Art",
		SortTitle:       "replaced art",
		Path:            filepath.Join(dir, "movie.mkv"),
		PosterPath:      poster,
		PosterMTimeUnix: 1000,
	}
	upsertTestItem(t, ctx, store, base)

	pending, _ := store.PendingArtworkHashes(ctx, 100)
	if err := store.SaveArtworkHashes(ctx, HashArtworkTargets(ctx, pending, 1)); err != nil {
		t.Fatal(err)
	}

	// New artwork on disk, new mtime recorded by the scanner's upsert.
	writeTestPoster(t, poster, color.RGBA{R: 40, G: 40, B: 200, A: 255})
	base.PosterMTimeUnix = 2000
	upsertTestItem(t, ctx, store, base)

	pending, err := store.PendingArtworkHashes(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 {
		t.Fatalf("replaced artwork did not become pending again: %+v", pending)
	}
}

// TestArtworkHashTombstonesUndecodableArtwork pins the retry behaviour: a file
// that cannot be decoded must be recorded as attempted, not retried on every
// sweep forever.
func TestArtworkHashTombstonesUndecodableArtwork(t *testing.T) {
	store, ctx := newTestStore(t)
	dir := t.TempDir()
	broken := filepath.Join(dir, "not-an-image.jpg")
	if err := os.WriteFile(broken, []byte("this is not a jpeg"), 0o644); err != nil {
		t.Fatal(err)
	}

	upsertTestItem(t, ctx, store, Item{
		LibraryID:       "movies",
		Kind:            "movie",
		Title:           "Broken Art",
		SortTitle:       "broken art",
		Path:            filepath.Join(dir, "movie.mkv"),
		PosterPath:      broken,
		PosterMTimeUnix: 1000,
	})

	pending, _ := store.PendingArtworkHashes(ctx, 100)
	results := HashArtworkTargets(ctx, pending, 1)
	if len(results) != 1 || results[0].Hash != "" {
		t.Fatalf("expected an empty hash for undecodable artwork, got %+v", results)
	}
	if err := store.SaveArtworkHashes(ctx, results); err != nil {
		t.Fatal(err)
	}

	pending, err := store.PendingArtworkHashes(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("undecodable artwork was not tombstoned, still pending: %+v", pending)
	}
}

// TestHashArtworkTargetsSharesWorkAcrossItems is the reason the pass stays
// cheap on TV libraries: every episode of a series points at the same show
// poster, and that file must be decoded once, with the result reaching all of
// the rows that reference it.
func TestHashArtworkTargetsSharesWorkAcrossItems(t *testing.T) {
	store, ctx := newTestStore(t)
	dir := t.TempDir()
	poster := filepath.Join(dir, "show-poster.jpg")
	writeTestPoster(t, poster, color.RGBA{R: 30, G: 180, B: 90, A: 255})

	var ids []int64
	for i := 1; i <= 5; i++ {
		item := upsertTestItem(t, ctx, store, Item{
			LibraryID:       "tv",
			Kind:            "episode",
			Title:           "Episode",
			SortTitle:       "episode",
			Path:            filepath.Join(dir, "show", "s01e0"+string(rune('0'+i))+".mkv"),
			ShowTitle:       "Show",
			SeasonNumber:    1,
			EpisodeNumber:   i,
			PosterPath:      poster,
			PosterMTimeUnix: 1000,
		})
		ids = append(ids, item.ID)
	}

	pending, err := store.PendingArtworkHashes(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 5 {
		t.Fatalf("expected 5 pending targets, got %d", len(pending))
	}

	results := HashArtworkTargets(ctx, pending, 4)
	if len(results) != 1 {
		t.Fatalf("shared poster decoded %d times, want 1", len(results))
	}
	if err := store.SaveArtworkHashes(ctx, results); err != nil {
		t.Fatal(err)
	}

	for _, id := range ids {
		got, err := store.GetItem(ctx, id)
		if err != nil {
			t.Fatal(err)
		}
		if got.PosterThumbhash != results[0].Hash {
			t.Fatalf("item %d thumbhash = %q, want the shared hash %q", id, got.PosterThumbhash, results[0].Hash)
		}
	}
}

func TestHashArtworkFileMissing(t *testing.T) {
	if got := HashArtworkFile(filepath.Join(t.TempDir(), "nope.jpg")); got != "" {
		t.Fatalf("got %q, want empty", got)
	}
}

func TestHashArtworkTargetsHonoursCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	targets := []ArtworkHashTarget{{ItemID: 1, Kind: "poster", Path: "/nope/a.jpg", MTimeUnix: 1}}
	if got := HashArtworkTargets(ctx, targets, 2); len(got) != 0 {
		t.Fatalf("expected no results from a cancelled context, got %d", len(got))
	}
}
