package server

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"testing"
	"time"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

func subtitleRequest(id, query string) *http.Request {
	req := httptest.NewRequest(http.MethodGet, "/api/items/"+id+"/subtitles/2.vtt"+query, nil)
	req.SetPathValue("id", id)
	req.SetPathValue("subtitle", "2.vtt")
	return req
}

// fakeSubtitleFFmpeg writes a marker VTT to stdout, standing in for a real
// stream extraction.
func fakeSubtitleFFmpeg(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "ffmpeg")
	script := "#!/bin/sh\nprintf 'WEBVTT-CONVERTED'\n"
	if err := os.WriteFile(path, []byte(script), 0o755); err != nil {
		t.Fatalf("write fake ffmpeg: %v", err)
	}
	return path
}

func newSubtitleTestApp(t *testing.T, dir string) (*App, string) {
	t.Helper()
	video := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(video, []byte("fake video"), 0o644); err != nil {
		t.Fatalf("write video: %v", err)
	}
	cfg := config.Config{FFmpegPath: fakeSubtitleFFmpeg(t)}
	app, item := newThumbTestApp(t, cfg, media.Item{
		LibraryID:  "movies",
		Path:       video,
		Kind:       "movie",
		Title:      "Movie",
		SortTitle:  "movie",
		MTimeUnix:  1234567,
		DurationMS: 60_000,
	})
	streams := []media.MediaStream{
		{ItemID: item.ID, Index: 0, Type: "video", Codec: "h264"},
		{ItemID: item.ID, Index: 2, Type: "subtitle", Codec: "ass"},
	}
	if err := app.store.ReplaceMediaStreams(context.Background(), item.ID, streams); err != nil {
		t.Fatalf("replace streams: %v", err)
	}
	return app, strconv.FormatInt(item.ID, 10)
}

func TestSubtitleServesSidecarWhenPresent(t *testing.T) {
	dir := t.TempDir()
	app, id := newSubtitleTestApp(t, dir)
	sidecar := filepath.Join(dir, "movie.s2.de.vtt")
	if err := os.WriteFile(sidecar, []byte("WEBVTT-SIDECAR"), 0o644); err != nil {
		t.Fatalf("write sidecar: %v", err)
	}

	rec := httptest.NewRecorder()
	app.subtitle(rec, subtitleRequest(id, ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("subtitle = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if got := rec.Body.String(); got != "WEBVTT-SIDECAR" {
		t.Fatalf("body = %q, want sidecar content", got)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "text/vtt; charset=utf-8" {
		t.Fatalf("Content-Type = %q", ct)
	}
}

func TestSubtitleIgnoresStaleSidecar(t *testing.T) {
	dir := t.TempDir()
	app, id := newSubtitleTestApp(t, dir)
	sidecar := filepath.Join(dir, "movie.s2.vtt")
	if err := os.WriteFile(sidecar, []byte("WEBVTT-STALE"), 0o644); err != nil {
		t.Fatalf("write sidecar: %v", err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(sidecar, old, old); err != nil {
		t.Fatalf("age sidecar: %v", err)
	}

	rec := httptest.NewRecorder()
	app.subtitle(rec, subtitleRequest(id, ""))
	if got := rec.Body.String(); got != "WEBVTT-CONVERTED" {
		t.Fatalf("body = %q, want on-the-fly conversion for stale sidecar", got)
	}
}

func TestSubtitleStreamsConversionWithoutSidecar(t *testing.T) {
	dir := t.TempDir()
	app, id := newSubtitleTestApp(t, dir)

	rec := httptest.NewRecorder()
	app.subtitle(rec, subtitleRequest(id, ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("subtitle = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if got := rec.Body.String(); got != "WEBVTT-CONVERTED" {
		t.Fatalf("body = %q, want converted output", got)
	}
	if !rec.Flushed {
		t.Fatalf("response was not flushed while streaming")
	}
}

func TestSubtitleSkipsSidecarForShiftedStart(t *testing.T) {
	dir := t.TempDir()
	app, id := newSubtitleTestApp(t, dir)
	if err := os.WriteFile(filepath.Join(dir, "movie.s2.de.vtt"), []byte("WEBVTT-SIDECAR"), 0o644); err != nil {
		t.Fatalf("write sidecar: %v", err)
	}

	rec := httptest.NewRecorder()
	app.subtitle(rec, subtitleRequest(id, "?start=30"))
	if got := rec.Body.String(); got != "WEBVTT-CONVERTED" {
		t.Fatalf("body = %q, want conversion for shifted start", got)
	}
}

func TestSubtitleTranscodeArgsAccuratelyRebaseShiftedCues(t *testing.T) {
	args := subtitleTranscodeArgs("/media/movie.mkv", 7, 120.5)
	input := slices.Index(args, "-i")
	seek := slices.Index(args, "-ss")
	if input < 0 || seek < input {
		t.Fatalf("args = %v, want accurate -ss after -i", args)
	}
	if !containsPair(args, "-ss", "120.500") {
		t.Fatalf("args = %v, want output seek at 120.500", args)
	}
	if !containsPair(args, "-output_ts_offset", "-120.500") {
		t.Fatalf("args = %v, want cues rebased by -120.500", args)
	}
	if !containsPair(args, "-map", "0:7") {
		t.Fatalf("args = %v, want subtitle stream 7", args)
	}
}
