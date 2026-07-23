package server

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"popcorn/internal/media"
)

type sidecarStatus struct {
	Trailer bool `json:"trailer,omitempty"`
	Theme   bool `json:"theme,omitempty"`
}

func (a *App) itemSidecars(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, sidecarStatus{
		Trailer: itemTrailerPath(item) != "",
	})
}

func (a *App) itemTrailer(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	path := itemTrailerPath(item)
	if path == "" {
		http.NotFound(w, r)
		return
	}
	serveSidecar(w, r, path)
}

func (a *App) showTheme(w http.ResponseWriter, r *http.Request) {
	libraryID := strings.TrimSpace(r.URL.Query().Get("libraryId"))
	showTitle := strings.TrimSpace(r.URL.Query().Get("showTitle"))
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	path := a.showThemePath(r, libraryID, showTitle)
	if r.URL.Query().Get("stream") != "1" {
		writeJSON(w, http.StatusOK, sidecarStatus{Theme: path != ""})
		return
	}
	if path == "" {
		http.NotFound(w, r)
		return
	}
	serveSidecar(w, r, path)
}

func (a *App) showThemePath(r *http.Request, libraryID, showTitle string) string {
	episodes, err := a.store.ListEpisodes(r.Context(), libraryID, showTitle, -1)
	if err != nil || len(episodes) == 0 {
		return ""
	}
	showDir := showRootDir(a.libraryRoot(libraryID), episodes[0].Path)
	return findNamedSidecar(showDir, []string{"theme.mp3"})
}

func itemTrailerPath(item media.Item) string {
	dir := filepath.Dir(item.Path)
	base := strings.TrimSuffix(filepath.Base(item.Path), filepath.Ext(item.Path))
	return findNamedSidecar(dir, []string{
		"trailer.mp4",
		base + "-trailer.mp4",
		base + ".trailer.mp4",
	})
}

func showRootDir(root, video string) string {
	dir := filepath.Dir(video)
	rootAbs, _ := filepath.Abs(root)
	for current := dir; ; current = filepath.Dir(current) {
		if _, err := os.Stat(filepath.Join(current, "tvshow.nfo")); err == nil {
			return current
		}
		if current == rootAbs || current == filepath.Dir(current) {
			break
		}
	}
	rel, err := filepath.Rel(root, video)
	if err != nil || strings.HasPrefix(rel, "..") {
		return dir
	}
	first := strings.Split(rel, string(os.PathSeparator))[0]
	return filepath.Join(root, first)
}

func findNamedSidecar(dir string, names []string) string {
	for _, name := range names {
		candidate := filepath.Join(dir, name)
		info, err := os.Stat(candidate)
		if err == nil && !info.IsDir() {
			abs, _ := filepath.Abs(candidate)
			return abs
		}
	}
	return ""
}

func serveSidecar(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	http.ServeContent(w, r, filepath.Base(path), time.Unix(info.ModTime().Unix(), 0), f)
}
