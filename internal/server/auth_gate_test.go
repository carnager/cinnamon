package server

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/database"
	"popcorn/internal/media"
)

func TestAuthGateProtectsPrivateAPI(t *testing.T) {
	app := newAuthGateTestApp(t)

	req := httptest.NewRequest(http.MethodGet, "/api/items", nil)
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("GET /api/items without token = %d, want %d", rec.Code, http.StatusUnauthorized)
	}
}

func TestAuthGateAllowsPublicAPI(t *testing.T) {
	app := newAuthGateTestApp(t)

	req := httptest.NewRequest(http.MethodGet, "/api/health", nil)
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/health without token = %d, want %d", rec.Code, http.StatusOK)
	}
}

func TestAuthGateAcceptsBearerToken(t *testing.T) {
	app := newAuthGateTestApp(t)
	user, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username:    "rasi",
		DisplayName: "Rasi",
		Password:    "secret1",
	})
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	token, err := app.auth.CreateSession(context.Background(), user.ID, time.Hour)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/auth/me", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/auth/me with token = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"username":"rasi"`) {
		t.Fatalf("GET /api/auth/me body = %s, want rasi user", rec.Body.String())
	}
}

func TestLoginCookieIsSecureBehindHTTPSProxy(t *testing.T) {
	app := newAuthGateTestApp(t)
	if _, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username: "rasi",
		Password: "secret1",
	}); err != nil {
		t.Fatalf("create user: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"rasi","password":"secret1"}`))
	req.Header.Set("X-Forwarded-Proto", "https")
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("login = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	cookies := rec.Result().Cookies()
	if len(cookies) == 0 || cookies[0].Name != "popcorn_token" || !cookies[0].Secure {
		t.Fatalf("login cookies = %#v, want secure popcorn_token", cookies)
	}
}

func TestLoginRateLimitBlocksRepeatedFailures(t *testing.T) {
	app := newAuthGateTestApp(t)
	if _, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username: "rasi",
		Password: "secret1",
	}); err != nil {
		t.Fatalf("create user: %v", err)
	}

	for i := 0; i < maxLoginFailures; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"rasi","password":"wrong"}`))
		req.RemoteAddr = "192.0.2.10:12345"
		rec := httptest.NewRecorder()
		app.Routes().ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("failed login %d = %d, want %d", i+1, rec.Code, http.StatusUnauthorized)
		}
	}

	req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(`{"username":"rasi","password":"secret1"}`))
	req.RemoteAddr = "192.0.2.10:12345"
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("login after repeated failures = %d, want %d", rec.Code, http.StatusTooManyRequests)
	}
}

func TestQRLoginCompletionIsOneShot(t *testing.T) {
	app := newAuthGateTestApp(t)
	first, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username: "first",
		Password: "secret1",
	})
	if err != nil {
		t.Fatalf("create first user: %v", err)
	}
	second, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username: "second",
		Password: "secret1",
	})
	if err != nil {
		t.Fatalf("create second user: %v", err)
	}
	firstToken, err := app.auth.CreateSession(context.Background(), first.ID, time.Hour)
	if err != nil {
		t.Fatalf("create first session: %v", err)
	}
	secondToken, err := app.auth.CreateSession(context.Background(), second.ID, time.Hour)
	if err != nil {
		t.Fatalf("create second session: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/auth/qr/start", strings.NewReader(`{"deviceName":"Shield"}`))
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("qr start = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var started struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &started); err != nil || started.Code == "" {
		t.Fatalf("qr start body = %s, parse err %v", rec.Body.String(), err)
	}

	req = httptest.NewRequest(http.MethodPost, "/api/auth/qr/claim", strings.NewReader(`{"code":"`+started.Code+`"}`))
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusConflict {
		t.Fatalf("unapproved qr claim = %d, want %d: %s", rec.Code, http.StatusConflict, rec.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/api/auth/qr/complete", strings.NewReader(`{"code":"`+started.Code+`"}`))
	req.Header.Set("Authorization", "Bearer "+firstToken)
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("first qr complete = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/api/auth/qr/complete", strings.NewReader(`{"code":"`+started.Code+`"}`))
	req.Header.Set("Authorization", "Bearer "+secondToken)
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusConflict {
		t.Fatalf("second qr complete = %d, want %d: %s", rec.Code, http.StatusConflict, rec.Body.String())
	}

	// A signed-out client can claim the approved session once.
	req = httptest.NewRequest(http.MethodPost, "/api/auth/qr/claim", strings.NewReader(`{"code":"`+started.Code+`"}`))
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("qr claim = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	var claimed struct {
		Token string    `json:"token"`
		User  auth.User `json:"user"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &claimed); err != nil {
		t.Fatalf("parse qr claim: %v", err)
	}
	if claimed.Token == "" || claimed.User.ID != first.ID {
		t.Fatalf("qr claim = %+v, want user %d and token", claimed, first.ID)
	}
	req = httptest.NewRequest(http.MethodGet, "/api/auth/me", nil)
	req.Header.Set("Authorization", "Bearer "+claimed.Token)
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("claimed qr session = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}

	req = httptest.NewRequest(http.MethodPost, "/api/auth/qr/claim", strings.NewReader(`{"code":"`+started.Code+`"}`))
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("second qr claim = %d, want %d: %s", rec.Code, http.StatusNotFound, rec.Body.String())
	}
}

