package server

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

// Matroska interleaves subtitle blocks with video across every cluster, so
// converting a track with ffmpeg demuxes the whole file: on a 9 GB remux over
// the network that is a minute of sustained reading to emit a few hundred bytes
// of text, against the same file a transcode session is streaming from. Seeking
// at the input only skips the head — the tail is still read to EOF — so it does
// nothing for a track selected at the start of a film.
//
// The file already carries the answer. Its Cues index records the cluster byte
// offset of each subtitle block, and muxers that write those entries turn the
// extraction into a dozen seeks. A file without those entries falls back to
// conversion; every error here means "fall back", not "fail".
var errNoSubtitleIndex = errors.New("no usable subtitle index")

const (
	idEBMLHeader        = 0x1A45DFA3
	idSegment           = 0x18538067
	idSeekHead          = 0x114D9B74
	idSeek              = 0x4DBB
	idSeekID            = 0x53AB
	idSeekPosition      = 0x53AC
	idInfo              = 0x1549A966
	idTimestampScale    = 0x2AD7B1
	idTracks            = 0x1654AE6B
	idTrackEntry        = 0xAE
	idTrackNumber       = 0xD7
	idTrackType         = 0x83
	idCodecID           = 0x86
	idCodecPrivate      = 0x63A2
	idCues              = 0x1C53BB6B
	idCuePoint          = 0xBB
	idCueTrackPositions = 0xB7
	idCueTrack          = 0xF7
	idCueClusterPos     = 0xF1
	idCluster           = 0x1F43B675
	idClusterTimestamp  = 0xE7
	idSimpleBlock       = 0xA3
	idBlockGroup        = 0xA0
	idBlock             = 0xA1
	idBlockDuration     = 0x9B

	trackTypeSubtitle = 17
)

// subtitleCue is one extracted subtitle event, in milliseconds on the file's
// own timeline. An end of zero means the block carried no duration.
type subtitleCue struct {
	StartMS int64
	EndMS   int64
	Text    string
}

// matroskaSubtitleTrack is a text subtitle track located in the Tracks element.
type matroskaSubtitleTrack struct {
	Number       uint64
	CodecID      string
	CodecPrivate []byte
}

type ebmlReader struct {
	f   *os.File
	br  *bufio.Reader
	pos int64
}

func newEBMLReader(f *os.File) *ebmlReader {
	return &ebmlReader{f: f, br: bufio.NewReaderSize(f, 64<<10)}
}

func (r *ebmlReader) seek(off int64) error {
	if _, err := r.f.Seek(off, io.SeekStart); err != nil {
		return err
	}
	r.br.Reset(r.f)
	r.pos = off
	return nil
}

func (r *ebmlReader) readFull(n int64) ([]byte, error) {
	// A corrupt or misparsed length must not turn into a huge allocation.
	if n < 0 || n > 64<<20 {
		return nil, errNoSubtitleIndex
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r.br, buf); err != nil {
		return nil, err
	}
	r.pos += n
	return buf, nil
}

// skip advances past an element's payload. Clusters run to hundreds of
// megabytes, which is more than a single Discard takes, so drain in chunks
// rather than silently stopping short and misreading everything after it.
func (r *ebmlReader) skip(n int64) error {
	if n < 0 {
		return errNoSubtitleIndex
	}
	r.pos += n
	for n > 0 {
		chunk := n
		if chunk > 1<<30 {
			chunk = 1 << 30
		}
		if _, err := r.br.Discard(int(chunk)); err != nil {
			return err
		}
		n -= chunk
	}
	return nil
}

// id reads an EBML element ID, keeping the length marker so the value matches
// the canonical constants above.
func (r *ebmlReader) id() (uint32, error) {
	first, err := r.br.ReadByte()
	if err != nil {
		return 0, err
	}
	r.pos++
	width := 0
	for i := 0; i < 4; i++ {
		if first&(0x80>>i) != 0 {
			width = i + 1
			break
		}
	}
	if width == 0 {
		return 0, errNoSubtitleIndex
	}
	value := uint32(first)
	for i := 1; i < width; i++ {
		b, err := r.br.ReadByte()
		if err != nil {
			return 0, err
		}
		r.pos++
		value = value<<8 | uint32(b)
	}
	return value, nil
}

