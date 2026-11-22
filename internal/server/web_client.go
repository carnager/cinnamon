package server

import (
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"

	"popcorn/internal/version"
	"popcorn/web"
)

// The web client lives in web/app and is served at /.
//
// It is plain ES modules with no build step, so cache busting cannot rely on
// rewriting the asset links in index.html: a module's own imports would keep
// their old URLs and stay cached. Instead every asset is served under
// /@<hash>/ where <hash> covers the content of all embedded client files.
// Relative imports resolve under the same prefix, so one deploy changes every
// URL at once and assets can be cached as immutable. index.html itself is never
// cached and is the fallback for every client-side route.

var (
	clientStampOnce sync.Once
	clientStamp     string
)

// htmlAssetRef matches local css/js references in remote.html so they can be
// stamped with the build for cache busting.
var htmlAssetRef = regexp.MustCompile(`(href|src)="(/[^"?]+\.(?:css|js))"`)

func clientAssetStamp() string {
	clientStampOnce.Do(func() {
		h := sha256.New()
		_ = fs.WalkDir(web.Files, "app", func(path string, d fs.DirEntry, err error) error {
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
		clientStamp = hex.EncodeToString(h.Sum(nil))[:12]
	})
	return clientStamp
}

// legacyMount returns the path without a retired mount prefix: /next was the
// client's address while it was being built, /web and /popcorn are older.
func legacyMount(path string) (string, bool) {
	for _, mount := range []string{"/next", "/web", "/popcorn"} {
		if path == mount {
			return "/", true
		}
		if strings.HasPrefix(path, mount+"/") {
			return strings.TrimPrefix(path, mount), true
		}
	}
	return "", false
}

// isStaticFile reports the fixed-URL files served as they are.
func isStaticFile(path string) bool {
	return path == "hls.min.js" || path == "remote.js" ||
		strings.HasPrefix(path, "fonts/") || strings.HasPrefix(path, "jassub/")
}

func webClient() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		if target, ok := legacyMount(r.URL.Path); ok {
			if r.URL.RawQuery != "" {
				target += "?" + r.URL.RawQuery
			}
			http.Redirect(w, r, target, http.StatusFound)
			return
		}
		path := strings.TrimPrefix(r.URL.Path, "/")
		switch {
		case strings.HasPrefix(path, "@"):
			serveClientAsset(w, r, strings.TrimPrefix(path, "@"))
		case path == "remote":
			serveRemotePage(w)
		case isStaticFile(path):
			if f, err := web.Files.Open(path); err != nil {
				http.NotFound(w, r)
				return
			} else {
				_ = f.Close()
			}
			w.Header().Set("Cache-Control", "public, max-age=86400")
			http.ServeFileFS(w, r, web.Files, path)
		default:
			serveClientPage(w, r)
		}
	})
}

// serveClientAsset serves <stamp>/<asset>. A stale stamp still gets the current
// file (a page loaded before a deploy can finish loading), just without the
// immutable cache header.
func serveClientAsset(w http.ResponseWriter, r *http.Request, rest string) {
	stamp, asset, _ := strings.Cut(rest, "/")
	if asset == "" || asset == "index.html" || strings.Contains(asset, "..") {
		http.NotFound(w, r)
		return
	}
	f, err := web.Files.Open("app/" + asset)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	_ = f.Close()
	if stamp == clientAssetStamp() {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		w.Header().Set("Cache-Control", "no-cache")
	}
	http.ServeFileFS(w, r, web.Files, "app/"+asset)
}

func serveClientPage(w http.ResponseWriter, r *http.Request) {
	data, err := web.Files.ReadFile("app/index.html")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	base := "/@" + clientAssetStamp() + "/"
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(strings.ReplaceAll(string(data), "__ASSETS__/", base)))
}

func serveRemotePage(w http.ResponseWriter) {
	data, err := web.Files.ReadFile("remote.html")
	if err != nil {
		http.Error(w, "remote page missing", http.StatusInternalServerError)
		return
	}
	ver := url.QueryEscape(version.Version + "-" + version.BuildTime)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(htmlAssetRef.ReplaceAll(data, []byte(`${1}="${2}?v=`+ver+`"`)))
}
