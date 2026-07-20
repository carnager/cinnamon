package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"path/filepath"
	"sort"
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
		a.enqueueScopedScan(libByID[id], paths)
	}
	w.WriteHeader(http.StatusAccepted)
}

// enqueueScopedScan hands paths to the per-library scan worker. Notifications
// are queued in a pending set and never dropped: a single worker per library
// drains the set in batches, so a burst of notifications coalesces into a few
// scans instead of a herd of goroutines racing for the scan lease — where
// starved ones used to give up after a minute and silently lose their paths
// (a freshly imported show then simply never appeared).
func (a *App) enqueueScopedScan(lib config.Library, paths []string) {
	a.scopedMu.Lock()
	set := a.scopedPending[lib.ID]
	if set == nil {
		set = map[string]bool{}
		a.scopedPending[lib.ID] = set
	}
	for _, p := range paths {
		set[p] = true
	}
	start := !a.scopedRunning[lib.ID]
	if start {
		a.scopedRunning[lib.ID] = true
	}
	a.scopedMu.Unlock()
	if start {
		go a.scopedScanWorker(lib)
	}
}

func (a *App) scopedScanWorker(lib config.Library) {
	for {
		a.scopedMu.Lock()
		set := a.scopedPending[lib.ID]
		if len(set) == 0 {
			a.scopedRunning[lib.ID] = false
			a.scopedMu.Unlock()
			return
		}
		delete(a.scopedPending, lib.ID)
		a.scopedMu.Unlock()
		paths := make([]string, 0, len(set))
		for p := range set {
			paths = append(paths, p)
		}
		sort.Strings(paths)
		if !a.runScopedScan(lib, paths) {
			// Lease stayed busy through the whole wait; put the paths back and
			// keep trying. They stay pending until a scan actually covers them.
			a.enqueueScopedScanLocked(lib, paths)
		}
	}
}

func (a *App) enqueueScopedScanLocked(lib config.Library, paths []string) {
	a.scopedMu.Lock()
	set := a.scopedPending[lib.ID]
	if set == nil {
		set = map[string]bool{}
		a.scopedPending[lib.ID] = set
	}
	for _, p := range paths {
		set[p] = true
	}
	a.scopedMu.Unlock()
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

// runScopedScan runs one batched scan, waiting out a busy scan lease (a
// manual full scan can hold it for a while). Returns false when the lease
// stayed busy for the whole wait so the caller can requeue the paths.
func (a *App) runScopedScan(lib config.Library, paths []string) bool {
	scanner := media.NewScanner(a.cfg, a.store, a.log)
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
		return true
	}
	a.log.Warn("scan lease stayed busy, requeueing scoped scan", "library", lib.ID, "paths", len(paths))
	return false
}
