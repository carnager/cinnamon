package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// thumbWidths lists the only sizes the server will generate. Requests snap up
// to the nearest bucket so arbitrary ?w= values cannot blow up the cache.
var thumbWidths = []int{200, 400, 800}

// thumbMaxAge bounds cache growth: artwork churn leaves orphaned entries
// behind (the cache key includes the source mtime), so anything this old is
// swept on startup. Live thumbnails regenerate lazily on the next request.
const thumbMaxAge = 180 * 24 * time.Hour

func normalizeThumbWidth(raw string) int {
	if raw == "" {
		return 0
	}
	width, err := strconv.Atoi(raw)
	if err != nil || width <= 0 {
		return 0
	}
	for _, bucket := range thumbWidths {
		if width <= bucket {
			return bucket
		}
	}
	return thumbWidths[len(thumbWidths)-1]
}

// thumbCacheDir returns the directory for resized artwork. Like the HLS
// scratch dir it lives next to the database on real disk so thumbnails
// survive reboots instead of vanishing with a RAM-backed /tmp.
func (a *App) thumbCacheDir() string {
	if a.cfg.DatabasePath == "" {
		return ""
	}
	dir := filepath.Join(filepath.Dir(a.cfg.DatabasePath), "thumb-cache")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		a.log.Warn("thumb cache dir unavailable, serving full-size images", "dir", dir, "error", err)
		return ""
	}
	return dir
}

// thumbnail returns a cached reduced copy of src at the given width,
// generating it with ffmpeg on first use. It returns "" whenever the original
// should be served instead (no cache dir, ffmpeg failure, source vanished).
func (a *App) thumbnail(ctx context.Context, src string, width int) string {
	dir := a.thumbCacheDir()
	if dir == "" {
		return ""
	}
	info, err := os.Stat(src)
	if err != nil {
		return ""
	}
	dst := filepath.Join(dir, thumbCacheName(src, info, width))
	if _, err := os.Stat(dst); err == nil {
		return dst
	}
	// Generate into a temp file and rename so concurrent requests for the
	// same image never observe a half-written thumbnail; the losing writer
	// just overwrites the winner's identical output.
	tmp, err := os.CreateTemp(dir, "gen-*"+filepath.Ext(dst))
	if err != nil {
		return ""
	}
	tmp.Close()
	defer os.Remove(tmp.Name())
	genCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(genCtx, a.cfg.FFmpegPath,
		"-y", "-loglevel", "error",
		"-i", src,
		"-frames:v", "1",
		"-vf", fmt.Sprintf("scale=min(%d\\,iw):-2", width),
		"-q:v", "4",
		tmp.Name(),
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		a.log.Warn("thumbnail generation failed, serving original",
			"src", src, "error", err, "output", strings.TrimSpace(string(out)))
		return ""
	}
	if err := os.Rename(tmp.Name(), dst); err != nil {
		return ""
	}
	return dst
}

// thumbCacheName keys on path, mtime, size and width so a replaced poster
// gets a fresh thumbnail. PNG stays PNG to keep transparency; everything
// else becomes JPEG.
func thumbCacheName(src string, info os.FileInfo, width int) string {
	sum := sha256.Sum256(fmt.Appendf(nil, "%s|%d|%d|%d", src, info.ModTime().Unix(), info.Size(), width))
	ext := ".jpg"
	if strings.EqualFold(filepath.Ext(src), ".png") {
		ext = ".png"
	}
	return hex.EncodeToString(sum[:8]) + "-w" + strconv.Itoa(width) + ext
}

func (a *App) cleanThumbCache() {
	if a.cfg.DatabasePath == "" {
		return
	}
	dir := filepath.Join(filepath.Dir(a.cfg.DatabasePath), "thumb-cache")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-thumbMaxAge)
	for _, entry := range entries {
		info, err := entry.Info()
		if err == nil && info.Mode().IsRegular() && info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(dir, entry.Name()))
		}
	}
}
