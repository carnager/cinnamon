package media

// ThumbHash encoding — a port of Evan Wallace's reference implementation
// (https://github.com/evanw/thumbhash, MIT). Vendored rather than pulled in as
// a dependency: it is ~150 lines of DCT and the server deliberately keeps its
// module graph small.
//
// A thumbhash is a ~25-byte lossy preview of an image. Clients decode it to a
// blurry data URL and paint it instantly while the real artwork loads, so
// posters fade in over the right colours instead of popping out of grey boxes.
//
// The reference is the interoperability contract: any deviation produces
// placeholders that decode wrong in the browser. thumbhash_test.go checks the
// output byte-for-byte against the npm `thumbhash` package.

import (
	"image"

	"math"

	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
)

// thumbHashMaxDim bounds the working image. The reference refuses anything
// larger than 100x100 — encoding more is slow with no visible benefit, since
// the hash only ever retains a handful of DCT coefficients.
const thumbHashMaxDim = 100

// maxBoxSamples caps how many source pixels per axis the downscaler averages
// into one destination pixel. Four is enough to kill aliasing at any realistic
// poster size while keeping the cost proportional to the 100x100 output.
const maxBoxSamples = 4

// RGBAToThumbHash encodes a w*h RGBA buffer (row-major, 4 bytes per pixel, RGB
// *not* premultiplied by alpha) into thumbhash bytes. It returns nil when the
// dimensions are unusable or do not match the buffer length.
func RGBAToThumbHash(w, h int, rgba []byte) []byte {
	if w <= 0 || h <= 0 || w > thumbHashMaxDim || h > thumbHashMaxDim || len(rgba) != w*h*4 {
		return nil
	}

	// Average colour, weighted by alpha. Transparent pixels must not drag the
	// average toward whatever RGB happens to sit under them.
	var avgR, avgG, avgB, avgA float64
	for i, j := 0, 0; i < w*h; i, j = i+1, j+4 {
		alpha := float64(rgba[j+3]) / 255
		avgR += alpha / 255 * float64(rgba[j])
		avgG += alpha / 255 * float64(rgba[j+1])
		avgB += alpha / 255 * float64(rgba[j+2])
		avgA += alpha
	}
	if avgA > 0 {
		avgR /= avgA
		avgG /= avgA
		avgB /= avgA
	}

	hasAlpha := avgA < float64(w*h)
	// Alpha costs coefficients, so a translucent image gets a coarser
	// luminance grid to keep the hash the same size.
	lLimit := 7.0
	if hasAlpha {
		lLimit = 5.0
	}
	maxDim := float64(max(w, h))
	lx := max(1, int(math.Round(lLimit*float64(w)/maxDim)))
	ly := max(1, int(math.Round(lLimit*float64(h)/maxDim)))

	// LPQA: luminance, yellow-blue, red-green, alpha — composited over the
	// average colour so transparent regions blur into the image rather than
	// into black.
	l := make([]float64, w*h)
	p := make([]float64, w*h)
	q := make([]float64, w*h)
	a := make([]float64, w*h)
	for i, j := 0, 0; i < w*h; i, j = i+1, j+4 {
		alpha := float64(rgba[j+3]) / 255
		r := avgR*(1-alpha) + alpha/255*float64(rgba[j])
		g := avgG*(1-alpha) + alpha/255*float64(rgba[j+1])
		b := avgB*(1-alpha) + alpha/255*float64(rgba[j+2])
		l[i] = (r + g + b) / 3
		p[i] = (r+g)/2 - b
		q[i] = r - g
		a[i] = alpha
	}

	encode := func(channel []float64, nx, ny int) (dc float64, ac []float64, scale float64) {
		fx := make([]float64, w)
		for cy := 0; cy < ny; cy++ {
			// Triangular coefficient selection: high frequencies in both
			// axes at once are dropped, which is where the size saving is.
			for cx := 0; cx*ny < nx*(ny-cy); cx++ {
				f := 0.0
				for x := 0; x < w; x++ {
					fx[x] = math.Cos(math.Pi / float64(w) * float64(cx) * (float64(x) + 0.5))
				}
				for y := 0; y < h; y++ {
					fy := math.Cos(math.Pi / float64(h) * float64(cy) * (float64(y) + 0.5))
					for x := 0; x < w; x++ {
						f += channel[x+y*w] * fx[x] * fy
					}
				}
				f /= float64(w * h)
				if cx > 0 || cy > 0 {
					ac = append(ac, f)
					scale = math.Max(scale, math.Abs(f))
				} else {
					dc = f
				}
			}
		}
		if scale > 0 {
			for i := range ac {
				ac[i] = 0.5 + 0.5/scale*ac[i]
			}
		}
		return dc, ac, scale
	}

	lDC, lAC, lScale := encode(l, max(3, lx), max(3, ly))
	pDC, pAC, pScale := encode(p, 3, 3)
	qDC, qAC, qScale := encode(q, 3, 3)
	var aDC, aScale float64
	var aAC []float64
	if hasAlpha {
		aDC, aAC, aScale = encode(a, 5, 5)
	}

	isLandscape := w > h
	alphaBit, landscapeBit := 0, 0
	if hasAlpha {
		alphaBit = 1
	}
	if isLandscape {
		landscapeBit = 1
	}
	// Every rounded term below is non-negative, so Go's round-half-away-from-
	// zero and JavaScript's round-half-up agree. Do not introduce a signed
	// term here without switching to math.Floor(x + 0.5).
	header24 := round(63*lDC) |
		round(31.5+31.5*pDC)<<6 |
		round(31.5+31.5*qDC)<<12 |
		round(31*lScale)<<18 |
		alphaBit<<23
	lDim := lx
	if isLandscape {
		lDim = ly
	}
	header16 := lDim |
		round(63*pScale)<<3 |
		round(63*qScale)<<9 |
		landscapeBit<<15

	acStart := 5
	if hasAlpha {
		acStart = 6
	}
	acCount := len(lAC) + len(pAC) + len(qAC) + len(aAC)
	hash := make([]byte, acStart+(acCount+1)/2)
	hash[0] = byte(header24 & 255)
	hash[1] = byte((header24 >> 8) & 255)
	hash[2] = byte(header24 >> 16)
	hash[3] = byte(header16 & 255)
	hash[4] = byte(header16 >> 8)
	if hasAlpha {
		hash[5] = byte(round(15*aDC) | round(15*aScale)<<4)
	}

	// Varying factors, two 4-bit nibbles per byte.
	groups := [][]float64{lAC, pAC, qAC}
	if hasAlpha {
		groups = append(groups, aAC)
	}
	acIndex := 0
	for _, group := range groups {
		for _, f := range group {
			hash[acStart+(acIndex>>1)] |= byte(round(15*f) << ((acIndex & 1) << 2))
			acIndex++
		}
	}
	return hash
}

