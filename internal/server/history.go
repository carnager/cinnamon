package server

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"time"

	"popcorn/internal/media"
)

type watchHistoryEntry struct {
	ID        string      `json:"id"`
	Item      *media.Item `json:"item,omitempty"`
	Kind      string      `json:"kind"`
	Title     string      `json:"title"`
	Subtitle  string      `json:"subtitle,omitempty"`
	Year      int         `json:"year,omitempty"`
	WatchedAt string      `json:"watchedAt"`
	Source    string      `json:"source"`
}

type watchHistoryResponse struct {
	Items       []watchHistoryEntry `json:"items"`
	Source      string              `json:"source"`
	TraktLinked bool                `json:"traktLinked"`
}

type historyCacheEntry struct {
	Response    watchHistoryResponse
	RefreshedAt time.Time
}

func (a *App) watchHistory(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 100
	}
	if limit > 200 {
		limit = 200
	}

	response, err := a.localWatchHistory(r, user.ID, limit)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	account, err := a.traktAccountForRequest(r.Context(), user.ID)
	if err == nil && account.AccessToken != "" {
		if cached, exists := a.cachedWatchHistory(user.ID); exists && time.Since(cached.RefreshedAt) < 5*time.Minute {
			writeJSON(w, http.StatusOK, cached.Response)
			return
		}
		cached, cachedExists := a.cachedWatchHistory(user.ID)
		startAt := ""
		if cachedExists && len(cached.Response.Items) > 0 {
			startAt = cached.Response.Items[0].WatchedAt
		}
		if entries, traktErr := a.traktWatchHistory(r, account.AccessToken, limit, startAt); traktErr == nil {
			response = watchHistoryResponse{
				Items:       mergeHistoryEntries(entries, cached.Response.Items),
				Source:      "trakt",
				TraktLinked: true,
			}
			a.storeWatchHistory(user.ID, response)
		} else if cachedExists {
			response = cached.Response
			a.log.Warn("trakt watch history refresh unavailable; using cached history", "user", user.ID, "error", traktErr)
		} else {
			response.TraktLinked = true
			a.log.Warn("trakt watch history unavailable; using local history", "user", user.ID, "error", traktErr)
		}
	}
	writeJSON(w, http.StatusOK, response)
}

func (a *App) localWatchHistory(r *http.Request, userID int64, limit int) (watchHistoryResponse, error) {
	progress, err := a.store.ListProgress(r.Context(), userID, 500, 0)
	if err != nil {
		return watchHistoryResponse{}, err
	}
	ids := make([]int64, 0, len(progress))
	for _, row := range progress {
		if row.Completed || row.PositionMS > 0 {
			ids = append(ids, row.ItemID)
		}
	}
	items, err := a.store.ItemsByIDs(r.Context(), ids)
	if err != nil {
		return watchHistoryResponse{}, err
	}
	byID := make(map[int64]media.Item, len(items))
	for _, item := range items {
		byID[item.ID] = item
	}
	entries := make([]watchHistoryEntry, 0, len(items))
	movieCount := 0
	episodeCount := 0
	for _, row := range progress {
		item, exists := byID[row.ItemID]
		if !exists || (!row.Completed && row.PositionMS <= 0) {
			continue
		}
		if item.Kind == "movie" {
			if movieCount >= limit {
				continue
			}
			movieCount++
		} else if item.Kind == "episode" {
			if episodeCount >= limit {
				continue
			}
			episodeCount++
		}
		entries = append(entries, watchHistoryEntry{
			ID:        fmt.Sprintf("local:%d:%s", item.ID, row.UpdatedAt),
			Item:      &item,
			Kind:      item.Kind,
			Title:     historyItemTitle(item),
			Subtitle:  historyItemSubtitle(item),
			Year:      item.Year,
			WatchedAt: row.UpdatedAt,
			Source:    "local",
		})
	}
	return watchHistoryResponse{Items: entries, Source: "local"}, nil
}

