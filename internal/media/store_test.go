package media

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
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

	shows, err := store.ListShows(ctx, "tv", "Casa", "", "", 0, 10, 0)
	if err != nil {
		t.Fatalf("list shows by original title: %v", err)
	}
	if len(shows) != 1 || shows[0].Title != "Money Heist" {
		t.Fatalf("list shows by original title returned %#v, want Money Heist", shows)
	}
}

func TestSearchFieldsKeepDescriptionsOptIn(t *testing.T) {
	store, ctx := newTestStore(t)
	movie := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Quiet Harbor",
		SortTitle: "quiet harbor",
		Path:      "/media/movies/quiet-harbor.mkv",
		Overview:  "A botanist discovers a luminous orchard.",
	})

	items, err := store.SearchItems(ctx, SearchOptions{Query: "luminous", SearchFields: "title", Limit: 10})
	if err != nil {
		t.Fatalf("default title search: %v", err)
	}
	if len(items) != 0 {
		t.Fatalf("default title search returned description match: %#v", items)
	}
	items, err = store.SearchItems(ctx, SearchOptions{Query: "luminous", SearchFields: "description", Limit: 10})
	if err != nil {
		t.Fatalf("description search: %v", err)
	}
	if len(items) != 1 || items[0].ID != movie.ID {
		t.Fatalf("description search returned %#v, want %s", items, movie.Title)
	}

	upsertTestItem(t, ctx, store, Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         "Night Watch S01E01",
		SortTitle:     "night watch 01 01",
		Path:          "/media/tv/night-watch/s01e01.mkv",
		ShowTitle:     "Night Watch",
		SeasonNumber:  1,
		EpisodeNumber: 1,
		Overview:      "A cartographer follows a vanished signal.",
	})
	shows, err := store.SearchShows(ctx, ShowOptions{LibraryID: "tv", Query: "cartographer", SearchFields: "title", Limit: 10})
	if err != nil {
		t.Fatalf("TV title search: %v", err)
	}
	if len(shows) != 0 {
		t.Fatalf("TV title search returned description match: %#v", shows)
	}
	shows, err = store.SearchShows(ctx, ShowOptions{LibraryID: "tv", Query: "cartographer", SearchFields: "description", Limit: 10})
	if err != nil {
		t.Fatalf("TV description search: %v", err)
	}
	if len(shows) != 1 || shows[0].Title != "Night Watch" {
		t.Fatalf("TV description search returned %#v, want Night Watch", shows)
	}
}

func TestUpsertEpisodeRenameKeepsStableEpisodeIdentity(t *testing.T) {
	store, ctx := newTestStore(t)
	oldItem := upsertTestItem(t, ctx, store, Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         "From S04E07 GERMAN DL 720p WEB h264-SAUERKRAUT",
		SortTitle:     "from s04e07 german dl 720p web h264 sauerkraut",
		Path:          "/media/tv/FROM/Season 4/From.S04E07.GERMAN.DL.720p.WEB.h264-SAUERKRAUT.mkv",
		ShowTitle:     "FROM",
		SeasonNumber:  4,
		EpisodeNumber: 7,
		EpisodeTitle:  "Episode 7",
	})
	userID := insertTestUser(t, store, "rename-progress")
	if _, err := store.SaveProgress(ctx, userID, oldItem.ID, 123_000, 3_000_000, false); err != nil {
		t.Fatalf("save old progress: %v", err)
	}

	if err := store.UpsertItem(ctx, Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         "FROM - S04E07 - Die besten Pläne",
		SortTitle:     "from s04e07 die besten plane",
		Path:          "/media/tv/FROM/Season 4/FROM - S04E07 - Die besten Plaene.mkv",
		ShowTitle:     "FROM",
		SeasonNumber:  4,
		EpisodeNumber: 7,
		EpisodeTitle:  "Die besten Pläne",
		MTimeUnix:     2,
		DurationMS:    3_000_000,
		SizeBytes:     200,
	}); err != nil {
		t.Fatalf("upsert renamed episode: %v", err)
	}

	episodes, err := store.ListEpisodes(ctx, "tv", "FROM", 4)
	if err != nil {
		t.Fatalf("list episodes: %v", err)
	}
	if len(episodes) != 1 {
		t.Fatalf("episodes = %#v, want only renamed episode", episodes)
	}
	if episodes[0].ID != oldItem.ID {
		t.Fatalf("episode id = %d, want stable old id %d", episodes[0].ID, oldItem.ID)
	}
	if episodes[0].Path != "/media/tv/FROM/Season 4/FROM - S04E07 - Die besten Plaene.mkv" {
		t.Fatalf("episode path = %q, want clean renamed path", episodes[0].Path)
	}
	progress, err := store.Progress(ctx, userID, oldItem.ID)
	if err != nil {
		t.Fatalf("progress after rename: %v", err)
	}
	if progress.PositionMS != 123_000 {
		t.Fatalf("progress = %#v, want preserved progress", progress)
	}
}

