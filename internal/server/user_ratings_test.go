package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/media"
)

func insertRatingTestUser(t *testing.T, app *App, id int64) {
	t.Helper()
	if _, err := app.store.DB().Exec(`INSERT INTO users(id, username, display_name, password_hash, is_admin) VALUES (?, ?, ?, 'x', 0)`, id, "user"+strconv.FormatInt(id, 10), "User"); err != nil {
		t.Fatalf("insert test user: %v", err)
	}
}

func ratingRequest(method, id, body string) *http.Request {
	req := httptest.NewRequest(method, "/api/items/"+id+"/rating", strings.NewReader(body))
	req.SetPathValue("id", id)
	return req.WithContext(context.WithValue(req.Context(), authUserContextKey{}, auth.User{ID: 1, Username: "tester"}))
}

func TestItemRatingRoundtrip(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "movie.mkv")
	if err := os.WriteFile(video, []byte("v"), 0o644); err != nil {
		t.Fatal(err)
	}
	app, item := newThumbTestApp(t, config.Config{}, media.Item{
		LibraryID: "movies",
		Path:      video,
		Kind:      "movie",
		Title:     "Movie",
		SortTitle: "movie",
		MTimeUnix: 1,
	})
	insertRatingTestUser(t, app, 1)
	id := strconv.FormatInt(item.ID, 10)

	rec := httptest.NewRecorder()
	app.itemRatingSave(rec, ratingRequest(http.MethodPut, id, `{"rating":8}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("save rating = %d: %s", rec.Code, rec.Body.String())
	}

	rec = httptest.NewRecorder()
	app.itemRatingSave(rec, ratingRequest(http.MethodPut, id, `{"rating":11}`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("rating 11 accepted: %d", rec.Code)
	}

	ratings, err := app.store.ListUserRatings(context.Background(), 1)
	if err != nil {
		t.Fatalf("list ratings: %v", err)
	}
	if len(ratings) != 1 || ratings[0].ItemID != item.ID || ratings[0].Rating != 8 {
		t.Fatalf("ratings = %+v, want one 8/10 for the item", ratings)
	}

	// Re-rating overwrites, delete removes.
	rec = httptest.NewRecorder()
	app.itemRatingSave(rec, ratingRequest(http.MethodPut, id, `{"rating":5}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("re-rate = %d", rec.Code)
	}
	ratings, _ = app.store.ListUserRatings(context.Background(), 1)
	if len(ratings) != 1 || ratings[0].Rating != 5 {
		t.Fatalf("ratings after re-rate = %+v", ratings)
	}
	rec = httptest.NewRecorder()
	app.itemRatingDelete(rec, ratingRequest(http.MethodDelete, id, ""))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("delete rating = %d", rec.Code)
	}
	ratings, _ = app.store.ListUserRatings(context.Background(), 1)
	if len(ratings) != 0 {
		t.Fatalf("ratings after delete = %+v", ratings)
	}
}

func TestShowRatingStoreRoundtrip(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "ep.mkv")
	if err := os.WriteFile(video, []byte("v"), 0o644); err != nil {
		t.Fatal(err)
	}
	app, _ := newThumbTestApp(t, config.Config{}, media.Item{
		LibraryID:     "tv",
		Path:          video,
		Kind:          "episode",
		Title:         "Show",
		SortTitle:     "show",
		ShowTitle:     "Show",
		SeasonNumber:  1,
		EpisodeNumber: 1,
		MTimeUnix:     1,
	})
	insertRatingTestUser(t, app, 7)
	ctx := context.Background()
	if err := app.store.SaveShowRating(ctx, 7, "tv", "Show", 9); err != nil {
		t.Fatalf("save show rating: %v", err)
	}
	ratings, err := app.store.ListUserRatings(ctx, 7)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(ratings) != 1 || ratings[0].Kind != "show" || ratings[0].ShowTitle != "Show" || ratings[0].Rating != 9 {
		t.Fatalf("ratings = %+v", ratings)
	}
	var payload []media.UserRating
	raw, _ := json.Marshal(ratings)
	if err := json.Unmarshal(raw, &payload); err != nil || payload[0].Rating != 9 {
		t.Fatalf("json roundtrip = %v %+v", err, payload)
	}
	if err := app.store.DeleteShowRating(ctx, 7, "tv", "Show"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	ratings, _ = app.store.ListUserRatings(ctx, 7)
	if len(ratings) != 0 {
		t.Fatalf("ratings after delete = %+v", ratings)
	}
}
