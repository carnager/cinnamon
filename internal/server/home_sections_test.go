package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"path/filepath"
	"testing"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func newHomeSectionsApp(t *testing.T) (*App, *media.Store, int64) {
	t.Helper()
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := media.NewStore(db)
	result, err := store.DB().Exec(`INSERT INTO users(username, display_name, password_hash, is_admin) VALUES ('alice', 'Alice', 'test', 0)`)
	if err != nil {
		t.Fatal(err)
	}
	userID, _ := result.LastInsertId()

	app := New(Options{
		Config: config.Config{Libraries: []config.Library{
			{ID: "movies", Name: "Movies", Type: "movies", Path: t.TempDir()},
			{ID: "tv", Name: "TV", Type: "tv", Path: t.TempDir()},
		}},
		Store: store,
		Log:   slog.New(slog.DiscardHandler),
	})
	t.Cleanup(app.Close)

	ctx := context.Background()
	for _, item := range []media.Item{
		{LibraryID: "movies", Kind: "movie", Path: "/movies/harbor.mkv", Title: "Harbor", SortTitle: "harbor", Rating: 8.4, Genres: "Horror", MTimeUnix: 20},
		{LibraryID: "movies", Kind: "movie", Path: "/movies/dunes.mkv", Title: "Dunes", SortTitle: "dunes", Rating: 7.1, Genres: "Drama", MTimeUnix: 10},
		{LibraryID: "tv", Kind: "episode", Path: "/tv/watch/s01e01.mkv", Title: "Night Watch S01E01", SortTitle: "night watch 01 01", ShowTitle: "Night Watch", SeasonNumber: 1, EpisodeNumber: 1, Rating: 8.0, Genres: "Horror", MTimeUnix: 15},
	} {
		if err := store.UpsertItem(ctx, item); err != nil {
			t.Fatalf("upsert %s: %v", item.Title, err)
		}
	}
	return app, store, userID
}

func homeSectionsFor(t *testing.T, app *App, userID int64) []media.HomeSection {
	t.Helper()
	payload, err := app.buildHomePayload(context.Background(), auth.User{ID: userID, Username: "alice"})
	if err != nil {
		t.Fatalf("build home payload: %v", err)
	}
	return payload.Sections
}

func sectionTypes(sections []media.HomeSection) []string {
	out := make([]string, 0, len(sections))
	for _, section := range sections {
		out = append(out, section.Type)
	}
	return out
}

func TestHomeSectionsUseTheDefaultLayoutWhenUnconfigured(t *testing.T) {
	app, _, userID := newHomeSectionsApp(t)

	sections := homeSectionsFor(t, app, userID)
	types := sectionTypes(sections)
	if len(types) == 0 {
		t.Fatal("default layout produced no sections")
	}
	// Empty shelves are dropped, so only the ones the fixture can fill show up.
	for _, want := range []string{"recommendations", "recent_movies", "recent_tv"} {
		if !containsString(types, want) {
			t.Fatalf("default sections %v missing %q", types, want)
		}
	}
	if types[0] != "recommendations" {
		t.Fatalf("default layout starts with %q, want the hero first", types[0])
	}
	for _, section := range sections {
		if section.ID == "" || section.Layout == "" || section.Kind == "" || section.Title == "" {
			t.Fatalf("section %#v is missing render metadata", section)
		}
	}
}

func TestHomeSectionsFollowStoredLayoutOrderAndToggles(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)

	layout := media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{ID: "recent-tv", Type: "recent_tv", Enabled: true},
		{ID: "off", Type: "recent_movies", Enabled: false},
		{ID: "hero", Type: "recommendations", Enabled: true, Title: "Tonight"},
	}}
	saved, err := validateHomeLayout(layout)
	if err != nil {
		t.Fatalf("validate layout: %v", err)
	}
	body, _ := json.Marshal(saved)
	if err := store.SaveHomeLayout(context.Background(), userID, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID)
	types := sectionTypes(sections)
	if len(types) != 2 || types[0] != "recent_tv" || types[1] != "recommendations" {
		t.Fatalf("sections = %v, want recent_tv then recommendations", types)
	}
	if sections[1].Title != "Tonight" {
		t.Fatalf("title override ignored: %q", sections[1].Title)
	}

	// Dropping the layout falls back to the built-in one.
	if err := store.DeleteHomeLayout(context.Background(), userID); err != nil {
		t.Fatalf("delete layout: %v", err)
	}
	if got := len(homeSectionsFor(t, app, userID)); got == 2 {
		t.Fatal("deleted layout was still applied")
	}
}

