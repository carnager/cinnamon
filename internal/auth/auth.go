package auth

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

const (
	hashIterations       = 120000
	saltBytes            = 16
	keyBytes             = 32
	sessionTouchInterval = 5 * time.Minute
)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrForbidden          = errors.New("forbidden")
)

type Store struct {
	db *sql.DB
}

type User struct {
	ID          int64  `json:"id"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
	IsAdmin     bool   `json:"isAdmin"`
	Disabled    bool   `json:"disabled,omitempty"`
	CreatedAt   string `json:"createdAt,omitempty"`
}

type CreateUserInput struct {
	Username    string
	DisplayName string
	Password    string
	IsAdmin     bool
}

type UpdateUserInput struct {
	DisplayName *string
	Password    string
	IsAdmin     *bool
	Disabled    *bool
}

func NewStore(db *sql.DB) *Store {
	return &Store{db: db}
}

func (s *Store) EnsureBootstrap(ctx context.Context, username, password string) (bool, User, error) {
	var count int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users`).Scan(&count); err != nil {
		return false, User{}, err
	}
	if count > 0 {
		return false, User{}, nil
	}
	user, err := s.CreateUser(ctx, CreateUserInput{
		Username:    username,
		DisplayName: username,
		Password:    password,
		IsAdmin:     true,
	})
	return err == nil, user, err
}

func (s *Store) CreateUser(ctx context.Context, in CreateUserInput) (User, error) {
	username := normalizeUsername(in.Username)
	if username == "" || len(username) > 80 {
		return User{}, fmt.Errorf("invalid username")
	}
	if len(in.Password) < 6 {
		return User{}, fmt.Errorf("password must be at least 6 characters")
	}
	displayName := strings.TrimSpace(in.DisplayName)
	if displayName == "" {
		displayName = username
	}
	hash, err := hashPassword(in.Password)
	if err != nil {
		return User{}, err
	}
	res, err := s.db.ExecContext(ctx, `
INSERT INTO users(username, display_name, password_hash, is_admin)
VALUES (?, ?, ?, ?)`, username, displayName, hash, boolInt(in.IsAdmin))
	if err != nil {
		return User{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return User{}, err
	}
	return s.User(ctx, id)
}

func (s *Store) Users(ctx context.Context) ([]User, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, username, display_name, is_admin, disabled, created_at FROM users ORDER BY username`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []User
	for rows.Next() {
		user, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		users = append(users, user)
	}
	return users, rows.Err()
}

func (s *Store) User(ctx context.Context, id int64) (User, error) {
	row := s.db.QueryRowContext(ctx, `SELECT id, username, display_name, is_admin, disabled, created_at FROM users WHERE id = ?`, id)
	return scanUser(row)
}

func (s *Store) UpdateUser(ctx context.Context, id int64, in UpdateUserInput) (User, error) {
	user, err := s.User(ctx, id)
	if err != nil {
		return User{}, err
	}
	displayName := user.DisplayName
	isAdmin := user.IsAdmin
	disabled := user.Disabled
	if in.DisplayName != nil {
		displayName = strings.TrimSpace(*in.DisplayName)
		if displayName == "" {
			displayName = user.Username
		}
	}
	if in.IsAdmin != nil {
		isAdmin = *in.IsAdmin
	}
	if in.Disabled != nil {
		disabled = *in.Disabled
	}
	if in.Password != "" && len(in.Password) < 6 {
		return User{}, fmt.Errorf("password must be at least 6 characters")
	}
	if in.Password != "" {
		hash, err := hashPassword(in.Password)
		if err != nil {
			return User{}, err
		}
		_, err = s.db.ExecContext(ctx, `
UPDATE users
SET display_name = ?, password_hash = ?, is_admin = ?, disabled = ?, updated_at = CURRENT_TIMESTAMP
WHERE id = ?`, displayName, hash, boolInt(isAdmin), boolInt(disabled), id)
		if err != nil {
			return User{}, err
		}
	} else {
		_, err = s.db.ExecContext(ctx, `
UPDATE users
SET display_name = ?, is_admin = ?, disabled = ?, updated_at = CURRENT_TIMESTAMP
WHERE id = ?`, displayName, boolInt(isAdmin), boolInt(disabled), id)
		if err != nil {
			return User{}, err
		}
	}
	return s.User(ctx, id)
}

func (s *Store) EnabledAdminCount(ctx context.Context) (int, error) {
	var count int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM users WHERE is_admin = 1 AND disabled = 0`).Scan(&count)
	return count, err
}