func TestUpsertMovieRenameKeepsStableExternalIDIdentity(t *testing.T) {
	store, ctx := newTestStore(t)
	oldItem := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "The Abyss 1989 1080p BluRay",
		SortTitle: "abyss 1989 1080p bluray",
		Path:      "/media/movies/The.Abyss.1989.1080p.BluRay.mkv",
		IMDbID:    "tt0096754",
		TMDbID:    "2756",
	})

	if err := store.UpsertItem(ctx, Item{
		LibraryID:  "movies",
		Kind:       "movie",
		Title:      "Abyss - Abgrund des Todes",
		SortTitle:  "abyss abgrund des todes",
		Path:       "/media/movies/Abyss - Abgrund des Todes/Abyss - Abgrund des Todes.mkv",
		IMDbID:     "tt0096754",
		TMDbID:     "2756",
		MTimeUnix:  2,
		DurationMS: 10_000,
		SizeBytes:  200,
	}); err != nil {
		t.Fatalf("upsert renamed movie: %v", err)
	}

	items, err := store.ListItems(ctx, "movies", "Abyss", "", "title", 0, 10, 0)
	if err != nil {
		t.Fatalf("list movies: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("items = %#v, want one renamed movie", items)
	}
	if items[0].ID != oldItem.ID {
		t.Fatalf("movie id = %d, want stable old id %d", items[0].ID, oldItem.ID)
	}
	if items[0].Path != "/media/movies/Abyss - Abgrund des Todes/Abyss - Abgrund des Todes.mkv" {
		t.Fatalf("movie path = %q, want clean renamed path", items[0].Path)
	}
}

func TestStoreFiltersItemsByGenreRatingAndMTime(t *testing.T) {
	store, ctx := newTestStore(t)
	oldLow := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Old Action",
		SortTitle: "old action",
		Path:      "/media/movies/old-action.mkv",
		Genres:    "Action / Thriller",
		Rating:    6.2,
		MTimeUnix: 10,
	})
	newHigh := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "New Action",
		SortTitle: "new action",
		Path:      "/media/movies/new-action.mkv",
		Genres:    "Action",
		Rating:    8.4,
		MTimeUnix: 20,
	})
	upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Drama",
		SortTitle: "drama",
		Path:      "/media/movies/drama.mkv",
		Genres:    "Drama",
		Rating:    9.1,
		MTimeUnix: 30,
	})

	items, err := store.ListItems(ctx, "movies", "", "Action", "mtime", 7, 10, 0)
	if err != nil {
		t.Fatalf("list filtered items: %v", err)
	}
	if len(items) != 1 || items[0].ID != newHigh.ID {
		t.Fatalf("filtered items = %#v, want only new high-rated action movie", items)
	}

	items, err = store.ListItems(ctx, "movies", "", "Action", "mtime", 0, 10, 0)
	if err != nil {
		t.Fatalf("list mtime sorted items: %v", err)
	}
	if len(items) != 2 || items[0].ID != newHigh.ID || items[1].ID != oldLow.ID {
		t.Fatalf("mtime sorted action items = %#v, want new then old", items)
	}
}

func TestStoreFiltersShowsByGenreRatingAndMTime(t *testing.T) {
	store, ctx := newTestStore(t)
	newHigh := episodeItem("New Action Show", 1, 1)
	newHigh.Genres = "Action"
	newHigh.Rating = 8.7
	newHigh.MTimeUnix = 30
	upsertTestItem(t, ctx, store, newHigh)
	oldLow := episodeItem("Old Action Show", 1, 1)
	oldLow.Genres = "Action"
	oldLow.Rating = 6.1
	oldLow.MTimeUnix = 10
	upsertTestItem(t, ctx, store, oldLow)
	drama := episodeItem("Drama Show", 1, 1)
	drama.Genres = "Drama"
	drama.Rating = 9.0
	drama.MTimeUnix = 40
	upsertTestItem(t, ctx, store, drama)

	shows, err := store.ListShows(ctx, "tv", "", "Action", "mtime", 7, 10, 0)
	if err != nil {
		t.Fatalf("list filtered shows: %v", err)
	}
	if len(shows) != 1 || shows[0].Title != "New Action Show" {
		t.Fatalf("filtered shows = %#v, want only new high-rated action show", shows)
	}

	shows, err = store.ListShows(ctx, "tv", "", "Action", "mtime", 0, 10, 0)
	if err != nil {
		t.Fatalf("list mtime sorted shows: %v", err)
	}
	if len(shows) != 2 || shows[0].Title != "New Action Show" || shows[1].Title != "Old Action Show" {
		t.Fatalf("mtime sorted action shows = %#v, want new then old", shows)
	}
}

func TestListGenresIncludesShowMetadataGenres(t *testing.T) {
	store, ctx := newTestStore(t)
	show := episodeItem("The Expanse", 1, 1)
	show.Genres = "Episode Drama"
	show.ShowMetadata = &ShowMetadata{
		LibraryID: "tv",
		Title:     "The Expanse",
		Genres:    "Science Fiction, Space Opera",
	}
	upsertTestItem(t, ctx, store, show)

	genres, err := store.ListGenres(ctx, "tv")
	if err != nil {
		t.Fatalf("list genres: %v", err)
	}
	want := map[string]bool{
		"Episode Drama":   false,
		"Science Fiction": false,
		"Space Opera":     false,
	}
	for _, genre := range genres {
		if _, ok := want[genre]; ok {
			want[genre] = true
		}
	}
	for genre, found := range want {
		if !found {
			t.Fatalf("genre %q missing from %#v", genre, genres)
		}
	}
}

