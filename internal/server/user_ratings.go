package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"popcorn/internal/media"
)

type ratingBody struct {
	Rating int `json:"rating"`
}

func parseRatingBody(w http.ResponseWriter, r *http.Request) (int, bool) {
	var in ratingBody
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return 0, false
	}
	if in.Rating < 1 || in.Rating > 10 {
		http.Error(w, "rating must be 1-10", http.StatusBadRequest)
		return 0, false
	}
	return in.Rating, true
}

func (a *App) userRatingsGet(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	ratings, err := a.store.ListUserRatings(r.Context(), user.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, ratings)
}

func (a *App) itemRatingSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	rating, ok := parseRatingBody(w, r)
	if !ok {
		return
	}
	if err := a.store.SaveItemRating(r.Context(), user.ID, item, rating); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncRatingItems(user.ID, []media.Item{item}, rating, false)
	writeJSON(w, http.StatusOK, map[string]any{"itemId": item.ID, "rating": rating})
}

func (a *App) itemRatingDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteItemRating(r.Context(), user.ID, item.ID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncRatingItems(user.ID, []media.Item{item}, 0, true)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) showRatingSave(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	show, ok := a.lookupShowFromQuery(w, r)
	if !ok {
		return
	}
	rating, ok := parseRatingBody(w, r)
	if !ok {
		return
	}
	if err := a.store.SaveShowRating(r.Context(), user.ID, show.LibraryID, show.Title, rating); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncRatingShows(user.ID, []media.ShowSummary{show}, rating, false)
	writeJSON(w, http.StatusOK, map[string]any{"libraryId": show.LibraryID, "showTitle": show.Title, "rating": rating})
}

func (a *App) showRatingDelete(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	show, ok := a.lookupShowFromQuery(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteShowRating(r.Context(), user.ID, show.LibraryID, show.Title); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go a.traktSyncRatingShows(user.ID, []media.ShowSummary{show}, 0, true)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) traktSyncRatingItems(userID int64, items []media.Item, rating int, remove bool) {
	if !a.traktConfigured() || len(items) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt rating sync account unavailable", "user", userID, "error", err)
		}
		return
	}
	movies := []map[string]any{}
	episodes := []map[string]any{}
	for _, item := range items {
		entry := traktHistoryEntry(item, false, "")
		if entry == nil {
			a.log.Debug("trakt rating sync skipped, item cannot be identified", "item", item.ID)
			continue
		}
		if !remove {
			entry["rating"] = rating
		}
		switch item.Kind {
		case "movie":
			movies = append(movies, entry)
		case "episode":
			episodes = append(episodes, entry)
		}
	}
	body := map[string]any{}
	if len(movies) > 0 {
		body["movies"] = movies
	}
	if len(episodes) > 0 {
		body["episodes"] = episodes
	}
	a.traktSyncRatingsBody(ctx, account.AccessToken, body, remove)
}

func (a *App) traktSyncRatingShows(userID int64, shows []media.ShowSummary, rating int, remove bool) {
	if !a.traktConfigured() || len(shows) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt rating sync account unavailable", "user", userID, "error", err)
		}
		return
	}
	entries := []map[string]any{}
	for _, show := range shows {
		entry := map[string]any{"title": show.Title}
		if show.Year > 0 {
			entry["year"] = show.Year
		}
		if !remove {
			entry["rating"] = rating
		}
		entries = append(entries, entry)
	}
	a.traktSyncRatingsBody(ctx, account.AccessToken, map[string]any{"shows": entries}, remove)
}

