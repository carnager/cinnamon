package media

import (
	"bytes"
	"image"
	"image/color"
	"testing"
)

// synthRGBA builds a deterministic test image. It must stay byte-identical to
// synth() in the generator script that produced the golden vectors below, or
// the comparison proves nothing.
func synthRGBA(w, h int, opaque bool) []byte {
	rgba := make([]byte, w*h*4)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			o := (y*w + x) * 4
			rgba[o] = byte((x*7 + y*3) % 256)
			rgba[o+1] = byte((x*x + y) % 256)
			rgba[o+2] = byte((x + y*11) % 256)
			if opaque {
				rgba[o+3] = 255
			} else {
				rgba[o+3] = byte((x*5 + y*9) % 256)
			}
		}
	}
	return rgba
}

// TestRGBAToThumbHashMatchesReference pins the encoder to the upstream
// implementation. The golden vectors were produced by running the npm
// `thumbhash` package (v0.1.1, the same one browsers decode with) over the
// images synthRGBA generates. A mismatch here means placeholders would decode
// to the wrong pixels client-side, so these bytes are a compatibility
// contract, not a snapshot to be re-blessed when the encoder changes.
func TestRGBAToThumbHashMatchesReference(t *testing.T) {
	cases := []struct {
		name   string
		w, h   int
		opaque bool
		want   []byte
	}{
		{"portrait_opaque", 32, 48, true, []byte{0xde, 0x27, 0x06, 0x25, 0x0a, 0x01, 0x85, 0x24, 0x89, 0x56, 0x98, 0x15, 0x77, 0x93, 0xc7, 0x77, 0x01, 0x3c, 0xd8, 0x95, 0x70}},
		{"landscape_opaque", 60, 40, true, []byte{0x1e, 0x18, 0x0a, 0x0d, 0x86, 0x63, 0x50, 0x77, 0x74, 0x75, 0x89, 0x66, 0x97, 0x18, 0x88, 0x99, 0x33, 0x7d, 0xbf, 0x4f, 0x88}},
		{"square_alpha", 40, 40, false, []byte{0x9e, 0x17, 0x86, 0x15, 0x04, 0x18, 0x07, 0x65, 0x67, 0xa7, 0x77, 0x1a, 0x99, 0x02, 0x3e, 0xfe, 0xc3, 0x71, 0x64, 0x78, 0x88, 0x76, 0x09, 0x35, 0x74}},
		{"tiny", 1, 1, true, []byte{0x00, 0x08, 0x02, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
		{"max_opaque", 100, 100, true, []byte{0x1f, 0x08, 0x02, 0x0f, 0x04, 0x42, 0x33, 0x50, 0x56, 0x48, 0x3a, 0x33, 0x36, 0x65, 0x57, 0x48, 0x76, 0x58, 0x41, 0x01, 0xfa, 0xbb, 0x7f, 0x06}},
		{"thin_alpha", 100, 7, false, []byte{0xdb, 0xf8, 0x81, 0x11, 0x82, 0x28, 0xa0, 0x34, 0x82, 0x99, 0x87, 0x4f, 0x7d, 0xf7, 0x9a, 0x85, 0x84, 0x30, 0x67, 0x67, 0x87, 0x77, 0x77}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := RGBAToThumbHash(tc.w, tc.h, synthRGBA(tc.w, tc.h, tc.opaque))
			if !bytes.Equal(got, tc.want) {
				t.Fatalf("thumbhash mismatch\n got %#v\nwant %#v", got, tc.want)
			}
		})
	}
}

func TestRGBAToThumbHashRejectsBadInput(t *testing.T) {
	cases := []struct {
		name string
		w, h int
		rgba []byte
	}{
		{"zero width", 0, 10, nil},
		{"zero height", 10, 0, nil},
		{"too wide", 101, 10, make([]byte, 101*10*4)},
		{"too tall", 10, 101, make([]byte, 10*101*4)},
		{"short buffer", 10, 10, make([]byte, 10*10*4-1)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := RGBAToThumbHash(tc.w, tc.h, tc.rgba); got != nil {
				t.Fatalf("got %v, want nil", got)
			}
		})
	}
}

// TestImageToThumbHashDownscales covers the path real artwork takes: a poster
// far larger than 100x100 must still produce a hash, and the encoder must see
// dimensions inside the reference's limit.
func TestImageToThumbHashDownscales(t *testing.T) {
	img := image.NewRGBA(image.Rect(0, 0, 1000, 1500))
	for y := 0; y < 1500; y++ {
		for x := 0; x < 1000; x++ {
			img.Set(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 0x40, A: 255})
		}
	}
	if got := ImageToThumbHash(img); len(got) == 0 {
		t.Fatal("no hash produced for oversized image")
	}
}

// TestImageToThumbHashSolidColour checks the hash actually carries the image's
// colour: a solid red poster and a solid blue one must not encode the same.
func TestImageToThumbHashSolidColour(t *testing.T) {
	solid := func(c color.RGBA) []byte {
		img := image.NewRGBA(image.Rect(0, 0, 200, 300))
		for y := 0; y < 300; y++ {
			for x := 0; x < 200; x++ {
				img.Set(x, y, c)
			}
		}
		return ImageToThumbHash(img)
	}
	red := solid(color.RGBA{R: 220, G: 30, B: 30, A: 255})
	blue := solid(color.RGBA{R: 30, G: 30, B: 220, A: 255})
	if len(red) == 0 || len(blue) == 0 {
		t.Fatal("solid image produced no hash")
	}
	if bytes.Equal(red, blue) {
		t.Fatal("red and blue encoded identically")
	}
}

func TestFitWithin(t *testing.T) {
	cases := []struct {
		name         string
		w, h, limit  int
		wantW, wantH int
	}{
		{"already small", 40, 60, 100, 40, 60},
		{"exact", 100, 100, 100, 100, 100},
		{"tall poster", 1000, 1500, 100, 66, 100},
		{"wide backdrop", 1920, 1080, 100, 100, 56},
		{"extreme ratio clamps to 1", 5000, 3, 100, 100, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			gotW, gotH := fitWithin(tc.w, tc.h, tc.limit)
			if gotW != tc.wantW || gotH != tc.wantH {
				t.Fatalf("got %dx%d, want %dx%d", gotW, gotH, tc.wantW, tc.wantH)
			}
		})
	}
}
