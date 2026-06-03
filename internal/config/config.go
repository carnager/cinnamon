package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/pelletier/go-toml/v2"
)

const (
	defaultTraktClientID     = "e84d64c923818f347e0bccc0cd187e93ae137e29d177c046d712cec1a0931c66"
	defaultTraktClientSecret = "3b789282d9f0551f49ba033a99523662e6410dcda3190789a91c7b49d0163c30"
)

type Config struct {
	Listen            string        `json:"listen" toml:"listen"`
	LogLevel          string        `json:"logLevel" toml:"logLevel"`
	DatabasePath      string        `json:"databasePath" toml:"databasePath"`
	Libraries         []Library     `json:"libraries" toml:"libraries"`
	FFmpegPath        string        `json:"ffmpegPath" toml:"ffmpegPath"`
	FFprobePath       string        `json:"ffprobePath" toml:"ffprobePath"`
	HWAccel           string        `json:"hwAccel" toml:"hwAccel"`
	HWDevice          string        `json:"hwDevice" toml:"hwDevice"`
	TraktClientID     string        `json:"traktClientId" toml:"traktClientId"`
	TraktClientSecret string        `json:"traktClientSecret" toml:"traktClientSecret"`
	TraktAPIURL       string        `json:"traktApiUrl" toml:"traktApiUrl"`
	TMDbAPIKey        string        `json:"tmdbApiKey" toml:"tmdbApiKey"`
	TMDbReadToken     string        `json:"tmdbReadAccessToken" toml:"tmdbReadAccessToken"`
	OMDbAPIKey        string        `json:"omdbApiKey" toml:"omdbApiKey"`
	ScanOnStart       bool          `json:"scanOnStart" toml:"scanOnStart"`
	AutoScan          bool          `json:"autoScan" toml:"autoScan"`
	AutoScanDebounce  time.Duration `json:"-"`
	AutoScanInterval  time.Duration `json:"-"`
	ScanTimeout       time.Duration `json:"-"`
}

type Library struct {
	ID   string `json:"id" toml:"id"`
	Name string `json:"name" toml:"name"`
	Path string `json:"path" toml:"path"`
	Type string `json:"type" toml:"type"`
}

type diskConfig struct {
	Listen            string    `json:"listen" toml:"listen"`
	LogLevel          string    `json:"logLevel" toml:"logLevel"`
	DatabasePath      string    `json:"databasePath" toml:"databasePath"`
	Libraries         []Library `json:"libraries" toml:"libraries"`
	FFmpegPath        string    `json:"ffmpegPath" toml:"ffmpegPath"`
	FFprobePath       string    `json:"ffprobePath" toml:"ffprobePath"`
	HWAccel           string    `json:"hwAccel" toml:"hwAccel"`
	HWDevice          string    `json:"hwDevice" toml:"hwDevice"`
	TraktClientID     string    `json:"traktClientId" toml:"traktClientId"`
	TraktClientSecret string    `json:"traktClientSecret" toml:"traktClientSecret"`
	TraktAPIURL       string    `json:"traktApiUrl" toml:"traktApiUrl"`
	TMDbAPIKey        string    `json:"tmdbApiKey" toml:"tmdbApiKey"`
	TMDbReadToken     string    `json:"tmdbReadAccessToken" toml:"tmdbReadAccessToken"`
	OMDbAPIKey        string    `json:"omdbApiKey" toml:"omdbApiKey"`
	ScanOnStart       *bool     `json:"scanOnStart" toml:"scanOnStart"`
	AutoScan          *bool     `json:"autoScan" toml:"autoScan"`
	AutoScanDebounce  string    `json:"autoScanDebounce" toml:"autoScanDebounce"`
	AutoScanInterval  string    `json:"autoScanInterval" toml:"autoScanInterval"`
	ScanTimeout       string    `json:"scanTimeout" toml:"scanTimeout"`
}

