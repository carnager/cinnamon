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
	if item.Kind != "movie" || !a.tmdbConfigured() {
		return []media.Item{}
	}
	tmdbID := strings.TrimSpace(item.TMDbID)
	if tmdbID == "" {
		tmdbID = a.tmdbMovieIDFromIMDb(ctx, item.IMDbID)
	}
	if tmdbID == "" {
		return []media.Item{}
	}
	// Prefer TMDb's curated /recommendations list — that is what the TMDb movie
	// page actually shows. Only fall back to the looser, genre-based /similar
	// list when none of the recommendations are in the library, otherwise the
	// row gets padded with unrelated same-genre blockbusters (e.g. Harry Potter
	// showing up under a Ghibli film).
	out := a.libraryItemsForTMDbMovieIDs(ctx, item.ID, a.fetchTMDbMovieList(ctx, "/3/movie/"+tmdbID+"/recommendations"))
	if len(out) == 0 {
		out = a.libraryItemsForTMDbMovieIDs(ctx, item.ID, a.fetchTMDbMovieList(ctx, "/3/movie/"+tmdbID+"/similar"))
	}
	return out
}

// fetchTMDbMovieList returns the de-duplicated TMDb movie ids from a list
// endpoint (recommendations or similar), kept in ranked order. It walks several
// pages because owned titles can sit past the first page of 20 results.
func (a *App) fetchTMDbMovieList(ctx context.Context, path string) []string {
	const maxPages = 5
	var ids []string
	seen := map[int]bool{}
	for page := 1; page <= maxPages; page++ {
		var res struct {
			Page       int `json:"page"`
			TotalPages int `json:"total_pages"`
			Results    []struct {
				ID int `json:"id"`
			} `json:"results"`
		}
		if err := a.tmdbGet(ctx, path, url.Values{"page": {strconv.Itoa(page)}}, &res); err != nil {
			break
		}
		for _, item := range res.Results {
			if item.ID > 0 && !seen[item.ID] {
				seen[item.ID] = true
				ids = append(ids, strconv.Itoa(item.ID))
			}
		}
		if len(res.Results) == 0 || page >= res.TotalPages {
			break
		}
	}
	return ids
}

// libraryItemsForTMDbMovieIDs intersects ranked TMDb ids with library movies,
// preserving rank order and excluding the source item. Capped at 24.
func (a *App) libraryItemsForTMDbMovieIDs(ctx context.Context, excludeID int64, ranked []string) []media.Item {
	out := []media.Item{}
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
	seen := map[int64]bool{excludeID: true}
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
