package server

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/media"
)

var (
	tmdbAPIBaseURL = "https://api.themoviedb.org"
	omdbAPIBaseURL = "https://www.omdbapi.com/"
)

type externalRatings struct {
	ItemID               int64   `json:"itemId"`
	IMDbID               string  `json:"imdbId,omitempty"`
	TMDbID               string  `json:"tmdbId,omitempty"`
	LocalRating          float64 `json:"localRating,omitempty"`
	IMDbRating           float64 `json:"imdbRating,omitempty"`
	TMDbRating           float64 `json:"tmdbRating,omitempty"`
	RottenTomatoesRating int     `json:"rottenTomatoesRating,omitempty"`
	MetacriticRating     int     `json:"metacriticRating,omitempty"`
	FetchedAt            string  `json:"fetchedAt,omitempty"`
	Source               string  `json:"source,omitempty"`
	TMDbConfigured       bool    `json:"tmdbConfigured"`
	OMDbConfigured       bool    `json:"omdbConfigured"`
}

func (a *App) itemRatings(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	ratings, err := a.ratingsForItem(r.Context(), item)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, ratings)
}

func (a *App) ratingsForItem(ctx context.Context, item media.Item) (externalRatings, error) {
	out := externalRatings{
		ItemID:         item.ID,
		IMDbID:         item.IMDbID,
		TMDbID:         item.TMDbID,
		LocalRating:    item.Rating,
		TMDbConfigured: a.tmdbConfigured(),
		OMDbConfigured: strings.TrimSpace(a.cfg.OMDbAPIKey) != "",
	}
	a.fillRatingsFromNFO(item, &out)
	if cached, ok := a.cachedRatings(ctx, item.ID); ok && !ratingsCacheExpired(cached.FetchedAt) {
		updated := false
		if out.IMDbRating > 0 && cached.IMDbRating == 0 {
			cached.IMDbRating = out.IMDbRating
			updated = true
		}
		if out.RottenTomatoesRating > 0 && cached.RottenTomatoesRating == 0 {
			cached.RottenTomatoesRating = out.RottenTomatoesRating
			updated = true
		}
		if out.TMDbRating > 0 && cached.TMDbRating == 0 {
			cached.TMDbRating = out.TMDbRating
			updated = true
		}
		if out.MetacriticRating > 0 && cached.MetacriticRating == 0 {
			cached.MetacriticRating = out.MetacriticRating
			updated = true
		}
		if cached.IMDbID == "" {
			cached.IMDbID = out.IMDbID
			updated = true
		}
		if cached.TMDbID == "" {
			cached.TMDbID = out.TMDbID
			updated = true
		}
		if out.TMDbConfigured {
			before := cached
			a.fillFromTMDb(ctx, item, &cached)
			if cached != before {
				updated = true
			}
		}
		if out.OMDbConfigured && cached.IMDbID != "" && (cached.IMDbRating == 0 || cached.RottenTomatoesRating == 0 || cached.MetacriticRating == 0) {
			before := cached
			a.fillRatingsFromOMDb(ctx, cached.IMDbID, &cached)
			if cached != before {
				updated = true
			}
		}
		if updated {
			cached.FetchedAt = time.Now().UTC().Format(time.RFC3339)
			_ = a.saveRatingsCache(ctx, cached)
		}
		cached.LocalRating = item.Rating
		cached.TMDbConfigured = out.TMDbConfigured
		cached.OMDbConfigured = out.OMDbConfigured
		return cached, nil
	}
	if out.TMDbConfigured {
		a.fillFromTMDb(ctx, item, &out)
	}
	if out.OMDbConfigured && out.IMDbID != "" {
		a.fillRatingsFromOMDb(ctx, out.IMDbID, &out)
	}
	out.FetchedAt = time.Now().UTC().Format(time.RFC3339)
	_ = a.saveRatingsCache(ctx, out)
	return out, nil
}

