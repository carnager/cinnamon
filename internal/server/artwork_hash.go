package server

import (
	"context"
	"runtime"
	"time"

	"popcorn/internal/media"
)

const (
	// artworkHashBatch bounds how much artwork one sweep claims. Batching keeps
	// each write transaction short so the pass never holds the database against
	// a browsing client for long.
	artworkHashBatch = 256

	// artworkHashIdleInterval is how long the worker waits after finding
	// nothing to do. Kept short deliberately: nothing wakes the worker when a
	// scan finishes, so this interval is also the lag between a scan importing
	// artwork and its placeholders appearing. The pending query is answered by
	// a partial index covering only unhashed rows, so polling this often costs
	// approximately nothing on an idle, fully-hashed library.
	artworkHashIdleInterval = 30 * time.Second

	// artworkHashBusyInterval is the pause between batches while a backlog is
	// draining. Long enough that a first-run backfill on a big library cannot
	// monopolise the CPU the scanner and transcoder need.
	artworkHashBusyInterval = 2 * time.Second
)

// artworkHashWorker computes thumbhash placeholders for poster and backdrop
// artwork in the background.
//
// This deliberately does not run inside the scanner. Decoding a poster costs
// far more than the metadata work the scanner does per item, and scan speed is
// the thing this server is good at; hashing rides behind it instead. The UI
// treats a missing hash as "no placeholder yet", so a library is fully browsable
// while the backlog drains.
func (a *App) artworkHashWorker() {
	// Leave headroom: hashing is pure CPU and competes with transcoding.
	workers := max(1, runtime.NumCPU()/2)

	for {
		hashed, err := a.hashArtworkBatch(workers)
		switch {
		case err != nil:
			if a.log != nil {
				a.log.Warn("artwork thumbhash pass failed", "error", err)
			}
		case hashed > 0 && a.log != nil:
			a.log.Debug("artwork thumbhash pass", "hashed", hashed)
		}

		delay := artworkHashIdleInterval
		if hashed > 0 && err == nil {
			delay = artworkHashBusyInterval
		}
		select {
		case <-time.After(delay):
		case <-a.ctx.Done():
			return
		}
	}
}

// hashArtworkBatch processes one batch and reports how many distinct artwork
// files were hashed.
func (a *App) hashArtworkBatch(workers int) (int, error) {
	if a.store == nil {
		return 0, nil
	}
	ctx, cancel := context.WithTimeout(a.ctx, 10*time.Minute)
	defer cancel()

	targets, err := a.store.PendingArtworkHashes(ctx, artworkHashBatch)
	if err != nil || len(targets) == 0 {
		return 0, err
	}
	results := media.HashArtworkTargets(ctx, targets, workers)
	if len(results) == 0 {
		return 0, nil
	}
	if err := a.store.SaveArtworkHashes(ctx, results); err != nil {
		return 0, err
	}
	return len(results), nil
}