func TestAlphabetIndexKeepsBracketedTitlesUnderHash(t *testing.T) {
	store, ctx := newTestStore(t)
	upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "[REC]",
		SortTitle: "[rec]",
		Path:      "/media/movies/rec.mkv",
	})
	upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Rambo",
		SortTitle: "rambo",
		Path:      "/media/movies/rambo.mkv",
	})

	entries, err := store.AlphabetIndex(ctx, AlphabetOptions{LibraryID: "movies", Kind: "movie"})
	if err != nil {
		t.Fatalf("alphabet index: %v", err)
	}
	if len(entries) < 2 {
		t.Fatalf("alphabet entries = %#v, want # and R", entries)
	}
	if entries[0].Letter != "#" || entries[0].Offset != 0 || entries[0].Count != 1 {
		t.Fatalf("first alphabet entry = %#v, want [REC] under # at offset 0", entries[0])
	}
	if entries[1].Letter != "R" || entries[1].Offset != 1 || entries[1].Count != 1 {
		t.Fatalf("second alphabet entry = %#v, want Rambo under R at offset 1", entries[1])
	}
}

func TestSearchItemsFiltersNameStartsWith(t *testing.T) {
	store, ctx := newTestStore(t)
	upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "[REC]",
		SortTitle: "[rec]",
		Path:      "/media/movies/rec.mkv",
	})
	panicRoom := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Panic Room",
		SortTitle: "panic room",
		Path:      "/media/movies/panic-room.mkv",
	})
	prestige := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "The Prestige",
		SortTitle: "prestige",
		Path:      "/media/movies/prestige.mkv",
	})
	upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Arrival",
		SortTitle: "arrival",
		Path:      "/media/movies/arrival.mkv",
	})

	items, err := store.SearchItems(ctx, SearchOptions{LibraryID: "movies", Kind: "movie", NameStartsWith: "P", Limit: 10})
	if err != nil {
		t.Fatalf("search P: %v", err)
	}
	if len(items) != 2 || items[0].ID != panicRoom.ID || items[1].ID != prestige.ID {
		t.Fatalf("P search returned %#v, want Panic Room and The Prestige", items)
	}

	items, err = store.SearchItems(ctx, SearchOptions{LibraryID: "movies", Kind: "movie", NameStartsWith: "#", Limit: 10})
	if err != nil {
		t.Fatalf("search #: %v", err)
	}
	if len(items) != 1 || items[0].Title != "[REC]" {
		t.Fatalf("# search returned %#v, want [REC]", items)
	}
}

func TestSearchItemsRelaxesPunctuationAndDiacritics(t *testing.T) {
	store, ctx := newTestStore(t)
	dance := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Let's Dance",
		SortTitle: "lets dance",
		Path:      "/media/movies/lets-dance.mkv",
	})
	amelie := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Amélie",
		SortTitle: "amelie",
		Path:      "/media/movies/amelie.mkv",
	})

	for _, query := range []string{"Lets Dance", "let's dance", "  Lets   Dance "} {
		items, err := store.SearchItems(ctx, SearchOptions{Query: query, Limit: 10})
		if err != nil {
			t.Fatalf("search %q: %v", query, err)
		}
		if len(items) != 1 || items[0].ID != dance.ID {
			t.Fatalf("search %q returned %#v, want Let's Dance", query, items)
		}
	}

	items, err := store.SearchItems(ctx, SearchOptions{Query: "Amelie", Limit: 10})
	if err != nil {
		t.Fatalf("search Amelie: %v", err)
	}
	if len(items) != 1 || items[0].ID != amelie.ID {
		t.Fatalf("search Amelie returned %#v, want Amélie", items)
	}
}

func TestSearchItemsTitleOnlySkipsOverviewMatches(t *testing.T) {
	store, ctx := newTestStore(t)
	match := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Widows",
		SortTitle: "widows",
		Path:      "/media/movies/widows.mkv",
	})
	upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Not a Match",
		SortTitle: "not a match",
		Path:      "/media/movies/not-a-match.mkv",
		Overview:  "A widow investigates a mystery.",
	})

	items, err := store.SearchItems(ctx, SearchOptions{LibraryID: "movies", Kind: "movie", Query: "widow", TitleOnly: true, Limit: 10})
	if err != nil {
		t.Fatalf("title-only search: %v", err)
	}
	if len(items) != 1 || items[0].ID != match.ID {
		t.Fatalf("title-only search returned %#v, want only title match", items)
	}
}

