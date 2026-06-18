package media

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"popcorn/internal/config"
)

type AutoScanner struct {
	cfg     config.Config
	log     *slog.Logger
	scanner *Scanner
	mu      sync.Mutex
	// dirMTimes tracks the last-seen modification time of every directory in
	// each library so a periodic pass can cheaply find directories that gained,
	// lost, or had files renamed without stat-ing every media file. This is the
	// detection mechanism that works on network mounts (NFS/SMB), where inotify
	// does not deliver events for writes made by other hosts.
	dirMTimes map[string]int64
}

func NewAutoScanner(cfg config.Config, store *Store, log *slog.Logger) *AutoScanner {
	return &AutoScanner{
		cfg:       cfg,
		log:       log,
		scanner:   NewScanner(cfg, store, log),
		dirMTimes: map[string]int64{},
	}
}

func (a *AutoScanner) Run(ctx context.Context) {
	if len(a.cfg.Libraries) == 0 {
		return
	}
	if a.cfg.ScanOnStart {
		a.scanFull(ctx, "startup")
		// We just walked everything, so record the baseline and let the periodic
		// loop only react to directories that change from here on.
		a.primeBaseline()
	}
	if !a.cfg.AutoScan {
		a.log.Info("auto scan disabled; use manual library update")
		<-ctx.Done()
		return
	}
	interval := a.cfg.AutoScanInterval
	if interval <= 0 {
		interval = 30 * time.Second
	}
	a.log.Info("auto scan watching libraries", "interval", interval)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			a.scanChanged(ctx)
		}
	}
}

func (a *AutoScanner) scanFull(ctx context.Context, reason string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.log.Info("auto scan full scan started", "reason", reason)
	scanCtx, cancel := context.WithTimeout(ctx, a.cfg.ScanTimeout)
	defer cancel()
	if err := a.scanner.Scan(scanCtx); errors.Is(err, ErrScanAlreadyRunning) {
		a.log.Info("auto scan full scan skipped", "reason", reason, "message", err.Error())
	} else if err != nil && scanCtx.Err() == nil {
		a.log.Warn("auto scan full scan failed", "reason", reason, "error", err)
	}
}

// scanChanged walks each library's directory tree, finds directories whose
// modification time changed since the last pass, and runs an incremental scan
// over just those directories. New episodes/movies bump their containing
// directory's mtime, so they are picked up within one interval without a full
// rescan.
func (a *AutoScanner) scanChanged(ctx context.Context) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, lib := range a.cfg.Libraries {
		if ctx.Err() != nil {
			return
		}
		changed, current, err := a.collectChangedDirs(lib.Path)
		if err != nil {
			a.log.Warn("auto scan walk failed", "library", lib.ID, "path", lib.Path, "error", err)
			continue
		}
		if len(changed) == 0 {
			a.applyBaseline(current)
			continue
		}
		scanCtx, cancel := context.WithTimeout(ctx, a.cfg.ScanTimeout)
		err = a.scanner.ScanPaths(scanCtx, lib, changed)
		cancel()
		if errors.Is(err, ErrScanAlreadyRunning) {
			// A manual or startup scan holds the lease; leave the baseline
			// untouched and retry on the next tick.
			continue
		}
		if err != nil && ctx.Err() == nil {
			a.log.Warn("auto scan incremental failed", "library", lib.ID, "changedDirs", len(changed), "error", err)
			continue
		}
		a.applyBaseline(current)
		a.log.Info("auto scan incremental finished", "library", lib.ID, "changedDirs", len(changed))
	}
}

// primeBaseline records the current directory mtimes for every library without
// scanning, so the first periodic pass only reacts to subsequent changes.
func (a *AutoScanner) primeBaseline() {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, lib := range a.cfg.Libraries {
		_, current, err := a.collectChangedDirs(lib.Path)
		if err != nil {
			continue
		}
		a.applyBaseline(current)
	}
}

// collectChangedDirs walks libPath and returns the directories whose mtime
// differs from the recorded baseline, alongside the full current snapshot of
// directory mtimes. The caller must hold a.mu.
func (a *AutoScanner) collectChangedDirs(libPath string) ([]string, map[string]int64, error) {
	current := map[string]int64{}
	var changed []string
	err := filepath.WalkDir(libPath, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			// Skip unreadable entries but keep walking the rest of the tree.
			if path == libPath {
				return err
			}
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		name := d.Name()
		if path != libPath && (strings.HasPrefix(name, ".") || name == "@eaDir") {
			return filepath.SkipDir
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		abs, err := filepath.Abs(path)
		if err != nil {
			abs = filepath.Clean(path)
		}
		mtime := info.ModTime().Unix()
		current[abs] = mtime
		if a.dirMTimes[abs] != mtime {
			changed = append(changed, abs)
		}
		return nil
	})
	if err != nil {
		return nil, nil, err
	}
	return changed, current, nil
}

// applyBaseline commits a freshly observed directory-mtime snapshot for a
// single library, replacing the entries that belong to it. The caller must hold
// a.mu.
func (a *AutoScanner) applyBaseline(current map[string]int64) {
	for dir, mtime := range current {
		a.dirMTimes[dir] = mtime
	}
}
