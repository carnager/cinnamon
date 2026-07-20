package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoadRejectsLibraryWithEmptyPath(t *testing.T) {
	configPath := writeTOMLConfig(t, `
[[libraries]]
id = "movies"
name = "Movies"
path = "   "
type = "movies"
`)

	if _, err := Load(configPath); err == nil {
		t.Fatalf("Load accepted a library with an empty path")
	}
}

func TestLoadMergesFileDefaultsAndEnvironment(t *testing.T) {
	dataDir := t.TempDir()
	dbPath := filepath.Join(t.TempDir(), "override.db")
	t.Setenv("XDG_DATA_HOME", dataDir)
	t.Setenv("POPCORN_DATABASE", dbPath)
	t.Setenv("POPCORN_TMDB_READ_ACCESS_TOKEN", "read-token")
	t.Setenv("POPCORN_AUTO_SCAN_INTERVAL", "30m")
	t.Setenv("POPCORN_AUTO_SCAN_WATCH_DEPTH", "1")

	configPath := writeTOMLConfig(t, `
listen = ":9999"
logLevel = "debug"
scanOnStart = false
autoScan = false
autoScanDebounce = "5s"
scanTimeout = "12m"
reconcileInterval = "45m"

[[libraries]]
id = " movies "
name = " Movies "
path = " /media/movies/../Movies "
type = ""
`)

	cfg, err := Load(configPath)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Listen != ":9999" || cfg.LogLevel != "debug" {
		t.Fatalf("basic config = %#v, want listen :9999 and debug log level", cfg)
	}
	if cfg.DatabasePath != dbPath {
		t.Fatalf("database path = %q, want env override %q", cfg.DatabasePath, dbPath)
	}
	if cfg.TMDbReadToken != "read-token" {
		t.Fatalf("tmdb read token = %q, want env override", cfg.TMDbReadToken)
	}
	if cfg.ScanOnStart {
		t.Fatalf("scanOnStart = true, want false from config")
	}
	if cfg.ScanTimeout != 12*time.Minute {
		t.Fatalf("scan timeout = %s, want 12m", cfg.ScanTimeout)
	}
	if cfg.ReconcileInterval != 45*time.Minute {
		t.Fatalf("reconcile interval = %s, want 45m from config", cfg.ReconcileInterval)
	}
	if cfg.AutoScan {
		t.Fatalf("autoScan = true, want false from config")
	}
	if cfg.AutoScanDebounce != 5*time.Second {
		t.Fatalf("auto scan debounce = %s, want 5s", cfg.AutoScanDebounce)
	}
	if cfg.AutoScanInterval != 30*time.Minute {
		t.Fatalf("auto scan interval = %s, want env override 30m", cfg.AutoScanInterval)
	}
	if cfg.AutoScanWatchDepth != 1 {
		t.Fatalf("auto scan watch depth = %d, want env override 1", cfg.AutoScanWatchDepth)
	}
	if len(cfg.Libraries) != 1 {
		t.Fatalf("libraries = %#v, want one library", cfg.Libraries)
	}
	lib := cfg.Libraries[0]
	if lib.ID != "movies" || lib.Name != "Movies" || lib.Path != "/media/Movies" || lib.Type != "movies" {
		t.Fatalf("normalized library = %#v, want trimmed movies library", lib)
	}
}

func TestLoadSupportsJSONConfigFallback(t *testing.T) {
	configPath := writeJSONConfig(t, `{
		"listen": ":9999",
		"logLevel": "warn",
		"libraries": [
			{"id": "movies", "name": "Movies", "path": "/media/movies", "type": "movies"}
		]
	}`)

	cfg, err := Load(configPath)
	if err != nil {
		t.Fatalf("Load JSON: %v", err)
	}
	if cfg.Listen != ":9999" || cfg.LogLevel != "warn" {
		t.Fatalf("json config = %#v, want listen :9999 and warn log level", cfg)
	}
}

