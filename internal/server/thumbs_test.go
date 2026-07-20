package server

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestNormalizeThumbWidth(t *testing.T) {
	cases := []struct {
		raw  string
		want int
	}{
		{"", 0},
		{"abc", 0},
		{"-1", 0},
		{"0", 0},
		{"1", 400},
		{"400", 400},
		{"401", 800},
		{"800", 800},
		{"5000", 800},
	}
	for _, tc := range cases {
		if got := normalizeThumbWidth(tc.raw); got != tc.want {
			t.Errorf("normalizeThumbWidth(%q) = %d, want %d", tc.raw, got, tc.want)
		}
	}
}

// fakeFFmpeg writes a script that prints THUMB into its last argument (the
// output file), standing in for a real ffmpeg resize.
func fakeFFmpeg(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "ffmpeg")
	script := "#!/bin/sh\nfor out; do :; done\nprintf THUMB > \"$out\"\n"
	if err := os.WriteFile(path, []byte(script), 0o755); err != nil {
		t.Fatalf("write fake ffmpeg: %v", err)
	}
	return path
}

func newThumbTestApp(t *testing.T, cfg config.Config, item media.Item) (*App, media.Item) {
	t.Helper()
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Fatalf("close database: %v", err)
		}
	})
	store := media.NewStore(db)
	if err := store.UpsertItem(context.Background(), item); err != nil {
		t.Fatalf("upsert item: %v", err)
	}
	items, err := store.ListItems(context.Background(), item.LibraryID, "", "", "", 0, 10, 0)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(items))
	}
	app := New(Options{
		Config: cfg,
		Log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		Store:  store,
		Auth:   auth.NewStore(db),
	})
	return app, items[0]
}

func imageRequest(id, query string) *http.Request {
	req := httptest.NewRequest(http.MethodGet, "/api/items/"+id+"/image/poster"+query, nil)
	req.SetPathValue("id", id)
	req.SetPathValue("kind", "poster")
	return req
}

func thumbTestItem(dir string) media.Item {
	return media.Item{
		LibraryID:  "movies",
		Path:       filepath.Join(dir, "movie.mkv"),
		Kind:       "movie",
		Title:      "Movie",
		SortTitle:  "movie",
		PosterPath: filepath.Join(dir, "poster.jpg"),
		MTimeUnix:  1234567,
	}
}

func TestImageWidthServesGeneratedThumbnail(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "poster.jpg"), []byte("FULLSIZE"), 0o644); err != nil {
		t.Fatalf("write poster: %v", err)
	}
	ffmpeg := fakeFFmpeg(t)
	cfg := config.Config{
		DatabasePath: filepath.Join(t.TempDir(), "data", "popcorn.db"),
		FFmpegPath:   ffmpeg,
	}
	if err := os.MkdirAll(filepath.Dir(cfg.DatabasePath), 0o755); err != nil {
		t.Fatalf("make data dir: %v", err)
	}
	app, item := newThumbTestApp(t, cfg, thumbTestItem(dir))
	id := strconv.FormatInt(item.ID, 10)

	rec := httptest.NewRecorder()
	app.image(rec, imageRequest(id, "?w=400"))
	if rec.Code != http.StatusOK {
		t.Fatalf("image = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if got := rec.Body.String(); got != "THUMB" {
		t.Fatalf("body = %q, want generated thumbnail", got)
	}
	if cc := rec.Header().Get("Cache-Control"); !strings.Contains(cc, "immutable") {
		t.Fatalf("Cache-Control = %q, want immutable", cc)
	}

	// A second request must come from the cache: removing the fake ffmpeg
	// makes regeneration impossible, so only a cache hit can return THUMB.
	if err := os.Remove(ffmpeg); err != nil {
		t.Fatalf("remove fake ffmpeg: %v", err)
	}
	rec = httptest.NewRecorder()
	app.image(rec, imageRequest(id, "?w=400"))
	if got := rec.Body.String(); got != "THUMB" {
		t.Fatalf("cached body = %q, want cached thumbnail", got)
	}
}

func TestImageWithoutWidthServesOriginal(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "poster.jpg"), []byte("FULLSIZE"), 0o644); err != nil {
		t.Fatalf("write poster: %v", err)
	}
	cfg := config.Config{
		DatabasePath: filepath.Join(t.TempDir(), "popcorn.db"),
		FFmpegPath:   fakeFFmpeg(t),
	}
	app, item := newThumbTestApp(t, cfg, thumbTestItem(dir))

	rec := httptest.NewRecorder()
	app.image(rec, imageRequest(strconv.FormatInt(item.ID, 10), ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("image = %d, want %d", rec.Code, http.StatusOK)
	}
	if got := rec.Body.String(); got != "FULLSIZE" {
		t.Fatalf("body = %q, want original poster", got)
	}
}

func TestImageWidthFallsBackToOriginalWhenFFmpegFails(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "poster.jpg"), []byte("FULLSIZE"), 0o644); err != nil {
		t.Fatalf("write poster: %v", err)
	}
	cfg := config.Config{
		DatabasePath: filepath.Join(t.TempDir(), "popcorn.db"),
		FFmpegPath:   filepath.Join(dir, "missing-ffmpeg"),
	}
	app, item := newThumbTestApp(t, cfg, thumbTestItem(dir))

	rec := httptest.NewRecorder()
	app.image(rec, imageRequest(strconv.FormatInt(item.ID, 10), "?w=400"))
	if rec.Code != http.StatusOK {
		t.Fatalf("image = %d, want %d", rec.Code, http.StatusOK)
	}
	if got := rec.Body.String(); got != "FULLSIZE" {
		t.Fatalf("body = %q, want original poster when ffmpeg is unavailable", got)
	}
}