func TestUpsertEpisodeIdentityDoesNotStealLiveFile(t *testing.T) {
	store, ctx := newTestStore(t)
	dir := t.TempDir()
	realPath := filepath.Join(dir, "Show - S01E01 - Real.mkv")
	misfiledPath := filepath.Join(dir, "Other Show - S01E01 - Misfiled.mkv")
	for _, path := range []string{realPath, misfiledPath} {
		if err := os.WriteFile(path, []byte("video"), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}
	real := episodeItem("Show", 1, 1)
	real.Path = realPath
	kept := upsertTestItem(t, ctx, store, real)

	misfiled := episodeItem("Show", 1, 1)
	misfiled.Path = misfiledPath
	other := upsertTestItem(t, ctx, store, misfiled)

	if other.ID == kept.ID {
		t.Fatalf("misfiled file stole the row of a file that still exists (id %d)", kept.ID)
	}
	items, err := store.AllItems(ctx)
	if err != nil {
		t.Fatalf("list items: %v", err)
	}
	paths := map[string]bool{}
	for _, item := range items {
		paths[item.Path] = true
	}
	if !paths[realPath] || !paths[misfiledPath] {
		t.Fatalf("expected rows for both existing files, got %#v", paths)
	}
}

func TestUpsertEpisodeIdentityClaimsRenamedFile(t *testing.T) {
	store, ctx := newTestStore(t)
	dir := t.TempDir()
	oldPath := filepath.Join(dir, "Show - S01E01.mkv")
	newPath := filepath.Join(dir, "Show - S01E01 - Pilot.mkv")
	if err := os.WriteFile(newPath, []byte("video"), 0o644); err != nil {
		t.Fatalf("write %s: %v", newPath, err)
	}
	original := episodeItem("Show", 1, 1)
	original.Path = oldPath // never on disk: the file was renamed before this scan
	row := upsertTestItem(t, ctx, store, original)
	userID := insertTestUser(t, store, "viewer")
	if _, err := store.SaveProgress(ctx, userID, row.ID, 60_000, 600_000, false); err != nil {
		t.Fatalf("save progress: %v", err)
	}

	renamed := episodeItem("Show", 1, 1)
	renamed.Path = newPath
	claimed := upsertTestItem(t, ctx, store, renamed)
	if claimed.ID != row.ID {
		t.Fatalf("renamed file created row %d, want to claim row %d", claimed.ID, row.ID)
	}
	progress, err := store.Progress(ctx, userID, row.ID)
	if err != nil {
		t.Fatalf("read progress: %v", err)
	}
	if progress.PositionMS != 60_000 {
		t.Fatalf("progress lost after rename: %#v", progress)
	}
}

func TestSearchShowsFiltersNameStartsWith(t *testing.T) {
	store, ctx := newTestStore(t)
	upsertTestItem(t, ctx, store, episodeItem("1899", 1, 1))
	upsertTestItem(t, ctx, store, episodeItem("Perry Mason", 1, 1))
	pacific := episodeItem("The Pacific", 1, 1)
	pacific.ShowMetadata = &ShowMetadata{LibraryID: "tv", Title: "The Pacific", SortTitle: "pacific"}
	upsertTestItem(t, ctx, store, pacific)
	upsertTestItem(t, ctx, store, episodeItem("Yellowjackets", 1, 1))

	shows, err := store.SearchShows(ctx, ShowOptions{LibraryID: "tv", NameStartsWith: "P", Limit: 10})
	if err != nil {
		t.Fatalf("search shows P: %v", err)
	}
	if len(shows) != 2 || shows[0].Title != "The Pacific" || shows[1].Title != "Perry Mason" {
		t.Fatalf("P shows returned %#v, want Perry Mason and The Pacific", shows)
	}

	shows, err = store.SearchShows(ctx, ShowOptions{LibraryID: "tv", NameStartsWith: "#", Limit: 10})
	if err != nil {
		t.Fatalf("search shows #: %v", err)
	}
	if len(shows) != 1 || shows[0].Title != "1899" {
		t.Fatalf("# shows returned %#v, want 1899", shows)
	}
}

func TestListShowsUsesTVShowNFOForShowMetadata(t *testing.T) {
	store, ctx := newTestStore(t)
	root := t.TempDir()
	showDir := filepath.Join(root, "Lost")
	seasonDir := filepath.Join(showDir, "Season 01")
	mustMkdirAll(t, seasonDir)
	mustWrite(t, filepath.Join(showDir, "tvshow.nfo"), `<tvshow>
  <title>Lost</title>
  <originaltitle>Perdidos</originaltitle>
  <year>2004</year>
  <plot>Plane crash survivors uncover an island mystery.</plot>
  <genre>Drama</genre>
  <genre>Mystery</genre>
  <rating>8.7</rating>
  <premiered>2004-09-22</premiered>
</tvshow>`)
	upsertTestItem(t, ctx, store, Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         "Lost - S01E01 - Pilot",
		SortTitle:     "lost 01 01 pilot",
		Path:          filepath.Join(seasonDir, "Lost - S01E01 - Pilot.mkv"),
		ShowTitle:     "Lost",
		SeasonNumber:  1,
		EpisodeNumber: 1,
		EpisodeTitle:  "Pilot",
		Overview:      "Episode-only plot should not become the show plot.",
		Genres:        "Episode Genre",
		Rating:        1.2,
		Premiered:     "2004-09-23",
		ShowMetadata: &ShowMetadata{
			LibraryID:     "tv",
			Title:         "Lost",
			SortTitle:     "lost",
			OriginalTitle: "Perdidos",
			Year:          2004,
			Overview:      "Plane crash survivors uncover an island mystery.",
			Genres:        "Drama, Mystery",
			Rating:        8.7,
			Premiered:     "2004-09-22",
		},
	})

	shows, err := store.ListShows(ctx, "tv", "", "", "", 0, 10, 0)
	if err != nil {
		t.Fatalf("list shows: %v", err)
	}
	if len(shows) != 1 {
		t.Fatalf("shows = %#v, want one show", shows)
	}
	show := shows[0]
	if show.Overview != "Plane crash survivors uncover an island mystery." {
		t.Fatalf("Overview = %q, want tvshow.nfo plot", show.Overview)
	}
	if show.OriginalTitle != "Perdidos" || show.Year != 2004 || show.Genres != "Drama, Mystery" || show.Rating != 8.7 || show.Premiered != "2004-09-22" {
		t.Fatalf("show metadata = %#v, want values from tvshow.nfo", show)
	}
}

