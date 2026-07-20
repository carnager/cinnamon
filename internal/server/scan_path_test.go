package server

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestLibraryForPath(t *testing.T) {
	app := &App{cfg: config.Config{Libraries: []config.Library{
		{ID: "movies", Path: "/nas/movies"},
		{ID: "tv", Path: "/nas/tv"},
		{ID: "kids", Path: "/nas/movies/kids"}, // nested: longest prefix should win
	}}}

	cases := []struct {
		path   string
		wantID string
		wantOK bool
	}{
		{"/nas/movies/The Matrix/movie.mkv", "movies", true},
		{"/nas/movies/kids/Up/movie.mkv", "kids", true},
		{"/nas/tv/Show/Season 01", "tv", true},
		{"/nas/movies", "movies", true},
		{"/nas/other/x.mkv", "", false},
		{"/nas/moviesX/x.mkv", "", false}, // must not prefix-match a sibling
	}
	for _, tc := range cases {
		lib, abs, ok := app.libraryForPath(tc.path)
		if ok != tc.wantOK || lib.ID != tc.wantID {
			t.Errorf("libraryForPath(%q) = (%q,%v), want (%q,%v)", tc.path, lib.ID, ok, tc.wantID, tc.wantOK)
		}
		if ok && abs != filepath.Clean(tc.path) {
			t.Errorf("libraryForPath(%q) abs = %q", tc.path, abs)
		}
	}
}

// Regression: notifications arriving while the scan lease is held must never
// be dropped. Before the queue, each notification spun in its own goroutine
// and gave up after 30 lease checks — during import storms some paths were
// silently lost and the new show never appeared in the library.
func TestScopedScanQueueSurvivesBusyLease(t *testing.T) {
	dir := t.TempDir()
	movie := filepath.Join(dir, "Queued Movie (2024)")
	if err := os.MkdirAll(movie, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(movie, "Queued Movie (2024).mkv"), []byte("v"), 0o644); err != nil {
		t.Fatal(err)
	}

	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	lib := config.Library{ID: "movies", Name: "Movies", Type: "movies", Path: dir}
	app := New(Options{
		Config: config.Config{Libraries: []config.Library{lib}, ScanTimeout: time.Minute},
		Log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		Store:  store,
		Auth:   auth.NewStore(db),
	})

	// Hold the global scan lease while the notification arrives, longer than
	// several of the worker's 2s lease retries.
	release, ok := media.TryStartScan()
	if !ok {
		t.Fatal("could not take scan lease")
	}
	app.enqueueScopedScan(lib, []string{movie})
	time.Sleep(5 * time.Second)
	release()

	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		items, err := store.ListItems(context.Background(), "movies", "", "", "", 0, 10, 0)
		if err == nil && len(items) == 1 {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("queued path was never scanned after the lease freed up")
}
