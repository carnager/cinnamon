package media

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"

	"popcorn/internal/config"
)

type AutoScanner struct {
	cfg     config.Config
	store   *Store
	log     *slog.Logger
	scanner *Scanner
	mu      sync.Mutex
}

func NewAutoScanner(cfg config.Config, store *Store, log *slog.Logger) *AutoScanner {
	return &AutoScanner{
		cfg:     cfg,
		store:   store,
		log:     log,
		scanner: NewScanner(cfg, store, log),
	}
}

func (a *AutoScanner) Run(ctx context.Context) {
	if len(a.cfg.Libraries) == 0 {
		return
	}
	if a.cfg.ScanOnStart {
		a.scanFull(ctx, "startup")
	}
	if !a.cfg.AutoScan {
		return
	}

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		a.log.Warn("auto scan watcher unavailable, using periodic scans only", "error", err)
		a.runPeriodic(ctx)
		return
	}
	defer watcher.Close()

	for _, lib := range a.cfg.Libraries {
		if err := addRecursiveWatch(watcher, lib.Path); err != nil {
			a.log.Warn("auto scan watch failed", "library", lib.ID, "path", lib.Path, "error", err)
		}
	}
	a.log.Info("auto scan enabled", "debounce", a.cfg.AutoScanDebounce, "interval", a.cfg.AutoScanInterval)

	pending := map[string]map[string]struct{}{}
	var timer *time.Timer
	var timerC <-chan time.Time
	resetTimer := func() {
		debounce := a.cfg.AutoScanDebounce
		if debounce <= 0 {
			debounce = 3 * time.Second
		}
		if timer == nil {
			timer = time.NewTimer(debounce)
			timerC = timer.C
			return
		}
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		timer.Reset(debounce)
	}
	stopTimer := func() {
		if timer == nil {
			return
		}
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		timerC = nil
	}

	var ticker *time.Ticker
	var tickerC <-chan time.Time
	if a.cfg.AutoScanInterval > 0 {
		ticker = time.NewTicker(a.cfg.AutoScanInterval)
		defer ticker.Stop()
		tickerC = ticker.C
	}

	for {
		select {
		case <-ctx.Done():
			return
		case event, ok := <-watcher.Events:
			if !ok {
				return
			}
			a.handleWatchEvent(watcher, pending, event)
			if len(pending) > 0 {
				resetTimer()
			}
		case err, ok := <-watcher.Errors:
			if !ok {
				return
			}
			a.log.Warn("auto scan watcher error", "error", err)
		case <-timerC:
			batch := pending
			pending = map[string]map[string]struct{}{}
			stopTimer()
			a.scanPending(ctx, batch)
		case <-tickerC:
			a.scanFull(ctx, "periodic")
		}
	}
}

func (a *AutoScanner) runPeriodic(ctx context.Context) {
	if a.cfg.AutoScanInterval <= 0 {
		<-ctx.Done()
		return
	}
	ticker := time.NewTicker(a.cfg.AutoScanInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			a.scanFull(ctx, "periodic")
		}
	}
}

func (a *AutoScanner) handleWatchEvent(watcher *fsnotify.Watcher, pending map[string]map[string]struct{}, event fsnotify.Event) {
	if event.Name == "" || isIgnoredScanPath(event.Name) {
		return
	}
	if event.Op&(fsnotify.Create|fsnotify.Rename) != 0 {
		if info, err := os.Stat(event.Name); err == nil && info.IsDir() {
			if err := addRecursiveWatch(watcher, event.Name); err != nil {
				a.log.Debug("auto scan add watch failed", "path", event.Name, "error", err)
			}
		}
	}
	lib, ok := matchingLibrary(a.cfg.Libraries, event.Name)
	if !ok {
		return
	}
	if pending[lib.ID] == nil {
		pending[lib.ID] = map[string]struct{}{}
	}
	abs, err := filepath.Abs(event.Name)
	if err != nil {
		abs = filepath.Clean(event.Name)
	}
	pending[lib.ID][abs] = struct{}{}
	a.log.Debug("auto scan queued path", "library", lib.ID, "op", event.Op.String(), "path", abs)
}

func (a *AutoScanner) scanFull(ctx context.Context, reason string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.log.Info("auto scan full scan started", "reason", reason)
	scanCtx, cancel := context.WithTimeout(ctx, a.cfg.ScanTimeout)
	defer cancel()
	if err := a.scanner.Scan(scanCtx); err != nil && scanCtx.Err() == nil {
		a.log.Warn("auto scan full scan failed", "reason", reason, "error", err)
	}
}

func (a *AutoScanner) scanPending(ctx context.Context, pending map[string]map[string]struct{}) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, lib := range a.cfg.Libraries {
		pathsByLibrary := pending[lib.ID]
		if len(pathsByLibrary) == 0 {
			continue
		}
		paths := make([]string, 0, len(pathsByLibrary))
		for path := range pathsByLibrary {
			paths = append(paths, path)
		}
		scanCtx, cancel := context.WithTimeout(ctx, a.cfg.ScanTimeout)
		err := a.scanner.ScanPaths(scanCtx, lib, paths)
		cancel()
		if err != nil && scanCtx.Err() == nil {
			a.log.Warn("auto scan incremental scan failed", "library", lib.ID, "paths", len(paths), "error", err)
		}
	}
}

func addRecursiveWatch(watcher *fsnotify.Watcher, root string) error {
	return filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		name := d.Name()
		if strings.HasPrefix(name, ".") || name == "@eaDir" {
			return filepath.SkipDir
		}
		if err := watcher.Add(path); err != nil {
			return nil
		}
		return nil
	})
}

func matchingLibrary(libraries []config.Library, path string) (config.Library, bool) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		absPath = filepath.Clean(path)
	}
	for _, lib := range libraries {
		root, err := filepath.Abs(lib.Path)
		if err != nil {
			root = filepath.Clean(lib.Path)
		}
		rel, err := filepath.Rel(root, absPath)
		if err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return lib, true
		}
	}
	return config.Library{}, false
}

func isIgnoredScanPath(path string) bool {
	base := filepath.Base(path)
	if strings.HasPrefix(base, ".") {
		return true
	}
	switch strings.ToLower(base) {
	case "@eadir", "thumbs.db", ".ds_store":
		return true
	default:
		return false
	}
}
