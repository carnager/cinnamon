package main

import (
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSidecarName(t *testing.T) {
	cases := []struct {
		lang   string
		forced bool
		want   string
	}{
		{"de", false, "/m/Movie (2024).s2.de.vtt"},
		{"", false, "/m/Movie (2024).s2.vtt"},
		{"und", false, "/m/Movie (2024).s2.vtt"},
		{"GER", false, "/m/Movie (2024).s2.ger.vtt"},
		{"de", true, "/m/Movie (2024).s2.de.forced.vtt"},
		{"../evil", false, "/m/Movie (2024).s2.vtt"},
	}
	for _, tc := range cases {
		if got := sidecarName("/m/Movie (2024).mkv", 2, tc.lang, tc.forced); got != tc.want {
			t.Errorf("sidecarName(lang=%q forced=%v) = %q, want %q", tc.lang, tc.forced, got, tc.want)
		}
	}
}

// fakeProbeTools writes ffprobe/ffmpeg stand-ins: ffprobe reports one ASS
// stream at index 2 (German) and one PGS stream (image-based, skipped);
// ffmpeg writes a marker into its output file (the last argument).
func fakeProbeTools(t *testing.T) (string, string) {
	t.Helper()
	dir := t.TempDir()
	ffprobe := filepath.Join(dir, "ffprobe")
	probeScript := `#!/bin/sh
cat <<'EOF'
{"streams":[
 {"index":0,"codec_name":"h264","codec_type":"video"},
 {"index":2,"codec_name":"ass","codec_type":"subtitle","disposition":{"forced":0},"tags":{"language":"de"}},
 {"index":3,"codec_name":"hdmv_pgs_subtitle","codec_type":"subtitle","disposition":{"forced":0},"tags":{"language":"de"}}
]}
EOF
`
	if err := os.WriteFile(ffprobe, []byte(probeScript), 0o755); err != nil {
		t.Fatalf("write fake ffprobe: %v", err)
	}
	ffmpeg := filepath.Join(dir, "ffmpeg")
	ffmpegScript := "#!/bin/sh\nfor out; do :; done\nprintf 'WEBVTT-EXTRACTED' > \"$out\"\n"
	if err := os.WriteFile(ffmpeg, []byte(ffmpegScript), 0o755); err != nil {
		t.Fatalf("write fake ffmpeg: %v", err)
	}
	return ffmpeg, ffprobe
}

func TestExtractFileWritesSidecarOnceAndSkipsImageSubs(t *testing.T) {
	ffmpeg, ffprobe := fakeProbeTools(t)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	e := &extractor{ffmpeg: ffmpeg, ffprobe: ffprobe, log: log}

	dir := t.TempDir()
	video := filepath.Join(dir, "Movie (2024).mkv")
	if err := os.WriteFile(video, []byte("v"), 0o644); err != nil {
		t.Fatal(err)
	}

	e.extractFile(video)
	sidecar := filepath.Join(dir, "Movie (2024).s2.de.vtt")
	content, err := os.ReadFile(sidecar)
	if err != nil {
		t.Fatalf("sidecar not written: %v", err)
	}
	if string(content) != "WEBVTT-EXTRACTED" {
		t.Fatalf("sidecar content = %q", content)
	}
	entries, _ := os.ReadDir(dir)
	for _, entry := range entries {
		if entry.Name() != "Movie (2024).mkv" && entry.Name() != "Movie (2024).s2.de.vtt" {
			t.Fatalf("unexpected file in dir: %s", entry.Name())
		}
	}

	// A second pass must skip the up-to-date sidecar (fake ffmpeg would
	// rewrite the mtime, so compare before/after).
	info1, _ := os.Stat(sidecar)
	e.extractFile(video)
	info2, _ := os.Stat(sidecar)
	if !info1.ModTime().Equal(info2.ModTime()) {
		t.Fatalf("sidecar was re-extracted despite being current")
	}

	// A replaced (newer) video must trigger re-extraction.
	future := time.Now().Add(time.Hour)
	if err := os.Chtimes(video, future, future); err != nil {
		t.Fatal(err)
	}
	e.extractFile(video)
	info3, _ := os.Stat(sidecar)
	if info2.ModTime().Equal(info3.ModTime()) {
		t.Fatalf("sidecar was not re-extracted for a newer video")
	}
}

func TestEnqueueDirPicksVideosOnly(t *testing.T) {
	ffmpeg, ffprobe := fakeProbeTools(t)
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	e := &extractor{ffmpeg: ffmpeg, ffprobe: ffprobe, log: log, queue: make(chan string, 16)}

	dir := t.TempDir()
	for i, name := range []string{"a.mkv", "b.mp4", "poster.jpg", "movie.nfo"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(fmt.Sprint(i)), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	e.enqueue(dir)
	close(e.queue)
	var got []string
	for p := range e.queue {
		got = append(got, filepath.Base(p))
	}
	if len(got) != 2 {
		t.Fatalf("enqueued %v, want the two videos", got)
	}
}

func TestIgnoredSkipsSubtitleFiles(t *testing.T) {
	for _, path := range []string{"/m/Movie.s2.de.vtt", "/m/Movie.srt", "/m/Movie.ASS", "/m/.hidden", "/m/@eaDir"} {
		if !ignored(path) {
			t.Errorf("ignored(%q) = false, want true", path)
		}
	}
	for _, path := range []string{"/m/Movie.mkv", "/m/Movie.nfo", "/m/Season 1"} {
		if ignored(path) {
			t.Errorf("ignored(%q) = true, want false", path)
		}
	}
}

// End-to-end with the real tools when available: mux an ASS track into a real
// mkv, extract it, and check the sidecar is valid WebVTT.
func TestExtractFileRealFFmpeg(t *testing.T) {
	ffmpeg, err1 := exec.LookPath("ffmpeg")
	ffprobe, err2 := exec.LookPath("ffprobe")
	if err1 != nil || err2 != nil {
		t.Skip("ffmpeg/ffprobe not installed")
	}
	dir := t.TempDir()
	srt := filepath.Join(dir, "subs.srt")
	if err := os.WriteFile(srt, []byte("1\n00:00:01,000 --> 00:00:03,000\nHallo Welt\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	video := filepath.Join(dir, "Testfilm (2024).mkv")
	mux := exec.Command(ffmpeg, "-y", "-loglevel", "error",
		"-f", "lavfi", "-i", "color=black:s=64x64:d=4",
		"-i", srt,
		"-map", "0:v", "-map", "1:s",
		"-c:v", "libx264", "-preset", "ultrafast",
		"-c:s", "ass", "-metadata:s:s:0", "language=ger",
		video)
	if out, err := mux.CombinedOutput(); err != nil {
		t.Fatalf("mux test mkv: %v: %s", err, out)
	}
	if err := os.Remove(srt); err != nil {
		t.Fatal(err)
	}

	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	e := &extractor{ffmpeg: ffmpeg, ffprobe: ffprobe, log: log}
	e.extractFile(video)

	sidecar := filepath.Join(dir, "Testfilm (2024).s1.ger.vtt")
	content, err := os.ReadFile(sidecar)
	if err != nil {
		entries, _ := os.ReadDir(dir)
		names := make([]string, 0, len(entries))
		for _, entry := range entries {
			names = append(names, entry.Name())
		}
		t.Fatalf("sidecar not written: %v (dir: %v)", err, names)
	}
	text := string(content)
	if !strings.HasPrefix(text, "WEBVTT") || !strings.Contains(text, "Hallo Welt") {
		t.Fatalf("sidecar is not the expected WebVTT: %q", text)
	}
}

func TestSiblingToolFindsExecutableNextToBinary(t *testing.T) {
	self, err := os.Executable()
	if err != nil {
		t.Skip("no executable path")
	}
	dir := filepath.Dir(self)
	tool := filepath.Join(dir, "fake-sibling-tool")
	if err := os.WriteFile(tool, []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Skipf("cannot write next to test binary: %v", err)
	}
	defer os.Remove(tool)
	if got := siblingTool("fake-sibling-tool"); got != tool {
		t.Fatalf("siblingTool = %q, want %q", got, tool)
	}
	if got := siblingTool("does-not-exist"); got != "" {
		t.Fatalf("siblingTool(missing) = %q, want empty", got)
	}
}
