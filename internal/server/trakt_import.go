package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"popcorn/internal/media"
)

type traktToken struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
}

type traktImportSummary struct {
	MoviesSeen        int              `json:"moviesSeen"`
	MoviesMatched     int              `json:"moviesMatched"`
	MoviesUnmatched   int              `json:"moviesUnmatched"`
	ShowsSeen         int              `json:"showsSeen"`
	EpisodesSeen      int              `json:"episodesSeen"`
	EpisodesMatched   int              `json:"episodesMatched"`
	EpisodesUnmatched int              `json:"episodesUnmatched"`
	ItemsMarked       int              `json:"itemsMarked"`
	Debug             traktImportDebug `json:"debug"`
}

type traktImportDebug struct {
	LocalMovies       int                       `json:"localMovies"`
	LocalEpisodes     int                       `json:"localEpisodes"`
	MovieIDs          int                       `json:"movieIds"`
	EpisodeIDs        int                       `json:"episodeIds"`
	SyncWatchedMovies int                       `json:"syncWatchedMovies"`
	UserWatchedMovies int                       `json:"userWatchedMovies"`
	HistoryMovies     int                       `json:"historyMovies"`
	AllHistoryMovies  int                       `json:"allHistoryMovies"`
	SyncWatchedShows  int                       `json:"syncWatchedShows"`
	UserWatchedShows  int                       `json:"userWatchedShows"`
	AllHistory        int                       `json:"allHistory"`
	TraktStats        *traktUserStats           `json:"traktStats,omitempty"`
	MatchedMovies     []string                  `json:"matchedMovies,omitempty"`
	UnmatchedMovies   []string                  `json:"unmatchedMovies,omitempty"`
	MatchedEpisodes   []string                  `json:"matchedEpisodes,omitempty"`
	UnmatchedEpisodes []string                  `json:"unmatchedEpisodes,omitempty"`
	TraktSources      map[string]traktPageDebug `json:"traktSources,omitempty"`
}

type traktPageDebug struct {
	Items           int `json:"items"`
	PagesFetched    int `json:"pagesFetched"`
	Limit           int `json:"limit"`
	HeaderItemCount int `json:"headerItemCount,omitempty"`
	HeaderPageCount int `json:"headerPageCount,omitempty"`
}

type traktUserStats struct {
	Movies struct {
		Plays     int `json:"plays"`
		Watched   int `json:"watched"`
		Collected int `json:"collected"`
		Ratings   int `json:"ratings"`
	} `json:"movies"`
	Shows struct {
		Watched   int `json:"watched"`
		Collected int `json:"collected"`
		Ratings   int `json:"ratings"`
	} `json:"shows"`
	Episodes struct {
		Plays     int `json:"plays"`
		Watched   int `json:"watched"`
		Collected int `json:"collected"`
		Ratings   int `json:"ratings"`
	} `json:"episodes"`
}

type traktIDsPayload struct {
	Trakt int    `json:"trakt"`
	Slug  string `json:"slug"`
	IMDb  string `json:"imdb"`
	TMDb  int    `json:"tmdb"`
	TVDb  int    `json:"tvdb"`
}

type traktWatchedMovie struct {
	Plays         int    `json:"plays"`
	LastWatchedAt string `json:"last_watched_at"`
	Movie         struct {
		Title string          `json:"title"`
		Year  int             `json:"year"`
		IDs   traktIDsPayload `json:"ids"`
	} `json:"movie"`
}

type traktWatchedShow struct {
	Plays         int    `json:"plays"`
	LastWatchedAt string `json:"last_watched_at"`
	Show          struct {
		Title string          `json:"title"`
		Year  int             `json:"year"`
		IDs   traktIDsPayload `json:"ids"`
	} `json:"show"`
	Seasons []struct {
		Number   int `json:"number"`
		Episodes []struct {
			Number int `json:"number"`
			Plays  int `json:"plays"`
		} `json:"episodes"`
	} `json:"seasons"`
}

