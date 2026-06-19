package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	"strings"
	"time"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

// scanPath runs a targeted incremental scan of specific directories, intended
// for a filesystem watcher running locally on the storage host (e.g. TrueNAS).
// It scans only the directories that actually changed, so it stays fast no
// matter how large the library is.
func (a *App) scanPath(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	var in struct {
		Paths []string `json:"paths"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	byLib := map[string][]string{}
	libByID := map[string]config.Library{}
	for _, p := range in.Paths {
		lib, abs, ok := a.libraryForPath(p)
		if !ok {
			continue
		}
		byLib[lib.ID] = append(byLib[lib.ID], abs)
		libByID[lib.ID] = lib
	}
	if len(byLib) == 0 {
		// Nothing mapped to a known library; ack so the watcher doesn't retry.
		w.WriteHeader(http.StatusNoContent)
		return
	}
	for id, paths := range byLib {
		lib := libByID[id]
		go a.runScopedScan(lib, paths)
	}
	w.WriteHeader(http.StatusAccepted)
}

// libraryForPath maps an absolute path to the library that contains it, picking
// the longest matching library root.
func (a *App) libraryForPath(p string) (config.Library, string, bool) {
	abs, err := filepath.Abs(strings.TrimSpace(p))
	if err != nil || abs == "" {
		return config.Library{}, "", false
	}
	abs = filepath.Clean(abs)
	var best config.Library
	bestLen := -1
	for _, lib := range a.cfg.Libraries {
		root, err := filepath.Abs(lib.Path)
		if err != nil {
			continue
		}
		root = filepath.Clean(root)
		if abs == root || strings.HasPrefix(abs, root+string(filepath.Separator)) {
			if len(root) > bestLen {
				best = lib
				bestLen = len(root)
			}
		}
	}
	return best, abs, bestLen >= 0
}

func (a *App) runScopedScan(lib config.Library, paths []string) {
	scanner := media.NewScanner(a.cfg, a.store, a.log)
	// A manual full scan holds the global scan lease; wait it out rather than
	// dropping the notification.
	for attempt := 0; attempt < 30; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), a.cfg.ScanTimeout)
		err := scanner.ScanPaths(ctx, lib, paths)
		cancel()
		if errors.Is(err, media.ErrScanAlreadyRunning) {
			time.Sleep(2 * time.Second)
			continue
		}
		if err != nil {
			a.log.Warn("scoped scan failed", "library", lib.ID, "paths", len(paths), "error", err)
		} else {
			a.log.Info("scoped scan finished", "library", lib.ID, "paths", len(paths))
		}
		a.invalidateResponseCache()
		return
	}
	a.log.Warn("scoped scan skipped: scan stayed busy", "library", lib.ID, "paths", len(paths))
}
