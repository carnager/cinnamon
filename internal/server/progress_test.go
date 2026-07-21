package server

import "testing"

func TestResumeEvidenceThresholdRequiresSettledPlayback(t *testing.T) {
	if got, want := resumeEvidenceThreshold(2*60*60_000), int64(2*60_000); got != want {
		t.Fatalf("movie threshold = %d, want %d", got, want)
	}
	if got, want := resumeEvidenceThreshold(5*60_000), int64(30_000); got != want {
		t.Fatalf("short video threshold = %d, want %d", got, want)
	}
}

func TestCompletionEvidenceThresholdRejectsEndSampling(t *testing.T) {
	if got, want := completionEvidenceThreshold(2*60*60_000), int64(5*60_000); got != want {
		t.Fatalf("movie threshold = %d, want %d", got, want)
	}
	if got, want := completionEvidenceThreshold(6*60_000), int64(2*60_000); got != want {
		t.Fatalf("short video threshold = %d, want %d", got, want)
	}
}
