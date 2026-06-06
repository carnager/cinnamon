package server

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/pelletier/go-toml/v2"

	"popcorn/internal/config"
)

type appUpdateRecord struct {
	App          string `json:"app"`
	APKPath      string `json:"-"`
	VersionCode  int    `json:"versionCode"`
	VersionName  string `json:"versionName"`
	ReleaseNotes string `json:"notes"`
	Source       string `json:"source"`
}

func (a *App) tvAppUpdate(w http.ResponseWriter, r *http.Request) {
	a.appUpdate(w, r, "tv")
}

func (a *App) companionAppUpdate(w http.ResponseWriter, r *http.Request) {
	a.appUpdate(w, r, "companion")
}

func (a *App) tvAppAPK(w http.ResponseWriter, r *http.Request) {
	a.appAPK(w, r, "tv", "popcorn-tv.apk")
}

func (a *App) companionAppAPK(w http.ResponseWriter, r *http.Request) {
	a.appAPK(w, r, "companion", "popcorn-companion.apk")
}

func (a *App) appUpdates(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	tv := a.appUpdatePayload(r, "tv")
	phone := a.appUpdatePayload(r, "companion")
	writeJSON(w, http.StatusOK, map[string]any{"tv": tv, "companion": phone})
}

func (a *App) uploadTVApp(w http.ResponseWriter, r *http.Request) {
	a.uploadApp(w, r, "tv")
}

func (a *App) uploadCompanionApp(w http.ResponseWriter, r *http.Request) {
	a.uploadApp(w, r, "companion")
}

func (a *App) appUpdate(w http.ResponseWriter, r *http.Request, app string) {
	if _, ok := a.requireUser(w, r); !ok {
		return
	}
	writeJSON(w, http.StatusOK, a.appUpdatePayload(r, app))
}

func (a *App) appUpdatePayload(r *http.Request, app string) map[string]any {
	record, ok, err := a.effectiveAppUpdate(r.Context(), app)
	if err != nil {
		a.log.Warn("app update lookup failed", "app", app, "error", err)
		return map[string]any{"app": app, "available": false, "configured": false, "error": err.Error()}
	}
	if !ok {
		return map[string]any{"app": app, "available": false, "configured": false}
	}
	sha, size, err := fileSHA256(record.APKPath)
	if err != nil {
		a.log.Warn("app update apk unavailable", "app", app, "path", record.APKPath, "error", err)
		return map[string]any{
			"app":         app,
			"available":   false,
			"configured":  true,
			"error":       "configured APK is not readable",
			"versionCode": record.VersionCode,
			"versionName": record.VersionName,
			"notes":       record.ReleaseNotes,
			"source":      record.Source,
		}
	}
	current, _ := strconv.Atoi(r.URL.Query().Get("versionCode"))
	return map[string]any{
		"app":         app,
		"available":   current <= 0 || record.VersionCode > current,
		"configured":  true,
		"versionCode": record.VersionCode,
		"versionName": record.VersionName,
		"notes":       record.ReleaseNotes,
		"apkUrl":      fmt.Sprintf("/api/app/%s/apk", app),
		"sha256":      sha,
		"sizeBytes":   size,
		"source":      record.Source,
	}
}

func (a *App) appAPK(w http.ResponseWriter, r *http.Request, app, filename string) {
	if _, ok := a.requireUser(w, r); !ok {
		return
	}
	record, ok, err := a.effectiveAppUpdate(r.Context(), app)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if !ok {
		http.Error(w, "app updater is not configured", http.StatusNotFound)
		return
	}
	if _, err := os.Stat(record.APKPath); err != nil {
		http.Error(w, "configured APK is not readable", http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, filename))
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, record.APKPath)
}

