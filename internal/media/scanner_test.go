package media

import (
	"encoding/xml"
	"testing"
)

func TestReadNFOIDs(t *testing.T) {
	meta := nfoMovie{
		ID:     "tt1179933",
		TMDbID: "333371",
		UniqueIDs: []nfoID{
			{Type: "tmdb", Value: "333371"},
			{Type: "imdb", Value: "tt1179933"},
		},
	}
	if got := meta.imdbID(); got != "tt1179933" {
		t.Fatalf("imdbID() = %q, want tt1179933", got)
	}
	if got := meta.tmdbID(); got != "333371" {
		t.Fatalf("tmdbID() = %q, want 333371", got)
	}
}

func TestReadNFOExternalIDs(t *testing.T) {
	meta := nfoMovie{
		ID:     "5482170",
		TMDbID: "1161836",
		TVDbID: "5482170",
		UniqueIDs: []nfoID{
			{Type: "imdb", Value: "tt4460418"},
			{Type: "tmdb", Value: "1138311"},
			{Type: "tvdb", Value: "5387592"},
		},
	}
	if got := meta.imdbID(); got != "tt4460418" {
		t.Fatalf("imdbID() = %q, want tt4460418", got)
	}
	if got := meta.tmdbID(); got != "1161836" {
		t.Fatalf("tmdbID() = %q, want 1161836", got)
	}
	if got := meta.tvdbID(); got != "5482170" {
		t.Fatalf("tvdbID() = %q, want 5482170", got)
	}
}

func TestReadNFOIDsFromTinyMediaManagerXML(t *testing.T) {
	var meta nfoMovie
	err := xml.Unmarshal([]byte(`<movie>
  <id>tt1179933</id>
  <tmdbid>333371</tmdbid>
  <uniqueid default="false" type="tmdb">333371</uniqueid>
  <uniqueid default="true" type="imdb">tt1179933</uniqueid>
</movie>`), &meta)
	if err != nil {
		t.Fatal(err)
	}
	if got := meta.imdbID(); got != "tt1179933" {
		t.Fatalf("imdbID() = %q, want tt1179933", got)
	}
	if got := meta.tmdbID(); got != "333371" {
		t.Fatalf("tmdbID() = %q, want 333371", got)
	}
}
