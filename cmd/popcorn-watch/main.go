// Command popcorn-watch runs on the storage host (e.g. TrueNAS) and watches the
// media datasets for changes using local filesystem events, then tells a popcorn
// server to incrementally scan just the directories that changed. This replaces
// polling a network mount from the server, which is slow and misses events.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
)

func main() {
	server := flag.String("server", env("POPCORN_WATCH_SERVER", ""), "popcorn base URL, e.g. http://gemenon:8097")
	user := flag.String("user", env("POPCORN_WATCH_USER", ""), "popcorn admin username")
	password := flag.String("password", env("POPCORN_WATCH_PASSWORD", ""), "popcorn admin password")
	watch := flag.String("watch", env("POPCORN_WATCH_DIRS", ""), "comma-separated local directories to watch")
	remap := flag.String("map", env("POPCORN_WATCH_MAP", ""), "comma-separated local=popcorn path prefix maps (e.g. /mnt/tank/movies=/nas/movies)")
	debounce := flag.Duration("debounce", envDuration("POPCORN_WATCH_DEBOUNCE", 2*time.Second), "coalesce events within this window before notifying")
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stdout, nil))
	roots := splitList(*watch)
	if *server == "" || *user == "" || *password == "" || len(roots) == 0 {
		log.Error("missing required config", "server", *server != "", "user", *user != "", "password", *password != "", "watch", len(roots))
		flag.Usage()
		os.Exit(2)
	}
	maps, err := parseMaps(*remap)
	if err != nil {
		log.Error("invalid -map", "error", err)
		os.Exit(2)
	}

	c := &client{base: strings.TrimRight(*server, "/"), user: *user, password: *password, log: log}
	if err := c.login(); err != nil {
		log.Error("initial login failed", "error", err)
		os.Exit(1)
	}

	w, err := fsnotify.NewWatcher()
	if err != nil {
		log.Error("create watcher", "error", err)
		os.Exit(1)
	}
	defer w.Close()
	watched := setupWatches(w, roots, log)
	watchAndNotify(w, roots, watched, maps, *debounce, c.notify, log)
}

// setupWatches registers recursive watches on every root and returns the set
// of watched directories. inotify tracks inodes, so when a directory is
// renamed its watch silently keeps reporting events under the OLD path —
// scans then target directories that no longer exist and nothing new under
// the renamed tree is ever watched. The set lets watchAndNotify drop every
// stale watch under a renamed/removed path so the matching Create re-adds the
// tree under its real name.
func setupWatches(w *fsnotify.Watcher, roots []string, log *slog.Logger) map[string]bool {
	watched := map[string]bool{}
	for _, root := range roots {
		n := addTree(w, root, watched, log)
		log.Info("watching", "root", root, "directories", n)
	}
	return watched
}

// watchAndNotify consumes watcher events and calls notify with the changed
// directories. It returns when the watcher is closed.
func watchAndNotify(w *fsnotify.Watcher, roots []string, watched map[string]bool, maps []pathMap, debounce time.Duration, notify func([]string) error, log *slog.Logger) {

	// Debounce per target path, not globally: a single global timer that
	// resets on every event never fires while anything on the datasets is
	// busy (e.g. a download writing chunks), starving notifications for
	// unrelated directories. Each target flushes once IT has been quiet for
	// the debounce window — so a directory receiving a slow copy is not
	// announced until the copy finishes, while everything else flushes on
	// time.
	pending := map[string]time.Time{}
	var mu sync.Mutex
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	flushDue := func() {
		now := time.Now()
		mu.Lock()
		var due []string
		var paths []string
		for p, lastEvent := range pending {
			if now.Sub(lastEvent) >= debounce {
				due = append(due, p)
				paths = append(paths, remapPath(p, maps))
				delete(pending, p)
			}
		}
		mu.Unlock()
		if len(paths) == 0 {
			return
		}
		if err := notify(paths); err != nil {
			// Keep the paths pending so a transient failure (server restart,
			// expired token, network blip) delays the notification instead of
			// silently losing it.
			log.Warn("notify failed, will retry", "paths", len(paths), "error", err)
			mu.Lock()
			for _, p := range due {
				if _, ok := pending[p]; !ok {
					pending[p] = time.Now()
				}
			}
			mu.Unlock()
		} else {
			log.Info("notified scan", "paths", len(paths))
		}
	}

	for {
		select {
		case event, ok := <-w.Events:
			if !ok {
				return
			}
			if ignored(event.Name) {
				continue
			}
			// A renamed or removed directory leaves watches behind that keep
			// reporting events under its old name. Drop them; the rename's
			// destination arrives as a Create and re-adds the tree.
			if event.Op&(fsnotify.Rename|fsnotify.Remove) != 0 {
				dropWatches(w, event.Name, watched)
			}
			// A newly created directory needs its own watch, and its children
			// may already exist (e.g. a whole show folder renamed into place).
			if event.Op&fsnotify.Create != 0 {
				if info, err := os.Stat(event.Name); err == nil && info.IsDir() {
					addTree(w, event.Name, watched, log)
				}
			}
			target := scanTarget(event.Name, roots)
			if target == "" {
				continue
			}
			mu.Lock()
			pending[target] = time.Now()
			mu.Unlock()
		case <-ticker.C:
			flushDue()
		case err, ok := <-w.Errors:
			if !ok {
				return
			}
			log.Warn("watch error", "error", err)
		}
	}
}