func TestAdminCanUploadAppAPKsAndUpdateConfig(t *testing.T) {
	app := newAuthGateTestApp(t)
	app.cfg.DatabasePath = filepath.Join(t.TempDir(), "popcorn.db")
	configPath := filepath.Join(t.TempDir(), "config.toml")
	if err := os.WriteFile(configPath, []byte(`
[appUpdate]
tvApkPath = "/tmp/popcorn-tv.apk"
tvVersionCode = 7
tvVersionName = "0.7.0"

[[libraries]]
id = "movies"
name = "Movies"
path = "/media/movies"
type = "movies"
`), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	app.cfg.ConfigPath = configPath
	app.cfg.AppUpdate.TVAPKPath = "/tmp/popcorn-tv.apk"
	app.cfg.AppUpdate.TVVersionCode = 7
	app.cfg.AppUpdate.TVVersionName = "0.7.0"
	user, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username: "admin",
		Password: "secret1",
		IsAdmin:  true,
	})
	if err != nil {
		t.Fatalf("create admin: %v", err)
	}
	token, err := app.auth.CreateSession(context.Background(), user.ID, time.Hour)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	uploadTestAppAPK(t, app, token, "companion", "42", "0.42.0")

	req := httptest.NewRequest(http.MethodGet, "/api/app/companion/update?versionCode=1", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("companion update = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	bodyText := rec.Body.String()
	if !strings.Contains(bodyText, `"available":true`) || !strings.Contains(bodyText, `"versionCode":42`) {
		t.Fatalf("companion update body = %s, want uploaded update", bodyText)
	}
	cfgText, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatalf("read synced config: %v", err)
	}
	for _, want := range []string{
		`tvVersionCode = 7`,
		`companionVersionCode = 42`,
		`companionVersionName = '0.42.0'`,
		`[[libraries]]`,
	} {
		if !strings.Contains(string(cfgText), want) {
			t.Fatalf("synced config missing %q:\n%s", want, string(cfgText))
		}
	}

	uploadTestAppAPK(t, app, token, "tv", "43", "0.43.0")

	req = httptest.NewRequest(http.MethodGet, "/api/app/tv/update?versionCode=1", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("tv update = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	bodyText = rec.Body.String()
	if !strings.Contains(bodyText, `"available":true`) || !strings.Contains(bodyText, `"versionCode":43`) {
		t.Fatalf("tv update body = %s, want uploaded update", bodyText)
	}
	cfgText, err = os.ReadFile(configPath)
	if err != nil {
		t.Fatalf("read synced config after tv upload: %v", err)
	}
	for _, want := range []string{
		`tvVersionCode = 43`,
		`tvVersionName = '0.43.0'`,
		`companionVersionCode = 42`,
		`companionVersionName = '0.42.0'`,
		`[[libraries]]`,
	} {
		if !strings.Contains(string(cfgText), want) {
			t.Fatalf("synced config after tv upload missing %q:\n%s", want, string(cfgText))
		}
	}
}

func TestAdminCanUploadCompanionAPKWithVersionDetectedFromAPK(t *testing.T) {
	_, file, _, _ := runtime.Caller(0)
	apkPath := filepath.Clean(filepath.Join(filepath.Dir(file), "..", "..", "dist", "updater-test", "popcorn-companion-updater-clean.apk"))
	if _, err := os.Stat(apkPath); err != nil {
		t.Skipf("test APK not available: %v", err)
	}

	app := newAuthGateTestApp(t)
	app.cfg.DatabasePath = filepath.Join(t.TempDir(), "popcorn.db")
	user, err := app.auth.CreateUser(context.Background(), auth.CreateUserInput{
		Username: "admin",
		Password: "secret1",
		IsAdmin:  true,
	})
	if err != nil {
		t.Fatalf("create admin: %v", err)
	}
	token, err := app.auth.CreateSession(context.Background(), user.ID, time.Hour)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("apk", "popcorn-companion.apk")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	apk, err := os.Open(apkPath)
	if err != nil {
		t.Fatalf("open test apk: %v", err)
	}
	if _, err := io.Copy(part, apk); err != nil {
		_ = apk.Close()
		t.Fatalf("copy test apk: %v", err)
	}
	if err := apk.Close(); err != nil {
		t.Fatalf("close test apk: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/app/companion/upload", &body)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("upload companion apk with detected version = %d, want %d: %s", rec.Code, http.StatusOK, rec.Body.String())
	}
	bodyText := rec.Body.String()
	if !strings.Contains(bodyText, `"versionCode":29675401`) || !strings.Contains(bodyText, `"versionName":"updater-clean"`) {
		t.Fatalf("upload response = %s, want detected APK version", bodyText)
	}
}

func uploadTestAppAPK(t *testing.T, app *App, token, target, versionCode, versionName string) {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("versionCode", versionCode)
	_ = writer.WriteField("versionName", versionName)
	_ = writer.WriteField("notes", "test build")
	part, err := writer.CreateFormFile("apk", "popcorn.apk")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("fake apk")); err != nil {
		t.Fatalf("write form file: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/app/"+target+"/upload", &body)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	rec := httptest.NewRecorder()
	app.Routes().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("upload %s apk = %d, want %d: %s", target, rec.Code, http.StatusOK, rec.Body.String())
	}
}

func newAuthGateTestApp(t *testing.T) *App {
	t.Helper()
	db, err := database.Open(filepath.Join(t.TempDir(), "popcorn.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Fatalf("close database: %v", err)
		}
	})
	return New(Options{
		Config: config.Config{},
		Log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		Store:  media.NewStore(db),
		Auth:   auth.NewStore(db),
	})
}
