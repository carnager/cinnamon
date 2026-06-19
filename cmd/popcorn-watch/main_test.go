package main

import "testing"

func TestScanTargetNeverReturnsWatchRoot(t *testing.T) {
	roots := []string{"/mnt/tank/movies", "/mnt/tank/tv"}

	// A file dropped directly in a watch root -> scan the file, not the root.
	if got := scanTarget("/mnt/tank/movies/The Matrix.mkv", roots); got != "/mnt/tank/movies/The Matrix.mkv" {
		t.Errorf("top-level file: got %q", got)
	}
	// A deeper file -> scan its containing directory.
	if got := scanTarget("/mnt/tank/tv/Show/Season 01/ep.mkv", roots); got != "/mnt/tank/tv/Show/Season 01" {
		t.Errorf("nested file: got %q", got)
	}
}

func TestRemapPath(t *testing.T) {
	maps, err := parseMaps("/mnt/tank/movies=/nas/movies, /mnt/tank/tv=/nas/tv")
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"/mnt/tank/movies/The Matrix":        "/nas/movies/The Matrix",
		"/mnt/tank/tv/Show/Season 01":        "/nas/tv/Show/Season 01",
		"/mnt/tank/movies":                   "/nas/movies",
		"/mnt/tank/other/x":                  "/mnt/tank/other/x", // unmapped passes through
	}
	for in, want := range cases {
		if got := remapPath(in, maps); got != want {
			t.Errorf("remapPath(%q) = %q, want %q", in, got, want)
		}
	}
}
