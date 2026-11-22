package server

import (
	"encoding/json"

	"os"
	"path/filepath"
	"popcorn/internal/media"
	"testing"
)

func TestReadTraktExportIncludesWatchedMoviesAndHistoryMovies(t *testing.T) {
	dir := t.TempDir()
	writeTestFile(t, filepath.Join(dir, "watched-movies.json"), `[
		{
			"plays": 1,
			"last_watched_at": "2026-05-24T20:15:00.000Z",
			"movie": {
				"title": "Burrow",
				"year": 2020,
				"ids": {"trakt": 587467, "slug": "burrow-2020", "imdb": "tt13167288", "tmdb": 747059}
			}
		}
	]`)
	writeTestFile(t, filepath.Join(dir, "watched-history.json"), `[
		{
			"id": 12299655356,
			"watched_at": "2026-05-24T20:15:00.000Z",
			"type": "movie",
			"movie": {
				"title": "Burrow",
				"year": 2020,
				"ids": {"trakt": 587467, "slug": "burrow-2020", "imdb": "tt13167288", "tmdb": 747059}
			}
		},
		{
			"id": 12299655357,
			"watched_at": "2026-05-25T20:15:00.000Z",
			"type": "movie",
			"movie": {
				"title": "One Day",
				"year": 2011,
				"ids": {"trakt": 36408, "slug": "one-day-2011", "imdb": "tt1563738", "tmdb": 51828}
			}
		}
	]`)

	export, err := readTraktExport(dir)
	if err != nil {
		t.Fatalf("readTraktExport: %v", err)
	}
	if export.MovieRows != 3 {
		t.Fatalf("MovieRows = %d, want 3", export.MovieRows)
	}
	if len(export.Movies) != 2 {
		t.Fatalf("len(Movies) = %d, want 2", len(export.Movies))
	}
}

func TestReadTraktExportFindsFilesRecursively(t *testing.T) {
	dir := t.TempDir()
	writeTestFile(t, filepath.Join(dir, "movies", "watched-movies-1.json"), `[
		{
			"movie": {
				"title": "10 Cloverfield Lane",
				"year": 2016,
				"ids": {"imdb": "tt1179933", "tmdb": 333371}
			}
		}
	]`)
	writeTestFile(t, filepath.Join(dir, "shows", "watched-shows.json"), `[
		{
			"show": {"title": "Lost", "year": 2004, "ids": {"tvdb": 73739}},
			"seasons": [{"number": 1, "episodes": [{"number": 1}]}]
		}
	]`)

	export, err := readTraktExport(dir)
	if err != nil {
		t.Fatalf("readTraktExport: %v", err)
	}
	if len(export.Movies) != 1 {
		t.Fatalf("len(Movies) = %d, want 1", len(export.Movies))
	}
	if len(export.Episodes) != 1 {
		t.Fatalf("len(Episodes) = %d, want 1", len(export.Episodes))
	}
}

func TestIsTraktExportJSONName(t *testing.T) {
	tests := map[string]bool{
		"watched-movies.json":     true,
		"watched-movies-1.json":   true,
		"watched-movies-12.json":  true,
		"watched-movies-old.json": false,
		"watched-movies.json.bak": false,
		"collection-movies.json":  false,
	}
	for name, want := range tests {
		if got := isTraktExportJSONName(name, "watched-movies"); got != want {
			t.Fatalf("isTraktExportJSONName(%q) = %v, want %v", name, got, want)
		}
	}
}

func TestTraktMoviesFromHistoryIgnoresEpisodes(t *testing.T) {
	entries := []traktHistoryItem{{Type: "movie"}, {Type: "episode"}}
	entries[0].Movie.Title = "Hoppers"
	entries[0].Movie.Year = 2025
	entries[0].Movie.IDs.TMDb = 123
	entries[1].Show.Title = "Lost"

	movies := traktMoviesFromHistory(entries)
	if len(movies) != 1 {
		t.Fatalf("len(movies) = %d, want 1", len(movies))
	}
	if movies[0].Movie.Title != "Hoppers" {
		t.Fatalf("movie title = %q, want Hoppers", movies[0].Movie.Title)
	}
}