func (s *Store) Authenticate(ctx context.Context, username, password string) (User, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT id, username, display_name, password_hash, is_admin, disabled, created_at
FROM users WHERE username = ?`, normalizeUsername(username))
	var user User
	var hash string
	var isAdmin, disabled int
	if err := row.Scan(&user.ID, &user.Username, &user.DisplayName, &hash, &isAdmin, &disabled, &user.CreatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return User{}, ErrInvalidCredentials
		}
		return User{}, err
	}
	if disabled != 0 {
		return User{}, ErrForbidden
	}
	if !checkPassword(password, hash) {
		return User{}, ErrInvalidCredentials
	}
	user.IsAdmin = isAdmin != 0
	user.Disabled = false
	return user, nil
}

func (s *Store) CreateSession(ctx context.Context, userID int64, ttl time.Duration) (string, error) {
	token, err := randomToken(32)
	if err != nil {
		return "", err
	}
	expires := time.Now().Add(ttl).UTC().Format(time.RFC3339)
	_, err = s.db.ExecContext(ctx, `
INSERT INTO auth_sessions(token, user_id, expires_at)
VALUES (?, ?, ?)`, token, userID, expires)
	return token, err
}

func (s *Store) UserByToken(ctx context.Context, token string) (User, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return User{}, sql.ErrNoRows
	}
	row := s.db.QueryRowContext(ctx, `
SELECT u.id, u.username, u.display_name, u.is_admin, u.disabled, u.created_at, s.last_seen_at
FROM auth_sessions s
JOIN users u ON u.id = s.user_id
WHERE s.token = ? AND s.expires_at > ? AND u.disabled = 0`, token, time.Now().UTC().Format(time.RFC3339))
	var user User
	var isAdmin, disabled int
	var lastSeen string
	if err := row.Scan(&user.ID, &user.Username, &user.DisplayName, &isAdmin, &disabled, &user.CreatedAt, &lastSeen); err != nil {
		return User{}, err
	}
	user.IsAdmin = isAdmin != 0
	user.Disabled = disabled != 0
	if shouldTouchSession(lastSeen, time.Now().UTC()) {
		_, _ = s.db.ExecContext(ctx, `UPDATE auth_sessions SET last_seen_at = CURRENT_TIMESTAMP WHERE token = ?`, token)
	}
	return user, nil
}

func shouldTouchSession(lastSeen string, now time.Time) bool {
	seenAt, err := parseSessionTime(lastSeen)
	if err != nil {
		return true
	}
	return now.Sub(seenAt) >= sessionTouchInterval
}

func parseSessionTime(value string) (time.Time, error) {
	value = strings.TrimSpace(value)
	for _, layout := range []string{time.RFC3339, "2006-01-02 15:04:05"} {
		t, err := time.Parse(layout, value)
		if err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid session timestamp")
}

func (s *Store) DeleteSession(ctx context.Context, token string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM auth_sessions WHERE token = ?`, strings.TrimSpace(token))
	return err
}

func scanUser(row interface{ Scan(dest ...any) error }) (User, error) {
	var user User
	var isAdmin, disabled int
	err := row.Scan(&user.ID, &user.Username, &user.DisplayName, &isAdmin, &disabled, &user.CreatedAt)
	user.IsAdmin = isAdmin != 0
	user.Disabled = disabled != 0
	return user, err
}

func hashPassword(password string) (string, error) {
	salt := make([]byte, saltBytes)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := pbkdf2SHA256([]byte(password), salt, hashIterations, keyBytes)
	return fmt.Sprintf("pbkdf2_sha256$%d$%s$%s",
		hashIterations,
		base64.RawURLEncoding.EncodeToString(salt),
		base64.RawURLEncoding.EncodeToString(key)), nil
}

func checkPassword(password, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2_sha256" {
		return false
	}
	iterations, err := strconv.Atoi(parts[1])
	if err != nil || iterations <= 0 {
		return false
	}
	salt, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return false
	}
	want, err := base64.RawURLEncoding.DecodeString(parts[3])
	if err != nil {
		return false
	}
	got := pbkdf2SHA256([]byte(password), salt, iterations, len(want))
	return subtle.ConstantTimeCompare(got, want) == 1
}

func pbkdf2SHA256(password, salt []byte, iterations, keyLen int) []byte {
	hashLen := sha256.Size
	blocks := (keyLen + hashLen - 1) / hashLen
	out := make([]byte, 0, blocks*hashLen)
	var intBlock [4]byte
	for block := 1; block <= blocks; block++ {
		binary.BigEndian.PutUint32(intBlock[:], uint32(block))
		mac := hmac.New(sha256.New, password)
		mac.Write(salt)
		mac.Write(intBlock[:])
		u := mac.Sum(nil)
		t := append([]byte(nil), u...)
		for i := 1; i < iterations; i++ {
			mac = hmac.New(sha256.New, password)
			mac.Write(u)
			u = mac.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}

func randomToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func normalizeUsername(v string) string {
	return strings.ToLower(strings.TrimSpace(v))
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