func Load(path string) (Config, error) {
	cfg := defaults()
	if path == "" {
		path = os.Getenv("POPCORN_CONFIG")
	}
	if path != "" {
		b, err := os.ReadFile(path)
		if err != nil {
			return Config{}, err
		}
		var raw diskConfig
		if err := decodeDiskConfig(path, b, &raw); err != nil {
			return Config{}, err
		}
		merge(&cfg, raw)
	}
	applyEnv(&cfg)
	normalize(&cfg)
	if err := validate(cfg); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func decodeDiskConfig(path string, b []byte, raw *diskConfig) error {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".toml":
		if err := toml.Unmarshal(b, raw); err != nil {
			return fmt.Errorf("parse TOML config: %w", err)
		}
	default:
		if err := json.Unmarshal(b, raw); err != nil {
			return fmt.Errorf("parse JSON config: %w", err)
		}
	}
	return nil
}

func defaults() Config {
	dataDir := os.Getenv("XDG_DATA_HOME")
	if dataDir == "" {
		if home, err := os.UserHomeDir(); err == nil {
			dataDir = filepath.Join(home, ".local", "share")
		}
	}
	if dataDir == "" {
		dataDir = "."
	}
	return Config{
		Listen:            ":8097",
		LogLevel:          "info",
		DatabasePath:      filepath.Join(dataDir, "popcorn", "popcorn.db"),
		FFmpegPath:        "ffmpeg",
		FFprobePath:       "ffprobe",
		HWAccel:           "auto",
		HWDevice:          "/dev/dri/renderD128",
		TraktClientID:     defaultTraktClientID,
		TraktClientSecret: defaultTraktClientSecret,
		TraktAPIURL:       "https://api.trakt.tv",
		ScanOnStart:       true,
		AutoScan:          true,
		AutoScanDebounce:  3 * time.Second,
		AutoScanInterval:  15 * time.Minute,
		ScanTimeout:       30 * time.Minute,
	}
}

func merge(cfg *Config, raw diskConfig) {
	if raw.Listen != "" {
		cfg.Listen = raw.Listen
	}
	if raw.LogLevel != "" {
		cfg.LogLevel = raw.LogLevel
	}
	if raw.DatabasePath != "" {
		cfg.DatabasePath = raw.DatabasePath
	}
	if raw.Libraries != nil {
		cfg.Libraries = raw.Libraries
	}
	if raw.FFmpegPath != "" {
		cfg.FFmpegPath = raw.FFmpegPath
	}
	if raw.FFprobePath != "" {
		cfg.FFprobePath = raw.FFprobePath
	}
	if raw.HWAccel != "" {
		cfg.HWAccel = raw.HWAccel
	}
	if raw.HWDevice != "" {
		cfg.HWDevice = raw.HWDevice
	}
	if raw.TraktClientID != "" {
		cfg.TraktClientID = raw.TraktClientID
	}
	if raw.TraktClientSecret != "" {
		cfg.TraktClientSecret = raw.TraktClientSecret
	}
	if raw.TraktAPIURL != "" {
		cfg.TraktAPIURL = raw.TraktAPIURL
	}
	if raw.TMDbAPIKey != "" {
		cfg.TMDbAPIKey = raw.TMDbAPIKey
	}
	if raw.TMDbReadToken != "" {
		cfg.TMDbReadToken = raw.TMDbReadToken
	}
	if raw.OMDbAPIKey != "" {
		cfg.OMDbAPIKey = raw.OMDbAPIKey
	}
	if raw.ScanOnStart != nil {
		cfg.ScanOnStart = *raw.ScanOnStart
	}
	if raw.AutoScan != nil {
		cfg.AutoScan = *raw.AutoScan
	}
	if raw.AutoScanDebounce != "" {
		if d, err := time.ParseDuration(raw.AutoScanDebounce); err == nil {
			cfg.AutoScanDebounce = d
		}
	}
	if raw.AutoScanInterval != "" {
		if d, err := time.ParseDuration(raw.AutoScanInterval); err == nil {
			cfg.AutoScanInterval = d
		}
	}
	if raw.ScanTimeout != "" {
		if d, err := time.ParseDuration(raw.ScanTimeout); err == nil {
			cfg.ScanTimeout = d
		}
	}
}

