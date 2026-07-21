package server

import "testing"

func TestMergeHistoryEntriesKeepsNewestAndDeduplicates(t *testing.T) {
	newEntries := []watchHistoryEntry{
		{ID: "trakt:3", WatchedAt: "2026-07-21T12:00:00Z"},
		{ID: "trakt:2", WatchedAt: "2026-07-20T12:00:00Z"},
	}
	cachedEntries := []watchHistoryEntry{
		{ID: "trakt:2", WatchedAt: "2026-07-20T12:00:00Z"},
		{ID: "trakt:1", WatchedAt: "2026-07-19T12:00:00Z"},
	}
	merged := mergeHistoryEntries(newEntries, cachedEntries)
	if len(merged) != 3 {
		t.Fatalf("len(merged) = %d, want 3", len(merged))
	}
	for index, want := range []string{"trakt:3", "trakt:2", "trakt:1"} {
		if merged[index].ID != want {
			t.Fatalf("merged[%d].ID = %q, want %q", index, merged[index].ID, want)
		}
	}
}
