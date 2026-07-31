package server

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/media"
)

// Everything a client needs to draw home: the shelves themselves, plus the
// user-wide state (progress, watchlist, exclusions) that decorates cards
// anywhere in the app. The shelf sources are only inputs to section building
// and never reach the wire — clients read them out of Sections instead.
type homePayload struct {
	User                       auth.User                `json:"user"`
	Libraries                  []config.Library         `json:"libraries"`
	Progress                   []media.PlaybackProgress `json:"progress"`
	ShowProgress               []media.ShowProgress     `json:"showProgress"`
	Watchlist                  media.Watchlist          `json:"watchlist"`
	ExcludedRecommendationKeys []string                 `json:"excludedRecommendationKeys"`
	Sections                   []media.HomeSection      `json:"sections"`

	RecentMovies     []media.Item           `json:"-"`
	RecentShows      []media.ShowSummary    `json:"-"`
	ContinueMovies   []media.Item           `json:"-"`
	ContinueEpisodes []media.Item           `json:"-"`
	Recommendations  []media.Recommendation `json:"-"`
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
	payload.ExcludedRecommendationKeys, err = a.store.ListRecommendationExclusionKeys(ctx, user.ID)
	if err != nil {
		return payload, err
	}

	if movieLib != nil {
		payload.RecentMovies, err = a.store.ListItemsForUser(ctx, movieLib.ID, "", "", "", "mtime", "", user.ID, 0, 24, 0)
		if err != nil {
			return payload, err
		}
	}
	if tvLib != nil {
		payload.RecentShows, err = a.store.ListShowsForUser(ctx, tvLib.ID, "", "", "", "mtime", "", user.ID, 0, 24, 0)
		if err != nil {
			return payload, err
		}
	}

	payload.ContinueMovies, payload.ContinueEpisodes, err = a.continueRows(ctx, user.ID, progress, showProgress)
	if err != nil {
		return payload, err
	}
	payload.Recommendations, err = a.homeRecommendations(ctx, user.ID, movieLib, tvLib, payload)
	if err != nil {
		return payload, err
	}
	payload.Sections, err = a.buildHomeSections(ctx, user.ID, movieLib, tvLib, &payload)
	if err != nil {
		return payload, err
	}
	return payload, nil
}