// size reads an EBML length. Unknown-size elements (all value bits set) appear
// in live-muxed files; the caller cannot bound them, so report it.
func (r *ebmlReader) size() (int64, bool, error) {
	first, err := r.br.ReadByte()
	if err != nil {
		return 0, false, err
	}
	r.pos++
	width := 0
	for i := 0; i < 8; i++ {
		if first&(0x80>>i) != 0 {
			width = i + 1
			break
		}
	}
	if width == 0 {
		return 0, false, errNoSubtitleIndex
	}
	value := uint64(first) & (0xFF >> uint(width))
	unknownBits := uint64(1)<<(uint(width)*7) - 1
	for i := 1; i < width; i++ {
		b, err := r.br.ReadByte()
		if err != nil {
			return 0, false, err
		}
		r.pos++
		value = value<<8 | uint64(b)
	}
	if value == unknownBits {
		return 0, true, nil
	}
	if value > 1<<62 {
		return 0, false, errNoSubtitleIndex
	}
	return int64(value), false, nil
}

func (r *ebmlReader) uint(n int64) (uint64, error) {
	b, err := r.readFull(n)
	if err != nil {
		return 0, err
	}
	var value uint64
	for _, c := range b {
		value = value<<8 | uint64(c)
	}
	return value, nil
}

// element reads one id/size pair, rejecting the unknown-size elements the
// callers below cannot walk.
func (r *ebmlReader) element() (uint32, int64, error) {
	id, err := r.id()
	if err != nil {
		return 0, 0, err
	}
	size, unknown, err := r.size()
	if err != nil {
		return 0, 0, err
	}
	if unknown {
		return 0, 0, errNoSubtitleIndex
	}
	return id, size, nil
}

// matroskaSegment locates the Segment payload and the offsets the SeekHead
// records for Tracks, Cues and Info. Positions in a SeekHead are relative to
// the start of the Segment payload.
type matroskaSegment struct {
	base       int64
	tracks     int64
	cues       int64
	info       int64
	timeScale  uint64
	haveTracks bool
	haveCues   bool
}

func readMatroskaSegment(r *ebmlReader) (*matroskaSegment, error) {
	if err := r.seek(0); err != nil {
		return nil, err
	}
	id, size, err := r.element()
	if err != nil {
		return nil, err
	}
	if id != idEBMLHeader {
		return nil, errNoSubtitleIndex
	}
	if err := r.skip(size); err != nil {
		return nil, err
	}
	// The Segment may declare an unknown size in a streamed file; every offset
	// below is relative to its payload, which still starts here either way.
	if id, err = r.id(); err != nil {
		return nil, err
	}
	if _, _, err := r.size(); err != nil {
		return nil, err
	}
	if id != idSegment {
		return nil, errNoSubtitleIndex
	}
	seg := &matroskaSegment{base: r.pos, timeScale: 1_000_000}

	// The SeekHead is the first element of the Segment in every file this
	// matters for; walking clusters to find Cues would defeat the point.
	id, size, err = r.element()
	if err != nil {
		return nil, err
	}
	if id != idSeekHead {
		return nil, errNoSubtitleIndex
	}
	end := r.pos + size
	for r.pos < end {
		id, size, err := r.element()
		if err != nil {
			return nil, err
		}
		if id != idSeek {
			if err := r.skip(size); err != nil {
				return nil, err
			}
			continue
		}
		seekEnd := r.pos + size
		var target uint32
		var position int64
		for r.pos < seekEnd {
			id, size, err := r.element()
			if err != nil {
				return nil, err
			}
			switch id {
			case idSeekID:
				b, err := r.readFull(size)
				if err != nil {
					return nil, err
				}
				for _, c := range b {
					target = target<<8 | uint32(c)
				}
			case idSeekPosition:
				v, err := r.uint(size)
				if err != nil {
					return nil, err
				}
				position = int64(v)
			default:
				if err := r.skip(size); err != nil {
					return nil, err
				}
			}
		}
		switch target {
		case idTracks:
			seg.tracks, seg.haveTracks = position, true
		case idCues:
			seg.cues, seg.haveCues = position, true
		case idInfo:
			seg.info = position
		}
	}
	if !seg.haveTracks || !seg.haveCues {
		return nil, errNoSubtitleIndex
	}
	if seg.info > 0 {
		if scale, err := readTimestampScale(r, seg.base+seg.info); err == nil && scale > 0 {
			seg.timeScale = scale
		}
	}
	return seg, nil
}

