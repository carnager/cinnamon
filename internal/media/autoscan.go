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

	watchedDirs := 0
	for _, lib := range a.cfg.Libraries {
		n, err := addRecursiveWatch(watcher, lib.Path, a.cfg.AutoScanWatchDepth)
		watchedDirs += n
		if err != nil {
			a.log.Warn("auto scan watch failed", "library", lib.ID, "path", lib.Path, "error", err)
		}
	}
	a.log.Info("auto scan enabled", "debounce", a.cfg.AutoScanDebounce, "interval", a.cfg.AutoScanInterval, "watchDepth", a.cfg.AutoScanWatchDepth, "watchedDirs", watchedDirs)

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
			if _, err := addRecursiveWatch(watcher, event.Name, a.cfg.AutoScanWatchDepth); err != nil {
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
	scanPath := scanPathForEvent(event.Name)
	abs, err := filepath.Abs(scanPath)
	if err != nil {
		abs = filepath.Clean(scanPath)
	}
	pending[lib.ID][abs] = struct{}{}
	a.log.Debug("auto scan queued path", "library", lib.ID, "op", event.Op.String(), "eventPath", event.Name, "scanPath", abs)
}

func scanPathForEvent(path string) string {
	if isMetadataOrArtwork(path) {
		return filepath.Dir(path)
	}
	return path
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
		if errors.Is(err, ErrScanAlreadyRunning) {
			a.log.Info("auto scan incremental scan skipped", "library", lib.ID, "paths", len(paths), "message", err.Error())
		} else if err != nil && scanCtx.Err() == nil {
			a.log.Warn("auto scan incremental scan failed", "library", lib.ID, "paths", len(paths), "error", err)
		}
	}
}

func addRecursiveWatch(watcher *fsnotify.Watcher, root string, maxDepth int) (int, error) {
	watched := 0
	root = filepath.Clean(root)
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		depth := watchDepth(root, path)
		if maxDepth >= 0 && depth > maxDepth {
			return filepath.SkipDir
		}
		name := d.Name()
		if strings.HasPrefix(name, ".") || name == "@eaDir" {
			return filepath.SkipDir
		}
		if err := watcher.Add(path); err != nil {
			return nil
		}
		watched++
		return nil
	})
	return watched, err
}

func watchDepth(root, path string) int {
	rel, err := filepath.Rel(root, path)
	if err != nil || rel == "." {
		return 0
	}
	return len(strings.Split(filepath.Clean(rel), string(filepath.Separator)))
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
	if pathHasComponent(path, ".actors") {
		return true
	}
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

func pathHasComponent(path, component string) bool {
	for _, part := range strings.Split(filepath.Clean(path), string(filepath.Separator)) {
		if part == component {
			return true
		}
	}
	return false
}