func applyEnv(cfg *Config) {
	if v := os.Getenv("POPCORN_LISTEN"); v != "" {
		cfg.Listen = v
	}
	if v := os.Getenv("POPCORN_LOG_LEVEL"); v != "" {
		cfg.LogLevel = v
	}
	if v := os.Getenv("POPCORN_DATABASE"); v != "" {
		cfg.DatabasePath = v
	}
	if v := os.Getenv("POPCORN_FFMPEG"); v != "" {
		cfg.FFmpegPath = v
	}
	if v := os.Getenv("POPCORN_FFPROBE"); v != "" {
		cfg.FFprobePath = v
	}
	if v := os.Getenv("POPCORN_HWACCEL"); v != "" {
		cfg.HWAccel = v
	}
	if v := os.Getenv("POPCORN_HWDEVICE"); v != "" {
		cfg.HWDevice = v
	}
	if v := os.Getenv("POPCORN_TRAKT_CLIENT_ID"); v != "" {
		cfg.TraktClientID = v
	}
	if v := os.Getenv("POPCORN_TRAKT_CLIENT_SECRET"); v != "" {
		cfg.TraktClientSecret = v
	}
	if v := os.Getenv("POPCORN_TRAKT_API_URL"); v != "" {
		cfg.TraktAPIURL = v
	}
	if v := os.Getenv("POPCORN_TMDB_API_KEY"); v != "" {
		cfg.TMDbAPIKey = v
	}
	if v := os.Getenv("POPCORN_TMDB_READ_ACCESS_TOKEN"); v != "" {
		cfg.TMDbReadToken = v
	}
	if v := os.Getenv("POPCORN_OMDB_API_KEY"); v != "" {
		cfg.OMDbAPIKey = v
	}
	if v := os.Getenv("POPCORN_AUTO_SCAN"); v != "" {
		cfg.AutoScan = parseBool(v, cfg.AutoScan)
	}
	if v := os.Getenv("POPCORN_AUTO_SCAN_DEBOUNCE"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			cfg.AutoScanDebounce = d
		}
	}
	if v := os.Getenv("POPCORN_AUTO_SCAN_INTERVAL"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			cfg.AutoScanInterval = d
		}
	}
	if v := os.Getenv("POPCORN_LIBRARY"); v != "" {
		libraryType := os.Getenv("POPCORN_LIBRARY_TYPE")
		if libraryType == "" {
			libraryType = "movies"
		}
		cfg.Libraries = []Library{{ID: "default", Name: "Media", Path: v, Type: libraryType}}
	}
}

func normalize(cfg *Config) {
	for i, lib := range cfg.Libraries {
		path := strings.TrimSpace(lib.Path)
		cfg.Libraries[i].ID = strings.TrimSpace(lib.ID)
		cfg.Libraries[i].Name = strings.TrimSpace(lib.Name)
		cfg.Libraries[i].Path = path
		if path != "" {
			cfg.Libraries[i].Path = filepath.Clean(path)
		}
		cfg.Libraries[i].Type = strings.TrimSpace(lib.Type)
		if cfg.Libraries[i].Type == "" {
			cfg.Libraries[i].Type = "movies"
		}
	}
}

func validate(cfg Config) error {
	if cfg.Listen == "" {
		return errors.New("listen address is required")
	}
	if cfg.DatabasePath == "" {
		return errors.New("database path is required")
	}
	if cfg.LogLevel != "debug" && cfg.LogLevel != "info" && cfg.LogLevel != "warn" && cfg.LogLevel != "error" {
		return errors.New("log level must be debug, info, warn, or error")
	}
	for _, lib := range cfg.Libraries {
		if strings.TrimSpace(lib.ID) == "" || strings.TrimSpace(lib.Name) == "" || strings.TrimSpace(lib.Path) == "" {
			return errors.New("library id, name, and path are required")
		}
		if lib.Type != "movies" && lib.Type != "tv" {
			return errors.New("library type must be movies or tv")
		}
	}
	if runtime.GOOS == "windows" && strings.HasPrefix(cfg.Listen, ":") {
		return errors.New("windows listen addresses should include host, for example 127.0.0.1:8097")
	}
	return nil
}

func parseBool(v string, fallback bool) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
	}
}