func TestHomeSectionsRepeatParameterisedGenreShelves(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)

	layout, err := validateHomeLayout(media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{ID: "horror", Type: "genre", Enabled: true, Params: map[string]string{"genre": "Horror", "limit": "5"}},
		{ID: "horror-tv", Type: "genre", Enabled: true, Params: map[string]string{"genre": "Horror", "kind": "tv"}},
		{ID: "nothing", Type: "genre", Enabled: true, Params: map[string]string{"genre": "Polka"}},
	}})
	if err != nil {
		t.Fatalf("validate layout: %v", err)
	}
	body, _ := json.Marshal(layout)
	if err := store.SaveHomeLayout(context.Background(), userID, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID)
	if len(sections) != 2 {
		t.Fatalf("got %d sections, want the two genres with matches", len(sections))
	}
	if sections[0].Title != "Horror" || len(sections[0].Items) != 1 || sections[0].Items[0].Title != "Harbor" {
		t.Fatalf("movie genre shelf = %#v", sections[0])
	}
	if sections[1].Kind != "show" || len(sections[1].Shows) != 1 || sections[1].Shows[0].Title != "Night Watch" {
		t.Fatalf("tv genre shelf = %#v", sections[1])
	}
}

func TestValidateHomeLayoutRejectsBadConfigurations(t *testing.T) {
	for name, layout := range map[string]media.HomeLayoutDoc{
		"unknown type": {Sections: []media.HomeLayoutSection{{Type: "moon_phase", Enabled: true}}},
		"repeated non-repeatable": {Sections: []media.HomeLayoutSection{
			{ID: "a", Type: "recent_movies", Enabled: true},
			{ID: "b", Type: "recent_movies", Enabled: true},
		}},
		"duplicate id": {Sections: []media.HomeLayoutSection{
			{ID: "same", Type: "recent_movies", Enabled: true},
			{ID: "same", Type: "recent_tv", Enabled: true},
		}},
		"unknown param": {Sections: []media.HomeLayoutSection{{Type: "recent_movies", Enabled: true, Params: map[string]string{"colour": "red"}}}},
		"bad enum":      {Sections: []media.HomeLayoutSection{{Type: "genre", Enabled: true, Params: map[string]string{"genre": "Horror", "kind": "books"}}}},
		"bad int":       {Sections: []media.HomeLayoutSection{{Type: "recent_movies", Enabled: true, Params: map[string]string{"limit": "lots"}}}},
		"missing genre": {Sections: []media.HomeLayoutSection{{Type: "genre", Enabled: true}}},
	} {
		if _, err := validateHomeLayout(layout); err == nil {
			t.Errorf("%s was accepted", name)
		}
	}

	ok, err := validateHomeLayout(media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{Type: "genre", Enabled: true, Params: map[string]string{"genre": " Horror ", "limit": "12"}},
		{Type: "genre", Enabled: true, Params: map[string]string{"genre": "Drama"}},
	}})
	if err != nil {
		t.Fatalf("valid layout rejected: %v", err)
	}
	if ok.Sections[0].ID != "genre-1" || ok.Sections[1].ID != "genre-2" {
		t.Fatalf("ids were not generated: %#v", ok.Sections)
	}
	if ok.Sections[0].Params["genre"] != "Horror" {
		t.Fatalf("param was not trimmed: %q", ok.Sections[0].Params["genre"])
	}
}

func TestHomeSectionCatalogLeadsWithTheDefaultLayout(t *testing.T) {
	catalog := homeSectionCatalog()
	if len(catalog) < len(defaultHomeLayout().Sections) {
		t.Fatalf("catalog has %d entries, fewer than the default layout", len(catalog))
	}
	if catalog[0].Type != "recommendations" {
		t.Fatalf("catalog starts with %q", catalog[0].Type)
	}
	byType := map[string]media.HomeSectionType{}
	for _, entry := range catalog {
		if entry.Layout == "" || entry.Kind == "" || entry.Label == "" {
			t.Fatalf("catalog entry %#v is not renderable by an editor", entry)
		}
		byType[entry.Type] = entry
	}
	genre, ok := byType["genre"]
	if !ok || !genre.Repeatable {
		t.Fatal("genre shelf must be in the catalog and repeatable")
	}
	if len(genre.Params) == 0 {
		t.Fatal("genre shelf must declare its parameters")
	}
}

