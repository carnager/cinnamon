package server

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"popcorn/internal/media"
)

func (a *App) progressList(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	progress, err := a.store.ListProgress(r.Context(), user.ID, limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, progress)
}

func (a *App) progressShows(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	progress, err := a.store.ListShowProgress(r.Context(), user.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, progress)
}

func (a *App) progressGet(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	progress, err := a.store.Progress(r.Context(), user.ID, item.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeJSON(w, http.StatusOK, map[string]any{
				"itemId":     item.ID,
				"positionMs": 0,
				"durationMs": item.DurationMS,
				"completed":  false,
			})
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, progress)
}

func (a *App) progressSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	var in struct {
		PositionMS int64  `json:"positionMs"`
		DurationMS int64  `json:"durationMs"`
		Completed  bool   `json:"completed"`
		State      string `json:"state"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	durationMS := in.DurationMS
	if durationMS == 0 {
		durationMS = item.DurationMS
	}
	completed := in.Completed || isFinished(in.PositionMS, durationMS)
	progress, err := a.store.SaveProgress(r.Context(), user.ID, item.ID, in.PositionMS, durationMS, completed)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if progress.Completed && strings.EqualFold(strings.TrimSpace(in.State), "manual") {
		go a.traktSyncHistoryItems(user.ID, []media.Item{item}, false)
		writeJSON(w, http.StatusOK, progress)
		return
	}
	if strings.TrimSpace(in.State) != "" || progress.Completed {
		go a.scrobblePlayback(user.ID, item, progress, in.State)
	}
	writeJSON(w, http.StatusOK, progress)
}

func (a *App) progressDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteProgress(r.Context(), user.ID, item.ID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncHistoryItems(user.ID, []media.Item{item}, true)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) progressShowSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	episodes, err := a.store.ListEpisodes(r.Context(), libraryID, showTitle, -1)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	for _, episode := range episodes {
		duration := episode.DurationMS
		if duration <= 0 {
			duration = 1
		}
		if _, err := a.store.SaveProgress(r.Context(), user.ID, episode.ID, duration, duration, true); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	go a.traktSyncHistoryItems(user.ID, episodes, false)
	writeJSON(w, http.StatusOK, map[string]any{"libraryId": libraryID, "showTitle": showTitle, "itemsMarked": len(episodes)})
}

func (a *App) progressShowDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	episodes, err := a.store.ListEpisodes(r.Context(), libraryID, showTitle, -1)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	for _, episode := range episodes {
		if err := a.store.DeleteProgress(r.Context(), user.ID, episode.ID); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	go a.traktSyncHistoryItems(user.ID, episodes, true)
	writeJSON(w, http.StatusOK, map[string]any{"libraryId": libraryID, "showTitle": showTitle, "itemsUnmarked": len(episodes)})
}

func (a *App) progressSeasonSave(w http.ResponseWriter, r *http.Request) {
	a.progressSeasonSet(w, r, true)
}

func (a *App) progressSeasonDelete(w http.ResponseWriter, r *http.Request) {
	a.progressSeasonSet(w, r, false)
}

func (a *App) progressSeasonSet(w http.ResponseWriter, r *http.Request, completed bool) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	season, err := strconv.Atoi(r.URL.Query().Get("season"))
	if libraryID == "" || showTitle == "" || err != nil {
		http.Error(w, "libraryId, showTitle and season are required", http.StatusBadRequest)
		return
	}
	episodes, err := a.store.ListEpisodes(r.Context(), libraryID, showTitle, season)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	for _, episode := range episodes {
		if completed {
			duration := episode.DurationMS
			if duration <= 0 {
				duration = 1
			}
			if _, err := a.store.SaveProgress(r.Context(), user.ID, episode.ID, duration, duration, true); err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			continue
		}
		if err := a.store.DeleteProgress(r.Context(), user.ID, episode.ID); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	go a.traktSyncHistoryItems(user.ID, episodes, !completed)
	key := "itemsMarked"
	if !completed {
		key = "itemsUnmarked"
	}
	writeJSON(w, http.StatusOK, map[string]any{"libraryId": libraryID, "showTitle": showTitle, "season": season, key: len(episodes)})
}

func isFinished(positionMS, durationMS int64) bool {
	if durationMS <= 0 || positionMS <= 0 {
		return false
	}
	if durationMS-positionMS <= 90_000 {
		return true
	}
	return float64(positionMS)/float64(durationMS) >= 0.92
}
