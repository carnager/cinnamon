package server

import (
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"popcorn/internal/media"
)

// itemDownload serves the item's original media file as a download. It honors
// Range requests, so interrupted downloads can resume, and keeps the on-disk
// filename (already renamed to a clean, descriptive form by the library
// tooling) as the suggested save name.
func (a *App) itemDownload(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	defer f.Close()
	modTime := time.Unix(item.MTimeUnix, 0)
	if info, err := f.Stat(); err == nil {
		modTime = info.ModTime()
	}
	name := filepath.Base(path)
	w.Header().Set("Content-Disposition", attachmentDisposition(name))
	// A generic type keeps browsers saving the file instead of handing it to a
	// registered media handler.
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, name, modTime, f)
}

// attachmentDisposition builds a Content-Disposition value with the filename
// encoded per RFC 2231/5987 so non-ASCII titles survive every browser.
func attachmentDisposition(name string) string {
	if v := mime.FormatMediaType("attachment", map[string]string{"filename": name}); v != "" {
		return v
	}
	return "attachment"
}
