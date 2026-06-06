package server

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

func (a *App) authQRStart(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return
	}
	var in struct {
		DeviceName string `json:"deviceName"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	deviceName := strings.TrimSpace(in.DeviceName)
	if deviceName == "" {
		deviceName = "Popcorn TV"
	}
	if len(deviceName) > 80 {
		deviceName = deviceName[:80]
	}
	code := randomHex(24)
	expires := time.Now().Add(10 * time.Minute).UTC().Format(time.RFC3339)
	_, _ = a.store.DB().ExecContext(r.Context(), `DELETE FROM auth_qr_codes WHERE expires_at <= ?`, time.Now().UTC().Format(time.RFC3339))
	_, err := a.store.DB().ExecContext(r.Context(), `
INSERT INTO auth_qr_codes(code, device_name, expires_at)
VALUES (?, ?, ?)`, code, deviceName, expires)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"code":       code,
		"deviceName": deviceName,
		"expiresAt":  expires,
	})
}

func (a *App) authQRPoll(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return
	}
	code := strings.TrimSpace(r.URL.Query().Get("code"))
	if code == "" {
		http.Error(w, "code is required", http.StatusBadRequest)
		return
	}
	var token sql.NullString
	var userID sql.NullInt64
	var expiresAt string
	err := a.store.DB().QueryRowContext(r.Context(), `
SELECT token, user_id, expires_at
FROM auth_qr_codes
WHERE code = ?`, code).Scan(&token, &userID, &expiresAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "invalid qr code", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if expiresAt <= time.Now().UTC().Format(time.RFC3339) {
		http.Error(w, "qr code expired", http.StatusGone)
		return
	}
	if !token.Valid || token.String == "" || !userID.Valid {
		writeJSON(w, http.StatusOK, map[string]any{"status": "pending"})
		return
	}
	user, err := a.auth.User(r.Context(), userID.Int64)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "approved", "token": token.String, "user": user})
}

func (a *App) authQRComplete(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return
	}
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var in struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	code := strings.TrimSpace(in.Code)
	if code == "" {
		http.Error(w, "code is required", http.StatusBadRequest)
		return
	}
	var expiresAt string
	var completedAt sql.NullString
	err := a.store.DB().QueryRowContext(r.Context(), `SELECT expires_at, completed_at FROM auth_qr_codes WHERE code = ?`, code).Scan(&expiresAt, &completedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "invalid qr code", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if expiresAt <= time.Now().UTC().Format(time.RFC3339) {
		http.Error(w, "qr code expired", http.StatusGone)
		return
	}
	if completedAt.Valid && completedAt.String != "" {
		http.Error(w, "qr code already completed", http.StatusConflict)
		return
	}
	token, err := a.auth.CreateSession(r.Context(), user.ID, 30*24*time.Hour)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	res, err := a.store.DB().ExecContext(r.Context(), `
UPDATE auth_qr_codes
SET user_id = ?, token = ?, completed_at = CURRENT_TIMESTAMP
WHERE code = ? AND completed_at IS NULL`, user.ID, token, code)
	if err != nil {
		_ = a.auth.DeleteSession(r.Context(), token)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if rows, _ := res.RowsAffected(); rows == 0 {
		_ = a.auth.DeleteSession(r.Context(), token)
		http.Error(w, "qr code already completed", http.StatusConflict)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "approved"})
}
