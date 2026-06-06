package server

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type remoteDevice struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	LastSeenAt string `json:"lastSeenAt,omitempty"`
}

type remoteCommand struct {
	ID        int64           `json:"id"`
	Type      string          `json:"type"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt string          `json:"createdAt,omitempty"`
}

func (a *App) remoteRegisterDevice(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var in struct {
		ID   string `json:"id"`
		Name string `json:"name"`
		Kind string `json:"kind"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	id := cleanRemoteID(in.ID)
	if id == "" {
		id = "dev_" + randomHex(12)
	}
	name := cleanRemoteName(in.Name, "Popcorn TV")
	kind := cleanRemoteKind(in.Kind)
	if kind == "" {
		kind = "tv"
	}
	previousUserID, hadPreviousOwner := a.remoteDeviceOwner(r.Context(), id)
	_, err := a.store.DB().ExecContext(r.Context(), `
INSERT INTO remote_devices(id, user_id, name, kind, last_seen_at)
VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(id) DO UPDATE SET
	user_id=excluded.user_id,
	name=excluded.name,
	kind=excluded.kind,
	last_seen_at=CURRENT_TIMESTAMP`, id, user.ID, name, kind)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if hadPreviousOwner && previousUserID != user.ID {
		a.clearRemoteDeviceRuntimeState(r.Context(), id)
	}
	writeJSON(w, http.StatusOK, remoteDevice{ID: id, Name: name, Kind: kind})
}

func (a *App) remoteListDevices(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	rows, err := a.store.DB().QueryContext(r.Context(), `
SELECT id, name, kind, last_seen_at
FROM remote_devices
WHERE user_id = ?
ORDER BY last_seen_at DESC`, user.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer rows.Close()
	out := []remoteDevice{}
	seen := map[string]bool{}
	for rows.Next() {
		var device remoteDevice
		if err := rows.Scan(&device.ID, &device.Name, &device.Kind, &device.LastSeenAt); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		device.Name = cleanRemoteName(device.Name, "Popcorn TV")
		device.Kind = cleanRemoteKind(device.Kind)
		key := remoteDeviceKey(device.Kind, device.Name)
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, device)
	}
	writeJSON(w, http.StatusOK, out)
}

func (a *App) remotePairingCode(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	deviceID := cleanRemoteID(r.PathValue("id"))
	if !a.userOwnsRemoteDevice(r.Context(), user.ID, deviceID) {
		http.NotFound(w, r)
		return
	}
	code := fmt.Sprintf("%06d", randomNumber(1000000))
	expires := time.Now().Add(10 * time.Minute).UTC().Format(time.RFC3339)
	_, err := a.store.DB().ExecContext(r.Context(), `
INSERT INTO remote_pairing_codes(code, device_id, expires_at)
VALUES (?, ?, ?)
ON CONFLICT(code) DO UPDATE SET device_id=excluded.device_id, expires_at=excluded.expires_at, created_at=CURRENT_TIMESTAMP`,
		code, deviceID, expires)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": code, "expiresAt": expires})
}

