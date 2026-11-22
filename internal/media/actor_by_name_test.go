package media

import (
	"context"
	"path/filepath"
	"testing"

	"popcorn/internal/database"
)

// Most actors have no stored thumbnail or role. Those NULLs must not turn the
// actor page and photo endpoints into a 500.
func TestActorByNameToleratesNullRoleAndThumb(t *testing.T) {
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	store := NewStore(db)
	if _, err := store.DB().Exec(`INSERT INTO media_actors(scope, name, role, thumb, sort_order) VALUES ('show', 'No Photo', NULL, NULL, 1)`); err != nil {
		t.Fatal(err)
	}
	actor, err := store.ActorByName(context.Background(), "No Photo")
	if err != nil {
		t.Fatalf("ActorByName: %v", err)
	}
	if actor.Name != "No Photo" || actor.Thumb != "" || actor.Role != "" {
		t.Fatalf("unexpected actor: %+v", actor)
	}
}
