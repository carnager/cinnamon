package server

import (
	"context"
	"time"

	"popcorn/internal/media"
)

const recommendationPrebuildInterval = 6 * time.Hour

// How many recently finished movies are considered as anchors, and how many of
// them a single request may warm in the background.
const (
	recommendationAnchorCandidates = 6
	recommendationAnchorWarmups    = 2
)

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
		// Walk the anchors until one has library matches: a film whose TMDb
		// list misses the library entirely must not leave the row empty.
		for _, anchor := range a.recommendationAnchors(ctx, user.ID, recommendationAnchorCandidates) {
			if items, ready := a.readySimilarItems(anchor.ID); ready {
				if len(items) > 0 {
					break
				}
				continue
			}
			if len(a.cachedSimilarItems(ctx, anchor)) > 0 {
				warmed++
				break
			}
			warmed++
		}
	}
	if warmed > 0 {
		a.invalidateResponseCache()
		if a.log != nil {
			a.log.Debug("recommendation sources prebuilt", "users", warmed)
		}
	}
}

// recommendationAnchors returns the movies a user finished most recently,
// newest first. More than one, because TMDb's list for any single film may not
// intersect the library at all — which is exactly what happened in practice and
// left "Because you watched" permanently blank.
func (a *App) recommendationAnchors(ctx context.Context, userID int64, limit int) []media.Item {
	progress, err := a.allProgress(ctx, userID)
	if err != nil {
		return nil
	}
	return a.anchorsFromProgress(ctx, progress, limit)
}

// anchorsFromProgress is the same thing for a caller that already has the
// user's progress in hand — home builds it once per request and must not pay
// for it twice.
func (a *App) anchorsFromProgress(ctx context.Context, progress []media.PlaybackProgress, limit int) []media.Item {
	if limit <= 0 {
		limit = recommendationAnchorCandidates
	}
	ids := make([]int64, 0, 60)
	for _, entry := range progress {
		if entry.Completed || isFinished(entry.PositionMS, entry.DurationMS) {
			ids = append(ids, entry.ItemID)
			if len(ids) >= 60 {
				break
			}
		}
	}
	items, err := a.store.ItemsByIDs(ctx, ids)
	if err != nil {
		return nil
	}
	byID := make(map[int64]media.Item, len(items))
	for _, item := range items {
		byID[item.ID] = item
	}
	out := make([]media.Item, 0, limit)
	for _, id := range ids {
		item, ok := byID[id]
		if !ok || item.Kind != "movie" {
			continue
		}
		out = append(out, item)
		if len(out) >= limit {
			break
		}
	}
	return out
}

// similarFromAnchors picks the newest anchor that already has library matches
// warmed. Anchors with nothing cached yet are queued so a later request can do
// better; an anchor cached as empty is simply skipped.
func (a *App) similarFromAnchors(anchors []media.Item) (media.Item, []media.Item, bool) {
	queued := 0
	for _, anchor := range anchors {
		items, ready := a.readySimilarItems(anchor.ID)
		if ready {
			if len(items) > 0 {
				return anchor, items, true
			}
			continue
		}
		if queued < recommendationAnchorWarmups {
			a.queueSimilarItems(anchor)
			queued++
		}
	}
	return media.Item{}, nil, false
}
