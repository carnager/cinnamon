package server

import (
	"context"
	"sort"
	"time"

	"popcorn/internal/media"
)

// A file that appears in the library after a Trakt import used to sit there
// unwatched until the user thought to run the import again — which re-fetches
// the whole history and re-matches every item in the library. So a film ripped
// or downloaded years after it was watched showed up as new and unseen, which
// is exactly the state the Trakt link exists to avoid.
//
// The scanner now reports the items it genuinely added, and those get checked
// against Trakt's watched history on their own. The history fetch is the same
// one the manual import uses; only the matching is narrowed, to the new items
// alone, so an item the user has since un-watched locally is not marked again
// behind their back.

// traktBackfillSettle delays the fetch after the last scan reported new items.
// A season arriving file by file is a burst of scans, and each one costs a full
// history fetch if answered on its own.
const traktBackfillSettle = 10 * time.Second

// traktBackfillTimeout bounds one back-fill run. The history fetch is paged and
// a large account takes a while, but it must not outlive the server.
const traktBackfillTimeout = 10 * time.Minute

// ItemsAdded is the scanner's new-items hook. It must not block the scan, so it
// only queues; the worker does the fetching.
func (a *App) ItemsAdded(paths []string) {
	if len(paths) == 0 {
		return
	}
	a.backfillMu.Lock()
	if a.backfillPending == nil {
		a.backfillPending = map[string]bool{}
	}
	for _, path := range paths {
		a.backfillPending[path] = true
	}
	start := !a.backfillRunning
	if start {
		a.backfillRunning = true
	}
	a.backfillMu.Unlock()
	if start {
		go a.traktBackfillWorker()
	}
	// New files mean the collection on Trakt is behind.
	a.LibraryChanged()
}

func (a *App) traktBackfillWorker() {
	for {
		select {
		case <-time.After(traktBackfillSettle):
		case <-a.ctx.Done():
			a.backfillMu.Lock()
			a.backfillRunning = false
			a.backfillMu.Unlock()
			return
		}
		a.backfillMu.Lock()
		pending := a.backfillPending
		if len(pending) == 0 {
			a.backfillRunning = false
			a.backfillMu.Unlock()
			return
		}
		a.backfillPending = nil
		a.backfillMu.Unlock()
		paths := make([]string, 0, len(pending))
		for path := range pending {
			paths = append(paths, path)
		}
		sort.Strings(paths)
		a.runTraktBackfill(paths)
	}
}

func (a *App) runTraktBackfill(paths []string) {
	ctx, cancel := context.WithTimeout(a.ctx, traktBackfillTimeout)
	defer cancel()
	users, err := a.store.TraktLinkedUsers(ctx)
	if err != nil {
		a.log.Warn("trakt backfill users failed", "error", err)
		return
	}
	if len(users) == 0 {
		return
	}
	items, err := a.store.ItemsByPaths(ctx, paths)
	if err != nil {
		a.log.Warn("trakt backfill items failed", "paths", len(paths), "error", err)
		return
	}
	if len(items) == 0 {
		return
	}
	index := newTraktImportIndex(items)
	marked := 0
	for _, userID := range users {
		account, err := a.traktAccountForRequest(ctx, userID)
		if err != nil {
			a.log.Warn("trakt backfill account failed", "user", userID, "error", err)
			continue
		}
		movies, err := a.traktWatchedMovies(ctx, account.AccessToken)
		if err != nil {
			a.log.Warn("trakt backfill watched movies failed", "user", userID, "error", err)
			continue
		}
		shows, err := a.traktWatchedShows(ctx, account.AccessToken)
		if err != nil {
			a.log.Warn("trakt backfill watched shows failed", "user", userID, "error", err)
			continue
		}
		count, err := a.markWatchedFromTrakt(ctx, userID, index, movies.Items, shows.Items)
		if err != nil {
			a.log.Warn("trakt backfill mark failed", "user", userID, "error", err)
		}
		marked += count
	}
	if marked > 0 {
		a.invalidateResponseCache()
	}
	a.log.Info("trakt backfill finished", "paths", len(paths), "items", len(items), "marked", marked)
}

// markWatchedFromTrakt marks every item in the index that Trakt reports as
// watched. The index holds only the items under consideration, so a history
// entry with no match here simply belongs to something else in the library.
func (a *App) markWatchedFromTrakt(ctx context.Context, userID int64, index traktImportIndex, movies []traktWatchedMovie, shows []traktWatchedShow) (int, error) {
	marked := map[int64]struct{}{}
	mark := func(item *media.Item) error {
		if item == nil {
			return nil
		}
		if _, done := marked[item.ID]; done {
			return nil
		}
		if err := markItemWatched(ctx, a.store, userID, *item); err != nil {
			return err
		}
		marked[item.ID] = struct{}{}
		return nil
	}
	for _, movie := range movies {
		item, _ := index.matchMovie(movie.Movie.Title, movie.Movie.Year, movie.Movie.IDs)
		if err := mark(item); err != nil {
			return len(marked), err
		}
	}
	for _, show := range shows {
		for _, season := range show.Seasons {
			for _, episode := range season.Episodes {
				item, _ := index.matchEpisode(show.Show.Title, show.Show.Year, show.Show.IDs, traktIDsPayload{}, season.Number, episode.Number)
				if err := mark(item); err != nil {
					return len(marked), err
				}
			}
		}
	}
	return len(marked), nil
}