func (a *App) traktWatchHistory(r *http.Request, bearer string, limit int, startAt string) ([]watchHistoryEntry, error) {
	movies, err := a.traktHistoryPage(r, bearer, "movies", limit, startAt)
	if err != nil {
		return nil, err
	}
	episodes, err := a.traktHistoryPage(r, bearer, "episodes", limit, startAt)
	if err != nil {
		return nil, err
	}
	for i := range movies {
		movies[i].Type = "movie"
	}
	for i := range episodes {
		episodes[i].Type = "episode"
	}
	history := append(movies, episodes...)
	sort.SliceStable(history, func(i, j int) bool { return history[i].WatchedAt > history[j].WatchedAt })
	entries := make([]watchHistoryEntry, 0, len(history))
	for _, event := range history {
		title := ""
		subtitle := ""
		year := 0
		switch event.Type {
		case "movie":
			title = event.Movie.Title
			year = event.Movie.Year
		case "episode":
			title = event.Episode.Title
			subtitle = fmt.Sprintf("%s · S%02dE%02d", event.Show.Title, event.Episode.Season, event.Episode.Number)
			year = event.Show.Year
		}
		if title == "" {
			continue
		}
		watchedAt := event.WatchedAt
		if parsed, parseErr := time.Parse(time.RFC3339, watchedAt); parseErr == nil {
			watchedAt = parsed.UTC().Format(time.RFC3339)
		}
		entries = append(entries, watchHistoryEntry{
			ID:        fmt.Sprintf("trakt:%d", event.ID),
			Kind:      event.Type,
			Title:     title,
			Subtitle:  subtitle,
			Year:      year,
			WatchedAt: watchedAt,
			Source:    "trakt",
		})
	}
	return entries, nil
}

func historyItemTitle(item media.Item) string {
	if item.Kind == "episode" && item.EpisodeTitle != "" {
		return item.EpisodeTitle
	}
	return item.Title
}

func historyItemSubtitle(item media.Item) string {
	if item.Kind != "episode" {
		return ""
	}
	return fmt.Sprintf("%s · S%02dE%02d", item.ShowTitle, item.SeasonNumber, item.EpisodeNumber)
}

func (a *App) traktHistoryPage(r *http.Request, bearer, kind string, limit int, startAt string) ([]traktHistoryItem, error) {
	path := fmt.Sprintf("/sync/history/%s?page=1&limit=%d", kind, limit)
	if startAt != "" {
		path += "&start_at=" + url.QueryEscape(startAt)
	}
	resp, err := a.traktRequest(r.Context(), bearer, http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("trakt %s history failed: %s", kind, body)
	}
	var history []traktHistoryItem
	if err := json.Unmarshal(body, &history); err != nil {
		return nil, err
	}
	return history, nil
}

func (a *App) cachedWatchHistory(userID int64) (historyCacheEntry, bool) {
	a.historyMu.Lock()
	defer a.historyMu.Unlock()
	entry, exists := a.history[userID]
	return entry, exists
}

func (a *App) storeWatchHistory(userID int64, response watchHistoryResponse) {
	a.historyMu.Lock()
	a.history[userID] = historyCacheEntry{Response: response, RefreshedAt: time.Now()}
	a.historyMu.Unlock()
}

func mergeHistoryEntries(newEntries, cachedEntries []watchHistoryEntry) []watchHistoryEntry {
	seen := make(map[string]bool, len(newEntries)+len(cachedEntries))
	merged := make([]watchHistoryEntry, 0, len(newEntries)+len(cachedEntries))
	for _, entry := range append(newEntries, cachedEntries...) {
		if entry.ID == "" || seen[entry.ID] {
			continue
		}
		seen[entry.ID] = true
		merged = append(merged, entry)
	}
	sort.SliceStable(merged, func(i, j int) bool { return merged[i].WatchedAt > merged[j].WatchedAt })
	return merged
}
