package server

import (
	"path/filepath"
	"runtime"
	"testing"
)

func TestAPKVersionFromBadging(t *testing.T) {
	code, name, err := apkVersionFromBadging("package: name='dev.popcorn.companion' versionCode='29675401' versionName='updater-clean' platformBuildVersionName='16'\n")
	if err != nil {
		t.Fatalf("apkVersionFromBadging: %v", err)
	}
	if code != 29675401 || name != "updater-clean" {
		t.Fatalf("apk version = %d %q, want 29675401 updater-clean", code, name)
	}
}

func TestAPKVersionFromBadgingRejectsMissingMetadata(t *testing.T) {
	if _, _, err := apkVersionFromBadging("package: name='dev.popcorn.companion'\n"); err == nil {
		t.Fatalf("apkVersionFromBadging accepted badging output without version metadata")
	}
}

func TestAPKVersionFromManifest(t *testing.T) {
	_, file, _, _ := runtime.Caller(0)
	apkPath := filepath.Clean(filepath.Join(filepath.Dir(file), "..", "..", "dist", "v0.1.0-39-g6ce8f7d-dirty", "popcorn-companion-v0.1.0-39-g6ce8f7d-dirty.apk"))
	code, name, err := apkVersionFromManifest(apkPath)
	if err != nil {
		t.Fatalf("apkVersionFromManifest: %v", err)
	}
	if code <= 0 || name != "v0.1.0-39-g6ce8f7d-dirty" {
		t.Fatalf("manifest APK version = %d %q, want positive code and release name", code, name)
	}
}
