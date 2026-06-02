package server

import (
	"net/http"
	"strconv"

	"popcorn/internal/media"
)

func (a *App) watchlistGet(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	watchlist, err := a.store.ListWatchlist(r.Context(), user.ID, limit)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, watchlist)
}

func (a *App) watchlistItemSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	if err := a.store.SaveItemWatchlist(r.Context(), user.ID, item); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncWatchlistItems(user.ID, []media.Item{item}, false)
	writeJSON(w, http.StatusOK, map[string]any{"itemId": item.ID, "watchlisted": true})
}

func (a *App) watchlistItemDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteItemWatchlist(r.Context(), user.ID, item.ID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncWatchlistItems(user.ID, []media.Item{item}, true)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) watchlistShowSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	show, ok := a.lookupShowFromQuery(w, r)
	if !ok {
		return
	}
	if err := a.store.SaveShowWatchlist(r.Context(), user.ID, show.LibraryID, show.Title); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncWatchlistShows(user.ID, []media.ShowSummary{show}, false)
	writeJSON(w, http.StatusOK, map[string]any{"libraryId": show.LibraryID, "showTitle": show.Title, "watchlisted": true})
}

func (a *App) watchlistShowDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	show, ok := a.lookupShowFromQuery(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteShowWatchlist(r.Context(), user.ID, show.LibraryID, show.Title); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncWatchlistShows(user.ID, []media.ShowSummary{show}, true)
	writeJSON(w, http.StatusOK, map[string]any{"libraryId": show.LibraryID, "showTitle": show.Title, "watchlisted": false})
}

func (a *App) lookupShowFromQuery(w http.ResponseWriter, r *http.Request) (media.ShowSummary, bool) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return media.ShowSummary{}, false
	}
	shows, err := a.store.ListShows(r.Context(), libraryID, showTitle, "", "", 20, 0)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return media.ShowSummary{}, false
	}
	for _, show := range shows {
		if show.LibraryID == libraryID && show.Title == showTitle {
			return show, true
		}
	}
	http.NotFound(w, r)
	return media.ShowSummary{}, false
}