// dropWatches removes the watch on path and everything below it. Errors are
// ignored: deleted directories lose their watches on their own.
func dropWatches(w *fsnotify.Watcher, path string, watched map[string]bool) {
	prefix := path + string(filepath.Separator)
	for p := range watched {
		if p == path || strings.HasPrefix(p, prefix) {
			_ = w.Remove(p)
			delete(watched, p)
		}
	}
}

// scanTarget is the directory to scan for an event path: the containing
// directory, unless that is a watch root (a top-level add/remove), in which case
// the changed entry itself is scanned so we never re-walk an entire library.
func scanTarget(path string, roots []string) string {
	dir := path
	if info, err := os.Stat(path); err != nil || !info.IsDir() {
		dir = filepath.Dir(path)
	}
	for _, root := range roots {
		if sameDir(dir, root) {
			return path
		}
	}
	return dir
}

func sameDir(a, b string) bool {
	ap, err1 := filepath.Abs(a)
	bp, err2 := filepath.Abs(b)
	if err1 != nil || err2 != nil {
		return a == b
	}
	return filepath.Clean(ap) == filepath.Clean(bp)
}

func addTree(w *fsnotify.Watcher, root string, watched map[string]bool, log *slog.Logger) int {
	count := 0
	failed := 0
	var firstErr error
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		if path != root && ignored(path) {
			return filepath.SkipDir
		}
		if err := w.Add(path); err == nil {
			watched[path] = true
			count++
		} else {
			failed++
			if firstErr == nil {
				firstErr = err
			}
		}
		return nil
	})
	if failed > 0 {
		// Usually fs.inotify.max_user_watches exhaustion — without a watch a
		// directory is a permanent blind spot, so this must be loud.
		log.Warn("could not watch some directories", "root", root, "failed", failed, "error", firstErr)
	}
	return count
}

func ignored(path string) bool {
	base := filepath.Base(path)
	return strings.HasPrefix(base, ".") || base == "@eaDir"
}

func remapPath(p string, maps []pathMap) string {
	clean := filepath.Clean(p)
	for _, m := range maps {
		if clean == m.from {
			return m.to
		}
		if strings.HasPrefix(clean, m.from+string(filepath.Separator)) {
			return m.to + clean[len(m.from):]
		}
	}
	return clean
}

type pathMap struct{ from, to string }

func parseMaps(s string) ([]pathMap, error) {
	var out []pathMap
	for _, part := range splitList(s) {
		from, to, ok := strings.Cut(part, "=")
		if !ok {
			return nil, fmt.Errorf("bad map %q, want local=remote", part)
		}
		from = filepath.Clean(strings.TrimSpace(from))
		to = strings.TrimRight(strings.TrimSpace(to), "/")
		if from == "" || to == "" {
			return nil, fmt.Errorf("bad map %q", part)
		}
		out = append(out, pathMap{from: from, to: to})
	}
	return out, nil
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// ── popcorn client ──

type client struct {
	base     string
	user     string
	password string
	token    string
	log      *slog.Logger
}

func (c *client) login() error {
	body, _ := json.Marshal(map[string]string{"username": c.user, "password": c.password})
	resp, err := http.Post(c.base+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return fmt.Errorf("login %d: %s", resp.StatusCode, strings.TrimSpace(string(msg)))
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return err
	}
	if out.Token == "" {
		return fmt.Errorf("login returned no token")
	}
	c.token = out.Token
	return nil
}

func (c *client) notify(paths []string) error {
	if err := c.post(paths); err == nil {
		return nil
	} else if !isUnauthorized(err) {
		return err
	}
	// Token expired: re-login once and retry.
	if err := c.login(); err != nil {
		return err
	}
	return c.post(paths)
}

type unauthorizedError struct{ status int }

func (e unauthorizedError) Error() string { return fmt.Sprintf("unauthorized (%d)", e.status) }
func isUnauthorized(err error) bool       { _, ok := err.(unauthorizedError); return ok }

func (c *client) post(paths []string) error {
	body, _ := json.Marshal(map[string]any{"paths": paths})
	req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, c.base+"/api/scan/path", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return unauthorizedError{status: resp.StatusCode}
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return fmt.Errorf("notify %d", resp.StatusCode)
	}
	return nil
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envDuration(key string, fallback time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return fallback
}
