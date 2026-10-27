//go:build windows

package tray

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// loadIconFromICO decodes one size from our own generated .ico blobs
// (BMP entries, 32bpp BGRA bottom-up) and returns an HICON.
func loadIconFromICO(data []byte, size int) (HICON, error) {
	if len(data) < 6 || data[0] != 0 || data[1] != 1 {
		return 0, errBadICO("not an .ico file")
	}
	count := int(data[4]) | int(data[5])<<8
	type entry struct {
		w, h, colors, _ byte
		planes, bpp     uint16
		size, offset    uint32
	}
	base := 6
	if base+16*count > len(data) {
		return 0, errBadICO("truncated directory")
	}
	var best int = -1
	bestDiff := 1 << 30
	for i := 0; i < count; i++ {
		e := data[base+16*i : base+16*i+16]
		w := int(e[0])
		if w == 0 {
			w = 256
		}
		d := w - size
		if d < 0 {
			d = -d
		}
		if d < bestDiff {
			bestDiff, best = d, i
		}
	}
	if best < 0 {
		return 0, errBadICO("no usable size")
	}
	e := data[base+16*best : base+16*best+16]
	blobW := int(e[0])
	if blobW == 0 {
		blobW = 256
	}
	blobH := int(e[1])
	if blobH == 0 {
		blobH = 256
	}
	off := le32(data[base+16*best+12:])
	sz := le32(data[base+16*best+8:])
	if int(off+sz) > len(data) {
		return 0, errBadICO("truncated bitmap")
	}
	bmp := data[off : off+sz]

	if len(bmp) < 40 {
		return 0, errBadICO("short BITMAPINFOHEADER")
	}
	biWidth := int32(le32(bmp[4:]))
	biHeight := int32(le32(bmp[8:]))
	bitCount := uint16(bmp[14]) | uint16(bmp[15])<<8
	if bitCount != 32 {
		return 0, errBadICO("only 32bpp icons supported")
	}
	w := int(biWidth)
	h := int(biHeight) / 2
	if w == 0 {
		w = blobW
	}
	if h == 0 {
		h = blobH
	}
	xor := bmp[40:]
	rowBytes := w * 4
	if len(xor) < rowBytes*h {
		return 0, errBadICO("truncated pixel data")
	}

	hdc := windows.Handle(call(procGetDC, 0))
	defer call(procReleaseDC, 0, uintptr(hdc))

	var bmi struct {
		hdr bitmapInfoHeader
	}
	bmi.hdr = bitmapInfoHeader{
		Size:      40,
		Width:     int32(w),
		Height:    int32(h),
		Planes:    1,
		BitCount:  32,
		Compression: 0, // BI_RGB
	}
	// ppvBits out-param: keep the raw pointer as unsafe.Pointer from the
	// start (no uintptr→pointer round trips, vet-clean).
	var bitsPtr unsafe.Pointer
	hbmColor := HBITMAP(call(procCreateDIBSection,
		uintptr(hdc), uintptr(unsafe.Pointer(&bmi)), 0, uintptr(unsafe.Pointer(&bitsPtr)), 0, 0))
	if hbmColor == 0 || bitsPtr == nil {
		return 0, errBadICO("CreateDIBSection failed")
	}
	copy(unsafe.Slice((*byte)(bitsPtr), rowBytes*h), xor[:rowBytes*h])

	// 32bpp icons carry alpha; the AND mask is all-opaque zeros.
	var maskBitsPtr unsafe.Pointer
	hbmMask := HBITMAP(call(procCreateDIBSection,
		uintptr(hdc), uintptr(unsafe.Pointer(&bmi)), 0, uintptr(unsafe.Pointer(&maskBitsPtr)), 0, 0))
	if hbmMask == 0 {
		call(procDeleteObject, uintptr(hbmColor))
		return 0, errBadICO("mask DIB failed")
	}
	fillZero(unsafe.Slice((*byte)(maskBitsPtr), rowBytes*h))

	ii := iconInfo{Icon: 1, HbmMask: hbmMask, HbmColor: hbmColor}
	hicon := HICON(call(procCreateIconIndirect, uintptr(unsafe.Pointer(&ii))))
	call(procDeleteObject, uintptr(hbmColor))
	call(procDeleteObject, uintptr(hbmMask))
	if hicon == 0 {
		return 0, errBadICO("CreateIconIndirect failed")
	}
	return hicon, nil
}

type errBadICO string

func (e errBadICO) Error() string { return string(e) }

func le32(b []byte) uint32 {
	return uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16 | uint32(b[3])<<24
}

func fillZero(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
