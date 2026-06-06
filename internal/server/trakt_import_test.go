package server

import (
	"os"
	"path/filepath"
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
