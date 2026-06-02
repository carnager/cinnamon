package media

import (
	"context"
	"path/filepath"
	"testing"

	"popcorn/internal/database"
)

func TestStoreSearchesOriginalTitles(t *testing.T) {
	store, ctx := newTestStore(t)

	movie := upsertTestItem(t, ctx, store, Item{
		LibraryID:     "movies",
		Kind:          "movie",
		Title:         "The Boat",
		OriginalTitle: "Das Boot",
		SortTitle:     "boat",
		Path:          "/media/movies/the-boat.mkv",
		Year:          1981,
	})
	upsertTestItem(t, ctx, store, Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         "Money Heist S01E01",
		OriginalTitle: "La Casa De Papel",
		SortTitle:     "money heist 01 01",
		Path:          "/media/tv/money-heist/s01e01.mkv",
		ShowTitle:     "Money Heist",
		SeasonNumber:  1,
		EpisodeNumber: 1,
		EpisodeTitle:  "Episode 1",
	})

	items, err := store.SearchItems(ctx, SearchOptions{Query: "Boot", Limit: 10})
	if err != nil {
		t.Fatalf("search by movie original title: %v", err)
	}
	if len(items) != 1 || items[0].ID != movie.ID {
		t.Fatalf("search by movie original title returned %#v, want %s", items, movie.Title)
	}

	items, err = store.SearchItems(ctx, SearchOptions{Query: "Casa", Limit: 10})
	if err != nil {
		t.Fatalf("search by episode original title: %v", err)
	}
	if len(items) != 1 || items[0].ShowTitle != "Money Heist" {
		t.Fatalf("search by episode original title returned %#v, want Money Heist episode", items)
	}

	shows, err := store.ListShows(ctx, "tv", "Casa", "", "", 10, 0)
	if err != nil {
		t.Fatalf("list shows by original title: %v", err)
	}
	if len(shows) != 1 || shows[0].Title != "Money Heist" {
		t.Fatalf("list shows by original title returned %#v, want Money Heist", shows)
	}
}

func TestStoreProgressClampsAndAggregatesShows(t *testing.T) {
	store, ctx := newTestStore(t)
	userID := insertTestUser(t, store, "progress")
	first := upsertTestItem(t, ctx, store, episodeItem("Battlestar Galactica", 1, 1))
	second := upsertTestItem(t, ctx, store, episodeItem("Battlestar Galactica", 1, 2))

	progress, err := store.SaveProgress(ctx, userID, first.ID, 15_000, 10_000, true)
	if err != nil {
		t.Fatalf("save clamped progress: %v", err)
	}
	if progress.PositionMS != 10_000 || progress.DurationMS != 10_000 || !progress.Completed {
		t.Fatalf("progress = %#v, want clamped completed progress", progress)
	}

	shows, err := store.ListShowProgress(ctx, userID)
	if err != nil {
		t.Fatalf("list partial show progress: %v", err)
	}
	show := requireShowProgress(t, shows, "tv", "Battlestar Galactica")
	if show.EpisodeCount != 2 || show.CompletedCount != 1 || !show.HasAnyCompletion || show.Completed {
		t.Fatalf("partial show progress = %#v, want 1/2 not completed", show)
	}

	if _, err := store.SaveProgress(ctx, userID, second.ID, 10_000, 10_000, true); err != nil {
		t.Fatalf("save second progress: %v", err)
	}
	shows, err = store.ListShowProgress(ctx, userID)
	if err != nil {
		t.Fatalf("list completed show progress: %v", err)
	}
	show = requireShowProgress(t, shows, "tv", "Battlestar Galactica")
	if show.EpisodeCount != 2 || show.CompletedCount != 2 || !show.HasAnyCompletion || !show.Completed {
		t.Fatalf("completed show progress = %#v, want 2/2 completed", show)
	}
}

