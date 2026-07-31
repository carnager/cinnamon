package server

import (
	"context"
	"math/rand"
	"net/http"

	"popcorn/internal/config"
	"popcorn/internal/media"
)

// surpriseMinRating keeps "Surprise Me" from serving the bottom of the library
// when there is anything decent left to watch.
const surpriseMinRating = 6.5

// surprise picks one unwatched movie or show at random. Clients used to do this
// by downloading 150 items of each library and rolling a die locally; the pick
// is a single indexed query, so it belongs here.
func (a *App) surprise(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	movieLib := firstLibraryOfType(a.cfg.Libraries, "movies", "movie")
	tvLib := firstLibraryOfType(a.cfg.Libraries, "tv")

	// Well-rated picks first; fall back to anything unseen so a library of
	// unrated files still answers.
	for _, minRating := range []float64{surpriseMinRating, 0} {
		item, show, err := a.surprisePick(r.Context(), user.ID, movieLib, tvLib, minRating)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if item != nil {
			writeJSON(w, http.StatusOK, map[string]any{"item": item})
			return
		}
		if show != nil {
			writeJSON(w, http.StatusOK, map[string]any{"show": show})
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{})
}

func (a *App) surprisePick(ctx context.Context, userID int64, movieLib, tvLib *config.Library, minRating float64) (*media.Item, *media.ShowSummary, error) {
	var movie *media.Item
	var show *media.ShowSummary
	if movieLib != nil {
		items, err := a.store.ListItemsForUser(ctx, movieLib.ID, "", "", "", "random", "unseen", userID, minRating, 1, 0)
		if err != nil {
			return nil, nil, err
		}
		if len(items) > 0 {
			movie = &items[0]
		}
	}
	if tvLib != nil {
		shows, err := a.store.ListShowsForUser(ctx, tvLib.ID, "", "", "", "random", "unseen", userID, minRating, 1, 0)
		if err != nil {
			return nil, nil, err
		}
		if len(shows) > 0 {
			show = &shows[0]
		}
	}
	if movie != nil && show != nil {
		if rand.Intn(2) == 0 {
			return movie, nil, nil
		}
		return nil, show, nil
	}
	return movie, show, nil
}
