package server

import (
	"net/http"
	"time"

	"popcorn/internal/media"
)

func (a *App) recommendationExclusionsGet(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "recommendation-exclusions", user.ID), 15*time.Second, func() (any, error) {
		return a.store.ListRecommendationExclusions(r.Context(), user.ID)
	})
}

func (a *App) recommendationItemSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	if item.Kind != "movie" {
		http.Error(w, "not interested is only supported for movies and shows", http.StatusBadRequest)
		return
	}
	if err := a.store.SaveItemRecommendationExclusion(r.Context(), user.ID, item); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	go a.traktSyncRecommendationExclusionItems(user.ID, []media.Item{item}, false)
	writeJSON(w, http.StatusOK, map[string]any{"key": recommendationItemKey(item.ID), "excluded": true})
}

func (a *App) recommendationItemDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteItemRecommendationExclusion(r.Context(), user.ID, item.ID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	go a.traktSyncRecommendationExclusionItems(user.ID, []media.Item{item}, true)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) recommendationShowSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	show, ok := a.lookupShowFromQuery(w, r)
	if !ok {
		return
	}
	if err := a.store.SaveShowRecommendationExclusion(r.Context(), user.ID, show.LibraryID, show.Title); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	go a.traktSyncRecommendationExclusionShows(user.ID, []media.ShowSummary{show}, false)
	writeJSON(w, http.StatusOK, map[string]any{
		"key":      recommendationShowKey(show.LibraryID, show.Title),
		"excluded": true,
	})
}

func (a *App) recommendationShowDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	show, ok := a.lookupShowFromQuery(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteShowRecommendationExclusion(r.Context(), user.ID, show.LibraryID, show.Title); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.invalidateResponseCache()
	go a.traktSyncRecommendationExclusionShows(user.ID, []media.ShowSummary{show}, true)
	writeJSON(w, http.StatusOK, map[string]any{
		"key":      recommendationShowKey(show.LibraryID, show.Title),
		"excluded": false,
	})
}