func round(v float64) int { return int(math.Round(v)) }

// ImageToThumbHash downscales img to fit within 100x100 and encodes it. It
// returns nil when the image has no pixels.
func ImageToThumbHash(img image.Image) []byte {
	bounds := img.Bounds()
	sw, sh := bounds.Dx(), bounds.Dy()
	if sw <= 0 || sh <= 0 {
		return nil
	}

	dw, dh := fitWithin(sw, sh, thumbHashMaxDim)

	// Box filter: every destination pixel averages the source rectangle that
	// maps onto it. Nearest-neighbour would alias hard at poster-to-100px
	// ratios, and thumbhash keeps so few coefficients that aliasing shows up
	// as a visibly wrong average colour.
	//
	// Sampling is capped per axis rather than reading every source pixel: a
	// 1000x1500 poster has 1.5M pixels but the hash cannot resolve more than
	// a 100x100 grid, so averaging every one is work nobody can see. The cap
	// makes the cost a function of the output size, not the input size.
	dst := make([]byte, dw*dh*4)
	for dy := 0; dy < dh; dy++ {
		y0 := dy * sh / dh
		y1 := max(y0+1, (dy+1)*sh/dh)
		stepY := max(1, (y1-y0+maxBoxSamples-1)/maxBoxSamples)
		for dx := 0; dx < dw; dx++ {
			x0 := dx * sw / dw
			x1 := max(x0+1, (dx+1)*sw/dw)
			stepX := max(1, (x1-x0+maxBoxSamples-1)/maxBoxSamples)

			var sr, sg, sb, sa, n int
			for y := y0; y < y1; y += stepY {
				for x := x0; x < x1; x += stepX {
					// color.Color.RGBA reports 16-bit alpha-premultiplied
					// components regardless of the decoded colour model.
					r, g, b, al := img.At(bounds.Min.X+x, bounds.Min.Y+y).RGBA()
					sr += int(r >> 8)
					sg += int(g >> 8)
					sb += int(b >> 8)
					sa += int(al >> 8)
					n++
				}
			}
			o := (dy*dw + dx) * 4
			// image.RGBA is alpha-premultiplied, which is exactly the space
			// an area average should be computed in; thumbhash wants straight
			// RGB, so undo the premultiplication once, afterwards.
			ar, ag, ab, aa := sr/n, sg/n, sb/n, sa/n
			if aa > 0 && aa < 255 {
				ar = min(255, ar*255/aa)
				ag = min(255, ag*255/aa)
				ab = min(255, ab*255/aa)
			}
			dst[o] = byte(ar)
			dst[o+1] = byte(ag)
			dst[o+2] = byte(ab)
			dst[o+3] = byte(aa)
		}
	}
	return RGBAToThumbHash(dw, dh, dst)
}

// fitWithin scales w x h down to fit in limit x limit, preserving aspect ratio
// and never returning a zero dimension. Images already within the limit are
// returned unchanged.
func fitWithin(w, h, limit int) (int, int) {
	if w <= limit && h <= limit {
		return w, h
	}
	if w >= h {
		return limit, max(1, h*limit/w)
	}
	return max(1, w*limit/h), limit
}