func readTimestampScale(r *ebmlReader, at int64) (uint64, error) {
	if err := r.seek(at); err != nil {
		return 0, err
	}
	id, size, err := r.element()
	if err != nil {
		return 0, err
	}
	if id != idInfo {
		return 0, errNoSubtitleIndex
	}
	end := r.pos + size
	for r.pos < end {
		id, size, err := r.element()
		if err != nil {
			return 0, err
		}
		if id == idTimestampScale {
			return r.uint(size)
		}
		if err := r.skip(size); err != nil {
			return 0, err
		}
	}
	return 0, errNoSubtitleIndex
}

// readSubtitleTrack finds the track ffmpeg would expose at streamIndex.
// ffmpeg numbers streams in Tracks order, so the Nth TrackEntry is stream N.
func readSubtitleTrack(r *ebmlReader, seg *matroskaSegment, streamIndex int) (*matroskaSubtitleTrack, error) {
	if err := r.seek(seg.base + seg.tracks); err != nil {
		return nil, err
	}
	id, size, err := r.element()
	if err != nil {
		return nil, err
	}
	if id != idTracks {
		return nil, errNoSubtitleIndex
	}
	end := r.pos + size
	ordinal := 0
	for r.pos < end {
		id, size, err := r.element()
		if err != nil {
			return nil, err
		}
		if id != idTrackEntry {
			if err := r.skip(size); err != nil {
				return nil, err
			}
			continue
		}
		entryEnd := r.pos + size
		track := &matroskaSubtitleTrack{}
		var trackType uint64
		for r.pos < entryEnd {
			id, size, err := r.element()
			if err != nil {
				return nil, err
			}
			switch id {
			case idTrackNumber:
				if track.Number, err = r.uint(size); err != nil {
					return nil, err
				}
			case idTrackType:
				if trackType, err = r.uint(size); err != nil {
					return nil, err
				}
			case idCodecID:
				b, err := r.readFull(size)
				if err != nil {
					return nil, err
				}
				track.CodecID = strings.TrimRight(string(b), "\x00")
			case idCodecPrivate:
				if track.CodecPrivate, err = r.readFull(size); err != nil {
					return nil, err
				}
			default:
				if err := r.skip(size); err != nil {
					return nil, err
				}
			}
		}
		if ordinal == streamIndex {
			// The caller resolved streamIndex from ffprobe. If the element at
			// that ordinal is not a subtitle track the two disagree, and
			// guessing would emit the wrong track's text.
			if trackType != trackTypeSubtitle {
				return nil, errNoSubtitleIndex
			}
			return track, nil
		}
		ordinal++
	}
	return nil, errNoSubtitleIndex
}

// readSubtitleClusterOffsets collects the cluster positions the Cues index
// records for a track. Some files index only video, leaving no offsets for
// subtitle extraction.
func readSubtitleClusterOffsets(r *ebmlReader, seg *matroskaSegment, trackNumber uint64) ([]int64, error) {
	if err := r.seek(seg.base + seg.cues); err != nil {
		return nil, err
	}
	id, size, err := r.element()
	if err != nil {
		return nil, err
	}
	if id != idCues {
		return nil, errNoSubtitleIndex
	}
	end := r.pos + size
	seen := map[int64]struct{}{}
	var offsets []int64
	for r.pos < end {
		id, size, err := r.element()
		if err != nil {
			return nil, err
		}
		if id != idCuePoint {
			if err := r.skip(size); err != nil {
				return nil, err
			}
			continue
		}
		pointEnd := r.pos + size
		for r.pos < pointEnd {
			id, size, err := r.element()
			if err != nil {
				return nil, err
			}
			if id != idCueTrackPositions {
				if err := r.skip(size); err != nil {
					return nil, err
				}
				continue
			}
			posEnd := r.pos + size
			var track uint64
			var cluster int64
			for r.pos < posEnd {
				id, size, err := r.element()
				if err != nil {
					return nil, err
				}
				switch id {
				case idCueTrack:
					if track, err = r.uint(size); err != nil {
						return nil, err
					}
				case idCueClusterPos:
					v, err := r.uint(size)
					if err != nil {
						return nil, err
					}
					cluster = int64(v)
				default:
					if err := r.skip(size); err != nil {
						return nil, err
					}
				}
			}
			if track == trackNumber {
				if _, dup := seen[cluster]; !dup {
					seen[cluster] = struct{}{}
					offsets = append(offsets, cluster)
				}
			}
		}
	}
	if len(offsets) == 0 {
		return nil, errNoSubtitleIndex
	}
	return offsets, nil
}

