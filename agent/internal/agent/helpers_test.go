package agent

import (
	"time"

	"svcmon/internal/checks"
)

func resultFor(id string) checks.Result {
	return checks.Result{CheckID: id, Ts: time.Now().UnixMilli(), Status: checks.Up}
}
