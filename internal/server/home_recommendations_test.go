package server

import (
	"testing"

	"popcorn/internal/media"
)

func TestRecommendationItemExcludedByItemOrParentShow(t *testing.T) {
	movie := media.Item{ID: 12, Kind: "movie", LibraryID: "movies", Title: "Harbor"}
	episode := media.Item{ID: 44, Kind: "episode", LibraryID: "TV", ShowTitle: "Night Watch"}

	if recommendationItemExcluded(movie, map[string]bool{}) {
		t.Fatal("movie unexpectedly excluded")
	}
	if !recommendationItemExcluded(movie, map[string]bool{"item:12": true}) {
		t.Fatal("movie item exclusion was ignored")
	}
	if !recommendationItemExcluded(episode, map[string]bool{"show:tv:night watch": true}) {
		t.Fatal("parent show exclusion did not exclude its episode")
	}
	if recommendationItemExcluded(episode, map[string]bool{"item:44": true}) {
		t.Fatal("episode-level exclusion should be ignored")
	}
}

func TestRecommendationKeysAreCanonical(t *testing.T) {
	if got := recommendationShowKey(" TV ", " Night Watch "); got != "show:tv:night watch" {
		t.Fatalf("show key = %q", got)
	}
	if got := recommendationItemKey(42); got != "item:42" {
		t.Fatalf("item key = %q", got)
	}
}
