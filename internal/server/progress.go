package server

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/media"
)

func (a *App) progressList(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	a.writeCachedJSON(w, r, cacheKey(r, "progress", user.ID), 15*time.Second, func() (any, error) {
		return a.store.ListProgress(r.Context(), user.ID, limit, offset)
	})
}

func (a *App) progressShows(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "progressShows", user.ID), 15*time.Second, func() (any, error) {
		return a.store.ListShowProgress(r.Context(), user.ID)
	})
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
		PositionMS   int64  `json:"positionMs"`
		DurationMS   int64  `json:"durationMs"`
		Completed    bool   `json:"completed"`
		State        string `json:"state"`
		ContinuousMS *int64 `json:"continuousMs"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	a.invalidateResponseCache()
	durationMS := in.DurationMS
	if durationMS == 0 {
		durationMS = item.DurationMS
	}
	state := strings.TrimSpace(in.State)
	manual := strings.EqualFold(state, "manual")
	// New clients report uninterrupted play time since their last seek. A seek
	// changes position, but it is not evidence that the intervening material was
	// watched. Until playback remains settled for a meaningful interval, retain
	// the last trustworthy resume point (or no point at all).
	continuousMS := int64(0)
	if in.ContinuousMS != nil {
		continuousMS = max(int64(0), *in.ContinuousMS)
	}
	if !manual {
		if required := resumeEvidenceThreshold(durationMS); continuousMS < required {
			// A discarded report is otherwise indistinguishable from one that
			// never arrived, which makes "why was this never marked watched?"
			// unanswerable after the fact.
			a.log.Info("progress report discarded: not enough settled playback",
				"user", user.ID, "item", item.ID, "state", state,
				"position", in.PositionMS, "duration", durationMS,
				"percent", progressPercent(in.PositionMS, durationMS),
				"claimedCompleted", in.Completed,
				"continuous", continuousMS, "required", required)
			progress, err := a.store.Progress(r.Context(), user.ID, item.ID)
			if err == nil {
				writeJSON(w, http.StatusOK, progress)
				return
			}
			if !errors.Is(err, sql.ErrNoRows) {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"itemId": item.ID, "positionMs": 0, "durationMs": durationMS, "completed": false,
			})
			return
		}
	}
	completed := manual || in.Completed
	if completed && !manual {
		if required := completionEvidenceThreshold(durationMS); continuousMS < required {
			a.log.Info("completion refused: not enough settled playback",
				"user", user.ID, "item", item.ID, "state", state,
				"position", in.PositionMS, "duration", durationMS,
				"percent", progressPercent(in.PositionMS, durationMS),
				"continuous", continuousMS, "required", required)
			completed = false
		}
	}
	progress, err := a.store.SaveProgress(r.Context(), user.ID, item.ID, in.PositionMS, durationMS, completed)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// A watchlist is a list of things to watch, so finishing one takes it off.
	if progress.Completed {
		if err := a.store.DeleteItemWatchlist(r.Context(), user.ID, item.ID); err != nil {
			a.log.Warn("watchlist cleanup failed", "user", user.ID, "item", item.ID, "error", err)
		}
	}
	if progress.Completed && manual {
		go a.traktSyncHistoryItems(user.ID, []media.Item{item}, false)
		writeJSON(w, http.StatusOK, progress)
		return
	}
	if state != "" || progress.Completed {
		go a.scrobblePlayback(user.ID, item, progress, state)
	}
	writeJSON(w, http.StatusOK, progress)
}

// progressPercent reports how far in a position sits, so a rejected report can
// be read at a glance without dividing two millisecond counts by hand.
func progressPercent(positionMS, durationMS int64) int {
	if durationMS <= 0 {
		return 0
	}
	return int(positionMS * 100 / durationMS)
}

func resumeEvidenceThreshold(durationMS int64) int64 {
	if durationMS <= 0 {
		return 2 * 60_000
	}
	return min(int64(2*60_000), max(int64(30_000), durationMS/20))
}

func completionEvidenceThreshold(durationMS int64) int64 {
	if durationMS <= 0 {
		return 5 * 60_000
	}
	return min(int64(5*60_000), max(int64(30_000), durationMS/3))
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
	a.invalidateResponseCache()
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
	a.invalidateResponseCache()
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
	a.invalidateResponseCache()
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
	a.invalidateResponseCache()
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
