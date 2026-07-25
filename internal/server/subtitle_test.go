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

	"popcorn/internal/config"
	"popcorn/internal/media"
)

func subtitleRequest(id, query string) *http.Request {
	return subtitleRequestFormat(id, "2.vtt", query)
}

func subtitleRequestFormat(id, subtitle, query string) *http.Request {
	req := httptest.NewRequest(http.MethodGet, "/api/items/"+id+"/subtitles/"+subtitle+query, nil)
	req.SetPathValue("id", id)
	req.SetPathValue("subtitle", subtitle)
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

func TestSubtitleStreamsConversion(t *testing.T) {
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

func TestSubtitleSSAContentType(t *testing.T) {
	dir := t.TempDir()
	app, id := newSubtitleTestApp(t, dir)

	rec := httptest.NewRecorder()
	app.subtitle(rec, subtitleRequestFormat(id, "2.ass", ""))
	if rec.Code != http.StatusOK {
		t.Fatalf("subtitle = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Type"); got != "text/x-ssa; charset=utf-8" {
		t.Fatalf("Content-Type = %q, want text/x-ssa", got)
	}
}

// A leftover sidecar next to the video (from the removed pre-extraction
// pipeline) must not be served — conversions always come from the source.
func TestSubtitleIgnoresLeftoverSidecarFiles(t *testing.T) {
	dir := t.TempDir()
	app, id := newSubtitleTestApp(t, dir)
	if err := os.WriteFile(filepath.Join(dir, "movie.s2.de.vtt"), []byte("WEBVTT-SIDECAR"), 0o644); err != nil {
		t.Fatalf("write sidecar: %v", err)
	}

	rec := httptest.NewRecorder()
	app.subtitle(rec, subtitleRequest(id, ""))
	if got := rec.Body.String(); got != "WEBVTT-CONVERTED" {
		t.Fatalf("body = %q, want converted output", got)
	}
}

func TestSubtitleTranscodeArgsAccuratelyRebaseShiftedCues(t *testing.T) {
	args := subtitleTranscodeArgs("/media/movie.mkv", 7, 120.5, false)
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
	if !containsPair(args, "-c:s", "webvtt") || !containsPair(args, "-f", "webvtt") {
		t.Fatalf("args = %v, want a WebVTT conversion", args)
	}
}

// The web player renders ASS itself, so ".ass" must pass the original styling
// through rather than flatten it to WebVTT.
func TestSubtitleTranscodeArgsKeepSSA(t *testing.T) {
	args := subtitleTranscodeArgs("/media/movie.mkv", 3, 0, true)
	if !containsPair(args, "-c:s", "ass") || !containsPair(args, "-f", "ass") {
		t.Fatalf("args = %v, want an ASS conversion", args)
	}
	if slices.Contains(args, "webvtt") {
		t.Fatalf("args = %v, want no WebVTT conversion", args)
	}
}
