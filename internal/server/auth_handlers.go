package server

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/auth"
)

func (a *App) login(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return
	}
	var in struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	if !a.allowLoginAttempt(w, r, in.Username) {
		return
	}
	user, err := a.auth.Authenticate(r.Context(), in.Username, in.Password)
	if err != nil {
		a.recordLoginFailure(r, in.Username)
		status := http.StatusUnauthorized
		if errors.Is(err, auth.ErrForbidden) {
			status = http.StatusForbidden
		}
		http.Error(w, err.Error(), status)
		return
	}
	token, err := a.auth.CreateSession(r.Context(), user.ID, 30*24*time.Hour)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	a.clearLoginFailures(r, in.Username)
	http.SetCookie(w, &http.Cookie{
		Name:     "popcorn_token",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   secureCookie(r),
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().Add(30 * 24 * time.Hour),
	})
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "user": user})
}

func (a *App) logout(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	token := bearerToken(r)
	if token == "" {
		if cookie, err := r.Cookie("popcorn_token"); err == nil {
			token = cookie.Value
		}
	}
	if token != "" {
		_ = a.auth.DeleteSession(r.Context(), token)
	}
	http.SetCookie(w, &http.Cookie{Name: "popcorn_token", Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: secureCookie(r), SameSite: http.SameSiteLaxMode})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) me(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	if token := bearerToken(r); token != "" {
		http.SetCookie(w, &http.Cookie{
			Name:     "popcorn_token",
			Value:    token,
			Path:     "/",
			HttpOnly: true,
			Secure:   secureCookie(r),
			SameSite: http.SameSiteLaxMode,
			Expires:  time.Now().Add(30 * 24 * time.Hour),
		})
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *App) users(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	users, err := a.auth.Users(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, users)
}

func (a *App) createUser(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	var in struct {
		Username    string `json:"username"`
		DisplayName string `json:"displayName"`
		Password    string `json:"password"`
		IsAdmin     bool   `json:"isAdmin"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	user, err := a.auth.CreateUser(r.Context(), auth.CreateUserInput{
		Username:    in.Username,
		DisplayName: in.DisplayName,
		Password:    in.Password,
		IsAdmin:     in.IsAdmin,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusCreated, user)
}

func (a *App) updateUser(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		http.Error(w, "invalid user id", http.StatusBadRequest)
		return
	}
	var in struct {
		DisplayName *string `json:"displayName"`
		Password    string  `json:"password"`
		IsAdmin     *bool   `json:"isAdmin"`
		Disabled    *bool   `json:"disabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	existing, err := a.auth.User(r.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	wouldRemoveAdmin := existing.IsAdmin && ((in.IsAdmin != nil && !*in.IsAdmin) || (in.Disabled != nil && *in.Disabled))
	if wouldRemoveAdmin {
		count, err := a.auth.EnabledAdminCount(r.Context())
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if count <= 1 {
			http.Error(w, "cannot disable or demote the last enabled admin", http.StatusBadRequest)
			return
		}
	}
	user, err := a.auth.UpdateUser(r.Context(), id, auth.UpdateUserInput{
		DisplayName: in.DisplayName,
		Password:    strings.TrimSpace(in.Password),
		IsAdmin:     in.IsAdmin,
		Disabled:    in.Disabled,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *App) requireUser(w http.ResponseWriter, r *http.Request) (auth.User, bool) {
	if user, ok := r.Context().Value(authUserContextKey{}).(auth.User); ok && user.ID > 0 {
		return user, true
	}
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return auth.User{}, false
	}
	token := bearerToken(r)
	if token == "" {
		if cookie, err := r.Cookie("popcorn_token"); err == nil {
			token = cookie.Value
		}
	}
	user, err := a.auth.UserByToken(r.Context(), token)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return auth.User{}, false
	}
	return user, true
}

func (a *App) requireAdmin(w http.ResponseWriter, r *http.Request) (auth.User, bool) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return auth.User{}, false
	}
	if !user.IsAdmin {
		http.Error(w, "forbidden", http.StatusForbidden)
		return auth.User{}, false
	}
	return user, true
}

func bearerToken(r *http.Request) string {
	authHeader := r.Header.Get("Authorization")
	if len(authHeader) > 7 && strings.EqualFold(authHeader[:7], "Bearer ") {
		return strings.TrimSpace(authHeader[7:])
	}
	if token := strings.TrimSpace(r.URL.Query().Get("api_key")); token != "" {
		return token
	}
	if token := queryTokenCaseInsensitive(r, "api_key"); token != "" {
		return token
	}
	return ""
}

func queryTokenCaseInsensitive(r *http.Request, key string) string {
	normalizedKey := normalizeTokenKey(key)
	for name, values := range r.URL.Query() {
		if normalizeTokenKey(name) != normalizedKey || len(values) == 0 {
			continue
		}
		return strings.TrimSpace(values[0])
	}
	return ""
}

func normalizeTokenKey(key string) string {
	key = strings.ToLower(strings.TrimSpace(key))
	key = strings.ReplaceAll(key, "_", "")
	key = strings.ReplaceAll(key, "-", "")
	return key
}

func secureCookie(r *http.Request) bool {
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

const (
	maxLoginFailures = 8
	loginFailWindow  = 5 * time.Minute
	loginBlockWindow = 5 * time.Minute
)

func (a *App) allowLoginAttempt(w http.ResponseWriter, r *http.Request, username string) bool {
	key := loginAttemptKey(r, username)
	now := time.Now()
	a.loginMu.Lock()
	defer a.loginMu.Unlock()
	if a.loginFails == nil {
		a.loginFails = map[string]loginAttempt{}
	}
	attempt := a.loginFails[key]
	if attempt.BlockedUntil.After(now) {
		w.Header().Set("Retry-After", strconv.Itoa(int(time.Until(attempt.BlockedUntil).Seconds())))
		http.Error(w, "too many login attempts", http.StatusTooManyRequests)
		return false
	}
	if !attempt.FirstFailure.IsZero() && now.Sub(attempt.FirstFailure) > loginFailWindow {
		delete(a.loginFails, key)
	}
	return true
}

func (a *App) recordLoginFailure(r *http.Request, username string) {
	key := loginAttemptKey(r, username)
	now := time.Now()
	a.loginMu.Lock()
	defer a.loginMu.Unlock()
	if a.loginFails == nil {
		a.loginFails = map[string]loginAttempt{}
	}
	attempt := a.loginFails[key]
	if attempt.FirstFailure.IsZero() || now.Sub(attempt.FirstFailure) > loginFailWindow {
		attempt = loginAttempt{FirstFailure: now}
	}
	attempt.Failures++
	if attempt.Failures >= maxLoginFailures {
		attempt.BlockedUntil = now.Add(loginBlockWindow)
	}
	a.loginFails[key] = attempt
}

func (a *App) clearLoginFailures(r *http.Request, username string) {
	key := loginAttemptKey(r, username)
	a.loginMu.Lock()
	defer a.loginMu.Unlock()
	delete(a.loginFails, key)
}

func loginAttemptKey(r *http.Request, username string) string {
	return clientIP(r) + "\x00" + strings.ToLower(strings.TrimSpace(username))
}

func clientIP(r *http.Request) string {
	if forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); forwarded != "" {
		if i := strings.IndexByte(forwarded, ','); i >= 0 {
			forwarded = strings.TrimSpace(forwarded[:i])
		}
		if net.ParseIP(forwarded) != nil {
			return forwarded
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil && host != "" {
		return host
	}
	return r.RemoteAddr
}
