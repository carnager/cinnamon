package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
	"popcorn/internal/server"
)

func main() {
	configPath := flag.String("config", "", "path to config.toml or config.json")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		slog.Error("load config", "error", err)
		os.Exit(1)
	}
	log := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: logLevel(cfg.LogLevel)}))

	db, err := database.Open(cfg.DatabasePath)
	if err != nil {
		log.Error("open database", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	store := media.NewStore(db)
	authStore := auth.NewStore(db)
	adminUser := envDefault("POPCORN_ADMIN_USER", "admin")
	adminPassword, passwordFromEnv := os.LookupEnv("POPCORN_ADMIN_PASSWORD")
	if !passwordFromEnv {
		adminPassword, err = randomBootstrapPassword()
		if err != nil {
			log.Error("generate bootstrap admin password", "error", err)
			os.Exit(1)
		}
	}
	if created, user, err := authStore.EnsureBootstrap(context.Background(), adminUser, adminPassword); err != nil {
		log.Error("bootstrap admin user", "error", err)
		os.Exit(1)
	} else if created {
		if passwordFromEnv {
			log.Warn("created bootstrap admin user", "username", user.Username, "passwordEnv", "POPCORN_ADMIN_PASSWORD")
		} else {
			log.Warn("created bootstrap admin user with generated password", "username", user.Username, "password", adminPassword, "passwordEnv", "POPCORN_ADMIN_PASSWORD")
		}
	}
	app := server.New(server.Options{
		Config: cfg,
		Log:    log,
		Store:  store,
		Auth:   authStore,
	})
	defer app.Close()

	scanCtx, stopScanner := context.WithCancel(context.Background())
	defer stopScanner()
	if cfg.ScanOnStart || cfg.AutoScan {
		go media.NewAutoScanner(cfg, store, log).Run(scanCtx)
	}

	httpServer := &http.Server{
		Addr:              cfg.Listen,
		Handler:           app.Routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		log.Info("popcorn listening", "addr", cfg.Listen, "database", cfg.DatabasePath)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("serve", "error", err)
			os.Exit(1)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	stopScanner()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Error("shutdown", "error", err)
		os.Exit(1)
	}
	app.Close()
}

func envDefault(key, fallback string) string {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	return v
}

func randomBootstrapPassword() (string, error) {
	b := make([]byte, 18)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func logLevel(level string) slog.Level {
	switch level {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
