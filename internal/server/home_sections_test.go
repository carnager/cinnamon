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

func homeSectionsFor(t *testing.T, app *App, userID int64, profile string) []media.HomeSection {
	t.Helper()
	payload, err := app.buildHomePayload(context.Background(), auth.User{ID: userID, Username: "alice"}, profile)
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

	sections := homeSectionsFor(t, app, userID, "")
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
	if err := store.SaveHomeLayout(context.Background(), userID, "tv", string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID, "tv")
	types := sectionTypes(sections)
	if len(types) != 2 || types[0] != "recent_tv" || types[1] != "recommendations" {
		t.Fatalf("sections = %v, want recent_tv then recommendations", types)
	}
	if sections[1].Title != "Tonight" {
		t.Fatalf("title override ignored: %q", sections[1].Title)
	}

	// A profile without its own row falls back to the default profile's layout,
	// which here is still the built-in one.
	if got := len(homeSectionsFor(t, app, userID, "phone")); got == 2 {
		t.Fatal("phone profile reused the tv layout instead of falling back")
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
	if err := store.SaveHomeLayout(context.Background(), userID, defaultHomeProfile, string(body)); err != nil {
		t.Fatalf("save layout: %v", err)
	}

	sections := homeSectionsFor(t, app, userID, "")
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
	if len(catalog) < len(defaultHomeLayout(defaultHomeProfile).Sections) {
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

func TestNormalizeHomeProfileFallsBackToDefault(t *testing.T) {
	if got := normalizeHomeProfile(" TV "); got != "tv" {
		t.Fatalf("profile = %q", got)
	}
	if got := normalizeHomeProfile("toaster"); got != defaultHomeProfile {
		t.Fatalf("unknown profile = %q, want %q", got, defaultHomeProfile)
	}
}