func (a *App) traktSyncRatingsBody(ctx context.Context, bearer string, body map[string]any, remove bool) {
	if len(body) == 0 {
		return
	}
	path := "/sync/ratings"
	action := "add"
	if remove {
		path = "/sync/ratings/remove"
		action = "remove"
	}
	resp, err := a.traktRequest(ctx, bearer, http.MethodPost, path, body)
	if err != nil {
		a.log.Debug("trakt rating sync request failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Debug("trakt rating sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.log.Debug("trakt rating sync sent", "action", action)
}

type traktRatingItem struct {
	RatedAt string `json:"rated_at"`
	Rating  int    `json:"rating"`
	Type    string `json:"type"`
	Movie   struct {
		Title string          `json:"title"`
		Year  int             `json:"year"`
		IDs   traktIDsPayload `json:"ids"`
	} `json:"movie"`
	Show struct {
		Title string          `json:"title"`
		Year  int             `json:"year"`
		IDs   traktIDsPayload `json:"ids"`
	} `json:"show"`
	Episode struct {
		Title  string          `json:"title"`
		Season int             `json:"season"`
		Number int             `json:"number"`
		IDs    traktIDsPayload `json:"ids"`
	} `json:"episode"`
}

func (a *App) traktRatings(ctx context.Context, bearer string) (movies, shows, episodes []traktRatingItem, err error) {
	if _, err = a.traktPaged(ctx, bearer, "/sync/ratings/movies", &movies); err != nil {
		return nil, nil, nil, err
	}
	if _, err = a.traktPaged(ctx, bearer, "/sync/ratings/shows", &shows); err != nil {
		return nil, nil, nil, err
	}
	if _, err = a.traktPaged(ctx, bearer, "/sync/ratings/episodes", &episodes); err != nil {
		return nil, nil, nil, err
	}
	return movies, shows, episodes, nil
}

// traktImportRatings pulls the user's Trakt ratings and stores every one that
// matches a library item or show — the fresh-install path for ratings.
func (a *App) traktImportRatings(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	account, err := a.traktAccountForRequest(r.Context(), user.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "trakt account is not linked", http.StatusConflict)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	items, err := a.store.AllItems(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	shows, err := a.store.ListShows(r.Context(), "", "", "", "", 0, 1000, 0)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	itemIndex := newTraktImportIndex(items)
	showIndex := newTraktShowIndex(shows)
	ratedMovies, ratedShows, ratedEpisodes, err := a.traktRatings(r.Context(), account.AccessToken)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	summary := traktWatchlistImportSummary{
		EntriesSeen:  len(ratedMovies) + len(ratedShows) + len(ratedEpisodes),
		MoviesSeen:   len(ratedMovies),
		ShowsSeen:    len(ratedShows),
		EpisodesSeen: len(ratedEpisodes),
	}
	seenItems := map[int64]struct{}{}
	saveItem := func(entry traktRatingItem, item *media.Item, matched *int, label string) bool {
		if entry.Rating < 1 || entry.Rating > 10 {
			return true
		}
		if _, ok := seenItems[item.ID]; ok {
			return true
		}
		if err := a.store.SaveItemRating(r.Context(), user.ID, *item, entry.Rating); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return false
		}
		seenItems[item.ID] = struct{}{}
		*matched++
		summary.ItemsMarked++
		summary.Matched = appendSample(summary.Matched, fmt.Sprintf("%s -> %s (%d/10)", label, item.Title, entry.Rating))
		return true
	}
	for _, movie := range ratedMovies {
		item, _ := itemIndex.matchMovie(movie.Movie.Title, movie.Movie.Year, movie.Movie.IDs)
		if item == nil {
			summary.MoviesUnmatched++
			summary.Unmatched = appendSample(summary.Unmatched, fmt.Sprintf("movie: %s (%d)", movie.Movie.Title, movie.Movie.Year))
			continue
		}
		if !saveItem(movie, item, &summary.MoviesMatched, fmt.Sprintf("%s (%d)", movie.Movie.Title, movie.Movie.Year)) {
			return
		}
	}
	for _, episode := range ratedEpisodes {
		item, _ := itemIndex.matchEpisode(episode.Show.Title, episode.Show.Year, episode.Show.IDs, episode.Episode.IDs, episode.Episode.Season, episode.Episode.Number)
		if item == nil {
			summary.EpisodesUnmatched++
			summary.Unmatched = appendSample(summary.Unmatched, fmt.Sprintf("episode: %s S%02dE%02d", episode.Show.Title, episode.Episode.Season, episode.Episode.Number))
			continue
		}
		if !saveItem(episode, item, &summary.EpisodesMatched, fmt.Sprintf("%s S%02dE%02d", episode.Show.Title, episode.Episode.Season, episode.Episode.Number)) {
			return
		}
	}
	seenShows := map[string]struct{}{}
	for _, traktShow := range ratedShows {
		if traktShow.Rating < 1 || traktShow.Rating > 10 {
			continue
		}
		show, _ := showIndex.matchShow(traktShow.Show.Title, traktShow.Show.Year, traktShow.Show.IDs)
		if show == nil {
			summary.ShowsUnmatched++
			summary.Unmatched = appendSample(summary.Unmatched, fmt.Sprintf("show: %s (%d)", traktShow.Show.Title, traktShow.Show.Year))
			continue
		}
		key := strings.ToLower(show.LibraryID + "\n" + show.Title)
		if _, ok := seenShows[key]; ok {
			continue
		}
		if err := a.store.SaveShowRating(r.Context(), user.ID, show.LibraryID, show.Title, traktShow.Rating); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seenShows[key] = struct{}{}
		summary.ShowsMatched++
		summary.ShowsMarked++
		summary.Matched = appendSample(summary.Matched, fmt.Sprintf("%s (%d) -> %s (%d/10)", traktShow.Show.Title, traktShow.Show.Year, show.Title, traktShow.Rating))
	}
	writeJSON(w, http.StatusOK, summary)
}
