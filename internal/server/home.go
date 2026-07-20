package server

import (
	"context"
	"net/http"
	"strings"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/media"
)

type homePayload struct {
	User             auth.User                `json:"user"`
	Libraries        []config.Library         `json:"libraries"`
	HomeMovies       []media.Item             `json:"homeMovies"`
	HomeShows        []media.ShowSummary      `json:"homeShows"`
	RecentMovies     []media.Item             `json:"recentMovies"`
	RecentShows      []media.ShowSummary      `json:"recentShows"`
	ContinueMovies   []media.Item             `json:"continueMovies"`
	ContinueEpisodes []media.Item             `json:"continueEpisodes"`
	Progress         []media.PlaybackProgress `json:"progress"`
	ShowProgress     []media.ShowProgress     `json:"showProgress"`
	Watchlist        media.Watchlist          `json:"watchlist"`
}

func (a *App) home(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "home", user.ID), 15*time.Second, func() (any, error) {
		return a.buildHomePayload(r.Context(), user)
	})
}

func (a *App) buildHomePayload(ctx context.Context, user auth.User) (homePayload, error) {
	payload := homePayload{
		User:      user,
		Libraries: a.cfg.Libraries,
	}
	movieLib := firstLibraryOfType(a.cfg.Libraries, "movies", "movie")
	tvLib := firstLibraryOfType(a.cfg.Libraries, "tv")

	progress, err := a.allProgress(ctx, user.ID)
	if err != nil {
		return payload, err
	}
	payload.Progress = progress
	showProgress, err := a.store.ListShowProgress(ctx, user.ID)
	if err != nil {
		return payload, err
	}
	payload.ShowProgress = showProgress
	watchlist, err := a.store.ListWatchlist(ctx, user.ID, 300)
	if err != nil {
		return payload, err
	}
	payload.Watchlist = watchlist

	if movieLib != nil {
		payload.HomeMovies, err = a.store.ListItemsForUser(ctx, movieLib.ID, "", "", "", "", "", user.ID, 0, 150, 0)
		if err != nil {
			return payload, err
		}
		payload.RecentMovies, err = a.store.ListItemsForUser(ctx, movieLib.ID, "", "", "", "mtime", "", user.ID, 0, 24, 0)
		if err != nil {
			return payload, err
		}
	}
	if tvLib != nil {
		payload.HomeShows, err = a.store.ListShowsForUser(ctx, tvLib.ID, "", "", "", "", "", user.ID, 0, 150, 0)
		if err != nil {
			return payload, err
		}
		payload.RecentShows, err = a.store.ListShowsForUser(ctx, tvLib.ID, "", "", "", "mtime", "", user.ID, 0, 24, 0)
		if err != nil {
			return payload, err
		}
	}

	payload.ContinueMovies, payload.ContinueEpisodes, err = a.continueRows(ctx, user.ID, progress, showProgress)
	if err != nil {
		return payload, err
	}
	return payload, nil
}

func (a *App) allProgress(ctx context.Context, userID int64) ([]media.PlaybackProgress, error) {
	out := []media.PlaybackProgress{}
	const pageSize = 500
	for offset := 0; ; offset += pageSize {
		page, err := a.store.ListProgress(ctx, userID, pageSize, offset)
		if err != nil {
			return nil, err
		}
		out = append(out, page...)
		if len(page) < pageSize {
			break
		}
	}
	return out, nil
}

func (a *App) continueRows(ctx context.Context, userID int64, progress []media.PlaybackProgress, showProgress []media.ShowProgress) ([]media.Item, []media.Item, error) {
	resumeIDs := make([]int64, 0, 40)
	for _, entry := range progress {
		if len(resumeIDs) >= 40 {
			break
		}
		if resumableProgress(entry) {
			resumeIDs = append(resumeIDs, entry.ItemID)
		}
	}
	resumeItems, err := a.store.ItemsByIDs(ctx, resumeIDs)
	if err != nil {
		return nil, nil, err
	}
	continueMovies := make([]media.Item, 0, 24)
	continueEpisodes := make([]media.Item, 0, 24)
	episodeIDs := map[int64]bool{}
	for _, item := range resumeItems {
		switch item.Kind {
		case "movie":
			if len(continueMovies) < 24 {
				continueMovies = append(continueMovies, item)
			}
		case "episode":
			if len(continueEpisodes) < 24 && !episodeIDs[item.ID] {
				continueEpisodes = append(continueEpisodes, item)
				episodeIDs[item.ID] = true
			}
		}
	}

	partialShows := make([]media.ShowProgress, 0, 24)
	for _, show := range showProgress {
		if len(partialShows) >= 24 {
			break
		}
		if show.CompletedCount > 0 && !show.Completed {
			partialShows = append(partialShows, show)
		}
	}
	nextEpisodes, err := a.store.NextUnwatchedEpisodesForShows(ctx, userID, partialShows, 24)
	if err != nil {
		return nil, nil, err
	}
	for _, item := range nextEpisodes {
		if len(continueEpisodes) >= 24 {
			break
		}
		if !episodeIDs[item.ID] {
			continueEpisodes = append(continueEpisodes, item)
			episodeIDs[item.ID] = true
		}
	}
	return continueMovies, continueEpisodes, nil
}

func resumableProgress(progress media.PlaybackProgress) bool {
	duration := progress.DurationMS
	position := progress.PositionMS
	if progress.Completed || duration <= 0 || position < 30000 {
		return false
	}
	minRemainingCutoff := duration - 90000
	if minRemainingCutoff < 30000 {
		minRemainingCutoff = 30000
	}
	return position < minRemainingCutoff
}

func firstLibraryOfType(libraries []config.Library, types ...string) *config.Library {
	for _, library := range libraries {
		for _, typ := range types {
			if strings.EqualFold(strings.TrimSpace(library.Type), typ) {
				lib := library
				return &lib
			}
		}
	}
	return nil
}
