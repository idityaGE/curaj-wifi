//go:build windows

package config

import (
	"fmt"
	"syscall"
	"unsafe"
)

// dpapi uses CryptProtectData/CryptUnprotectData from crypt32.dll with the
// CRYPTPROTECT_LOCAL_MACHINE flag: the SYSTEM service and the elevated
// setup both need access across user accounts.

type blob struct {
	cbData uint32
	pbData *byte
}

const cryptProtectLocalMachine = 0x4

var (
	modcrypt32                  = syscall.NewLazyDLL("crypt32.dll")
	procCryptProtectData        = modcrypt32.NewProc("CryptProtectData")
	procCryptUnprotectData      = modcrypt32.NewProc("CryptUnprotectData")
	modkernel32                 = syscall.NewLazyDLL("kernel32.dll")
	procLocalFree               = modkernel32.NewProc("LocalFree")
)

// ProtectorLM is the production Protector (DPAPI LocalMachine scope).
type ProtectorLM struct{}

func (ProtectorLM) Protect(plain []byte) ([]byte, error) {
	if len(plain) == 0 {
		return nil, fmt.Errorf("nothing to protect")
	}
	in := blob{uint32(len(plain)), &plain[0]}
	var out blob
	desc, _ := syscall.UTF16PtrFromString("curaj-wifi")
	r1, _, err := procCryptProtectData.Call(
		uintptr(unsafe.Pointer(&in)),
		uintptr(unsafe.Pointer(desc)),
		0, 0, 0,
		cryptProtectLocalMachine,
		uintptr(unsafe.Pointer(&out)),
	)
	if r1 == 0 {
		return nil, fmt.Errorf("CryptProtectData: %v", err)
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.pbData)))
	return blobBytes(out), nil
}

func (ProtectorLM) Unprotect(cipher []byte) ([]byte, error) {
	if len(cipher) == 0 {
		return nil, fmt.Errorf("empty blob")
	}
	in := blob{uint32(len(cipher)), &cipher[0]}
	var out blob
	r1, _, err := procCryptUnprotectData.Call(
		uintptr(unsafe.Pointer(&in)),
		0, // description out — ignore
		0, 0, 0,
		cryptProtectLocalMachine,
		uintptr(unsafe.Pointer(&out)),
	)
	if r1 == 0 {
		return nil, fmt.Errorf("CryptUnprotectData: %v", err)
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.pbData)))
	return blobBytes(out), nil
}

func blobBytes(b blob) []byte {
	if b.cbData == 0 || b.pbData == nil {
		return nil
	}
	out := make([]byte, b.cbData)
	copy(out, unsafe.Slice(b.pbData, b.cbData))
	return out
}
