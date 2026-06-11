package server

import (
	"encoding/json"
	"net/http"
	"strings"
)

func (a *App) clientLog(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var in struct {
		DeviceID    string         `json:"deviceId"`
		DeviceName  string         `json:"deviceName"`
		Event       string         `json:"event"`
		Screen      string         `json:"screen"`
		ItemID      int64          `json:"itemId"`
		Title       string         `json:"title"`
		PlanMode    string         `json:"planMode"`
		UsesHLS     bool           `json:"usesHls"`
		UsesMPV     bool           `json:"usesMpv"`
		HLSID       string         `json:"hlsId"`
		PositionMS  int64          `json:"positionMs"`
		DurationMS  int64          `json:"durationMs"`
		KeyCode     int            `json:"keyCode"`
		KeyAction   int            `json:"keyAction"`
		PlayerState string         `json:"playerState"`
		Message     string         `json:"message"`
		Extra       map[string]any `json:"extra"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	event := cleanClientLogValue(in.Event, 80)
	if event == "" {
		event = "event"
	}
	a.log.Info("client log",
		"user", user.ID,
		"username", user.Username,
		"device", cleanClientLogValue(in.DeviceID, 80),
		"deviceName", cleanClientLogValue(in.DeviceName, 80),
		"event", event,
		"screen", cleanClientLogValue(in.Screen, 40),
		"item", in.ItemID,
		"title", cleanClientLogValue(in.Title, 120),
		"planMode", cleanClientLogValue(in.PlanMode, 40),
		"usesHls", in.UsesHLS,
		"usesMpv", in.UsesMPV,
		"hls", cleanClientLogValue(in.HLSID, 120),
		"position", in.PositionMS,
		"duration", in.DurationMS,
		"keyCode", in.KeyCode,
		"keyAction", in.KeyAction,
		"playerState", cleanClientLogValue(in.PlayerState, 60),
		"message", cleanClientLogValue(in.Message, 240),
		"extra", cleanClientLogExtra(in.Extra),
	)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func cleanClientLogValue(value string, limit int) string {
	value = strings.TrimSpace(value)
	if limit > 0 && len(value) > limit {
		value = value[:limit]
	}
	return value
}

func cleanClientLogExtra(extra map[string]any) map[string]any {
	if len(extra) == 0 {
		return nil
	}
	out := map[string]any{}
	for key, value := range extra {
		key = cleanClientLogValue(key, 60)
		if key == "" {
			continue
		}
		switch v := value.(type) {
		case string:
			out[key] = cleanClientLogValue(v, 160)
		case float64, bool:
			out[key] = v
		}
		if len(out) >= 12 {
			break
		}
	}
	return out
}