func writeTestFile(t *testing.T, path string, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// A local show titled in German ("Haus des Geldes") never matched Trakt's
// English title ("Money Heist"), so watched shows imported nothing for it.
// The episode IDs from history must carry the match instead.
func TestWatchedShowEpisodesMatchByHistoryIDsDespiteTranslatedTitle(t *testing.T) {
	var shows []traktWatchedShow
	if err := json.Unmarshal([]byte(`[{
		"show": {"title": "Money Heist", "year": 2017, "ids": {"trakt": 117105}},
		"seasons": [{"number": 1, "episodes": [{"number": 2, "plays": 1}]}]
	}]`), &shows); err != nil {
		t.Fatal(err)
	}
	var history []traktHistoryItem
	if err := json.Unmarshal([]byte(`[{
		"type": "episode",
		"show": {"title": "Money Heist", "ids": {"trakt": 117105}},
		"episode": {"season": 1, "number": 2, "ids": {"imdb": "tt6798708", "tvdb": 6056378}}
	}]`), &history); err != nil {
		t.Fatal(err)
	}
	attachTraktEpisodeIDs(shows, history)

	local := media.Item{ID: 7, Kind: "episode", ShowTitle: "Haus des Geldes", SeasonNumber: 1, EpisodeNumber: 2, TVDbID: "6056378"}
	index := newTraktImportIndex([]media.Item{local})
	show := shows[0]
	episode := show.Seasons[0].Episodes[0]

	if item, _ := index.matchEpisode(show.Show.Title, show.Show.Year, show.Show.IDs, traktIDsPayload{}, 1, 2); item != nil {
		t.Fatalf("title-only match unexpectedly found %d; test no longer exercises the translated-title case", item.ID)
	}
	item, method := index.matchEpisode(show.Show.Title, show.Show.Year, show.Show.IDs, episode.IDs, 1, 2)
	if item == nil || item.ID != 7 {
		t.Fatalf("expected local episode 7 via history IDs, got %+v (%s)", item, method)
	}
}

// Trakt's /sync/watched/shows now returns show-level counts with no
// "seasons", so the watched episodes must come from history. Rewatches and
// the merge with the (empty) watched-shows entry must not duplicate them.
func TestWatchedShowsBuiltFromHistoryWhenSeasonsMissing(t *testing.T) {
	var watched []traktWatchedShow
	if err := json.Unmarshal([]byte(`[{"plays": 3, "show": {"title": "FROM", "year": 2022, "ids": {"trakt": 179735}}, "aired_episodes": 40}]`), &watched); err != nil {
		t.Fatal(err)
	}
	var history []traktHistoryItem
	if err := json.Unmarshal([]byte(`[
		{"type": "episode", "show": {"title": "FROM", "ids": {"trakt": 179735}}, "episode": {"season": 1, "number": 1, "ids": {"tvdb": 8808273}}},
		{"type": "episode", "show": {"title": "FROM", "ids": {"trakt": 179735}}, "episode": {"season": 1, "number": 2, "ids": {"tvdb": 8808282}}},
		{"type": "episode", "show": {"title": "FROM", "ids": {"trakt": 179735}}, "episode": {"season": 1, "number": 1, "ids": {"tvdb": 8808273}}},
		{"type": "episode", "show": {"title": "FROM", "ids": {"trakt": 179735}}, "episode": {"season": 2, "number": 1, "ids": {"tvdb": 9553512}}}
	]`), &history); err != nil {
		t.Fatal(err)
	}
	shows := mergeTraktShows(watched, watched, traktShowsFromHistory(history))
	attachTraktEpisodeIDs(shows, history)
	if len(shows) != 1 {
		t.Fatalf("expected one show, got %d", len(shows))
	}
	if got := countTraktEpisodes(shows); got != 3 {
		t.Fatalf("expected 3 distinct episodes, got %d: %+v", got, shows[0].Seasons)
	}
	local := []media.Item{
		{ID: 1, Kind: "episode", ShowTitle: "From (DE)", SeasonNumber: 1, EpisodeNumber: 1, TVDbID: "8808273"},
		{ID: 2, Kind: "episode", ShowTitle: "From (DE)", SeasonNumber: 1, EpisodeNumber: 2, TVDbID: "8808282"},
		{ID: 3, Kind: "episode", ShowTitle: "From (DE)", SeasonNumber: 2, EpisodeNumber: 1, TVDbID: "9553512"},
	}
	index := newTraktImportIndex(local)
	matched := map[int64]bool{}
	for _, show := range shows {
		for _, season := range show.Seasons {
			for _, episode := range season.Episodes {
				if item, _ := index.matchEpisode(show.Show.Title, show.Show.Year, show.Show.IDs, episode.IDs, season.Number, episode.Number); item != nil {
					matched[item.ID] = true
				}
			}
		}
	}
	if len(matched) != 3 {
		t.Fatalf("expected all 3 local episodes matched, got %v", matched)
	}
}