type traktHistoryItem struct {
	ID        int64  `json:"id"`
	Type      string `json:"type"`
	WatchedAt string `json:"watched_at"`
	Movie     struct {
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

type traktWatchlistItem struct {
	ListedAt string `json:"listed_at"`
	Type     string `json:"type"`
	Movie    struct {
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

type traktWatchlistSource struct {
	Movies        []traktWatchlistItem
	Shows         []traktWatchlistItem
	Episodes      []traktWatchlistItem
	MoviesDebug   traktPageDebug
	ShowsDebug    traktPageDebug
	EpisodesDebug traktPageDebug
}

type traktWatchlistImportSummary struct {
	EntriesSeen       int                       `json:"entriesSeen"`
	MoviesSeen        int                       `json:"moviesSeen"`
	MoviesMatched     int                       `json:"moviesMatched"`
	MoviesUnmatched   int                       `json:"moviesUnmatched"`
	ShowsSeen         int                       `json:"showsSeen"`
	ShowsMatched      int                       `json:"showsMatched"`
	ShowsUnmatched    int                       `json:"showsUnmatched"`
	EpisodesSeen      int                       `json:"episodesSeen"`
	EpisodesMatched   int                       `json:"episodesMatched"`
	EpisodesUnmatched int                       `json:"episodesUnmatched"`
	ItemsMarked       int                       `json:"itemsMarked"`
	ShowsMarked       int                       `json:"showsMarked"`
	Matched           []string                  `json:"matched,omitempty"`
	Unmatched         []string                  `json:"unmatched,omitempty"`
	TraktSources      map[string]traktPageDebug `json:"traktSources,omitempty"`
}

type traktExportSource struct {
	Movies      []traktWatchedMovie
	Episodes    []traktHistoryItem
	ShowsSeen   int
	AllHistory  int
	MovieRows   int
	EpisodeRows int
}

type traktImportIndex struct {
	moviesByIMDb   map[string]media.Item
	moviesByTMDb   map[int]media.Item
	moviesByTitle  map[string]media.Item
	episodesByIMDb map[string]media.Item
	episodesByTMDb map[int]media.Item
	episodesByTVDb map[int]media.Item
	episodes       map[string]media.Item
	localMovies    int
	localEpisodes  int
	movieIDs       int
	episodeIDs     int
}

type traktShowIndex struct {
	byTitle map[string]media.ShowSummary
}

func newTraktShowIndex(shows []media.ShowSummary) traktShowIndex {
	index := traktShowIndex{byTitle: map[string]media.ShowSummary{}}
	for _, show := range shows {
		for _, key := range titleKeys(show.Title, show.OriginalTitle, show.Year) {
			if _, exists := index.byTitle[key]; !exists {
				index.byTitle[key] = show
			}
		}
	}
	return index
}

func (i traktShowIndex) matchShow(title string, year int, ids traktIDsPayload) (*media.ShowSummary, string) {
	_ = ids
	for _, key := range titleKeys(title, "", year) {
		if show, ok := i.byTitle[key]; ok {
			if strings.Contains(key, ":") {
				return &show, "title-year"
			}
			return &show, "title"
		}
	}
	return nil, ""
}

func newTraktImportIndex(items []media.Item) traktImportIndex {
	index := traktImportIndex{
		moviesByIMDb:   map[string]media.Item{},
		moviesByTMDb:   map[int]media.Item{},
		moviesByTitle:  map[string]media.Item{},
		episodesByIMDb: map[string]media.Item{},
		episodesByTMDb: map[int]media.Item{},
		episodesByTVDb: map[int]media.Item{},
		episodes:       map[string]media.Item{},
	}
	for _, item := range items {
		switch item.Kind {
		case "movie":
			index.localMovies++
			imdbID, tmdbID := item.IMDbID, item.TMDbID
			if imdbID == "" && tmdbID == "" && item.NFOPath != "" {
				imdbID, tmdbID = media.ReadNFOIDs(item.NFOPath)
			}
			hadID := false
			if imdbID != "" {
				index.moviesByIMDb[strings.ToLower(imdbID)] = item
				hadID = true
			}
			if n, err := strconv.Atoi(tmdbID); err == nil && n > 0 {
				index.moviesByTMDb[n] = item
				hadID = true
			}
			if hadID {
				index.movieIDs++
			}
			for _, key := range titleKeys(item.Title, item.OriginalTitle, item.Year) {
				if _, exists := index.moviesByTitle[key]; !exists {
					index.moviesByTitle[key] = item
				}
			}
		case "episode":
			index.localEpisodes++
			imdbID, tmdbID, tvdbID := item.IMDbID, item.TMDbID, item.TVDbID
			if (imdbID == "" || tmdbID == "" || tvdbID == "") && item.NFOPath != "" {
				nfoIMDb, nfoTMDb, nfoTVDb := media.ReadNFOExternalIDs(item.NFOPath)
				if imdbID == "" {
					imdbID = nfoIMDb
				}
				if tmdbID == "" {
					tmdbID = nfoTMDb
				}
				if tvdbID == "" {
					tvdbID = nfoTVDb
				}
			}
			hadID := false
			if imdbID != "" {
				index.episodesByIMDb[strings.ToLower(imdbID)] = item
				hadID = true
			}
			if n, err := strconv.Atoi(tmdbID); err == nil && n > 0 {
				index.episodesByTMDb[n] = item
				hadID = true
			}
			if n, err := strconv.Atoi(tvdbID); err == nil && n > 0 {
				index.episodesByTVDb[n] = item
				hadID = true
			}
			if hadID {
				index.episodeIDs++
			}
			for _, show := range []string{item.ShowTitle, item.OriginalTitle} {
				key := episodeKey(show, item.SeasonNumber, item.EpisodeNumber)
				if key != "" {
					index.episodes[key] = item
				}
			}
		}
	}
	return index
}

func (i traktImportIndex) matchMovie(title string, year int, ids traktIDsPayload) (*media.Item, string) {
	if ids.IMDb != "" {
		if item, ok := i.moviesByIMDb[strings.ToLower(ids.IMDb)]; ok {
			return &item, "imdb"
		}
	}
	if ids.TMDb > 0 {
		if item, ok := i.moviesByTMDb[ids.TMDb]; ok {
			return &item, "tmdb"
		}
	}
	for _, key := range titleKeys(title, "", year) {
		if item, ok := i.moviesByTitle[key]; ok {
			if strings.Contains(key, ":") {
				return &item, "title-year"
			}
			return &item, "title"
		}
	}
	return nil, ""
}

func (i traktImportIndex) matchEpisode(showTitle string, showYear int, showIDs, episodeIDs traktIDsPayload, season, episode int) (*media.Item, string) {
	_ = showIDs
	if episodeIDs.IMDb != "" {
		if item, ok := i.episodesByIMDb[strings.ToLower(episodeIDs.IMDb)]; ok {
			return &item, "episode-imdb"
		}
	}
	if episodeIDs.TMDb > 0 {
		if item, ok := i.episodesByTMDb[episodeIDs.TMDb]; ok {
			return &item, "episode-tmdb"
		}
	}
	if episodeIDs.TVDb > 0 {
		if item, ok := i.episodesByTVDb[episodeIDs.TVDb]; ok {
			return &item, "episode-tvdb"
		}
	}
	for _, key := range []string{
		episodeKey(showTitle, season, episode),
		episodeKeyWithYear(showTitle, showYear, season, episode),
	} {
		if key == "" {
			continue
		}
		if item, ok := i.episodes[key]; ok {
			if strings.Count(key, ":") >= 3 {
				return &item, "show-year-season-episode"
			}
			return &item, "show-season-episode"
		}
	}
	return nil, ""
}

func readTraktExport(dir string) (traktExportSource, error) {
	info, err := os.Stat(dir)
	if err != nil {
		return traktExportSource{}, err
	}
	if !info.IsDir() {
		return traktExportSource{}, fmt.Errorf("trakt export path must be an extracted directory")
	}
	source := traktExportSource{}
	readAny := false
	movies, ok, err := readJSONSliceFiles[traktWatchedMovie](dir, "watched-movies")
	if err != nil {
		return traktExportSource{}, err
	}
	if ok {
		readAny = true
		source.MovieRows += len(movies)
	}
	history, ok, err := readJSONSliceFiles[traktHistoryItem](dir, "watched-history")
	if err != nil {
		return traktExportSource{}, err
	}
	if ok {
		readAny = true
		source.AllHistory = len(history)
	}
	episodesByKey := map[string]traktHistoryItem{}
	showsByKey := map[string]struct{}{}
	for _, entry := range history {
		switch entry.Type {
		case "movie":
			source.MovieRows++
			movies = append(movies, traktMovieFromHistory(entry))
		case "episode":
			source.EpisodeRows++
			key := traktHistoryEpisodeKey(entry)
			if key != "" {
				episodesByKey[key] = entry
			}
			if showKey := traktHistoryShowKey(entry); showKey != "" {
				showsByKey[showKey] = struct{}{}
			}
		}
	}
	shows, ok, err := readJSONSliceFiles[traktWatchedShow](dir, "watched-shows")
	if err != nil {
		return traktExportSource{}, err
	}
	if ok {
		readAny = true
	}
	for _, show := range shows {
		showEntry := traktHistoryItem{}
		showEntry.Type = "episode"
		showEntry.Show.Title = show.Show.Title
		showEntry.Show.Year = show.Show.Year
		showEntry.Show.IDs = show.Show.IDs
		if showKey := traktHistoryShowKey(showEntry); showKey != "" {
			showsByKey[showKey] = struct{}{}
		}
		for _, season := range show.Seasons {
			for _, episode := range season.Episodes {
				entry := showEntry
				entry.Episode.Season = season.Number
				entry.Episode.Number = episode.Number
				key := traktHistoryEpisodeKey(entry)
				if key != "" {
					if _, exists := episodesByKey[key]; !exists {
						episodesByKey[key] = entry
					}
				}
			}
		}
	}
	if !readAny {
		return traktExportSource{}, fmt.Errorf("no Trakt export JSON files found in %s", dir)
	}
	source.Movies = mergeTraktMovies(movies)
	source.Episodes = make([]traktHistoryItem, 0, len(episodesByKey))
	for _, episode := range episodesByKey {
		source.Episodes = append(source.Episodes, episode)
	}
	source.ShowsSeen = len(showsByKey)
	return source, nil
}

func readJSONIfExists(path string, dest any) (bool, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	if err := json.Unmarshal(b, dest); err != nil {
		return false, fmt.Errorf("%s: %w", path, err)
	}
	return true, nil
}

func readJSONSliceFiles[T any](root, basename string) ([]T, bool, error) {
	paths := []string{}
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() || !isTraktExportJSONName(d.Name(), basename) {
			return nil
		}
		paths = append(paths, path)
		return nil
	})
	if err != nil {
		return nil, false, err
	}
	sort.Strings(paths)
	out := []T{}
	for _, path := range paths {
		var items []T
		if ok, err := readJSONIfExists(path, &items); err != nil {
			return nil, false, err
		} else if ok {
			out = append(out, items...)
		}
	}
	return out, len(paths) > 0, nil
}

func isTraktExportJSONName(name, basename string) bool {
	if name == basename+".json" {
		return true
	}
	if !strings.HasPrefix(name, basename+"-") || !strings.HasSuffix(name, ".json") {
		return false
	}
	chunk := strings.TrimSuffix(strings.TrimPrefix(name, basename+"-"), ".json")
	if chunk == "" {
		return false
	}
	for _, r := range chunk {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func expandLocalPath(path string) (string, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return "", fmt.Errorf("path is required")
	}
	if path == "~" || strings.HasPrefix(path, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if path == "~" {
			path = home
		} else {
			path = filepath.Join(home, strings.TrimPrefix(path, "~/"))
		}
	}
	return filepath.Abs(path)
}

func traktMovieFromHistory(entry traktHistoryItem) traktWatchedMovie {
	var movie traktWatchedMovie
	movie.LastWatchedAt = entry.WatchedAt
	movie.Plays = 1
	movie.Movie.Title = entry.Movie.Title
	movie.Movie.Year = entry.Movie.Year
	movie.Movie.IDs = entry.Movie.IDs
	return movie
}

func traktMoviesFromHistory(entries []traktHistoryItem) []traktWatchedMovie {
	movies := make([]traktWatchedMovie, 0)
	for _, entry := range entries {
		if entry.Type == "movie" {
			movies = append(movies, traktMovieFromHistory(entry))
		}
	}
	return mergeTraktMovies(movies)
}

func traktHistoryEpisodeKey(entry traktHistoryItem) string {
	if entry.Episode.IDs.Trakt > 0 {
		return fmt.Sprintf("trakt:%d", entry.Episode.IDs.Trakt)
	}
	if entry.Episode.IDs.IMDb != "" {
		return "imdb:" + strings.ToLower(entry.Episode.IDs.IMDb)
	}
	if entry.Episode.IDs.TVDb > 0 {
		return fmt.Sprintf("tvdb:%d", entry.Episode.IDs.TVDb)
	}
	if entry.Episode.IDs.TMDb > 0 {
		return fmt.Sprintf("tmdb:%d", entry.Episode.IDs.TMDb)
	}
	showKey := traktHistoryShowKey(entry)
	if showKey == "" || entry.Episode.Season <= 0 || entry.Episode.Number <= 0 {
		return ""
	}
	return fmt.Sprintf("%s:s%d:e%d", showKey, entry.Episode.Season, entry.Episode.Number)
}

func traktHistoryShowKey(entry traktHistoryItem) string {
	if entry.Show.IDs.Trakt > 0 {
		return fmt.Sprintf("trakt:%d", entry.Show.IDs.Trakt)
	}
	if entry.Show.IDs.TVDb > 0 {
		return fmt.Sprintf("tvdb:%d", entry.Show.IDs.TVDb)
	}
	if entry.Show.IDs.TMDb > 0 {
		return fmt.Sprintf("tmdb:%d", entry.Show.IDs.TMDb)
	}
	if entry.Show.IDs.IMDb != "" {
		return "imdb:" + strings.ToLower(entry.Show.IDs.IMDb)
	}
	if entry.Show.Title == "" {
		return ""
	}
	return fmt.Sprintf("title:%s:%d", normalizeMatch(entry.Show.Title), entry.Show.Year)
}