func TestSurpriseShelfPicksUnwatchedAndFallsBackBelowTheRatingFloor(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)
	ctx := context.Background()

	// The fixture's movies are both well rated; add one that is not.
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/turkey.mkv",
		Title: "Turkey", SortTitle: "turkey", Rating: 2.1, MTimeUnix: 5,
	}); err != nil {
		t.Fatal(err)
	}
	layout, err := validateHomeLayout(media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{ID: "surprise", Type: "surprise", Enabled: true},
	}})
	if err != nil {
		t.Fatalf("validate layout: %v", err)
	}
	body, _ := json.Marshal(layout)
	if err := store.SaveHomeLayout(ctx, userID, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	// Repeat: the shelf is randomly ordered, so one clean draw proves little.
	for i := 0; i < 8; i++ {
		sections := homeSectionsFor(t, app, userID)
		if len(sections) != 1 || len(sections[0].Items) == 0 {
			t.Fatalf("surprise shelf = %#v", sections)
		}
		for _, item := range sections[0].Items {
			if item.Title == "Turkey" {
				t.Fatal("shelf offered a movie below the rating floor while better ones are unwatched")
			}
		}
	}

	// Finish the well-rated ones: the shelf must fall back rather than vanish.
	items, err := store.ListItemsForUser(ctx, "movies", "", "", "", "", "", userID, 0, 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range items {
		if item.Title == "Turkey" {
			continue
		}
		if _, err := store.SaveProgress(ctx, userID, item.ID, 100, 100, true); err != nil {
			t.Fatal(err)
		}
	}
	sections := homeSectionsFor(t, app, userID)
	if len(sections) != 1 || len(sections[0].Items) != 1 || sections[0].Items[0].Title != "Turkey" {
		t.Fatalf("fallback shelf = %#v", sections)
	}
}

func TestFilterShelfNarrowsAndNamesItself(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)
	ctx := context.Background()

	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/short.mkv", Title: "Short One", SortTitle: "short one",
		Rating: 9.0, Genres: "Drama", Countries: "Japan", Studios: "Ghibli", DurationMS: 80 * 60_000, MTimeUnix: 30,
	}); err != nil {
		t.Fatal(err)
	}

	layout, err := validateHomeLayout(media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{ID: "japan", Type: "filter", Enabled: true, Params: map[string]string{"country": "Japan"}},
		{ID: "short", Type: "filter", Enabled: true, Params: map[string]string{"maxMinutes": "90"}},
		{ID: "studio", Type: "filter", Enabled: true, Params: map[string]string{"studio": "Ghibli", "genre": "Drama"}},
		{ID: "nothing", Type: "filter", Enabled: true, Params: map[string]string{"country": "Atlantis"}},
	}})
	if err != nil {
		t.Fatalf("validate layout: %v", err)
	}
	body, _ := json.Marshal(layout)
	if err := store.SaveHomeLayout(ctx, userID, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID)
	if len(sections) != 3 {
		t.Fatalf("got %d sections, want the three with matches: %#v", len(sections), sectionTypes(sections))
	}
	for _, section := range sections {
		if len(section.Items) != 1 || section.Items[0].Title != "Short One" {
			t.Fatalf("section %q = %#v, want only the matching movie", section.ID, section.Items)
		}
	}
	if sections[0].Title != "Japan" || sections[1].Title != "Under 90 minutes" || sections[2].Title != "Ghibli · Drama" {
		t.Fatalf("titles = %q, %q, %q", sections[0].Title, sections[1].Title, sections[2].Title)
	}
}

func TestNewEpisodesShelfOnlyCoversStartedShows(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)
	ctx := context.Background()

	// A second show the user has never touched, added most recently.
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "tv", Kind: "episode", Path: "/tv/other/s01e01.mkv", Title: "Other S01E01", SortTitle: "other 01 01",
		ShowTitle: "Other", SeasonNumber: 1, EpisodeNumber: 1, MTimeUnix: 99,
	}); err != nil {
		t.Fatal(err)
	}
	// A new episode of the show that has been started.
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "tv", Kind: "episode", Path: "/tv/watch/s01e02.mkv", Title: "Night Watch S01E02", SortTitle: "night watch 01 02",
		ShowTitle: "Night Watch", SeasonNumber: 1, EpisodeNumber: 2, MTimeUnix: 98,
	}); err != nil {
		t.Fatal(err)
	}
	episodes, err := store.ListEpisodes(ctx, "tv", "Night Watch", -1)
	if err != nil {
		t.Fatal(err)
	}
	for _, episode := range episodes {
		if episode.EpisodeNumber != 1 {
			continue
		}
		if _, err := store.SaveProgress(ctx, userID, episode.ID, 100, 100, true); err != nil {
			t.Fatal(err)
		}
	}

	layout, err := validateHomeLayout(media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{ID: "new-episodes", Type: "new_episodes", Enabled: true},
	}})
	if err != nil {
		t.Fatalf("validate layout: %v", err)
	}
	body, _ := json.Marshal(layout)
	if err := store.SaveHomeLayout(ctx, userID, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID)
	if len(sections) != 1 {
		t.Fatalf("sections = %#v", sectionTypes(sections))
	}
	if len(sections[0].Items) != 1 || sections[0].Items[0].EpisodeNumber != 2 {
		t.Fatalf("shelf = %#v, want only the new episode of the started show", sections[0].Items)
	}
}

func TestGatheringDustAndWatchlistAgeOrderByAge(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)
	ctx := context.Background()

	layout, err := validateHomeLayout(media.HomeLayoutDoc{Sections: []media.HomeLayoutSection{
		{ID: "dust", Type: "gathering_dust", Enabled: true},
	}})
	if err != nil {
		t.Fatalf("validate layout: %v", err)
	}
	body, _ := json.Marshal(layout)
	if err := store.SaveHomeLayout(ctx, userID, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID)
	if len(sections) != 1 || len(sections[0].Items) == 0 {
		t.Fatalf("dust shelf = %#v", sections)
	}
	// Dunes has the older mtime of the two fixture movies.
	if sections[0].Items[0].Title != "Dunes" {
		t.Fatalf("oldest first = %q, want Dunes", sections[0].Items[0].Title)
	}
}
