package server

import (
	"archive/zip"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"unicode/utf16"
)

const (
	axmlStringPoolType        = 0x0001
	axmlStartElement          = 0x0102
	axmlUTF8Flag              = 0x00000100
	axmlNoString       uint32 = 0xffffffff
)

func apkVersionFromManifest(path string) (int, string, error) {
	archive, err := zip.OpenReader(path)
	if err != nil {
		return 0, "", err
	}
	defer archive.Close()
	for _, file := range archive.File {
		if file.Name != "AndroidManifest.xml" {
			continue
		}
		rc, err := file.Open()
		if err != nil {
			return 0, "", err
		}
		data, readErr := io.ReadAll(rc)
		closeErr := rc.Close()
		if readErr != nil {
			return 0, "", readErr
		}
		if closeErr != nil {
			return 0, "", closeErr
		}
		return apkVersionFromBinaryManifest(data)
	}
	return 0, "", errors.New("AndroidManifest.xml not found in APK")
}

func apkVersionFromBinaryManifest(data []byte) (int, string, error) {
	strings, err := axmlStringPool(data)
	if err != nil {
		return 0, "", err
	}
	var versionCode int
	var versionName string
	for off := int(binary.LittleEndian.Uint16(data[2:4])); off+8 <= len(data); {
		chunkType, headerSize, chunkSize, ok := axmlChunk(data, off)
		if !ok {
			break
		}
		if chunkType == axmlStartElement && int(headerSize) >= 16 && off+36 <= len(data) {
			attrStart := int(binary.LittleEndian.Uint16(data[off+24 : off+26]))
			attrSize := int(binary.LittleEndian.Uint16(data[off+26 : off+28]))
			attrCount := int(binary.LittleEndian.Uint16(data[off+28 : off+30]))
			attrOff := off + 16 + attrStart
			if attrSize <= 0 {
				attrSize = 20
			}
			for i := 0; i < attrCount; i++ {
				pos := attrOff + i*attrSize
				if pos+20 > off+int(chunkSize) || pos+20 > len(data) {
					break
				}
				nameIndex := binary.LittleEndian.Uint32(data[pos+4 : pos+8])
				name := axmlString(strings, nameIndex)
				rawValue := binary.LittleEndian.Uint32(data[pos+8 : pos+12])
				dataType := data[pos+15]
				typedData := binary.LittleEndian.Uint32(data[pos+16 : pos+20])
				switch name {
				case "versionCode":
					versionCode = int(typedData)
				case "versionName":
					if rawValue != axmlNoString {
						versionName = axmlString(strings, rawValue)
					} else if dataType == 0x03 {
						versionName = axmlString(strings, typedData)
					} else {
						versionName = fmt.Sprint(typedData)
					}
				}
			}
		}
		off += int(chunkSize)
	}
	if versionCode <= 0 {
		return 0, "", errors.New("APK manifest did not contain a valid versionCode")
	}
	if versionName == "" {
		return 0, "", errors.New("APK manifest did not contain a valid versionName")
	}
	return versionCode, versionName, nil
}

func axmlStringPool(data []byte) ([]string, error) {
	for off := 8; off+28 <= len(data); {
		chunkType, _, chunkSize, ok := axmlChunk(data, off)
		if !ok {
			break
		}
		if chunkType != axmlStringPoolType {
			off += int(chunkSize)
			continue
		}
		stringCount := int(binary.LittleEndian.Uint32(data[off+8 : off+12]))
		flags := binary.LittleEndian.Uint32(data[off+16 : off+20])
		stringsStart := int(binary.LittleEndian.Uint32(data[off+20 : off+24]))
		offsetsStart := off + 28
		if stringCount < 0 || offsetsStart+stringCount*4 > len(data) || off+stringsStart > len(data) {
			return nil, errors.New("invalid APK string pool")
		}
		out := make([]string, 0, stringCount)
		for i := 0; i < stringCount; i++ {
			rel := int(binary.LittleEndian.Uint32(data[offsetsStart+i*4 : offsetsStart+i*4+4]))
			pos := off + stringsStart + rel
			if pos >= len(data) {
				out = append(out, "")
				continue
			}
			if flags&axmlUTF8Flag != 0 {
				out = append(out, decodeAXMLUTF8(data[pos:]))
			} else {
				out = append(out, decodeAXMLUTF16(data[pos:]))
			}
		}
		return out, nil
	}
	return nil, errors.New("APK manifest string pool not found")
}

func axmlChunk(data []byte, off int) (uint16, uint16, uint32, bool) {
	if off+8 > len(data) {
		return 0, 0, 0, false
	}
	chunkType := binary.LittleEndian.Uint16(data[off : off+2])
	headerSize := binary.LittleEndian.Uint16(data[off+2 : off+4])
	chunkSize := binary.LittleEndian.Uint32(data[off+4 : off+8])
	if chunkSize < 8 || off+int(chunkSize) > len(data) {
		return 0, 0, 0, false
	}
	return chunkType, headerSize, chunkSize, true
}

func axmlString(strings []string, index uint32) string {
	if index == axmlNoString || int(index) >= len(strings) {
		return ""
	}
	return strings[index]
}

func decodeAXMLUTF8(data []byte) string {
	_, n := axmlVarint8(data)
	size, m := axmlVarint8(data[n:])
	start := n + m
	if start < 0 || size < 0 || start+size > len(data) {
		return ""
	}
	return string(data[start : start+size])
}

func axmlVarint8(data []byte) (int, int) {
	if len(data) == 0 {
		return 0, 0
	}
	first := int(data[0])
	if first&0x80 == 0 || len(data) < 2 {
		return first, 1
	}
	return ((first & 0x7f) << 8) | int(data[1]), 2
}

func decodeAXMLUTF16(data []byte) string {
	length, n := axmlVarint16(data)
	start := n
	if start < 0 || length < 0 || start+length*2 > len(data) {
		return ""
	}
	u16 := make([]uint16, 0, length)
	for i := 0; i < length; i++ {
		u16 = append(u16, binary.LittleEndian.Uint16(data[start+i*2:start+i*2+2]))
	}
	return string(utf16.Decode(u16))
}

func axmlVarint16(data []byte) (int, int) {
	if len(data) < 2 {
		return 0, 0
	}
	first := binary.LittleEndian.Uint16(data[:2])
	if first&0x8000 == 0 || len(data) < 4 {
		return int(first), 2
	}
	second := binary.LittleEndian.Uint16(data[2:4])
	return int(first&0x7fff)<<16 | int(second), 4
}