func (a *App) fillRatingsFromNFO(item media.Item, ratings *externalRatings) {
	sourceRatings := media.ReadNFOSourceRatings(item.NFOPath)
	if sourceRatings.IMDb > 0 {
		ratings.IMDbRating = sourceRatings.IMDb
		ratings.Source = appendSource(ratings.Source, "nfo")
	}
	if sourceRatings.RottenTomatoes > 0 {
		ratings.RottenTomatoesRating = sourceRatings.RottenTomatoes
		ratings.Source = appendSource(ratings.Source, "nfo")
	}
	if sourceRatings.TMDb > 0 {
		ratings.TMDbRating = sourceRatings.TMDb
		ratings.Source = appendSource(ratings.Source, "nfo")
	}
	if sourceRatings.Metacritic > 0 {
		ratings.MetacriticRating = sourceRatings.Metacritic
		ratings.Source = appendSource(ratings.Source, "nfo")
	}
}

func (a *App) cachedRatings(ctx context.Context, itemID int64) (externalRatings, bool) {
	row := a.store.DB().QueryRowContext(ctx, `
SELECT item_id, COALESCE(imdb_id, ''), COALESCE(tmdb_id, ''), COALESCE(imdb_rating, 0), COALESCE(tmdb_rating, 0), COALESCE(rotten_tomatoes_rating, 0), COALESCE(metacritic_rating, 0), source, fetched_at
FROM external_ratings_cache
WHERE item_id = ?`, itemID)
	var out externalRatings
	if err := row.Scan(&out.ItemID, &out.IMDbID, &out.TMDbID, &out.IMDbRating, &out.TMDbRating, &out.RottenTomatoesRating, &out.MetacriticRating, &out.Source, &out.FetchedAt); err != nil {
		return externalRatings{}, false
	}
	return out, true
}

func (a *App) saveRatingsCache(ctx context.Context, ratings externalRatings) error {
	_, err := a.store.DB().ExecContext(ctx, `
INSERT INTO external_ratings_cache(item_id, imdb_id, tmdb_id, imdb_rating, tmdb_rating, rotten_tomatoes_rating, metacritic_rating, source, fetched_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(item_id) DO UPDATE SET
	imdb_id=excluded.imdb_id,
	tmdb_id=excluded.tmdb_id,
	imdb_rating=excluded.imdb_rating,
	tmdb_rating=excluded.tmdb_rating,
	rotten_tomatoes_rating=excluded.rotten_tomatoes_rating,
	metacritic_rating=excluded.metacritic_rating,
	source=excluded.source,
	fetched_at=excluded.fetched_at`,
		ratings.ItemID, nullString(ratings.IMDbID), nullString(ratings.TMDbID), nullableFloat(ratings.IMDbRating), nullableFloat(ratings.TMDbRating), nullableInt(ratings.RottenTomatoesRating), nullableInt(ratings.MetacriticRating), ratings.Source, ratings.FetchedAt)
	return err
}

func ratingsCacheExpired(fetchedAt string) bool {
	t, err := time.Parse(time.RFC3339, fetchedAt)
	if err != nil {
		return true
	}
	return time.Since(t) > 7*24*time.Hour
}

func (a *App) fillFromTMDb(ctx context.Context, item media.Item, ratings *externalRatings) {
	a.fillIDsFromTMDb(ctx, item, ratings)
	if ratings.TMDbRating != 0 {
		return
	}
	if item.Kind == "movie" && ratings.TMDbID == "" {
		return
	}
	switch item.Kind {
	case "movie":
		a.fillMovieRatingFromTMDb(ctx, ratings)
	case "episode":
		a.fillEpisodeRatingFromTMDb(ctx, item, ratings)
	}
}

