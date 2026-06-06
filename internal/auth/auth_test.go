package auth

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"popcorn/internal/database"
)

func TestEnsureBootstrapCreatesOneAdminUser(t *testing.T) {
	store, ctx := newTestStore(t)

	created, user, err := store.EnsureBootstrap(ctx, " Admin ", "secret1")
	if err != nil {
		t.Fatalf("bootstrap first user: %v", err)
	}
	if !created || user.Username != "admin" || !user.IsAdmin {
		t.Fatalf("bootstrap user = created %v, %#v; want admin user", created, user)
	}

	created, user, err = store.EnsureBootstrap(ctx, "other", "secret2")
	if err != nil {
		t.Fatalf("bootstrap with existing user: %v", err)
	}
	if created || user.ID != 0 {
		t.Fatalf("second bootstrap = created %v, %#v; want no-op", created, user)
	}

	users, err := store.Users(ctx)
	if err != nil {
		t.Fatalf("list users: %v", err)
	}
	if len(users) != 1 || users[0].Username != "admin" {
		t.Fatalf("users after bootstrap = %#v, want only admin", users)
	}
}

func TestAuthenticateNormalizesUsernameAndRejectsInvalidUsers(t *testing.T) {
	store, ctx := newTestStore(t)

	user, err := store.CreateUser(ctx, CreateUserInput{
		Username:    " Rasi ",
		DisplayName: "Rasi",
		Password:    "secret1",
		IsAdmin:     false,
	})
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	if user.Username != "rasi" {
		t.Fatalf("created username = %q, want normalized username", user.Username)
	}

	authenticated, err := store.Authenticate(ctx, "RASI", "secret1")
	if err != nil {
		t.Fatalf("authenticate normalized username: %v", err)
	}
	if authenticated.ID != user.ID {
		t.Fatalf("authenticated user = %#v, want %#v", authenticated, user)
	}

	if _, err := store.Authenticate(ctx, "rasi", "wrong-password"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("wrong password error = %v, want ErrInvalidCredentials", err)
	}

	if _, err := store.CreateUser(ctx, CreateUserInput{Username: "rasi", Password: "secret2"}); err == nil {
		t.Fatalf("duplicate username unexpectedly succeeded")
	}

	if _, err := store.CreateUser(ctx, CreateUserInput{Username: "short", Password: "123"}); err == nil {
		t.Fatalf("short password unexpectedly succeeded")
	}

	if _, err := store.db.ExecContext(ctx, `UPDATE users SET disabled = 1 WHERE id = ?`, user.ID); err != nil {
		t.Fatalf("disable user: %v", err)
	}
	if _, err := store.Authenticate(ctx, "rasi", "secret1"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("disabled user auth error = %v, want ErrForbidden", err)
	}
}

func TestUpdateUserChangesProfileRoleAndPassword(t *testing.T) {
	store, ctx := newTestStore(t)
	user, err := store.CreateUser(ctx, CreateUserInput{
		Username:    "player",
		DisplayName: "Player",
		Password:    "secret1",
		IsAdmin:     false,
	})
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	displayName := "Movie Player"
	isAdmin := true
	disabled := false
	updated, err := store.UpdateUser(ctx, user.ID, UpdateUserInput{
		DisplayName: &displayName,
		Password:    "secret2",
		IsAdmin:     &isAdmin,
		Disabled:    &disabled,
	})
	if err != nil {
		t.Fatalf("update user: %v", err)
	}
	if updated.DisplayName != "Movie Player" || !updated.IsAdmin || updated.Disabled {
		t.Fatalf("updated user = %#v, want display name, admin, enabled", updated)
	}
	if _, err := store.Authenticate(ctx, "player", "secret1"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("old password auth error = %v, want ErrInvalidCredentials", err)
	}
	if _, err := store.Authenticate(ctx, "player", "secret2"); err != nil {
		t.Fatalf("new password auth: %v", err)
	}
	count, err := store.EnabledAdminCount(ctx)
	if err != nil {
		t.Fatalf("enabled admin count: %v", err)
	}
	if count != 1 {
		t.Fatalf("enabled admin count = %d, want 1", count)
	}
}

func TestSessionLifecycleAndExpiry(t *testing.T) {
	store, ctx := newTestStore(t)
	user, err := store.CreateUser(ctx, CreateUserInput{
		Username: "player",
		Password: "secret1",
	})
	if err != nil {
		t.Fatalf("create user: %v", err)
	}

	token, err := store.CreateSession(ctx, user.ID, time.Hour)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	sessionUser, err := store.UserByToken(ctx, " "+token+" ")
	if err != nil {
		t.Fatalf("lookup session by trimmed token: %v", err)
	}
	if sessionUser.ID != user.ID {
		t.Fatalf("session user = %#v, want %#v", sessionUser, user)
	}

	if err := store.DeleteSession(ctx, token); err != nil {
		t.Fatalf("delete session: %v", err)
	}
	if _, err := store.UserByToken(ctx, token); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("deleted session error = %v, want sql.ErrNoRows", err)
	}

	expiredToken, err := store.CreateSession(ctx, user.ID, -time.Second)
	if err != nil {
		t.Fatalf("create expired session: %v", err)
	}
	if _, err := store.UserByToken(ctx, expiredToken); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("expired session error = %v, want sql.ErrNoRows", err)
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