func TestLoadSupportsTVUpdaterConfigAndEnvironment(t *testing.T) {
	t.Setenv("POPCORN_TV_VERSION_NAME", "0.3.0")
	t.Setenv("POPCORN_TV_RELEASE_NOTES", "env notes")
	t.Setenv("POPCORN_COMPANION_VERSION_NAME", "0.3.0-phone")

	configPath := writeTOMLConfig(t, `
[appUpdate]
tvApkPath = " ./dist/popcorn-tv.apk "
tvVersionCode = 26060305
tvVersionName = "0.2.0"
companionApkPath = " ./dist/popcorn-companion.apk "
companionVersionCode = 26060306
companionVersionName = "0.2.0-phone"

[[libraries]]
id = "movies"
name = "Movies"
path = "/media/movies"
type = "movies"
`)

	cfg, err := Load(configPath)
	if err != nil {
		t.Fatalf("Load updater config: %v", err)
	}
	if cfg.AppUpdate.TVAPKPath != "dist/popcorn-tv.apk" {
		t.Fatalf("tv apk path = %q, want cleaned relative path", cfg.AppUpdate.TVAPKPath)
	}
	if cfg.AppUpdate.TVVersionCode != 26060305 {
		t.Fatalf("tv version code = %d, want 26060305", cfg.AppUpdate.TVVersionCode)
	}
	if cfg.AppUpdate.TVVersionName != "0.3.0" {
		t.Fatalf("tv version name = %q, want env override", cfg.AppUpdate.TVVersionName)
	}
	if cfg.AppUpdate.TVReleaseNotes != "env notes" {
		t.Fatalf("tv release notes = %q, want env override", cfg.AppUpdate.TVReleaseNotes)
	}
	if cfg.AppUpdate.CompanionAPKPath != "dist/popcorn-companion.apk" {
		t.Fatalf("companion apk path = %q, want cleaned relative path", cfg.AppUpdate.CompanionAPKPath)
	}
	if cfg.AppUpdate.CompanionVersionCode != 26060306 {
		t.Fatalf("companion version code = %d, want 26060306", cfg.AppUpdate.CompanionVersionCode)
	}
	if cfg.AppUpdate.CompanionVersionName != "0.3.0-phone" {
		t.Fatalf("companion version name = %q, want env override", cfg.AppUpdate.CompanionVersionName)
	}
}

func TestLoadRejectsIncompleteTVUpdaterConfig(t *testing.T) {
	configPath := writeTOMLConfig(t, `
[appUpdate]
tvApkPath = "/tmp/popcorn.apk"

[[libraries]]
id = "movies"
name = "Movies"
path = "/media/movies"
type = "movies"
`)

	if _, err := Load(configPath); err == nil {
		t.Fatalf("Load accepted updater config without version fields")
	}
}

func TestLoadSupportsSingleLibraryEnvironmentOverride(t *testing.T) {
	t.Setenv("POPCORN_LIBRARY", "/media/tv")
	t.Setenv("POPCORN_LIBRARY_TYPE", "tv")

	cfg, err := Load("")
	if err != nil {
		t.Fatalf("Load from env: %v", err)
	}
	if len(cfg.Libraries) != 1 {
		t.Fatalf("libraries = %#v, want one env library", cfg.Libraries)
	}
	lib := cfg.Libraries[0]
	if lib.ID != "default" || lib.Name != "Media" || lib.Path != "/media/tv" || lib.Type != "tv" {
		t.Fatalf("env library = %#v, want default tv library", lib)
	}
}

func writeTOMLConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.toml")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	return path
}

func writeJSONConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	return path
}

func TestReconcileIntervalDefaultsOn(t *testing.T) {
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	cfg, err := Load(writeTOMLConfig(t, `
[[libraries]]
id = "movies"
name = "Movies"
path = "/media/movies"
type = "movies"
`))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ReconcileInterval != time.Hour {
		t.Fatalf("default reconcile interval = %s, want 1h", cfg.ReconcileInterval)
	}
}