func TestStorePersistsActorsAndSeasonMetadata(t *testing.T) {
	store, ctx := newTestStore(t)
	item := upsertTestItem(t, ctx, store, Item{
		LibraryID:     "tv",
		Kind:          "episode",
		Title:         "Lost - S01E01 - Pilot",
		SortTitle:     "lost 01 01 pilot",
		Path:          "/media/tv/Lost/Season 01/Lost - S01E01 - Pilot.mkv",
		ShowTitle:     "Lost",
		SeasonNumber:  1,
		EpisodeNumber: 1,
		EpisodeTitle:  "Pilot",
		Actors: []Actor{
			{Name: "Matthew Fox", Role: "Jack Shephard", Order: 1},
		},
		ShowMetadata: &ShowMetadata{
			LibraryID: "tv",
			Title:     "Lost",
			SortTitle: "lost",
			Actors:    []Actor{{Name: "Evangeline Lilly", Role: "Kate Austen", Order: 1}},
		},
		SeasonMetadata: &SeasonMetadata{
			LibraryID:       "tv",
			ShowTitle:       "Lost",
			SeasonNumber:    1,
			Title:           "Season 1",
			Overview:        "The crash survivors settle in.",
			Rating:          8.4,
			PosterPath:      "/media/tv/Lost/Season 01/poster.jpg",
			PosterMTimeUnix: 42,
			Actors:          []Actor{{Name: "Terry O'Quinn", Role: "John Locke", Order: 1}},
		},
	})

	stored, err := store.GetItem(ctx, item.ID)
	if err != nil {
		t.Fatalf("get item: %v", err)
	}
	if len(stored.Actors) != 1 || stored.Actors[0].Name != "Matthew Fox" || stored.Actors[0].Role != "Jack Shephard" {
		t.Fatalf("item actors = %#v, want stored episode actor", stored.Actors)
	}
	showActors, err := store.ListShowActors(ctx, "tv", "Lost")
	if err != nil {
		t.Fatalf("show actors: %v", err)
	}
	if len(showActors) != 1 || showActors[0].Name != "Evangeline Lilly" {
		t.Fatalf("show actors = %#v, want stored show actor", showActors)
	}
	seasonActors, err := store.ListSeasonActors(ctx, "tv", "Lost", 1)
	if err != nil {
		t.Fatalf("season actors: %v", err)
	}
	if len(seasonActors) != 1 || seasonActors[0].Name != "Terry O'Quinn" {
		t.Fatalf("season actors = %#v, want stored season actor", seasonActors)
	}
	seasons, err := store.ListSeasons(ctx, "tv", "Lost")
	if err != nil {
		t.Fatalf("list seasons: %v", err)
	}
	if len(seasons) != 1 || seasons[0].Title != "Season 1" || seasons[0].Overview != "The crash survivors settle in." || seasons[0].Rating != 8.4 || seasons[0].PosterPath != "/media/tv/Lost/Season 01/poster.jpg" || seasons[0].PosterMTimeUnix != 42 {
		t.Fatalf("season metadata = %#v, want DB-backed season metadata", seasons)
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
	root := t.TempDir()
	showDir := filepath.Join(root, "The Expanse")
	seasonOneDir := filepath.Join(showDir, "Season 01")
	seasonTwoDir := filepath.Join(showDir, "Season 02")
	mustMkdirAll(t, seasonOneDir)
	mustMkdirAll(t, seasonTwoDir)
	mustWrite(t, filepath.Join(showDir, "tvshow.nfo"), `<tvshow>
  <title>The Expanse</title>
  <plot>Humanity spreads across the solar system.</plot>
  <genre>Science Fiction</genre>
  <rating>8.5</rating>
</tvshow>`)
	movie := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Hoppers",
		SortTitle: "hoppers",
		Path:      "/media/movies/hoppers.mkv",
		Year:      2026,
	})
	first := episodeItem("The Expanse", 1, 1)
	first.Path = filepath.Join(seasonOneDir, "The Expanse - S01E01.mkv")
	first.Overview = "Episode one plot"
	first.Rating = 1.1
	first.ShowMetadata = &ShowMetadata{
		LibraryID: "tv",
		Title:     "The Expanse",
		SortTitle: "the expanse",
		Overview:  "Humanity spreads across the solar system.",
		Genres:    "Science Fiction",
		Rating:    8.5,
	}
	upsertTestItem(t, ctx, store, first)
	second := episodeItem("The Expanse", 2, 1)
	second.Path = filepath.Join(seasonTwoDir, "The Expanse - S02E01.mkv")
	upsertTestItem(t, ctx, store, second)

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
	if watchlist.Shows[0].Overview != "Humanity spreads across the solar system." || watchlist.Shows[0].Genres != "Science Fiction" || watchlist.Shows[0].Rating != 8.5 {
		t.Fatalf("watchlist show metadata = %#v, want tvshow.nfo values", watchlist.Shows[0])
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

func TestRecommendationExclusionsArePerUserReversibleAndReportMedia(t *testing.T) {
	store, ctx := newTestStore(t)
	alice := insertTestUser(t, store, "alice")
	bob := insertTestUser(t, store, "bob")
	movie := upsertTestItem(t, ctx, store, Item{
		LibraryID: "movies",
		Kind:      "movie",
		Title:     "Quiet Harbor",
		SortTitle: "quiet harbor",
		Path:      "/media/movies/Quiet Harbor/movie.mkv",
		SizeBytes: 3_000,
	})
	episode := episodeItem("Night Watch", 1, 1)
	episode.LibraryID = "tv"
	episode.Path = "/media/tv/Night Watch/Season 1/episode.mkv"
	episode.SizeBytes = 7_000
	upsertTestItem(t, ctx, store, episode)

	if err := store.SaveItemRecommendationExclusion(ctx, alice, movie); err != nil {
		t.Fatalf("exclude movie: %v", err)
	}
	if err := store.SaveShowRecommendationExclusion(ctx, alice, "tv", "Night Watch"); err != nil {
		t.Fatalf("exclude show: %v", err)
	}
	keys, err := store.ListRecommendationExclusionKeys(ctx, alice)
	if err != nil {
		t.Fatalf("list keys: %v", err)
	}
	if len(keys) != 2 {
		t.Fatalf("keys = %#v, want two", keys)
	}
	bobKeys, err := store.ListRecommendationExclusionKeys(ctx, bob)
	if err != nil {
		t.Fatalf("list Bob keys: %v", err)
	}
	if len(bobKeys) != 0 {
		t.Fatalf("Bob keys = %#v, want none", bobKeys)
	}
	entries, err := store.ListRecommendationExclusions(ctx, alice)
	if err != nil {
		t.Fatalf("list exclusions: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("entries = %#v, want two", entries)
	}
	byKey := map[string]RecommendationExclusion{}
	for _, entry := range entries {
		byKey[entry.Key] = entry
	}
	if got := byKey[itemWatchKey(movie.ID)]; got.Item == nil || got.Path != movie.Path || got.SizeBytes != movie.SizeBytes {
		t.Fatalf("movie exclusion = %#v", got)
	}
	show := byKey[showWatchKey("tv", "Night Watch")]
	if show.Show == nil || show.Path != "/media/tv/Night Watch/Season 1" || show.SizeBytes != 7_000 {
		t.Fatalf("show exclusion = %#v", show)
	}

	if err := store.DeleteItemRecommendationExclusion(ctx, alice, movie.ID); err != nil {
		t.Fatalf("restore movie: %v", err)
	}
	if err := store.DeleteShowRecommendationExclusion(ctx, alice, "tv", "Night Watch"); err != nil {
		t.Fatalf("restore show: %v", err)
	}
	keys, err = store.ListRecommendationExclusionKeys(ctx, alice)
	if err != nil || len(keys) != 0 {
		t.Fatalf("keys after restore = %#v, err %v", keys, err)
	}
}

func TestPruneOrphanShowRowsKeepsLiveShows(t *testing.T) {
	store, ctx := newTestStore(t)
	if err := store.UpsertItems(ctx, []Item{{
		LibraryID:      "tv_shows",
		Path:           "/tv/Keeper (2020)/Season 1/Keeper - S01E01.mkv",
		Kind:           "episode",
		Title:          "Keeper - S01E01",
		SortTitle:      "keeper s01e01",
		ShowTitle:      "Keeper",
		SeasonNumber:   1,
		EpisodeNumber:  1,
		MTimeUnix:      1000,
		ShowMetadata:   &ShowMetadata{LibraryID: "tv_shows", Title: "Keeper", Actors: []Actor{{Name: "Jane Doe"}}},
		SeasonMetadata: &SeasonMetadata{LibraryID: "tv_shows", ShowTitle: "Keeper", SeasonNumber: 1},
	}, {
		LibraryID:      "tv_shows",
		Path:           "/tv/Ghost.Release-GROUP/Ghost.S01E01.mkv",
		Kind:           "episode",
		Title:          "Ghost - S01E01",
		SortTitle:      "ghost s01e01",
		ShowTitle:      "Ghost.Release-GROUP",
		SeasonNumber:   1,
		EpisodeNumber:  1,
		MTimeUnix:      1000,
		ShowMetadata:   &ShowMetadata{LibraryID: "tv_shows", Title: "Ghost.Release-GROUP", Actors: []Actor{{Name: "John Doe"}}},
		SeasonMetadata: &SeasonMetadata{LibraryID: "tv_shows", ShowTitle: "Ghost.Release-GROUP", SeasonNumber: 1},
	}}); err != nil {
		t.Fatalf("upsert items: %v", err)
	}

	// The ghost release folder disappears; its episode row goes with it, and
	// pruning must then drop the empty show/season/actor rows while leaving the
	// live show untouched.
	if err := store.RemovePaths(ctx, "tv_shows", []string{"/tv/Ghost.Release-GROUP/Ghost.S01E01.mkv"}); err != nil {
		t.Fatalf("remove ghost episode: %v", err)
	}
	pruned, err := store.PruneOrphanShowRows(ctx, "tv_shows")
	if err != nil {
		t.Fatalf("prune orphan show rows: %v", err)
	}
	if pruned != 2 {
		t.Fatalf("pruned = %d, want 2 (one show row, one season row)", pruned)
	}

	counts := map[string]string{
		"shows":         `SELECT COUNT(*) FROM media_shows WHERE library_id = 'tv_shows'`,
		"seasons":       `SELECT COUNT(*) FROM media_seasons WHERE library_id = 'tv_shows'`,
		"show actors":   `SELECT COUNT(*) FROM media_actors WHERE scope = 'show'`,
		"season actors": `SELECT COUNT(*) FROM media_actors WHERE scope = 'season'`,
	}
	want := map[string]int{"shows": 1, "seasons": 1, "show actors": 1, "season actors": 0}
	for name, query := range counts {
		var n int
		if err := store.DB().QueryRow(query).Scan(&n); err != nil {
			t.Fatalf("count %s: %v", name, err)
		}
		if n != want[name] {
			t.Fatalf("%s = %d after prune, want %d", name, n, want[name])
		}
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

func TestDecadeFilterSQL(t *testing.T) {
	if got := decadeFilterSQL("year", ""); got != "" {
		t.Fatalf("empty decades = %q, want empty", got)
	}
	if got := decadeFilterSQL("year", "abc,12"); got != "" {
		t.Fatalf("invalid decades = %q, want empty", got)
	}
	got := decadeFilterSQL("year", "1995,2000")
	want := "AND ((year >= 1990 AND year < 2000) OR (year >= 2000 AND year < 2010))"
	if got != want {
		t.Fatalf("decadeFilterSQL = %q, want %q", got, want)
	}
}

func TestSearchItemsFiltersByDecades(t *testing.T) {
	store, ctx := newTestStore(t)
	for _, movie := range []struct {
		title string
		year  int
	}{
		{"Heat", 1995},
		{"Inception", 2010},
		{"Casablanca", 1942},
	} {
		upsertTestItem(t, ctx, store, Item{
			LibraryID: "movies",
			Path:      "/movies/" + movie.title + ".mkv",
			Kind:      "movie",
			Title:     movie.title,
			SortTitle: strings.ToLower(movie.title),
			Year:      movie.year,
			SizeBytes: 1,
		})
	}

	items, err := store.SearchItems(ctx, SearchOptions{LibraryID: "movies", Decades: "1990,2010"})
	if err != nil {
		t.Fatalf("search with decades: %v", err)
	}
	titles := make([]string, 0, len(items))
	for _, item := range items {
		titles = append(titles, item.Title)
	}
	if len(titles) != 2 || titles[0] != "Heat" || titles[1] != "Inception" {
		t.Fatalf("decade-filtered titles = %v, want [Heat Inception]", titles)
	}

	decades, err := store.ListDecades(ctx, "movies", "movie")
	if err != nil {
		t.Fatalf("list decades: %v", err)
	}
	if len(decades) != 3 || decades[0] != 1940 || decades[1] != 1990 || decades[2] != 2010 {
		t.Fatalf("decades = %v, want [1940 1990 2010]", decades)
	}
}

func TestSearchShowsFiltersByDecades(t *testing.T) {
	store, ctx := newTestStore(t)
	// Severance has no year at all, only a premiered date — the decade
	// filter must fall back to the date's year part.
	for _, show := range []struct {
		title     string
		year      int
		premiered string
	}{
		{"The Wire", 2002, ""},
		{"Severance", 0, "2022-02-18"},
	} {
		upsertTestItem(t, ctx, store, Item{
			LibraryID:     "tv",
			Path:          "/tv/" + show.title + "/S01E01.mkv",
			Kind:          "episode",
			Title:         show.title,
			SortTitle:     strings.ToLower(show.title),
			ShowTitle:     show.title,
			SeasonNumber:  1,
			EpisodeNumber: 1,
			Year:          show.year,
			Premiered:     show.premiered,
			SizeBytes:     1,
		})
	}

	shows, err := store.SearchShows(ctx, ShowOptions{LibraryID: "tv", Decades: "2020"})
	if err != nil {
		t.Fatalf("search shows with decades: %v", err)
	}
	if len(shows) != 1 || shows[0].Title != "Severance" {
		t.Fatalf("decade-filtered shows = %+v, want only Severance", shows)
	}

	alphabet, err := store.AlphabetIndex(ctx, AlphabetOptions{LibraryID: "tv", Kind: "tv", Decades: "2020"})
	if err != nil {
		t.Fatalf("alphabet with decades: %v", err)
	}
	if len(alphabet) != 1 || alphabet[0].Letter != "S" {
		t.Fatalf("decade-filtered alphabet = %+v, want only S", alphabet)
	}

	decades, err := store.ListDecades(ctx, "tv", "tv")
	if err != nil {
		t.Fatalf("list tv decades: %v", err)
	}
	if len(decades) != 2 || decades[0] != 2000 || decades[1] != 2020 {
		t.Fatalf("tv decades = %v, want [2000 2020]", decades)
	}
}

func TestItemsByPathsReturnsOnlyRequestedItems(t *testing.T) {
	store, ctx := newTestStore(t)
	for _, item := range []Item{
		{LibraryID: "movies", Path: "/movies/one.mkv", Kind: "movie", Title: "One", SortTitle: "one"},
		{LibraryID: "movies", Path: "/movies/two.mkv", Kind: "movie", Title: "Two", SortTitle: "two"},
		{LibraryID: "movies", Path: "/movies/three.mkv", Kind: "movie", Title: "Three", SortTitle: "three"},
	} {
		if err := store.UpsertItem(ctx, item); err != nil {
			t.Fatalf("upsert %s: %v", item.Path, err)
		}
	}

	items, err := store.ItemsByPaths(ctx, []string{"/movies/two.mkv", "/movies/gone.mkv", "/movies/two.mkv"})
	if err != nil {
		t.Fatalf("items by paths: %v", err)
	}
	if len(items) != 1 || items[0].Path != "/movies/two.mkv" {
		t.Fatalf("items = %#v, want only /movies/two.mkv", items)
	}
	empty, err := store.ItemsByPaths(ctx, nil)
	if err != nil {
		t.Fatalf("items by no paths: %v", err)
	}
	if len(empty) != 0 {
		t.Fatalf("items for no paths = %#v, want none", empty)
	}
}

func TestSearchShowsSeenStatusFilters(t *testing.T) {
	store, ctx := newTestStore(t)
	userID := insertTestUser(t, store, "viewer")

	episode := func(show string, number int) Item {
		return upsertTestItem(t, ctx, store, Item{
			LibraryID:     "tv",
			Kind:          "episode",
			Title:         show + " E" + strconv.Itoa(number),
			SortTitle:     strings.ToLower(show),
			Path:          "/tv/" + strings.ToLower(show) + "/e" + strconv.Itoa(number) + ".mkv",
			ShowTitle:     show,
			SeasonNumber:  1,
			EpisodeNumber: number,
			DurationMS:    600_000,
		})
	}
	watchedS1, watchedS2 := episode("Watched", 1), episode("Watched", 2)
	startedS1, _ := episode("Started", 1), episode("Started", 2)
	episode("Fresh", 1)

	for _, id := range []int64{watchedS1.ID, watchedS2.ID} {
		if _, err := store.SaveProgress(ctx, userID, id, 600_000, 600_000, true); err != nil {
			t.Fatalf("save completed progress: %v", err)
		}
	}
	if _, err := store.SaveProgress(ctx, userID, startedS1.ID, 300_000, 600_000, false); err != nil {
		t.Fatalf("save partial progress: %v", err)
	}

	titles := func(status string) []string {
		shows, err := store.ListShowsForUser(ctx, "tv", "", "", "", "", status, userID, 0, 50, 0)
		if err != nil {
			t.Fatalf("list shows (%s): %v", status, err)
		}
		out := make([]string, 0, len(shows))
		for _, show := range shows {
			out = append(out, show.Title)
		}
		sort.Strings(out)
		return out
	}

	for _, tc := range []struct {
		status string
		want   []string
	}{
		{"", []string{"Fresh", "Started", "Watched"}},
		{"seen", []string{"Watched"}},
		{"unseen", []string{"Fresh", "Started"}},
		{"started", []string{"Started"}},
	} {
		if got := titles(tc.status); !slices.Equal(got, tc.want) {
			t.Errorf("shows for seen=%q = %v, want %v", tc.status, got, tc.want)
		}
	}

	// Without a user there is nothing watched, so "unseen" must not filter.
	if got := len(mustShows(t, store, ctx, "unseen", 0)); got != 3 {
		t.Errorf("anonymous unseen returned %d shows, want 3", got)
	}
	if got := len(mustShows(t, store, ctx, "seen", 0)); got != 0 {
		t.Errorf("anonymous seen returned %d shows, want 0", got)
	}
}

func mustShows(t *testing.T, store *Store, ctx context.Context, status string, userID int64) []ShowSummary {
	t.Helper()
	shows, err := store.ListShowsForUser(ctx, "tv", "", "", "", "", status, userID, 0, 50, 0)
	if err != nil {
		t.Fatalf("list shows (%s): %v", status, err)
	}
	return shows
}
