package server

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/media"
)

// itemSimilar returns library movies related to the given item, ranked by
// TMDb's recommendations/similar lists and intersected with what is actually in
// the library. Empty when nothing matches (the client hides the row).
func (a *App) itemSimilar(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "similar", item.ID), 12*time.Hour, func() (any, error) {
		return a.similarItems(r.Context(), item), nil
	})
}

func (a *App) similarItems(ctx context.Context, item media.Item) []media.Item {
	out := []media.Item{}
	if item.Kind != "movie" || !a.tmdbConfigured() {
		return out
	}
	tmdbID := strings.TrimSpace(item.TMDbID)
	if tmdbID == "" {
		tmdbID = a.tmdbMovieIDFromIMDb(ctx, item.IMDbID)
	}
	if tmdbID == "" {
		return out
	}
	ranked := a.fetchTMDbSimilarMovieIDs(ctx, tmdbID)
	if len(ranked) == 0 {
		return out
	}
	libItems, err := a.store.ItemsByExternalIDs(ctx, "movie", ranked, nil)
	if err != nil {
		return out
	}
	byTMDb := make(map[string]media.Item, len(libItems))
	for _, it := range libItems {
		if id := strings.TrimSpace(it.TMDbID); id != "" {
			byTMDb[id] = it
		}
	}
	seen := map[int64]bool{item.ID: true}
	for _, id := range ranked {
		it, ok := byTMDb[id]
		if !ok || seen[it.ID] {
			continue
		}
		seen[it.ID] = true
		out = append(out, it)
		if len(out) >= 24 {
			break
		}
	}
	return out
}

// fetchTMDbSimilarMovieIDs returns TMDb movie ids related to tmdbID, preferring
// the curated /recommendations list and topping up with /similar, de-duplicated
// and kept in ranked order.
func (a *App) fetchTMDbSimilarMovieIDs(ctx context.Context, tmdbID string) []string {
	var ids []string
	seen := map[int]bool{}
	collect := func(path string) {
		var res struct {
			Results []struct {
				ID int `json:"id"`
			} `json:"results"`
		}
		if err := a.tmdbGet(ctx, path, url.Values{"page": {"1"}}, &res); err != nil {
			return
		}
		for _, item := range res.Results {
			if item.ID > 0 && !seen[item.ID] {
				seen[item.ID] = true
				ids = append(ids, strconv.Itoa(item.ID))
			}
		}
	}
	collect("/3/movie/" + tmdbID + "/recommendations")
	collect("/3/movie/" + tmdbID + "/similar")
	return ids
}

// tmdbMovieIDFromIMDb resolves a TMDb movie id from an IMDb id for the rare
// library item that has no tmdb_id of its own.
func (a *App) tmdbMovieIDFromIMDb(ctx context.Context, imdbID string) string {
	imdbID = strings.TrimSpace(imdbID)
	if imdbID == "" {
		return ""
	}
	var res struct {
		MovieResults []struct {
			ID int `json:"id"`
		} `json:"movie_results"`
	}
	if err := a.tmdbGet(ctx, "/3/find/"+imdbID, url.Values{"external_source": {"imdb_id"}}, &res); err != nil {
		return ""
	}
	if len(res.MovieResults) > 0 && res.MovieResults[0].ID > 0 {
		return strconv.Itoa(res.MovieResults[0].ID)
	}
	return ""
}