func (a *App) remotePairDevice(w http.ResponseWriter, r *http.Request) {
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
	var device remoteDevice
	err := a.store.DB().QueryRowContext(r.Context(), `
SELECT d.id, d.name, d.kind, d.last_seen_at
FROM remote_pairing_codes p
JOIN remote_devices d ON d.id = p.device_id
WHERE p.code = ? AND p.expires_at > ?`, code, time.Now().UTC().Format(time.RFC3339)).
		Scan(&device.ID, &device.Name, &device.Kind, &device.LastSeenAt)
	if err != nil {
		http.Error(w, "invalid or expired pairing code", http.StatusNotFound)
		return
	}
	device.Name = cleanRemoteName(device.Name, "Popcorn TV")
	device.Kind = cleanRemoteKind(device.Kind)
	previousUserID, hadPreviousOwner := a.remoteDeviceOwner(r.Context(), device.ID)
	_, err = a.store.DB().ExecContext(r.Context(), `UPDATE remote_devices SET user_id = ?, last_seen_at = CURRENT_TIMESTAMP WHERE id = ?`, user.ID, device.ID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if hadPreviousOwner && previousUserID != user.ID {
		a.clearRemoteDeviceRuntimeState(r.Context(), device.ID)
	}
	_, _ = a.store.DB().ExecContext(r.Context(), `DELETE FROM remote_pairing_codes WHERE code = ?`, code)
	writeJSON(w, http.StatusOK, device)
}

func (a *App) remotePostCommand(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	deviceID := cleanRemoteID(r.PathValue("id"))
	if !a.userOwnsRemoteDevice(r.Context(), user.ID, deviceID) {
		http.NotFound(w, r)
		return
	}
	var in struct {
		Type    string          `json:"type"`
		Payload json.RawMessage `json:"payload"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	typ := strings.TrimSpace(in.Type)
	if typ == "" {
		http.Error(w, "command type is required", http.StatusBadRequest)
		return
	}
	payload := strings.TrimSpace(string(in.Payload))
	if payload == "" || payload == "null" {
		payload = "{}"
	}
	res, err := a.store.DB().ExecContext(r.Context(), `
INSERT INTO remote_commands(device_id, user_id, type, payload)
VALUES (?, ?, ?, ?)`, deviceID, user.ID, typ, payload)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	id, _ := res.LastInsertId()
	writeJSON(w, http.StatusAccepted, map[string]any{"id": id})
}

func (a *App) remoteGetCommands(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	deviceID := cleanRemoteID(r.PathValue("id"))
	if !a.userOwnsRemoteDevice(r.Context(), user.ID, deviceID) {
		http.NotFound(w, r)
		return
	}
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	rows, err := a.store.DB().QueryContext(r.Context(), `
SELECT id, type, payload, created_at
FROM remote_commands
WHERE device_id = ? AND id > ?
ORDER BY id
LIMIT 50`, deviceID, after)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer rows.Close()
	out := []remoteCommand{}
	for rows.Next() {
		var cmd remoteCommand
		var payload string
		if err := rows.Scan(&cmd.ID, &cmd.Type, &payload, &cmd.CreatedAt); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		cmd.Payload = json.RawMessage(payload)
		out = append(out, cmd)
	}
	_, _ = a.store.DB().ExecContext(r.Context(), `UPDATE remote_devices SET last_seen_at = CURRENT_TIMESTAMP WHERE id = ?`, deviceID)
	writeJSON(w, http.StatusOK, out)
}

func (a *App) remotePutState(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	deviceID := cleanRemoteID(r.PathValue("id"))
	if !a.userOwnsRemoteDevice(r.Context(), user.ID, deviceID) {
		http.NotFound(w, r)
		return
	}
	var in struct {
		ItemID     int64  `json:"itemId"`
		Title      string `json:"title"`
		State      string `json:"state"`
		PositionMS int64  `json:"positionMs"`
		DurationMS int64  `json:"durationMs"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	payload, _ := json.Marshal(in)
	_, err := a.store.DB().ExecContext(r.Context(), `
INSERT INTO remote_device_state(device_id, item_id, title, state, position_ms, duration_ms, updated_at, payload)
VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
ON CONFLICT(device_id) DO UPDATE SET
	item_id=excluded.item_id,
	title=excluded.title,
	state=excluded.state,
	position_ms=excluded.position_ms,
	duration_ms=excluded.duration_ms,
	updated_at=CURRENT_TIMESTAMP,
	payload=excluded.payload`,
		deviceID, nullableInt64(in.ItemID), strings.TrimSpace(in.Title), strings.TrimSpace(in.State), in.PositionMS, in.DurationMS, string(payload))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	_, _ = a.store.DB().ExecContext(r.Context(), `UPDATE remote_devices SET last_seen_at = CURRENT_TIMESTAMP WHERE id = ?`, deviceID)
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) remoteGetState(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	deviceID := cleanRemoteID(r.PathValue("id"))
	if !a.userOwnsRemoteDevice(r.Context(), user.ID, deviceID) {
		http.NotFound(w, r)
		return
	}
	var payload string
	var updatedAt string
	err := a.store.DB().QueryRowContext(r.Context(), `SELECT payload, updated_at FROM remote_device_state WHERE device_id = ?`, deviceID).Scan(&payload, &updatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeJSON(w, http.StatusOK, map[string]any{"deviceId": deviceID, "state": "idle"})
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	var out map[string]any
	if json.Unmarshal([]byte(payload), &out) != nil {
		out = map[string]any{}
	}
	out["deviceId"] = deviceID
	out["updatedAt"] = updatedAt
	writeJSON(w, http.StatusOK, out)
}

func (a *App) userOwnsRemoteDevice(ctx context.Context, userID int64, deviceID string) bool {
	if deviceID == "" {
		return false
	}
	var n int
	err := a.store.DB().QueryRowContext(ctx, `SELECT COUNT(*) FROM remote_devices WHERE id = ? AND user_id = ?`, deviceID, userID).Scan(&n)
	return err == nil && n > 0
}

func (a *App) remoteDeviceOwner(ctx context.Context, deviceID string) (int64, bool) {
	if deviceID == "" {
		return 0, false
	}
	var userID int64
	err := a.store.DB().QueryRowContext(ctx, `SELECT user_id FROM remote_devices WHERE id = ?`, deviceID).Scan(&userID)
	return userID, err == nil
}

func (a *App) clearRemoteDeviceRuntimeState(ctx context.Context, deviceID string) {
	if deviceID == "" {
		return
	}
	_, _ = a.store.DB().ExecContext(ctx, `DELETE FROM remote_commands WHERE device_id = ?`, deviceID)
	_, _ = a.store.DB().ExecContext(ctx, `DELETE FROM remote_device_state WHERE device_id = ?`, deviceID)
	_, _ = a.store.DB().ExecContext(ctx, `DELETE FROM remote_pairing_codes WHERE device_id = ?`, deviceID)
}

func cleanRemoteID(v string) string {
	v = strings.TrimSpace(v)
	if len(v) > 80 {
		v = v[:80]
	}
	var b strings.Builder
	for _, r := range v {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func cleanRemoteName(v, fallback string) string {
	parts := strings.Fields(strings.TrimSpace(v))
	if len(parts) == 0 {
		return fallback
	}
	for {
		repeated := false
		for n := len(parts) / 2; n >= 1; n-- {
			if equalFoldSlice(parts[:n], parts[n:2*n]) {
				parts = append(parts[:n], parts[2*n:]...)
				repeated = true
				break
			}
		}
		if !repeated {
			break
		}
	}
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if len(out) > 0 && strings.EqualFold(out[len(out)-1], part) {
			continue
		}
		out = append(out, part)
	}
	name := strings.Join(out, " ")
	if name == "" {
		return fallback
	}
	return name
}

func equalFoldSlice(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if !strings.EqualFold(a[i], b[i]) {
			return false
		}
	}
	return true
}

func cleanRemoteKind(v string) string {
	return strings.ToLower(strings.TrimSpace(v))
}

func remoteDeviceKey(kind, name string) string {
	kind = cleanRemoteKind(kind)
	if kind == "" {
		kind = "device"
	}
	name = strings.ToLower(cleanRemoteName(name, ""))
	return kind + "\x00" + name
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(b)
}

func randomNumber(max int) int {
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		return int(time.Now().UnixNano() % int64(max))
	}
	n := int(b[0])<<24 | int(b[1])<<16 | int(b[2])<<8 | int(b[3])
	if n < 0 {
		n = -n
	}
	return n % max
}

func nullableInt64(v int64) any {
	if v == 0 {
		return nil
	}
	return v
}
