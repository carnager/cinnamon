# Subtitle extraction

Notes for anyone touching subtitle delivery. The short version: **popcornd reads
embedded text subtitles through the container's own Cues index, and a file whose
index omits its subtitle track falls back to a path slow enough to break
playback.**

## Why this matters (the bug it caused)

The Android TV app sideloads subtitles as a separate `MediaItem.SubtitleConfiguration`
(`android-tv/.../PlayerScreen.kt`, `playbackMediaItem`) whenever a plan uses HLS.
Direct play does not — it uses the track embedded in the stream.

ExoPlayer merges that sideloaded source with the HLS source and reports the
merged buffer as the **minimum** of the two. A `SingleSampleMediaSource` reports
a buffered position of zero until its whole response has been read. So playback
cannot start until the entire VTT has arrived.

If serving that VTT takes ~60s (see below), the client's stall watchdog replans
first, which cancels the request and starts the wait over. It never converges:
the player sits in `exo-buffering`, the bandwidth ladder walks down, and the
position advances by exactly one segment per restart. Turning subtitles off makes
it play, because no merged source is created.

Symptom in the journal on gemenon — the same item replanning every 30-40s with
`playerState=exo-buffering` throughout:

    journalctl --user -u popcornd | grep "client log" | grep usesHls=true

## What makes extraction fast

Matroska interleaves subtitle blocks with video across every cluster. Converting
a track with ffmpeg therefore demuxes the **whole file**, however small the
output:

| request | elapsed | payload |
|---|---|---|
| `start=0`, 9.2 GB remux over the network | 64 s | 616 bytes |
| `start=30` | 42 s | 616 bytes |
| `start=1800` | 5 s | 428 bytes |

An input `-ss` only skips the head; the tail is still read to EOF. That is why
seeking at the input (commit `e6749d6`) helped a mid-film resume and did nothing
for a track selected at the start of a film.

**The file already knows where the blocks are.** Matroska's Cues element records
a cluster byte offset per indexed track, and most muxers write those entries for
text subtitle tracks. Seeking straight to them turns the extraction into a dozen
reads:

    CueTrackPositions per TrackNumber: {1: 779, 4: 12}   <- track 4 is the subtitles

On the same 9.2 GB file: **464 ms instead of 42.7 s**, byte-identical output.

That is what `internal/server/mkvsubs.go` does. `subtitle()` in
`internal/server/hls.go` tries it first and falls back to the old streamed
ffmpeg conversion when a file cannot be read that way.

## What a file needs to qualify

All of these, or it takes the slow path:

1. Matroska. Nothing else has an index this code reads.
2. A SeekHead pointing at `Tracks` and `Cues`.
3. A **text** subtitle track — `S_TEXT/UTF8`, `S_TEXT/ASS`, `S_TEXT/SSA`,
   `S_TEXT/WEBVTT`. Bitmap tracks (`S_HDMV/PGS`, `S_VOBSUB`) are irrelevant:
   `isTextSubtitleCodec` rejects them and the endpoint returns 415, so they never
   reach this code at all.
4. `Cues` containing `CueTrackPositions` whose `CueTrack` is that subtitle
   track's number.

Point 4 is the one that fails. Use `scripts/subtitle-index` to find them:

    scripts/subtitle-index scan /mnt/nas/Videos    # writes the lists below
    scripts/subtitle-index fix --dry-run           # show what would be remuxed
    scripts/subtitle-index fix                     # remux them in place
    scripts/subtitle-index check FILE...           # classify one file

`scan` writes three lists into the current directory:

| list | meaning |
|---|---|
| `needs-fix.txt` | text subtitles, not indexed — **these are the problem** |
| `bitmap-subs.txt` | PGS/VobSub only; popcornd cannot use these at all, and nothing here can be fixed by remuxing |
| `no-subtitle-blocks.txt` | a text track carrying no cues; no muxer can index an empty track |

Run it on the storage host — over a network mount it is far slower, and `fix`
rewrites whole files.

## How to fix a file

Remux it with a current mkvmerge — it writes cue entries for text subtitle
tracks, and it is a container rewrite, so no re-encoding:

    mkvmerge -o fixed.mkv broken.mkv

Measured: 314 MB in 0.6 s on local disk, size within 13 KB, streams identical,
duration differing by 64 ms (mkvmerge recomputing from the last frame). The file
then extracts in 2 ms and matches ffmpeg exactly.

`scripts/subtitle-index fix` does this in bulk, verifying each result.

Note the remux changes the file size, and `internal/media/scanner.go` compares
`SizeBytes` as well as mtime, so these re-probe on the next scan. That is
correct — the file really did change.

## What actually produces broken files

Do not trust the muxer name as a proxy; this was got wrong twice. Measured over
this library (8,625 MKVs, 631 genuinely affected):

| muxer | affected files |
|---|---|
| mkvmerge v3-v6 (2010-2013) | 610 |
| mkvmerge v10-v67 (assorted) | 5 |
| HandBrake | 13 |
| other | 3 |
| **ffmpeg (any version)** | **0** |

It correlates with muxer **age**, not with ffmpeg. Current ffmpeg
(`Lavf62.12.102`) *does* write subtitle cue entries — verified by transcoding a
range that actually contains subtitles and re-checking. A clip that happens to
contain no subtitle blocks classifies as "broken" vacuously, which is an easy way
to reach the wrong conclusion; `scripts/subtitle-index` reports those separately
as `no-subtitle-blocks`.

## The remaining hole

A file with no usable index still falls back to the streamed ffmpeg conversion,
which runs under `r.Context()` — so the client's replan cancels it, and the next
attempt starts from zero. It makes no progress across attempts and deadlocks
exactly as the original bug did.

Two changes would close it, and they only work together:

1. Run the extraction under `context.Background()` so a client giving up does not
   kill the work.
2. Cache the result (key on path+size+mtime+index+format, cache the full track at
   `start=0`, apply the `start` offset in Go at serve time).

Optionally warm that cache when a playback plan selects a transcode mode with a
text subtitle, so even the first play is a hit.

Not implemented — the remux keeps the affected set at zero, and nothing in the
current pipeline produces new broken files.

## Verifying changes

Run the subtitle repair queue tests with:

    python3 -B -m unittest discover -s scripts -p 'test_*.py'

`internal/server/mkvsubs_test.go` has unit tests plus a corpus test that diffs
the index path against ffmpeg over real files:

    go test -c -o /tmp/subtest ./internal/server
    scp /tmp/subtest gemenon:/tmp/
    ssh gemenon 'find "/mnt/nas/Videos/Movies" -name "*.mkv" | head -20 > /tmp/corpus.txt
      POPCORN_SUBTITLE_CORPUS=/tmp/corpus.txt /tmp/subtest \
        -test.run TestIndexedSubtitleMatchesFFmpeg -test.v -test.timeout 60m'

It compares both the WebVTT and the ASS output, cue by cue. It is worth running
against real files after any change here — it caught three bugs that unit tests
did not: dropped `{\i1}` italics, a malformed empty ASS `Layer` field, and end
times computed as `round(start+duration)` where ffmpeg uses
`round(start)+round(duration)`.
