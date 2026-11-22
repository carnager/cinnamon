package server

import (
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"net/http"
	"strings"
	"sync"

	"popcorn/web"
)

// The next-generation web client lives in web/next and is served at /next
// while it is being built, next to the current client at /.
//
// It is plain ES modules with no build step, so cache busting cannot rely on
// rewriting the asset links in index.html: a module's own imports would keep
// their old URLs and stay cached. Instead every asset is served under
// /next/@<hash>/ where <hash> covers the content of all embedded next files.
// Relative imports resolve under the same prefix, so one deploy changes every
// URL at once and assets can be cached as immutable. index.html itself is never
// cached and is the fallback for every client-side route.

const nextMount = "/next"

var (
	nextStampOnce sync.Once
	nextStamp     string
)

func nextAssetStamp() string {
	nextStampOnce.Do(func() {
		h := sha256.New()
		_ = fs.WalkDir(web.Files, "next", func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			data, err := web.Files.ReadFile(path)
			if err != nil {
				return err
			}
			h.Write([]byte(path))
			h.Write([]byte{0})
			h.Write(data)
			return nil
		})
		nextStamp = hex.EncodeToString(h.Sum(nil))[:12]
	})
	return nextStamp
}

func nextWebFiles() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, nextMount)
		rest = strings.TrimPrefix(rest, "/")
		if strings.HasPrefix(rest, "@") {
			// /next/@<stamp>/<asset>. A stale stamp still gets the current
			// file (a page loaded before a deploy can finish loading), just
			// without the immutable cache header.
			stamp, asset, _ := strings.Cut(strings.TrimPrefix(rest, "@"), "/")
			if asset == "" || asset == "index.html" || strings.Contains(asset, "..") {
				http.NotFound(w, r)
				return
			}
			if f, err := web.Files.Open("next/" + asset); err != nil {
				http.NotFound(w, r)
				return
			} else {
				_ = f.Close()
			}
			if stamp == nextAssetStamp() {
				w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			} else {
				w.Header().Set("Cache-Control", "no-cache")
			}
			http.ServeFileFS(w, r, web.Files, "next/"+asset)
			return
		}
		data, err := web.Files.ReadFile("next/index.html")
		if err != nil {
			http.NotFound(w, r)
			return
		}
		base := nextMount + "/@" + nextAssetStamp() + "/"
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(strings.ReplaceAll(string(data), "__ASSETS__/", base)))
	})
}
