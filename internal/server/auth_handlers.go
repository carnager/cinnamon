package server

import (
	"encoding/json"
	"errors"
	"net/http"
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
	user, err := a.auth.Authenticate(r.Context(), in.Username, in.Password)
	if err != nil {
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
	http.SetCookie(w, &http.Cookie{
		Name:     "popcorn_token",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
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
	http.SetCookie(w, &http.Cookie{Name: "popcorn_token", Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) me(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
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

func (a *App) requireUser(w http.ResponseWriter, r *http.Request) (auth.User, bool) {
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
	return ""
}
