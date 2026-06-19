package server

import (
	"path/filepath"
	"testing"

	"popcorn/internal/config"
)

func TestLibraryForPath(t *testing.T) {
	app := &App{cfg: config.Config{Libraries: []config.Library{
		{ID: "movies", Path: "/nas/movies"},
		{ID: "tv", Path: "/nas/tv"},
		{ID: "kids", Path: "/nas/movies/kids"}, // nested: longest prefix should win
	}}}

	cases := []struct {
		path   string
		wantID string
		wantOK bool
	}{
		{"/nas/movies/The Matrix/movie.mkv", "movies", true},
		{"/nas/movies/kids/Up/movie.mkv", "kids", true},
		{"/nas/tv/Show/Season 01", "tv", true},
		{"/nas/movies", "movies", true},
		{"/nas/other/x.mkv", "", false},
		{"/nas/moviesX/x.mkv", "", false}, // must not prefix-match a sibling
	}
	for _, tc := range cases {
		lib, abs, ok := app.libraryForPath(tc.path)
		if ok != tc.wantOK || lib.ID != tc.wantID {
			t.Errorf("libraryForPath(%q) = (%q,%v), want (%q,%v)", tc.path, lib.ID, ok, tc.wantID, tc.wantOK)
		}
		if ok && abs != filepath.Clean(tc.path) {
			t.Errorf("libraryForPath(%q) abs = %q", tc.path, abs)
		}
	}
}