func (a *App) fillIDsFromTMDb(ctx context.Context, item media.Item, ratings *externalRatings) {
	if ratings.TMDbID != "" && ratings.IMDbID == "" && item.Kind == "movie" {
		var raw struct {
			IMDbID string `json:"imdb_id"`
		}
		if a.tmdbGet(ctx, "/3/movie/"+url.PathEscape(ratings.TMDbID)+"/external_ids", nil, &raw) == nil {
			ratings.IMDbID = raw.IMDbID
			ratings.Source = appendSource(ratings.Source, "tmdb")
		}
		return
	}
	if ratings.IMDbID != "" && ratings.TMDbID == "" {
		var raw struct {
			MovieResults []struct {
				ID int `json:"id"`
			} `json:"movie_results"`
			TVEpisodeResults []tmdbEpisodeResult `json:"tv_episode_results"`
			TVResults        []struct {
				ID int `json:"id"`
			} `json:"tv_results"`
		}
		values := url.Values{"external_source": {"imdb_id"}}
		if a.tmdbGet(ctx, "/3/find/"+url.PathEscape(ratings.IMDbID), values, &raw) == nil {
			if item.Kind == "movie" && len(raw.MovieResults) > 0 {
				ratings.TMDbID = strconv.Itoa(raw.MovieResults[0].ID)
				ratings.Source = appendSource(ratings.Source, "tmdb")
			} else if item.Kind == "episode" {
				if episode, ok := matchingTMDbEpisode(raw.TVEpisodeResults, item); ok {
					ratings.TMDbID = strconv.Itoa(episode.ID)
					ratings.Source = appendSource(ratings.Source, "tmdb")
				}
			}
		}
	}
	if item.Kind != "episode" || ratings.IMDbID != "" {
		return
	}
	if episode, ok := a.tmdbEpisodeLocation(ctx, item, ratings); ok {
		var raw struct {
			IMDbID string `json:"imdb_id"`
		}
		path := fmt.Sprintf("/3/tv/%d/season/%d/episode/%d/external_ids", episode.ShowID, episode.SeasonNumber, episode.EpisodeNumber)
		if a.tmdbGet(ctx, path, nil, &raw) == nil && raw.IMDbID != "" {
			ratings.IMDbID = raw.IMDbID
			if episode.ID > 0 && ratings.TMDbID == "" {
				ratings.TMDbID = strconv.Itoa(episode.ID)
			}
			ratings.Source = appendSource(ratings.Source, "tmdb")
		}
	}
}

type tmdbEpisodeResult struct {
	ID            int `json:"id"`
	ShowID        int `json:"show_id"`
	SeasonNumber  int `json:"season_number"`
	EpisodeNumber int `json:"episode_number"`
}

func matchingTMDbEpisode(results []tmdbEpisodeResult, item media.Item) (tmdbEpisodeResult, bool) {
	for _, episode := range results {
		if item.SeasonNumber > 0 && episode.SeasonNumber != item.SeasonNumber {
			continue
		}
		if item.EpisodeNumber > 0 && episode.EpisodeNumber != item.EpisodeNumber {
			continue
		}
		return episode, true
	}
	if len(results) > 0 {
		return results[0], true
	}
	return tmdbEpisodeResult{}, false
}

func (a *App) tmdbEpisodeLocation(ctx context.Context, item media.Item, ratings *externalRatings) (tmdbEpisodeResult, bool) {
	if item.Kind != "episode" || item.SeasonNumber < 0 || item.EpisodeNumber <= 0 {
		return tmdbEpisodeResult{}, false
	}
	showID := a.tmdbShowIDForEpisode(ctx, item)
	if showID > 0 {
		return tmdbEpisodeResult{
			ID:            atoi(ratings.TMDbID),
			ShowID:        showID,
			SeasonNumber:  item.SeasonNumber,
			EpisodeNumber: item.EpisodeNumber,
		}, true
	}
	if ratings.IMDbID == "" {
		return tmdbEpisodeResult{}, false
	}
	var raw struct {
		TVEpisodeResults []tmdbEpisodeResult `json:"tv_episode_results"`
	}
	values := url.Values{"external_source": {"imdb_id"}}
	if a.tmdbGet(ctx, "/3/find/"+url.PathEscape(ratings.IMDbID), values, &raw) != nil {
		return tmdbEpisodeResult{}, false
	}
	return matchingTMDbEpisode(raw.TVEpisodeResults, item)
}

func (a *App) tmdbShowIDForEpisode(ctx context.Context, item media.Item) int {
	showNFO := media.ShowNFOPath(a.libraryRoot(item.LibraryID), item.Path)
	if showNFO == "" {
		return 0
	}
	imdbID, tmdbID, tvdbID := media.ReadNFOExternalIDs(showNFO)
	if n := atoi(tmdbID); n > 0 {
		return n
	}
	for _, source := range []struct {
		id     string
		source string
	}{
		{imdbID, "imdb_id"},
		{tvdbID, "tvdb_id"},
	} {
		if source.id == "" {
			continue
		}
		var raw struct {
			TVResults []struct {
				ID int `json:"id"`
			} `json:"tv_results"`
		}
		values := url.Values{"external_source": {source.source}}
		if a.tmdbGet(ctx, "/3/find/"+url.PathEscape(source.id), values, &raw) == nil && len(raw.TVResults) > 0 {
			return raw.TVResults[0].ID
		}
	}
	return 0
}

