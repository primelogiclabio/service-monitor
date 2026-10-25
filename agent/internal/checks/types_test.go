package checks

import (
	"strings"
	"testing"
)

// The checks file is validated against Types, so every type listed there must be one Run can run.
func TestEveryListedTypeIsRunnable(t *testing.T) {
	for _, typ := range Types {
		r := Run(t.Context(), Check{ID: "x", Type: typ, Target: "x", Timeout: 200 * 1000000})
		if strings.Contains(r.Message, "unsupported check type") {
			t.Errorf("%s is listed in Types but Run does not handle it", typ)
		}
	}
}
