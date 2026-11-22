package server

import (
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

func webGet(t *testing.T, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	webClient().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestWebClientRoutes(t *testing.T) {
	for _, path := range []string{"/", "/library/movies", "/library/tv/show/Some%20Show", "/settings/trakt", "/watch/12"} {
		rec := webGet(t, path)
		if rec.Code != http.StatusOK || rec.Header().Get("Cache-Control") != "no-store" || !strings.Contains(rec.Body.String(), `id="app"`) {
			t.Errorf("%s: code=%d cache=%q", path, rec.Code, rec.Header().Get("Cache-Control"))
		}
		if strings.Contains(rec.Body.String(), "__ASSETS__") {
			t.Errorf("%s: asset placeholder not replaced", path)
		}
	}
}

func TestWebClientAssetsAreStampedAndImmutable(t *testing.T) {
	page := webGet(t, "/").Body.String()
	match := regexp.MustCompile(`/@[a-f0-9]{12}/main\.js`).FindString(page)
	if match == "" {
		t.Fatalf("no stamped entry point in page")
	}
	rec := webGet(t, match)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("main.js: code=%d cache=%q", rec.Code, rec.Header().Get("Cache-Control"))
	}
	if stale := webGet(t, "/@000000000000/main.js"); stale.Code != http.StatusOK || strings.Contains(stale.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("stale stamp: code=%d cache=%q", stale.Code, stale.Header().Get("Cache-Control"))
	}
	if escape := webGet(t, "/@x/../web.go"); escape.Code == http.StatusOK && strings.Contains(escape.Body.String(), "go:embed") {
		t.Fatal("asset path escaped the client directory")
	}
}

func TestWebClientFixedFilesAndRemote(t *testing.T) {
	for _, path := range []string{"/hls.min.js", "/remote.js", "/fonts/inter-var-latin.woff2", "/jassub/jassub.js", "/jassub/jassub-worker.wasm"} {
		if rec := webGet(t, path); rec.Code != http.StatusOK || strings.Contains(rec.Body.String(), `id="app"`) {
			t.Errorf("%s: code=%d", path, rec.Code)
		}
	}
	if rec := webGet(t, "/remote"); rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "/remote.js?v=") {
		t.Errorf("/remote: code=%d", rec.Code)
	}
}

func TestRetiredMountsRedirect(t *testing.T) {
	for from, to := range map[string]string{
		"/next":                          "/",
		"/next/":                         "/",
		"/next/library/movies?sort=year": "/library/movies?sort=year",
		"/next/watch/12":                 "/watch/12",
		"/web/settings":                  "/settings",
		"/popcorn":                       "/",
	} {
		rec := webGet(t, from)
		if rec.Code != http.StatusFound || rec.Header().Get("Location") != to {
			t.Errorf("%s: code=%d location=%q, want %q", from, rec.Code, rec.Header().Get("Location"), to)
		}
	}
}
