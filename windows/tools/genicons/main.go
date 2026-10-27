// genicons draws the tinted Wi-Fi-glyph tray icons (plan A.1) and writes
// them as BMP-format .ico files (16/20/32px) for internal/icons to embed.
//
// Usage (repo root/windows): go run ./tools/genicons -out internal/icons
package main

import (
	"bufio"
	"flag"
	"fmt"
	"math"
	"os"
	"path/filepath"
)

type tintDef struct {
	name    string
	r, g, b uint8
}

var tints = []tintDef{
	{"green", 46, 164, 79},   // #2EA44F
	{"amber", 255, 149, 0},   // #FF9500
	{"red", 255, 59, 48},     // #FF3B30
	{"white", 255, 255, 255}, // #FFFFFF
	{"gray", 158, 158, 158},  // #9E9E9E
}

var sizes = []int{16, 20, 32}

type icoEntry struct {
	data  []byte
	w, h  int
}

func main() {
	out := flag.String("out", ".", "output directory for .ico files")
	flag.Parse()
	if err := run(*out); err != nil {
		fmt.Fprintln(os.Stderr, "genicons:", err)
		os.Exit(1)
	}
}

func run(out string) error {
	for _, t := range tints {
		entries := make([]icoEntry, 0, len(sizes))
		for _, s := range sizes {
			data, w, h, err := renderBGRA(t, s)
			if err != nil {
				return err
			}
			entries = append(entries, icoEntry{data, w, h})
		}
		path := filepath.Join(out, t.name+".ico")
		f, err := os.Create(path)
		if err != nil {
			return err
		}
		w := bufio.NewWriter(f)
		if _, err := w.Write(assembleICO(entries)); err != nil {
			f.Close()
			return err
		}
		if err := w.Flush(); err != nil {
			f.Close()
			return err
		}
		if err := f.Close(); err != nil {
			return err
		}
		fmt.Println("wrote", path)
	}
	return nil
}

// glyph returns coverage (0..1) of the Wi-Fi glyph at a pixel: three arc
// segments opening upward + a center dot, 4x4 supersampled for antialiasing.
func glyph(x, y, size float64) float64 {
	const ss = 4
	const thick = 0.075  // stroke width, fraction of icon
	const dotR = 0.05    // center dot radius
	cx, cy := 0.5, 0.80  // glyph origin (the dot)
	arcA, arcB := 45.0, 135.0
	radii := [3]float64{0.17, 0.30, 0.43}
	count := 0
	for sy := 0.0; sy < ss; sy++ {
		for sx := 0.0; sx < ss; sx++ {
			px := (x + (sx+0.5)/ss) / size
			py := (y + (sy+0.5)/ss) / size
			dy := cy - py // flip Y so "up" is up
			dx := px - cx
			d := math.Sqrt(dx*dx + dy*dy)
			if d <= dotR {
				count++
				continue
			}
			deg := math.Atan2(dy, dx) * 180 / math.Pi
			if deg < arcA || deg > arcB {
				continue
			}
			for _, r := range radii {
				if d >= r-thick/2 && d <= r+thick/2 {
					count++
					break
				}
			}
		}
	}
	return float64(count) / (ss * ss)
}

// renderBGRA renders one size into bottom-up 32bpp rows (+ empty AND mask).
func renderBGRA(t tintDef, size int) ([]byte, int, int, error) {
	bgra := make([]byte, size*size*4)
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			a := glyph(float64(x), float64(y), float64(size))
			if a <= 0 {
				continue
			}
			aa := uint8(clamp01(a) * 255)
			i := ((size-1-y)*size + x) * 4
			// Premultiplied BGRA, bottom-up.
			bgra[i+0] = uint8(float64(t.b) * float64(aa) / 255)
			bgra[i+1] = uint8(float64(t.g) * float64(aa) / 255)
			bgra[i+2] = uint8(float64(t.r) * float64(aa) / 255)
			bgra[i+3] = aa
		}
	}
	// AND mask: 1bpp rows padded to 32-bit boundaries, all zeros (alpha wins).
	maskRow := ((size + 31) / 32) * 4
	mask := make([]byte, maskRow*size)
	return append(bgra, mask...), size, size, nil
}

// assembleICO packs BMP entries into an .ico container.
func assembleICO(entries []icoEntry) []byte {
	count := len(entries)
	total := 6 + 16*count
	for _, e := range entries {
		total += len(e.data)
	}
	out := make([]byte, 0, total)
	out = append(out, 0, 0, 1, 0)
	out = append(out, byte(count), 0)
	offset := uint32(6 + 16*count)
	for _, e := range entries {
		out = append(out, byteSize(e.w), byteSize(e.h), 0, 0)
		out = append(out, 1, 0)  // planes
		out = append(out, 32, 0) // bpp
		sz := uint32(len(e.data))
		out = append(out, byte(sz), byte(sz>>8), byte(sz>>16), byte(sz>>24))
		out = append(out, byte(offset), byte(offset>>8), byte(offset>>16), byte(offset>>24))
		offset += sz
	}
	for _, e := range entries {
		// BITMAPINFOHEADER (biHeight = 2x for icons), then XOR, then mask.
		hdr := make([]byte, 40)
		put32(hdr[0:], 40)
		put32(hdr[4:], uint32(e.w))
		put32(hdr[8:], uint32(e.h*2))
		put16(hdr[12:], 1)
		put16(hdr[14:], 32)
		out = append(out, hdr...)
		out = append(out, e.data...)
	}
	return out
}

func byteSize(v int) byte {
	if v >= 256 {
		return 0
	}
	return byte(v)
}

func put32(b []byte, v uint32) {
	b[0], b[1], b[2], b[3] = byte(v), byte(v>>8), byte(v>>16), byte(v>>24)
}

func put16(b []byte, v uint16) {
	b[0], b[1] = byte(v), byte(v>>8)
}

func clamp01(v float64) float64 {
	return math.Min(1, math.Max(0, v))
}
