package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoadRejectsLibraryWithEmptyPath(t *testing.T) {
	configPath := writeConfig(t, `{
		"libraries": [
			{"id": "movies", "name": "Movies", "path": "   ", "type": "movies"}
		]
	}`)

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

	configPath := writeConfig(t, `{
		"listen": ":9999",
		"logLevel": "debug",
		"libraries": [
			{"id": " movies ", "name": " Movies ", "path": " /media/movies/../Movies ", "type": ""}
		],
		"scanOnStart": false,
		"autoScan": false,
		"autoScanDebounce": "5s",
		"scanTimeout": "12m"
	}`)

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
	if cfg.AutoScan {
		t.Fatalf("autoScan = true, want false from config")
	}
	if cfg.AutoScanDebounce != 5*time.Second {
		t.Fatalf("auto scan debounce = %s, want 5s", cfg.AutoScanDebounce)
	}
	if cfg.AutoScanInterval != 30*time.Minute {
		t.Fatalf("auto scan interval = %s, want env override 30m", cfg.AutoScanInterval)
	}
	if len(cfg.Libraries) != 1 {
		t.Fatalf("libraries = %#v, want one library", cfg.Libraries)
	}
	lib := cfg.Libraries[0]
	if lib.ID != "movies" || lib.Name != "Movies" || lib.Path != "/media/Movies" || lib.Type != "movies" {
		t.Fatalf("normalized library = %#v, want trimmed movies library", lib)
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

func writeConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	return path
}
