package server

import "testing"

func TestCleanRemoteName(t *testing.T) {
	tests := []struct {
		name string
		want string
	}{
		{name: "", want: "Popcorn TV"},
		{name: "  Shield   SHIELD Android TV  ", want: "Shield Android TV"},
		{name: "Living Room Living Room TV", want: "Living Room TV"},
		{name: "Shield Android TV", want: "Shield Android TV"},
	}

	for _, tt := range tests {
		if got := cleanRemoteName(tt.name, "Popcorn TV"); got != tt.want {
			t.Fatalf("cleanRemoteName(%q) = %q, want %q", tt.name, got, tt.want)
		}
	}
}

func TestRemoteDeviceKeyNormalizesNameAndKind(t *testing.T) {
	a := remoteDeviceKey("TV", "Shield SHIELD Android TV")
	b := remoteDeviceKey(" tv ", "shield android tv")
	if a != b {
		t.Fatalf("remote device keys differ: %q != %q", a, b)
	}
}