func (a *App) uploadApp(w http.ResponseWriter, r *http.Request, app string) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 256*1024*1024)
	if err := r.ParseMultipartForm(256 * 1024 * 1024); err != nil {
		http.Error(w, "invalid upload: "+err.Error(), http.StatusBadRequest)
		return
	}
	versionCodeOverride := strings.TrimSpace(r.FormValue("versionCode"))
	versionNameOverride := strings.TrimSpace(r.FormValue("versionName"))
	file, header, err := r.FormFile("apk")
	if err != nil {
		http.Error(w, "apk file is required", http.StatusBadRequest)
		return
	}
	defer file.Close()
	if header.Size <= 0 {
		http.Error(w, "apk file is empty", http.StatusBadRequest)
		return
	}
	if !strings.EqualFold(filepath.Ext(header.Filename), ".apk") {
		http.Error(w, "uploaded file must be an APK", http.StatusBadRequest)
		return
	}

	dir := a.appUpdateDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	tmp, err := os.CreateTemp(dir, ".upload-*.apk")
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	tmpPath := tmp.Name()
	_, copyErr := io.Copy(tmp, file)
	closeErr := tmp.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(tmpPath)
		if copyErr != nil {
			http.Error(w, copyErr.Error(), http.StatusInternalServerError)
		} else {
			http.Error(w, closeErr.Error(), http.StatusInternalServerError)
		}
		return
	}

	versionCode, versionName, detectErr := apkVersionFromFile(r.Context(), tmpPath)
	if versionCodeOverride != "" {
		override, err := strconv.Atoi(versionCodeOverride)
		if err != nil || override <= 0 {
			_ = os.Remove(tmpPath)
			http.Error(w, "versionCode override must be a positive integer", http.StatusBadRequest)
			return
		}
		versionCode = override
	}
	if versionNameOverride != "" {
		versionName = versionNameOverride
	}
	if versionCode <= 0 || versionName == "" {
		_ = os.Remove(tmpPath)
		if detectErr != nil {
			http.Error(w, "could not read APK version metadata; install aapt or use the advanced version override: "+detectErr.Error(), http.StatusBadRequest)
		} else {
			http.Error(w, "could not read APK version metadata; use the advanced version override", http.StatusBadRequest)
		}
		return
	}

	dst := filepath.Join(dir, fmt.Sprintf("popcorn-%s-%d.apk", app, versionCode))
	if err := os.Rename(tmpPath, dst); err != nil {
		_ = os.Remove(tmpPath)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	record := appUpdateRecord{
		App:          app,
		APKPath:      dst,
		VersionCode:  versionCode,
		VersionName:  versionName,
		ReleaseNotes: strings.TrimSpace(r.FormValue("notes")),
		Source:       "upload",
	}
	if err := a.saveAppUpdate(r.Context(), record); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	payload := a.appUpdatePayload(r, app)
	if err := a.syncAppUpdateConfig(r.Context()); err != nil {
		a.log.Warn("app update config sync failed", "app", app, "error", err)
		payload["warning"] = "uploaded APK is active, but config.toml was not updated: " + err.Error()
	}
	writeJSON(w, http.StatusOK, payload)
}

func (a *App) effectiveAppUpdate(ctx context.Context, app string) (appUpdateRecord, bool, error) {
	if a.store != nil {
		record, ok, err := a.dbAppUpdate(ctx, app)
		if err != nil {
			return appUpdateRecord{}, false, err
		}
		if ok {
			return record, true, nil
		}
	}
	update := a.cfg.AppUpdate
	switch app {
	case "tv":
		if update.TVAPKPath == "" {
			return appUpdateRecord{}, false, nil
		}
		return appUpdateRecord{App: app, APKPath: update.TVAPKPath, VersionCode: update.TVVersionCode, VersionName: update.TVVersionName, ReleaseNotes: update.TVReleaseNotes, Source: "config"}, true, nil
	case "companion":
		if update.CompanionAPKPath == "" {
			return appUpdateRecord{}, false, nil
		}
		return appUpdateRecord{App: app, APKPath: update.CompanionAPKPath, VersionCode: update.CompanionVersionCode, VersionName: update.CompanionVersionName, ReleaseNotes: update.CompanionReleaseNotes, Source: "config"}, true, nil
	default:
		return appUpdateRecord{}, false, fmt.Errorf("unknown app %q", app)
	}
}

func (a *App) dbAppUpdate(ctx context.Context, app string) (appUpdateRecord, bool, error) {
	var record appUpdateRecord
	err := a.store.DB().QueryRowContext(ctx, `
SELECT app, apk_path, version_code, version_name, release_notes
FROM app_updates
WHERE app = ?`, app).Scan(&record.App, &record.APKPath, &record.VersionCode, &record.VersionName, &record.ReleaseNotes)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return appUpdateRecord{}, false, nil
		}
		return appUpdateRecord{}, false, err
	}
	record.Source = "upload"
	return record, true, nil
}

func (a *App) saveAppUpdate(ctx context.Context, record appUpdateRecord) error {
	_, err := a.store.DB().ExecContext(ctx, `
INSERT INTO app_updates(app, apk_path, version_code, version_name, release_notes, updated_at)
VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
ON CONFLICT(app) DO UPDATE SET
	apk_path=excluded.apk_path,
	version_code=excluded.version_code,
	version_name=excluded.version_name,
	release_notes=excluded.release_notes,
	updated_at=CURRENT_TIMESTAMP`, record.App, record.APKPath, record.VersionCode, record.VersionName, record.ReleaseNotes)
	return err
}

func apkVersionFromFile(ctx context.Context, path string) (int, string, error) {
	aapt, err := exec.LookPath("aapt")
	if err != nil {
		return apkVersionFromManifest(path)
	}
	out, err := exec.CommandContext(ctx, aapt, "dump", "badging", path).CombinedOutput()
	if err != nil {
		if code, name, manifestErr := apkVersionFromManifest(path); manifestErr == nil {
			return code, name, nil
		}
		return 0, "", fmt.Errorf("%w: %s", err, strings.TrimSpace(string(out)))
	}
	return apkVersionFromBadging(string(out))
}