func TestStoreWatchlistReturnsMoviesAndShows(t *testing.T) {
	store, ctx := newTestStore(t)
	userID := insertTestUser(t, store, "watchlist")
	movie := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Hoppers",
		SortTitle: "hoppers",
		Path:      "/media/movies/hoppers.mkv",
		Year:      2026,
	})
	upsertTestItem(t, ctx, store, episodeItem("The Expanse", 1, 1))
	upsertTestItem(t, ctx, store, episodeItem("The Expanse", 2, 1))

	if err := store.SaveItemWatchlist(ctx, userID, movie); err != nil {
		t.Fatalf("save item watchlist: %v", err)
	}
	if err := store.SaveShowWatchlist(ctx, userID, "tv", "The Expanse"); err != nil {
		t.Fatalf("save show watchlist: %v", err)
	}

	watchlist, err := store.ListWatchlist(ctx, userID, 10)
	if err != nil {
		t.Fatalf("list watchlist: %v", err)
	}
	if len(watchlist.Items) != 1 || watchlist.Items[0].ID != movie.ID {
		t.Fatalf("watchlist items = %#v, want Hoppers", watchlist.Items)
	}
	if len(watchlist.Shows) != 1 || watchlist.Shows[0].Title != "The Expanse" || watchlist.Shows[0].EpisodeCount != 2 || watchlist.Shows[0].SeasonCount != 2 {
		t.Fatalf("watchlist shows = %#v, want The Expanse with two seasons", watchlist.Shows)
	}

	if err := store.DeleteItemWatchlist(ctx, userID, movie.ID); err != nil {
		t.Fatalf("delete item watchlist: %v", err)
	}
	if err := store.DeleteShowWatchlist(ctx, userID, "tv", "The Expanse"); err != nil {
		t.Fatalf("delete show watchlist: %v", err)
	}
	watchlist, err = store.ListWatchlist(ctx, userID, 10)
	if err != nil {
		t.Fatalf("list empty watchlist: %v", err)
	}
	if len(watchlist.Items) != 0 || len(watchlist.Shows) != 0 {
		t.Fatalf("watchlist after delete = %#v, want empty", watchlist)
	}
}

func newTestStore(t *testing.T) (*Store, context.Context) {
	t.Helper()
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Fatalf("close test database: %v", err)
		}
	})
	return NewStore(db), context.Background()
}

func insertTestUser(t *testing.T, store *Store, username string) int64 {
	t.Helper()
	result, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES (?, ?, 'test', 0)`, username, username)
	if err != nil {
		t.Fatalf("insert test user: %v", err)
	}
	id, err := result.LastInsertId()
	if err != nil {
		t.Fatalf("read test user id: %v", err)
	}
	return id
}

func upsertTestItem(t *testing.T, ctx context.Context, store *Store, item Item) Item {
	t.Helper()
	if item.MTimeUnix == 0 {
		item.MTimeUnix = 1
	}
	if item.DurationMS == 0 {
		item.DurationMS = 10_000
	}
	if err := store.UpsertItem(ctx, item); err != nil {
		t.Fatalf("upsert item %q: %v", item.Title, err)
	}
	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items after upsert: %v", err)
	}
	for _, candidate := range items {
		if candidate.Path == item.Path {
			return candidate
		}
	}
	t.Fatalf("item %q was not returned after upsert", item.Path)
	return Item{}
}

func episodeItem(showTitle string, season, episode int) Item {
	title := showTitle + " S" + twoDigits(season) + "E" + twoDigits(episode)
	return Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         title,
		SortTitle:     title,
		Path:          "/media/tv/" + showTitle + "/" + title + ".mkv",
		ShowTitle:     showTitle,
		SeasonNumber:  season,
		EpisodeNumber: episode,
		EpisodeTitle:  "Episode " + twoDigits(episode),
	}
}

func requireShowProgress(t *testing.T, shows []ShowProgress, libraryID, showTitle string) ShowProgress {
	t.Helper()
	for _, show := range shows {
		if show.LibraryID == libraryID && show.ShowTitle == showTitle {
			return show
		}
	}
	t.Fatalf("show progress for %s/%s not found in %#v", libraryID, showTitle, shows)
	return ShowProgress{}
}
