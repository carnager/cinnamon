package server

import (
	"context"
	"time"

	"popcorn/internal/media"
)

const recommendationPrebuildInterval = 6 * time.Hour

// recommendationPrebuildWorker keeps the expensive external part of the home
// feed warm. Local progress, exclusion, and library filtering remains live on
// every request, but a phone opening Home never has to wait for TMDb.
func (a *App) recommendationPrebuildWorker() {
	a.prebuildRecommendationSources()
	ticker := time.NewTicker(recommendationPrebuildInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			a.prebuildRecommendationSources()
		case <-a.ctx.Done():
			return
		}
	}
}

func (a *App) prebuildRecommendationSources() {
	if a.auth == nil || a.store == nil || !a.tmdbConfigured() {
		return
	}
	ctx, cancel := context.WithTimeout(a.ctx, 10*time.Minute)
	defer cancel()
	users, err := a.auth.Users(ctx)
	if err != nil {
		if a.log != nil {
			a.log.Warn("recommendation prebuild users failed", "error", err)
		}
		return
	}
	warmed := 0
	for _, user := range users {
		if user.Disabled || ctx.Err() != nil {
			continue
		}
		anchor, ok := a.recommendationAnchor(ctx, user.ID)
		if !ok {
			continue
		}
		if _, ready := a.readySimilarItems(anchor.ID); ready {
			continue
		}
		a.cachedSimilarItems(ctx, anchor)
		warmed++
	}
	if warmed > 0 {
		a.invalidateResponseCache()
		if a.log != nil {
			a.log.Debug("recommendation sources prebuilt", "users", warmed)
		}
	}
}

func (a *App) recommendationAnchor(ctx context.Context, userID int64) (media.Item, bool) {
	progress, err := a.allProgress(ctx, userID)
	if err != nil {
		return media.Item{}, false
	}
	ids := make([]int64, 0, 12)
	for _, entry := range progress {
		if entry.Completed || isFinished(entry.PositionMS, entry.DurationMS) {
			ids = append(ids, entry.ItemID)
			if len(ids) >= 12 {
				break
			}
		}
	}
	items, err := a.store.ItemsByIDs(ctx, ids)
	if err != nil {
		return media.Item{}, false
	}
	byID := make(map[int64]media.Item, len(items))
	for _, item := range items {
		byID[item.ID] = item
	}
	for _, id := range ids {
		if item, ok := byID[id]; ok && item.Kind == "movie" {
			return item, true
		}
	}
	return media.Item{}, false
}
