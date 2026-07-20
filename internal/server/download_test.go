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

func newDownloadTestApp(t *testing.T, item media.Item) (*App, media.Item) {
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
		Config: config.Config{},
		Log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		Store:  store,
		Auth:   auth.NewStore(db),
	})
	return app, items[0]
}

func downloadRequest(id string, header map[string]string) *http.Request {
	req := httptest.NewRequest(http.MethodGet, "/api/items/"+id+"/download", nil)
	req.SetPathValue("id", id)
	for k, v := range header {
		req.Header.Set(k, v)
	}
	return req
}

func TestItemDownloadServesAttachmentWithRangeSupport(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "Der Räuber (2010).mkv")
	if err := os.WriteFile(video, []byte("0123456789"), 0o644); err != nil {
		t.Fatalf("write video: %v", err)
	}
	app, item := newDownloadTestApp(t, media.Item{
		LibraryID: "movies",
		Path:      video,
		Kind:      "movie",
		Title:     "Der Räuber",
		SortTitle: "rauber",
		SizeBytes: 10,
		MTimeUnix: 1234567,
	})
	id := strconv.FormatInt(item.ID, 10)

	rec := httptest.NewRecorder()
	app.itemDownload(rec, downloadRequest(id, nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("download = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if got := rec.Body.String(); got != "0123456789" {
		t.Fatalf("body = %q, want file content", got)
	}
	disposition := rec.Header().Get("Content-Disposition")
	if !strings.HasPrefix(disposition, "attachment") {
		t.Fatalf("Content-Disposition = %q, want attachment", disposition)
	}
	if !strings.Contains(strings.ToLower(disposition), "filename") {
		t.Fatalf("Content-Disposition = %q, want a filename", disposition)
	}
	if got := rec.Header().Get("Content-Type"); got != "application/octet-stream" {
		t.Fatalf("Content-Type = %q, want application/octet-stream", got)
	}

	rec = httptest.NewRecorder()
	app.itemDownload(rec, downloadRequest(id, map[string]string{"Range": "bytes=4-6"}))
	if rec.Code != http.StatusPartialContent {
		t.Fatalf("ranged download = %d, want %d", rec.Code, http.StatusPartialContent)
	}
	if got := rec.Body.String(); got != "456" {
		t.Fatalf("ranged body = %q, want \"456\"", got)
	}
}

func TestItemDownloadMissingFileReturnsNotFound(t *testing.T) {
	app, item := newDownloadTestApp(t, media.Item{
		LibraryID: "movies",
		Path:      filepath.Join(t.TempDir(), "gone.mkv"),
		Kind:      "movie",
		Title:     "Gone",
		SortTitle: "gone",
		SizeBytes: 10,
		MTimeUnix: 1234567,
	})

	rec := httptest.NewRecorder()
	app.itemDownload(rec, downloadRequest(strconv.FormatInt(item.ID, 10), nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("download of missing file = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestAttachmentDispositionEncodesNonASCII(t *testing.T) {
	got := attachmentDisposition("Der Räuber (2010).mkv")
	if !strings.HasPrefix(got, "attachment") {
		t.Fatalf("attachmentDisposition = %q, want attachment prefix", got)
	}
	if !strings.Contains(got, "filename") {
		t.Fatalf("attachmentDisposition = %q, want filename parameter", got)
	}
}
