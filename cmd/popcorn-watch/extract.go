package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// The extractor pre-extracts embedded text subtitles into WebVTT sidecars
// next to the video ("Movie (2024).s2.de.vtt"). Demuxing a whole file for a
// subtitle track is far too slow to do at playback time over the network, so
// it happens here on the storage host at local disk speed, once, when the
// file appears. popcornd serves the sidecar directly and only falls back to
// on-the-fly conversion when none exists yet.
type extractor struct {
	ffmpeg  string
	ffprobe string
	log     *slog.Logger
	queue   chan string
}

var videoExtensions = map[string]bool{
	".mkv": true, ".mp4": true, ".m4v": true, ".mov": true,
	".avi": true, ".webm": true, ".ts": true, ".wmv": true,
}

func newExtractor(ffmpeg, ffprobe string, log *slog.Logger) *extractor {
	e := &extractor{ffmpeg: ffmpeg, ffprobe: ffprobe, log: log, queue: make(chan string, 4096)}
	go e.worker()
	return e
}

// enqueue accepts a video file or a directory (whose videos are enqueued).
// Non-blocking: during event storms a full queue drops work with a warning
// rather than stalling the notify path; the next event or backfill retries.
func (e *extractor) enqueue(path string) {
	info, err := os.Stat(path)
	if err != nil {
		return
	}
	if info.IsDir() {
		entries, err := os.ReadDir(path)
		if err != nil {
			return
		}
		for _, entry := range entries {
			if !entry.IsDir() && videoExtensions[strings.ToLower(filepath.Ext(entry.Name()))] {
				e.enqueueFile(filepath.Join(path, entry.Name()))
			}
		}
		return
	}
	if videoExtensions[strings.ToLower(filepath.Ext(path))] {
		e.enqueueFile(path)
	}
}

func (e *extractor) enqueueFile(path string) {
	select {
	case e.queue <- path:
	default:
		e.log.Warn("subtitle extraction queue full, skipping", "path", path)
	}
}

// backfill sweeps whole roots for videos with missing sidecars. Run in a
// goroutine; sends block so the walk paces itself to the worker.
func (e *extractor) backfill(roots []string) {
	for _, root := range roots {
		count := 0
		_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() {
				if path != root && ignored(path) {
					return filepath.SkipDir
				}
				return nil
			}
			if videoExtensions[strings.ToLower(filepath.Ext(path))] {
				e.queue <- path
				count++
			}
			return nil
		})
		e.log.Info("subtitle backfill queued", "root", root, "videos", count)
	}
}

func (e *extractor) worker() {
	for path := range e.queue {
		e.extractFile(path)
	}
}

type probedStream struct {
	Index       int    `json:"index"`
	CodecName   string `json:"codec_name"`
	CodecType   string `json:"codec_type"`
	Disposition struct {
		Forced int `json:"forced"`
	} `json:"disposition"`
	Tags struct {
		Language string `json:"language"`
	} `json:"tags"`
}

func isTextSubtitleCodec(codec string) bool {
	switch strings.ToLower(codec) {
	case "subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text":
		return true
	default:
		return false
	}
}

func (e *extractor) extractFile(path string) {
	videoInfo, err := os.Stat(path)
	if err != nil {
		return
	}
	streams, err := e.probeSubtitles(path)
	if err != nil {
		e.log.Warn("subtitle probe failed", "path", path, "error", err)
		return
	}
	for _, stream := range streams {
		sidecar := sidecarName(path, stream.Index, stream.Tags.Language, stream.Disposition.Forced != 0)
		if info, err := os.Stat(sidecar); err == nil && !info.ModTime().Before(videoInfo.ModTime()) {
			continue
		}
		if err := e.extractTrack(path, stream.Index, sidecar); err != nil {
			e.log.Warn("subtitle extraction failed", "path", path, "stream", stream.Index, "error", err)
			continue
		}
		e.log.Info("extracted subtitle sidecar", "sidecar", filepath.Base(sidecar), "stream", stream.Index)
	}
}

func (e *extractor) probeSubtitles(path string) ([]probedStream, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	out, err := exec.CommandContext(ctx, e.ffprobe, "-v", "error", "-show_streams", "-of", "json", path).Output()
	if err != nil {
		return nil, err
	}
	var parsed struct {
		Streams []probedStream `json:"streams"`
	}
	if err := json.Unmarshal(out, &parsed); err != nil {
		return nil, err
	}
	subs := parsed.Streams[:0]
	for _, stream := range parsed.Streams {
		if stream.CodecType == "subtitle" && isTextSubtitleCodec(stream.CodecName) {
			subs = append(subs, stream)
		}
	}
	return subs, nil
}

func (e *extractor) extractTrack(path string, index int, sidecar string) error {
	// Write to a dot-prefixed temp file (invisible to the watcher's event
	// filter) and rename, so a half-written sidecar is never served.
	tmp := filepath.Join(filepath.Dir(sidecar), "."+filepath.Base(sidecar)+".tmp")
	defer os.Remove(tmp)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, e.ffmpeg,
		"-y", "-hide_banner", "-loglevel", "error",
		"-i", path,
		"-map", "0:"+strconv.Itoa(index),
		"-c:s", "webvtt",
		"-f", "webvtt",
		tmp,
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		if msg := strings.TrimSpace(string(out)); msg != "" {
			e.log.Warn("ffmpeg output", "path", path, "stream", index, "output", msg)
		}
		return err
	}
	return os.Rename(tmp, sidecar)
}

var sidecarLangPattern = regexp.MustCompile(`^[a-z0-9]{1,8}$`)

// sidecarName builds "<base>.s<index>[.<lang>][.forced].vtt" — the stream
// index makes the mapping to the embedded track unambiguous for popcornd,
// the language tag keeps the file meaningful to humans and other players.
func sidecarName(videoPath string, index int, lang string, forced bool) string {
	base := strings.TrimSuffix(videoPath, filepath.Ext(videoPath))
	name := base + ".s" + strconv.Itoa(index)
	lang = strings.ToLower(strings.TrimSpace(lang))
	if lang != "" && lang != "und" && sidecarLangPattern.MatchString(lang) {
		name += "." + lang
	}
	if forced {
		name += ".forced"
	}
	return name + ".vtt"
}
