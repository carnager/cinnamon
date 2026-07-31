package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"popcorn/internal/auth"
	"popcorn/internal/media"
)

func TestSurprisePicksAnUnwatchedTitle(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)
	ctx := context.Background()

	items, err := store.ListItemsForUser(ctx, "movies", "", "", "", "", "", userID, 0, 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	// Finish everything but Harbor, so a movie pick can only be Harbor.
	for _, item := range items {
		if item.Title == "Harbor" {
			continue
		}
		if _, err := store.SaveProgress(ctx, userID, item.ID, 100, 100, true); err != nil {
			t.Fatal(err)
		}
	}

	for i := 0; i < 12; i++ {
		body := surpriseResponse(t, app, userID)
		if body.Item == nil && body.Show == nil {
			t.Fatal("surprise returned nothing while unwatched titles exist")
		}
		if body.Item != nil && body.Item.Title != "Harbor" {
			t.Fatalf("surprise offered %q, which is already watched", body.Item.Title)
		}
	}
}

func TestSurpriseFallsBackBelowTheRatingFloor(t *testing.T) {
	app, store, userID := newHomeSectionsApp(t)
	ctx := context.Background()

	// Only a poorly rated movie is left: the floor must not empty the response.
	if err := store.UpsertItem(ctx, media.Item{
		LibraryID: "movies", Kind: "movie", Path: "/movies/turkey.mkv",
		Title: "Turkey", SortTitle: "turkey", Rating: 2.1, MTimeUnix: 5,
	}); err != nil {
		t.Fatal(err)
	}
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
	episodes, err := store.ListEpisodes(ctx, "tv", "Night Watch", -1)
	if err != nil {
		t.Fatal(err)
	}
	for _, episode := range episodes {
		if _, err := store.SaveProgress(ctx, userID, episode.ID, 100, 100, true); err != nil {
			t.Fatal(err)
		}
	}

	body := surpriseResponse(t, app, userID)
	if body.Item == nil || body.Item.Title != "Turkey" {
		t.Fatalf("surprise = %#v, want the only unwatched movie", body)
	}
}

type surpriseBody struct {
	Item *media.Item        `json:"item"`
	Show *media.ShowSummary `json:"show"`
}

func surpriseResponse(t *testing.T, app *App, userID int64) surpriseBody {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/surprise", nil)
	req = req.WithContext(context.WithValue(req.Context(), authUserContextKey{}, auth.User{ID: userID, Username: "alice"}))
	rec := httptest.NewRecorder()
	app.surprise(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("surprise status %d: %s", rec.Code, rec.Body.String())
	}
	var body surpriseBody
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode surprise: %v", err)
	}
	return body
}
