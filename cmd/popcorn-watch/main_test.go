package main

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/fsnotify/fsnotify"
)

func TestScanTargetNeverReturnsWatchRoot(t *testing.T) {
	roots := []string{"/mnt/tank/movies", "/mnt/tank/tv"}

	// A file dropped directly in a watch root -> scan the file, not the root.
	if got := scanTarget("/mnt/tank/movies/The Matrix.mkv", roots); got != "/mnt/tank/movies/The Matrix.mkv" {
		t.Errorf("top-level file: got %q", got)
	}
	// A deeper file -> scan its containing directory.
	if got := scanTarget("/mnt/tank/tv/Show/Season 01/ep.mkv", roots); got != "/mnt/tank/tv/Show/Season 01" {
		t.Errorf("nested file: got %q", got)
	}
}

func TestIgnoredSkipsSubtitleFiles(t *testing.T) {
	for _, path := range []string{"/m/Movie.s2.de.vtt", "/m/Movie.srt", "/m/Movie.ASS", "/m/.hidden", "/m/@eaDir"} {
		if !ignored(path) {
			t.Errorf("ignored(%q) = false, want true", path)
		}
	}
	for _, path := range []string{"/m/Movie.mkv", "/m/Movie.nfo", "/m/Season 1"} {
		if ignored(path) {
			t.Errorf("ignored(%q) = true, want false", path)
		}
	}
}

func TestRemapPath(t *testing.T) {
	maps, err := parseMaps("/mnt/tank/movies=/nas/movies, /mnt/tank/tv=/nas/tv")
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"/mnt/tank/movies/The Matrix": "/nas/movies/The Matrix",
		"/mnt/tank/tv/Show/Season 01": "/nas/tv/Show/Season 01",
		"/mnt/tank/movies":            "/nas/movies",
		"/mnt/tank/other/x":           "/mnt/tank/other/x", // unmapped passes through
	}
	for in, want := range cases {
		if got := remapPath(in, maps); got != want {
			t.Errorf("remapPath(%q) = %q, want %q", in, got, want)
		}
	}
}

type notifyRecorder struct {
	mu    sync.Mutex
	paths []string
}

func (n *notifyRecorder) notify(paths []string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.paths = append(n.paths, paths...)
	return nil
}

func (n *notifyRecorder) all() []string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return append([]string{}, n.paths...)
}

func (n *notifyRecorder) waitFor(t *testing.T, want string) bool {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, p := range n.all() {
			if p == want {
				return true
			}
		}
		time.Sleep(25 * time.Millisecond)
	}
	return false
}

func startTestWatcher(t *testing.T, root string) *notifyRecorder {
	t.Helper()
	w, err := fsnotify.NewWatcher()
	if err != nil {
		t.Fatalf("create watcher: %v", err)
	}
	rec := &notifyRecorder{}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	watched := setupWatches(w, []string{root}, log)
	done := make(chan struct{})
	go func() {
		defer close(done)
		watchAndNotify(w, []string{root}, watched, nil, 150*time.Millisecond, rec.notify, log)
	}()
	t.Cleanup(func() {
		w.Close()
		<-done
	})
	return rec
}

func mustMkdir(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", path, err)
	}
}

func mustWriteFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

// A renamer typically downloads into a scene-named folder, then renames it in
// place and shuffles episodes into Season folders. inotify watches follow
// inodes, not paths, so without explicit handling the renamed tree keeps
// reporting events under the old name and new subdirectories are never
// watched — the show silently stops producing scan notifications.
func TestRenamedShowDirKeepsNotifyingUnderNewName(t *testing.T) {
	root := t.TempDir()
	rec := startTestWatcher(t, root)

	oldDir := filepath.Join(root, "Lucky.2026.S01.1080p.WEB")
	mustMkdir(t, oldDir)
	mustWriteFile(t, filepath.Join(oldDir, "lucky.s01e01.mkv"), "v")
	if !rec.waitFor(t, oldDir) {
		t.Fatalf("no notification for initial download dir %s; got %v", oldDir, rec.all())
	}

	// The renamer strikes: rename the folder, then build the Season layout.
	newDir := filepath.Join(root, "Lucky (2026)")
	if err := os.Rename(oldDir, newDir); err != nil {
		t.Fatalf("rename show dir: %v", err)
	}
	season := filepath.Join(newDir, "Season 1")
	mustMkdir(t, season)
	if err := os.Rename(filepath.Join(newDir, "lucky.s01e01.mkv"), filepath.Join(season, "Lucky - S01E01.mkv")); err != nil {
		t.Fatalf("move episode: %v", err)
	}
	if !rec.waitFor(t, newDir) {
		t.Fatalf("no notification for renamed dir %s; got %v", newDir, rec.all())
	}

	// The regression: events inside the renamed tree must still be seen, under
	// the new path. Before the fix the stale watch reported the old path and
	// Season 1 was never watched at all.
	mustWriteFile(t, filepath.Join(season, "Lucky - S01E02.mkv"), "v")
	if !rec.waitFor(t, season) {
		t.Fatalf("no notification for %s after in-place rename; got %v", season, rec.all())
	}
}

// Nested directories created rapid-fire inside a fresh tree (a whole show
// copied in) must all end up watched, including for later activity.
func TestNewNestedDirectoriesAreWatched(t *testing.T) {
	root := t.TempDir()
	rec := startTestWatcher(t, root)

	// The whole tree appears at once; the event for the file itself may be
	// lost to the watch-registration race, but the show dir's Create is
	// notified and the server scans it recursively.
	show := filepath.Join(root, "Station Nord (2015)")
	season := filepath.Join(show, "Season 2")
	mustMkdir(t, season)
	mustWriteFile(t, filepath.Join(season, "Station Nord - S02E01.mkv"), "v")
	if !rec.waitFor(t, show) {
		t.Fatalf("no notification for new show dir %s; got %v", show, rec.all())
	}

	// Later activity in the nested dir must be seen under its own path.
	mustWriteFile(t, filepath.Join(season, "Station Nord - S02E02.mkv"), "v")
	if !rec.waitFor(t, season) {
		t.Fatalf("no notification for later file in %s; got %v", season, rec.all())
	}
}

// A removed directory must still notify (so the server prunes it).
func TestRemovedDirNotifies(t *testing.T) {
	root := t.TempDir()
	rec := startTestWatcher(t, root)

	show := filepath.Join(root, "Gone (2020)")
	mustMkdir(t, show)
	mustWriteFile(t, filepath.Join(show, "gone.mkv"), "v")
	if !rec.waitFor(t, show) {
		t.Fatalf("no notification for new dir %s; got %v", show, rec.all())
	}

	if err := os.RemoveAll(show); err != nil {
		t.Fatalf("remove show: %v", err)
	}
	if !rec.waitFor(t, show) {
		t.Fatalf("no notification for removed dir %s; got %v", show, rec.all())
	}
}
