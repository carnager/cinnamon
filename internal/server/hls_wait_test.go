package server

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// A fast remux can finish between the file check and the wait: the playlist
// exists and the transcoder has exited. That is success, not a failure.
func TestWaitForFileOrDoneAcceptsFileWrittenBeforeExit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "index.m3u8")
	done := make(chan struct{})
	go func() {
		time.Sleep(10 * time.Millisecond)
		_ = os.WriteFile(path, []byte("#EXTM3U\n"), 0o644)
		close(done)
	}()
	if err := waitForFileOrDone(context.Background(), path, 2*time.Second, done); err != nil {
		t.Fatalf("file was written before exit, got %v", err)
	}
}

func TestWaitForFileOrDoneReportsExitWithoutFile(t *testing.T) {
	done := make(chan struct{})
	close(done)
	path := filepath.Join(t.TempDir(), "index.m3u8")
	if err := waitForFileOrDone(context.Background(), path, 2*time.Second, done); err == nil {
		t.Fatal("expected an error when the transcoder exits without writing the file")
	}
}