// readClusterCues pulls one cluster's blocks for the wanted track.
func readClusterCues(r *ebmlReader, at int64, trackNumber uint64, scale uint64, out *[]subtitleCue) error {
	if err := r.seek(at); err != nil {
		return err
	}
	id, size, err := r.element()
	if err != nil {
		return err
	}
	if id != idCluster {
		return errNoSubtitleIndex
	}
	end := r.pos + size
	var clusterTime uint64
	for r.pos < end {
		id, size, err := r.element()
		if err != nil {
			return err
		}
		switch id {
		case idClusterTimestamp:
			if clusterTime, err = r.uint(size); err != nil {
				return err
			}
		case idSimpleBlock:
			cue, ok, err := readBlock(r, size, trackNumber, clusterTime, scale)
			if err != nil {
				return err
			}
			if ok {
				*out = append(*out, cue)
			}
		case idBlockGroup:
			groupEnd := r.pos + size
			var cue subtitleCue
			var have bool
			var duration uint64
			for r.pos < groupEnd {
				id, size, err := r.element()
				if err != nil {
					return err
				}
				switch id {
				case idBlock:
					cue, have, err = readBlock(r, size, trackNumber, clusterTime, scale)
					if err != nil {
						return err
					}
				case idBlockDuration:
					if duration, err = r.uint(size); err != nil {
						return err
					}
				default:
					if err := r.skip(size); err != nil {
						return err
					}
				}
			}
			if have {
				if duration > 0 {
					cue.EndMS = cue.StartMS + int64(duration*scale/1_000_000)
				}
				*out = append(*out, cue)
			}
		default:
			if err := r.skip(size); err != nil {
				return err
			}
		}
	}
	return nil
}

// readBlock decodes a (Simple)Block, returning false when it belongs to another
// track. Laced blocks pack several frames into one payload; subtitles are never
// laced in practice, and misreading one would corrupt the text, so bail out.
func readBlock(r *ebmlReader, size int64, trackNumber uint64, clusterTime, scale uint64) (subtitleCue, bool, error) {
	buf, err := r.readFull(size)
	if err != nil {
		return subtitleCue{}, false, err
	}
	track, n := parseVint(buf)
	if n == 0 || len(buf) < n+3 {
		return subtitleCue{}, false, errNoSubtitleIndex
	}
	if track != trackNumber {
		return subtitleCue{}, false, nil
	}
	relative := int16(binary.BigEndian.Uint16(buf[n : n+2]))
	flags := buf[n+2]
	if flags&0x06 != 0 {
		return subtitleCue{}, false, errNoSubtitleIndex
	}
	text := buf[n+3:]
	timestamp := (int64(clusterTime) + int64(relative)) * int64(scale) / 1_000_000
	if timestamp < 0 {
		timestamp = 0
	}
	return subtitleCue{StartMS: timestamp, Text: string(text)}, true, nil
}

// parseVint reads an EBML variable-length integer from a byte slice, returning
// the value and how many bytes it used.
func parseVint(b []byte) (uint64, int) {
	if len(b) == 0 {
		return 0, 0
	}
	width := 0
	for i := 0; i < 8; i++ {
		if b[0]&(0x80>>i) != 0 {
			width = i + 1
			break
		}
	}
	if width == 0 || len(b) < width {
		return 0, 0
	}
	value := uint64(b[0]) & (0xFF >> uint(width))
	for i := 1; i < width; i++ {
		value = value<<8 | uint64(b[i])
	}
	return value, width
}

// matroskaSubtitleCues extracts a text subtitle track through the file's Cues
// index. It returns errNoSubtitleIndex whenever the file cannot be served this
// way, which is the caller's signal to fall back to the ffmpeg conversion.
func matroskaSubtitleCues(path string, streamIndex int) ([]subtitleCue, *matroskaSubtitleTrack, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, nil, err
	}
	defer f.Close()
	r := newEBMLReader(f)

	seg, err := readMatroskaSegment(r)
	if err != nil {
		return nil, nil, errNoSubtitleIndex
	}
	track, err := readSubtitleTrack(r, seg, streamIndex)
	if err != nil {
		return nil, nil, errNoSubtitleIndex
	}
	offsets, err := readSubtitleClusterOffsets(r, seg, track.Number)
	if err != nil {
		return nil, nil, errNoSubtitleIndex
	}
	var cues []subtitleCue
	for _, off := range offsets {
		if err := readClusterCues(r, seg.base+off, track.Number, seg.timeScale, &cues); err != nil {
			return nil, nil, errNoSubtitleIndex
		}
	}
	if len(cues) == 0 {
		return nil, nil, errNoSubtitleIndex
	}
	sortCues(cues)
	return cues, track, nil
}