func (a *App) homeRecommendations(ctx context.Context, userID int64, movieLib, tvLib *config.Library, home homePayload) ([]media.Recommendation, error) {
	excluded := make(map[string]bool, len(home.ExcludedRecommendationKeys))
	for _, key := range home.ExcludedRecommendationKeys {
		excluded[key] = true
	}
	completedItems := map[int64]bool{}
	for _, progress := range home.Progress {
		if progress.Completed || isFinished(progress.PositionMS, progress.DurationMS) {
			completedItems[progress.ItemID] = true
		}
	}
	completedShows := map[string]bool{}
	for _, progress := range home.ShowProgress {
		if progress.Completed {
			completedShows[recommendationShowKey(progress.LibraryID, progress.ShowTitle)] = true
		}
	}

	var topMovies []media.Item
	var topShows []media.ShowSummary
	var err error
	if movieLib != nil {
		topMovies, err = a.store.ListItemsForUser(ctx, movieLib.ID, "", "", "", "rating", "unseen", userID, 0, 48, 0)
		if err != nil {
			return nil, err
		}
	}
	if tvLib != nil {
		topShows, err = a.store.ListShowsForUser(ctx, tvLib.ID, "", "", "", "rating", "unseen", userID, 0, 48, 0)
		if err != nil {
			return nil, err
		}
	}

	recommendations := make([]media.Recommendation, 0, 16)
	added := map[string]bool{}
	addItem := func(item media.Item, reason, source string) {
		key := recommendationItemKey(item.ID)
		if item.ID <= 0 || added[key] || recommendationItemExcluded(item, excluded) || completedItems[item.ID] {
			return
		}
		copy := item
		recommendations = append(recommendations, media.Recommendation{Key: key, Reason: reason, Source: source, Item: &copy})
		added[key] = true
	}
	addShow := func(show media.ShowSummary, reason, source string) {
		key := recommendationShowKey(show.LibraryID, show.Title)
		if show.Title == "" || added[key] || excluded[key] || completedShows[key] {
			return
		}
		copy := show
		recommendations = append(recommendations, media.Recommendation{Key: key, Reason: reason, Source: source, Show: &copy})
		added[key] = true
	}

	for _, item := range append(home.ContinueMovies, home.ContinueEpisodes...) {
		addItem(item, "Continue watching", "progress")
		if len(recommendations) >= 3 {
			break
		}
	}

	var watched []media.Item
	watchedIDs := make([]int64, 0, len(completedItems))
	for _, progress := range home.Progress {
		if completedItems[progress.ItemID] {
			watchedIDs = append(watchedIDs, progress.ItemID)
			if len(watchedIDs) >= 12 {
				break
			}
		}
	}
	if len(watchedIDs) > 0 {
		loaded, loadErr := a.store.ItemsByIDs(ctx, watchedIDs)
		err = loadErr
		if err != nil {
			return nil, err
		}
		byID := make(map[int64]media.Item, len(loaded))
		for _, item := range loaded {
			byID[item.ID] = item
		}
		for _, id := range watchedIDs {
			if item, ok := byID[id]; ok && item.Kind == "movie" {
				watched = append(watched, item)
			}
		}
	}
	// "Because you watched" is reserved for TMDb's ranked recommendations (or
	// its similar endpoint fallback), intersected with the local library. A
	// shared genre alone is too weak to justify that wording.
	if len(watched) > 0 {
		anchor := watched[0]
		if candidates, ready := a.readySimilarItems(anchor.ID); ready {
			for _, candidate := range candidates {
				addItem(candidate, fmt.Sprintf("Because you watched %s", anchor.Title), "tmdb")
				if len(recommendations) >= 7 {
					break
				}
			}
		} else {
			// Home must never wait on an external API. The current request gets
			// the local-library portion of the feed immediately; a later request
			// can use the asynchronously warmed TMDb result.
			a.queueSimilarItems(anchor)
		}
	}

	for _, item := range home.RecentMovies {
		addItem(item, "New in your library", "library")
		if len(recommendations) >= 10 {
			break
		}
	}
	for _, show := range home.RecentShows {
		addShow(show, "New in your library", "library")
		if len(recommendations) >= 12 {
			break
		}
	}
	for _, item := range home.Watchlist.Items {
		addItem(item, "From your watchlist", "watchlist")
		if len(recommendations) >= 14 {
			break
		}
	}
	for _, show := range home.Watchlist.Shows {
		addShow(show, "From your watchlist", "watchlist")
		if len(recommendations) >= 16 {
			break
		}
	}
	for _, item := range topMovies {
		addItem(item, "Recommended for you", "library")
		if len(recommendations) >= 20 {
			break
		}
	}
	for _, show := range topShows {
		addShow(show, "Recommended for you", "library")
		if len(recommendations) >= 24 {
			break
		}
	}
	return recommendations, nil
}

func recommendationItemExcluded(item media.Item, excluded map[string]bool) bool {
	if item.Kind != "episode" && excluded[recommendationItemKey(item.ID)] {
		return true
	}
	return item.Kind == "episode" && excluded[recommendationShowKey(item.LibraryID, item.ShowTitle)]
}

func recommendationItemKey(itemID int64) string {
	return fmt.Sprintf("item:%d", itemID)
}

func recommendationShowKey(libraryID, showTitle string) string {
	return "show:" + strings.ToLower(strings.TrimSpace(libraryID)) + ":" + strings.ToLower(strings.TrimSpace(showTitle))
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
