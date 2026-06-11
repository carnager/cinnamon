package media

import (
	"context"
	"errors"
	"log/slog"
	"sync"

	"popcorn/internal/config"
)

type AutoScanner struct {
	cfg     config.Config
	log     *slog.Logger
	scanner *Scanner
	mu      sync.Mutex
}

func NewAutoScanner(cfg config.Config, store *Store, log *slog.Logger) *AutoScanner {
	return &AutoScanner{
		cfg:     cfg,
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
	a.log.Info("auto scan disabled; use manual library update")
	<-ctx.Done()
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