func sortCues(cues []subtitleCue) {
	// Cues come out in cluster order, which is already chronological; a stable
	// insertion pass costs nothing and protects against an out-of-order muxer.
	for i := 1; i < len(cues); i++ {
		for j := i; j > 0 && cues[j].StartMS < cues[j-1].StartMS; j-- {
			cues[j], cues[j-1] = cues[j-1], cues[j]
		}
	}
}

// subtitleTextCodec reports whether a Matroska CodecID is one this package can
// render without ffmpeg.
func subtitleTextCodec(codecID string) (plain bool, ssa bool, ok bool) {
	switch strings.ToUpper(strings.TrimSpace(codecID)) {
	case "S_TEXT/UTF8", "S_TEXT/ASCII":
		return true, false, true
	case "S_TEXT/WEBVTT":
		return true, false, true
	case "S_TEXT/ASS", "S_TEXT/SSA":
		return false, true, true
	}
	return false, false, false
}

// renderWebVTT writes cues as WebVTT, dropping everything before startMS and
// rebasing the rest — the same output an input-seeked ffmpeg conversion
// produces, without touching the file again.
func renderWebVTT(cues []subtitleCue, ssa bool, startMS int64) []byte {
	var b strings.Builder
	b.WriteString("WEBVTT\n\n")
	for _, cue := range cues {
		text := cue.Text
		if ssa {
			text = ssaDialogueText(text)
		}
		text = normalizeSubtitleText(text)
		if strings.TrimSpace(text) == "" {
			continue
		}
		end := cue.EndMS
		if end <= cue.StartMS {
			end = cue.StartMS + 3000
		}
		if end <= startMS {
			continue
		}
		start := max(cue.StartMS-startMS, 0)
		b.WriteString(formatVTTTime(start))
		b.WriteString(" --> ")
		b.WriteString(formatVTTTime(end - startMS))
		b.WriteString("\n")
		b.WriteString(text)
		b.WriteString("\n\n")
	}
	return []byte(b.String())
}

