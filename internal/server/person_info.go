package server

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/media"
)

func (a *App) actorInfo(ctx context.Context, actor media.Actor) media.ActorInfo {
	name := strings.TrimSpace(actor.Name)
	if name == "" {
		return media.ActorInfo{}
	}
	if cached, ok := a.store.CachedActorInfo(ctx, name); ok && !actorInfoCacheExpired(cached.FetchedAt) {
		return cached
	}
	info := media.ActorInfo{
		Name:      name,
		Source:    "local",
		FetchedAt: time.Now().UTC().Format(time.RFC3339),
	}
	if a.tmdbConfigured() {
		if tmdbInfo, ok := a.fetchActorInfoFromTMDb(ctx, name); ok {
			info = tmdbInfo
		}
	}
	_ = a.store.SaveActorInfo(ctx, info)
	return info
}

func (a *App) fetchActorInfoFromTMDb(ctx context.Context, name string) (media.ActorInfo, bool) {
	var search struct {
		Results []struct {
			ID                 int     `json:"id"`
			Name               string  `json:"name"`
			KnownForDepartment string  `json:"known_for_department"`
			ProfilePath        string  `json:"profile_path"`
			Popularity         float64 `json:"popularity"`
		} `json:"results"`
	}
	values := url.Values{
		"query":         {name},
		"include_adult": {"false"},
	}
	if err := a.tmdbGet(ctx, "/3/search/person", values, &search); err != nil || len(search.Results) == 0 {
		return media.ActorInfo{}, false
	}
	best := search.Results[0]
	for _, candidate := range search.Results {
		if strings.EqualFold(strings.TrimSpace(candidate.Name), name) {
			best = candidate
			break
		}
	}
	var detail struct {
		ID                 int    `json:"id"`
		Name               string `json:"name"`
		Biography          string `json:"biography"`
		Birthday           string `json:"birthday"`
		Deathday           string `json:"deathday"`
		PlaceOfBirth       string `json:"place_of_birth"`
		KnownForDepartment string `json:"known_for_department"`
		ProfilePath        string `json:"profile_path"`
		ExternalIDs        struct {
			IMDbID string `json:"imdb_id"`
		} `json:"external_ids"`
	}
	values = url.Values{"append_to_response": {"external_ids"}}
	if err := a.tmdbGet(ctx, "/3/person/"+strconv.Itoa(best.ID), values, &detail); err != nil {
		return media.ActorInfo{
			Name:               firstNonEmpty(best.Name, name),
			TMDbID:             strconv.Itoa(best.ID),
			KnownForDepartment: best.KnownForDepartment,
			ProfilePath:        best.ProfilePath,
			Source:             "tmdb-search",
			FetchedAt:          time.Now().UTC().Format(time.RFC3339),
		}, true
	}
	return media.ActorInfo{
		Name:               firstNonEmpty(detail.Name, best.Name, name),
		TMDbID:             strconv.Itoa(detail.ID),
		IMDbID:             detail.ExternalIDs.IMDbID,
		Biography:          detail.Biography,
		Birthday:           detail.Birthday,
		Deathday:           detail.Deathday,
		PlaceOfBirth:       detail.PlaceOfBirth,
		KnownForDepartment: firstNonEmpty(detail.KnownForDepartment, best.KnownForDepartment),
		ProfilePath:        firstNonEmpty(detail.ProfilePath, best.ProfilePath),
		Source:             "tmdb",
		FetchedAt:          time.Now().UTC().Format(time.RFC3339),
	}, true
}

func actorInfoCacheExpired(fetchedAt string) bool {
	t, err := time.Parse(time.RFC3339, fetchedAt)
	if err != nil {
		return true
	}
	return time.Since(t) > 30*24*time.Hour
}

func tmdbProfileURL(path string) string {
	path = strings.TrimSpace(path)
	if path == "" {
		return ""
	}
	if strings.HasPrefix(path, "http://") || strings.HasPrefix(path, "https://") {
		return path
	}
	return "https://image.tmdb.org/t/p/h632/" + strings.TrimPrefix(path, "/")
}
