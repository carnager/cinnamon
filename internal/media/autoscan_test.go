package media

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"popcorn/internal/config"
	"popcorn/internal/database"
)

// The reconciliation sweep is the safety net for lost watcher notifications:
// with autoScan off it must still import media on its own within an interval.
func TestReconcileSweepImportsWithoutEvents(t *testing.T) {
	libDir := t.TempDir()
	movie := filepath.Join(libDir, "Missed Movie (2024)")
	if err := os.MkdirAll(movie, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(movie, "Missed Movie (2024).mkv"), []byte("v"), 0o644); err != nil {
		t.Fatal(err)
	}

	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := NewStore(db)
	cfg := config.Config{
		Libraries:         []config.Library{{ID: "movies", Name: "Movies", Type: "movies", Path: libDir}},
		ScanTimeout:       time.Minute,
		ReconcileInterval: 100 * time.Millisecond,
		AutoScan:          false,
		ScanOnStart:       false,
	}
	auto := NewAutoScanner(cfg, store, slog.New(slog.NewTextHandler(io.Discard, nil)))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go auto.Run(ctx)

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		items, err := store.ListItems(context.Background(), "movies", "", "", "", 0, 10, 0)
		if err == nil && len(items) == 1 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("reconciliation sweep never imported the movie")
}