var (
	apkVersionCodeRe = regexp.MustCompile(`\bversionCode='([^']+)'`)
	apkVersionNameRe = regexp.MustCompile(`\bversionName='([^']*)'`)
)

func apkVersionFromBadging(output string) (int, string, error) {
	codeMatch := apkVersionCodeRe.FindStringSubmatch(output)
	nameMatch := apkVersionNameRe.FindStringSubmatch(output)
	if len(codeMatch) < 2 || len(nameMatch) < 2 {
		return 0, "", errors.New("aapt output did not contain versionCode and versionName")
	}
	code, err := strconv.Atoi(codeMatch[1])
	if err != nil || code <= 0 {
		return 0, "", fmt.Errorf("invalid APK versionCode %q", codeMatch[1])
	}
	name := strings.TrimSpace(nameMatch[1])
	if name == "" {
		return 0, "", errors.New("APK versionName is empty")
	}
	return code, name, nil
}

func (a *App) syncAppUpdateConfig(ctx context.Context) error {
	if strings.TrimSpace(a.cfg.ConfigPath) == "" {
		return nil
	}
	update := a.cfg.AppUpdate
	if a.store != nil {
		for _, app := range []string{"tv", "companion"} {
			record, ok, err := a.dbAppUpdate(ctx, app)
			if err != nil {
				return err
			}
			if ok {
				applyAppUpdateRecord(&update, record)
			}
		}
	}
	return writeAppUpdateConfig(a.cfg.ConfigPath, update)
}

func applyAppUpdateRecord(update *config.AppUpdate, record appUpdateRecord) {
	switch record.App {
	case "tv":
		update.TVAPKPath = record.APKPath
		update.TVVersionCode = record.VersionCode
		update.TVVersionName = record.VersionName
		update.TVReleaseNotes = record.ReleaseNotes
	case "companion":
		update.CompanionAPKPath = record.APKPath
		update.CompanionVersionCode = record.VersionCode
		update.CompanionVersionName = record.VersionName
		update.CompanionReleaseNotes = record.ReleaseNotes
	}
}

func writeAppUpdateConfig(path string, update config.AppUpdate) error {
	if strings.ToLower(filepath.Ext(path)) != ".toml" {
		return fmt.Errorf("app update config sync only supports TOML config files")
	}
	existing, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var parsed map[string]any
	if err := toml.Unmarshal(existing, &parsed); err != nil {
		return fmt.Errorf("parse TOML config before update: %w", err)
	}
	section, err := toml.Marshal(struct {
		AppUpdate config.AppUpdate `toml:"appUpdate"`
	}{AppUpdate: update})
	if err != nil {
		return err
	}
	next := replaceTOMLTable(string(existing), "appUpdate", string(section))
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".config-*.toml")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	if _, err := tmp.WriteString(next); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if err := os.Chmod(tmpPath, info.Mode().Perm()); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return nil
}

func replaceTOMLTable(doc, table, replacement string) string {
	replacement = strings.TrimSpace(replacement) + "\n"
	lines := splitLines(doc)
	start := -1
	for i, line := range lines {
		if strings.TrimSpace(line) == "["+table+"]" {
			start = i
			break
		}
	}
	replacementLines := splitLines(replacement)
	if start >= 0 {
		end := len(lines)
		for i := start + 1; i < len(lines); i++ {
			if isTOMLTableHeader(strings.TrimSpace(lines[i])) {
				end = i
				break
			}
		}
		next := append([]string{}, lines[:start]...)
		next = append(next, replacementLines...)
		next = append(next, lines[end:]...)
		return strings.Join(next, "")
	}

	insert := len(lines)
	for i, line := range lines {
		if strings.TrimSpace(line) == "[[libraries]]" {
			insert = i
			break
		}
	}
	next := append([]string{}, lines[:insert]...)
	if len(next) > 0 && strings.TrimSpace(next[len(next)-1]) != "" {
		next = append(next, "\n")
	}
	next = append(next, replacementLines...)
	if insert < len(lines) && strings.TrimSpace(lines[insert]) != "" {
		next = append(next, "\n")
	}
	next = append(next, lines[insert:]...)
	return strings.Join(next, "")
}

func splitLines(s string) []string {
	lines := strings.SplitAfter(s, "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	return lines
}

func isTOMLTableHeader(line string) bool {
	return strings.HasPrefix(line, "[") && strings.Contains(line, "]")
}

func (a *App) appUpdateDir() string {
	base := filepath.Dir(a.cfg.DatabasePath)
	if base == "." || base == "" {
		if abs, err := filepath.Abs("."); err == nil {
			base = abs
		}
	}
	return filepath.Join(base, "updates")
}

func fileSHA256(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()

	h := sha256.New()
	size, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), size, nil
}