// renderASS rebuilds a script from the track's CodecPrivate header and its
// blocks. Matroska strips "Dialogue:" and hoists a ReadOrder field to the
// front, so each block has to be put back into the header's field order.
func renderASS(private []byte, cues []subtitleCue, startMS int64) []byte {
	var b strings.Builder
	header := normalizeSubtitleText(string(stripNULs(append([]byte(nil), private...))))
	b.WriteString(strings.TrimRight(header, "\n"))
	b.WriteString("\n")
	if !strings.Contains(header, "[Events]") {
		b.WriteString("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
	}
	for _, cue := range cues {
		end := cue.EndMS
		if end <= cue.StartMS {
			end = cue.StartMS + 3000
		}
		if end <= startMS {
			continue
		}
		fields := strings.SplitN(normalizeSubtitleText(cue.Text), ",", 9)
		if len(fields) < 9 {
			continue
		}
		start := max(cue.StartMS-startMS, 0)
		// Matroska often leaves Layer empty; a script with a bare leading comma
		// is malformed, and ffmpeg writes the default in its place.
		layer := strings.TrimSpace(fields[1])
		if layer == "" {
			layer = "0"
		}
		// The end is the rounded start plus the rounded duration, not the
		// rounded end: a block carries a start and a duration, and rounding
		// their sum instead lands a centisecond either side of what every other
		// tool writes for the same cue.
		startCentis := assCentis(start)
		endCentis := startCentis + assCentis(end-startMS-start)
		b.WriteString("Dialogue: ")
		b.WriteString(layer)
		b.WriteString(",")
		b.WriteString(formatASSCentis(startCentis))
		b.WriteString(",")
		b.WriteString(formatASSCentis(endCentis))
		for _, f := range fields[2:] {
			b.WriteString(",")
			b.WriteString(f)
		}
		b.WriteString("\n")
	}
	return []byte(b.String())
}

// ssaDialogueText reduces a Matroska ASS block to text a WebVTT renderer can
// show. Positioning and colour overrides are dropped, but italic, bold and
// underline carry meaning a viewer would notice missing, so they become the
// HTML tags WebVTT understands — the same mapping ffmpeg's converter makes.
func ssaDialogueText(block string) string {
	fields := strings.SplitN(block, ",", 9)
	if len(fields) < 9 {
		return block
	}
	text := fields[8]

	var out strings.Builder
	var open []string
	toggle := func(tag string, on bool) {
		idx := -1
		for i, t := range open {
			if t == tag {
				idx = i
				break
			}
		}
		if on == (idx >= 0) {
			return
		}
		if on {
			out.WriteString("<" + tag + ">")
			open = append(open, tag)
			return
		}
		// Close back to the tag so the markup stays properly nested, then
		// reopen whatever was closed on the way.
		reopen := append([]string(nil), open[idx+1:]...)
		for i := len(open) - 1; i >= idx; i-- {
			out.WriteString("</" + open[i] + ">")
		}
		open = append(open[:idx], reopen...)
		for _, t := range reopen {
			out.WriteString("<" + t + ">")
		}
	}

	for i := 0; i < len(text); {
		switch {
		case text[i] == '{':
			end := strings.IndexByte(text[i:], '}')
			if end < 0 {
				out.WriteString(text[i:])
				i = len(text)
				continue
			}
			applyASSOverrides(text[i+1:i+end], toggle)
			i += end + 1
		case text[i] == '\\' && i+1 < len(text):
			switch text[i+1] {
			case 'N', 'n':
				out.WriteString("\n")
			case 'h':
				out.WriteString(" ")
			default:
				out.WriteByte(text[i])
				i++
				continue
			}
			i += 2
		default:
			out.WriteByte(text[i])
			i++
		}
	}
	for i := len(open) - 1; i >= 0; i-- {
		out.WriteString("</" + open[i] + ">")
	}
	return out.String()
}

// applyASSOverrides reads one {...} override block, reporting the styling tags
// it switches on or off. A \b weight of 100–900 is bold when it reaches 700,
// which is how ASS spells a bold font rather than a toggle.
func applyASSOverrides(block string, toggle func(tag string, on bool)) {
	for i := 0; i < len(block); i++ {
		if block[i] != '\\' || i+1 >= len(block) {
			continue
		}
		var tag string
		switch block[i+1] {
		case 'i':
			tag = "i"
		case 'b':
			tag = "b"
		case 'u':
			tag = "u"
		default:
			continue
		}
		j := i + 2
		start := j
		for j < len(block) && block[j] >= '0' && block[j] <= '9' {
			j++
		}
		if j == start {
			continue
		}
		value, err := strconv.Atoi(block[start:j])
		if err != nil {
			continue
		}
		if tag == "b" && value > 1 {
			toggle(tag, value >= 700)
			continue
		}
		toggle(tag, value != 0)
	}
}

// normalizeSubtitleText matches what the ffmpeg path emitted: CRLF collapsed to
// LF, and the NUL Matroska leaves in an ASS header removed.
func normalizeSubtitleText(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	if strings.IndexByte(s, 0) >= 0 {
		s = string(stripNULs([]byte(s)))
	}
	return s
}

func formatVTTTime(ms int64) string {
	if ms < 0 {
		ms = 0
	}
	h := ms / 3_600_000
	m := ms / 60_000 % 60
	s := ms / 1000 % 60
	milli := ms % 1000
	if h > 0 {
		return fmt.Sprintf("%02d:%02d:%02d.%03d", h, m, s, milli)
	}
	return fmt.Sprintf("%02d:%02d.%03d", m, s, milli)
}

// assCentis converts to the centisecond resolution an ASS script stores,
// rounding rather than truncating so a cue does not drift up to 10 ms early
// against the same file's WebVTT.
func assCentis(ms int64) int64 {
	if ms < 0 {
		return 0
	}
	return (ms + 5) / 10
}

func formatASSCentis(cs int64) string {
	if cs < 0 {
		cs = 0
	}
	return fmt.Sprintf("%d:%02d:%02d.%02d", cs/360_000, cs/6_000%60, cs/100%60, cs%100)
}
