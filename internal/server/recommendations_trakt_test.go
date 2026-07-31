package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestTraktRecommendationExclusionUsesHiddenRecommendationsSection(t *testing.T) {
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
	if err := store.SaveTraktAccount(context.Background(), media.TraktAccount{
		UserID: userID, AccessToken: "token", RefreshToken: "refresh", ExpiresAt: "2099-01-01T00:00:00Z",
	}); err != nil {
		t.Fatal(err)
	}

	type request struct {
		path string
		body map[string]any
	}
	requests := make(chan request, 2)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode request: %v", err)
		}
		requests <- request{path: r.URL.Path, body: body}
		_ = json.NewEncoder(w).Encode(map[string]any{"added": map[string]any{"shows": 1}})
	}))
	defer upstream.Close()

	app := New(Options{
		Config: config.Config{
			TraktClientID:     "client",
			TraktClientSecret: "secret",
			TraktAPIURL:       upstream.URL,
		},
		Store: store,
		Log:   slog.New(slog.DiscardHandler),
	})
	t.Cleanup(app.Close)
	show := media.ShowSummary{LibraryID: "tv", Title: "Night Watch", Year: 2024}
	app.traktSyncRecommendationExclusionShows(userID, []media.ShowSummary{show}, false)
	app.traktSyncRecommendationExclusionShows(userID, []media.ShowSummary{show}, true)

	add := <-requests
	remove := <-requests
	if add.path != "/users/hidden/recommendations" {
		t.Fatalf("add path = %q", add.path)
	}
	if remove.path != "/users/hidden/recommendations/remove" {
		t.Fatalf("remove path = %q", remove.path)
	}
	shows, ok := add.body["shows"].([]any)
	if !ok || len(shows) != 1 {
		t.Fatalf("add body = %#v", add.body)
	}
}
