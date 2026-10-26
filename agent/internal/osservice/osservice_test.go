package osservice

import (
	"strings"
	"testing"
)

// The hub's service is "Service Monitor hub" (hub/service/windows/svcmon-hub.xml, svcmon-hub.service); the agent's is
// named the same way, so the two are told apart by the last word and never mistaken for each other in a service list.
func TestTheServiceIsNamedLikeTheHubs(t *testing.T) {
	o := DefaultOptions(`C:\svcmon\svcmon.exe`, []string{"agent", "run"})
	if o.Name != "svcmon-agent" {
		t.Errorf("service name %q, want svcmon-agent (the hub's is svcmon-hub)", o.Name)
	}
	if o.Display != "Service Monitor agent" {
		t.Errorf("display name %q, want %q", o.Display, "Service Monitor agent")
	}
	if !strings.HasPrefix(o.Description, "Service Monitor agent") {
		t.Errorf("description %q should start with the display name", o.Description)
	}
	if o.Exe != `C:\svcmon\svcmon.exe` || len(o.Args) != 2 {
		t.Errorf("exe and args were not passed through: %+v", o)
	}
}