func (a *App) fillEpisodeRatingFromTMDb(ctx context.Context, item media.Item, ratings *externalRatings) {
	episode, ok := a.tmdbEpisodeLocation(ctx, item, ratings)
	if !ok {
		return
	}
	var raw struct {
		ID          int     `json:"id"`
		VoteAverage float64 `json:"vote_average"`
	}
	path := fmt.Sprintf("/3/tv/%d/season/%d/episode/%d", episode.ShowID, episode.SeasonNumber, episode.EpisodeNumber)
	if a.tmdbGet(ctx, path, nil, &raw) != nil {
		return
	}
	if raw.ID > 0 {
		ratings.TMDbID = strconv.Itoa(raw.ID)
	}
	if raw.VoteAverage > 0 {
		ratings.TMDbRating = raw.VoteAverage
		ratings.Source = appendSource(ratings.Source, "tmdb")
	}
}

func atoi(v string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(v))
	return n
}

func (a *App) fillMovieRatingFromTMDb(ctx context.Context, ratings *externalRatings) {
	var raw struct {
		VoteAverage float64 `json:"vote_average"`
	}
	if a.tmdbGet(ctx, "/3/movie/"+url.PathEscape(ratings.TMDbID), nil, &raw) != nil {
		return
	}
	if raw.VoteAverage > 0 {
		ratings.TMDbRating = raw.VoteAverage
		ratings.Source = appendSource(ratings.Source, "tmdb")
	}
}

func (a *App) fillRatingsFromOMDb(ctx context.Context, imdbID string, ratings *externalRatings) {
	values := url.Values{"apikey": {a.cfg.OMDbAPIKey}, "i": {imdbID}, "tomatoes": {"true"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, omdbAPIBaseURL+"?"+values.Encode(), nil)
	if err != nil {
		return
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 128*1024))
	var raw struct {
		IMDbRating string `json:"imdbRating"`
		Metascore  string `json:"Metascore"`
		Ratings    []struct {
			Source string `json:"Source"`
			Value  string `json:"Value"`
		} `json:"Ratings"`
	}
	if json.Unmarshal(body, &raw) != nil {
		return
	}
	if v, err := strconv.ParseFloat(raw.IMDbRating, 64); err == nil {
		ratings.IMDbRating = v
	}
	if v, err := strconv.Atoi(raw.Metascore); err == nil {
		ratings.MetacriticRating = v
	}
	for _, entry := range raw.Ratings {
		switch strings.ToLower(entry.Source) {
		case "rotten tomatoes":
			if strings.HasSuffix(entry.Value, "%") {
				if v, err := strconv.Atoi(strings.TrimSuffix(entry.Value, "%")); err == nil {
					ratings.RottenTomatoesRating = v
				}
			}
		case "metacritic":
			if parts := strings.Split(entry.Value, "/"); len(parts) > 0 {
				if v, err := strconv.Atoi(parts[0]); err == nil {
					ratings.MetacriticRating = v
				}
			}
		}
	}
	ratings.Source = appendSource(ratings.Source, "omdb")
}

func (a *App) tmdbGet(ctx context.Context, path string, values url.Values, dest any) error {
	if values == nil {
		values = url.Values{}
	}
	if strings.TrimSpace(a.cfg.TMDbReadToken) == "" {
		values.Set("api_key", a.cfg.TMDbAPIKey)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(tmdbAPIBaseURL, "/")+path+"?"+values.Encode(), nil)
	if err != nil {
		return err
	}
	if token := strings.TrimSpace(a.cfg.TMDbReadToken); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Accept", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 128*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("tmdb request failed: %s", strings.TrimSpace(string(body)))
	}
	return json.Unmarshal(body, dest)
}

func (a *App) tmdbConfigured() bool {
	return strings.TrimSpace(a.cfg.TMDbAPIKey) != "" || strings.TrimSpace(a.cfg.TMDbReadToken) != ""
}

func appendSource(current, next string) string {
	if current == "" {
		return next
	}
	if strings.Contains(","+current+",", ","+next+",") {
		return current
	}
	return current + "," + next
}

func nullString(v string) any {
	if v == "" {
		return nil
	}
	return v
}

func nullableFloat(v float64) any {
	if v == 0 {
		return nil
	}
	return v
}

func nullableInt(v int) any {
	if v == 0 {
		return nil
	}
	return v
}
