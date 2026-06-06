package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestEpisodeRatingsUseTMDbEpisodeEndpointFromShowNFO(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	showDir := filepath.Join(root, "Lost")
	seasonDir := filepath.Join(showDir, "Season 01")
	mustMkdirAll(t, seasonDir)
	video := filepath.Join(seasonDir, "Lost - S01E02.mkv")
	nfo := filepath.Join(seasonDir, "Lost - S01E02.nfo")
	mustWrite(t, video, "fake video")
	mustWrite(t, nfo, `<episodedetails><title>Pilot Part 2</title><season>1</season><episode>2</episode></episodedetails>`)
	mustWrite(t, filepath.Join(showDir, "tvshow.nfo"), `<tvshow><title>Lost</title><tmdbid>4607</tmdbid><rating>8.7</rating></tvshow>`)

	app, item := testRatingsApp(t, config.Config{
		TMDbAPIKey: "tmdb-test",
		Libraries:  []config.Library{{ID: "tv", Path: root, Type: "tv"}},
	}, media.Item{
		LibraryID:     "tv",
		Path:          video,
		Kind:          "episode",
		Title:         "Lost - S01E02 - Pilot Part 2",
		SortTitle:     "lost 01 02 pilot part 2",
		NFOPath:       nfo,
		SizeBytes:     10,
		MTimeUnix:     123,
		ShowTitle:     "Lost",
		SeasonNumber:  1,
		EpisodeNumber: 2,
		Rating:        0,
	})

	seen := map[string]int{}
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen[r.URL.Path]++
		switch r.URL.Path {
		case "/3/tv/4607/season/1/episode/2/external_ids":
			_ = json.NewEncoder(w).Encode(map[string]any{"imdb_id": "tt0636290"})
		case "/3/tv/4607/season/1/episode/2":
			_ = json.NewEncoder(w).Encode(map[string]any{"id": 91515, "vote_average": 8.2})
		default:
			http.NotFound(w, r)
		}
	}))
	defer ts.Close()
	withTMDbBaseURL(t, ts.URL)

	ratings, err := app.ratingsForItem(ctx, item)
	if err != nil {
		t.Fatal(err)
	}
	if ratings.IMDbID != "tt0636290" || ratings.TMDbID != "91515" || ratings.TMDbRating != 8.2 {
		t.Fatalf("ratings = %#v, want episode imdb id, tmdb id, and vote average", ratings)
	}
	if ratings.LocalRating != 0 {
		t.Fatalf("LocalRating = %.1f, want episode rating absent unless episode NFO has one", ratings.LocalRating)
	}
	if seen["/3/tv/4607/season/1/episode/2/external_ids"] != 1 || seen["/3/tv/4607/season/1/episode/2"] != 1 {
		t.Fatalf("tmdb requests = %#v, want external_ids and episode details once", seen)
	}
}

func TestEpisodeIMDbFindUsesTVEpisodeResults(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	video := filepath.Join(root, "Show", "Season 02", "Show - S02E05.mkv")
	nfo := filepath.Join(root, "Show", "Season 02", "Show - S02E05.nfo")
	mustMkdirAll(t, filepath.Dir(video))
	mustWrite(t, video, "fake video")
	mustWrite(t, nfo, `<episodedetails><title>The One</title><season>2</season><episode>5</episode></episodedetails>`)

	app, item := testRatingsApp(t, config.Config{
		TMDbAPIKey: "tmdb-test",
		Libraries:  []config.Library{{ID: "tv", Path: root, Type: "tv"}},
	}, media.Item{
		LibraryID:     "tv",
		Path:          video,
		Kind:          "episode",
		Title:         "Show - S02E05 - The One",
		SortTitle:     "show 02 05 the one",
		NFOPath:       nfo,
		IMDbID:        "tt2222222",
		SizeBytes:     10,
		MTimeUnix:     123,
		ShowTitle:     "Show",
		SeasonNumber:  2,
		EpisodeNumber: 5,
	})

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/3/find/tt2222222":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"tv_results": []map[string]any{{"id": 100}},
				"tv_episode_results": []map[string]any{{
					"id": 9876, "show_id": 100, "season_number": 2, "episode_number": 5,
				}},
			})
		case "/3/tv/100/season/2/episode/5":
			_ = json.NewEncoder(w).Encode(map[string]any{"id": 9876, "vote_average": 7.4})
		default:
			http.NotFound(w, r)
		}
	}))
	defer ts.Close()
	withTMDbBaseURL(t, ts.URL)

	ratings, err := app.ratingsForItem(ctx, item)
	if err != nil {
		t.Fatal(err)
	}
	if ratings.TMDbID != "9876" || ratings.TMDbRating != 7.4 {
		t.Fatalf("ratings = %#v, want episode TMDb result, not show TMDb result", ratings)
	}
}

func testRatingsApp(t *testing.T, cfg config.Config, item media.Item) (*App, media.Item) {
	t.Helper()
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	if err := store.UpsertItem(context.Background(), item); err != nil {
		t.Fatal(err)
	}
	stored, err := store.GetItem(context.Background(), item.ID)
	if err != nil {
		episodes, listErr := store.ListEpisodes(context.Background(), item.LibraryID, item.ShowTitle, item.SeasonNumber)
		if listErr != nil {
			t.Fatal(listErr)
		}
		if len(episodes) != 1 {
			t.Fatalf("episodes = %d, want 1", len(episodes))
		}
		stored = episodes[0]
	}
	return New(Options{Config: cfg, Store: store}), stored
}

func withTMDbBaseURL(t *testing.T, url string) {
	t.Helper()
	previous := tmdbAPIBaseURL
	tmdbAPIBaseURL = url
	t.Cleanup(func() { tmdbAPIBaseURL = previous })
}

func mustMkdirAll(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
}

func mustWrite(t *testing.T, path string, data string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
}
