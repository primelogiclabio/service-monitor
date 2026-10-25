// Package checks runs the checks an agent is asked to perform and turns each outcome into a Result
// the hub understands.
package checks

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Status values match the hub's.
const (
	Up      = "UP"
	Warn    = "WARN"
	Down    = "DOWN"
	Unknown = "UNKNOWN"
)

// Check is one thing to measure.
type Check struct {
	ID      string
	Type    string
	Target  string
	Config  map[string]any
	Timeout time.Duration
}

// Result is the JSON the hub's /agent/report endpoint accepts.
type Result struct {
	CheckID   string   `json:"checkId"`
	Ts        int64    `json:"ts"`
	Status    string   `json:"status"`
	LatencyMs *float64 `json:"latencyMs,omitempty"`
	Value     *float64 `json:"value,omitempty"`
	Message   string   `json:"message,omitempty"`
}

// Run executes a check. It never panics and always returns a result: a measurement that could not be
// taken is UNKNOWN, a service or endpoint that is verifiably unhealthy is DOWN.
func Run(ctx context.Context, c Check) (res Result) {
	if c.Timeout <= 0 {
		c.Timeout = 5 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, c.Timeout)
	defer cancel()
	defer func() {
		if r := recover(); r != nil {
			res = result(c, Unknown, nil, nil, fmt.Sprintf("check crashed: %v", r))
		}
	}()

	switch strings.ToUpper(c.Type) {
	case "HTTP":
		return httpCheck(ctx, c)
	case "TCP":
		return tcpCheck(ctx, c)
	case "PING":
		return pingCheck(ctx, c)
	case "SERVICE":
		return serviceCheck(ctx, c)
	case "CONTAINER":
		return containerCheck(ctx, c)
	case "CPU":
		return cpuCheck(ctx, c)
	case "MEMORY":
		return memoryCheck(ctx, c)
	case "DISK":
		return diskCheck(ctx, c)
	default:
		return result(c, Unknown, nil, nil, "unsupported check type "+c.Type)
	}
}

func result(c Check, status string, latencyMs, value *float64, message string) Result {
	return Result{
		CheckID:   c.ID,
		Ts:        time.Now().UnixMilli(),
		Status:    status,
		LatencyMs: latencyMs,
		Value:     value,
		Message:   message,
	}
}

func ptr(f float64) *float64 { return &f }

func millis(d time.Duration) *float64 { return ptr(float64(d.Microseconds()) / 1000) }

func round1(f float64) float64 { return float64(int64(f*10+0.5)) / 10 }

// level maps a percentage-used reading to a status using warn and crit thresholds.
func level(value, warn, crit float64) string {
	switch {
	case value >= crit:
		return Down
	case value >= warn:
		return Warn
	default:
		return Up
	}
}

func cfgString(m map[string]any, key, def string) string {
	if v, ok := m[key]; ok && v != nil {
		return fmt.Sprint(v)
	}
	return def
}

func cfgBool(m map[string]any, key string, def bool) bool {
	switch v := m[key].(type) {
	case bool:
		return v
	case string:
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
	}
	return def
}

func cfgFloat(m map[string]any, key string, def float64) float64 {
	switch v := m[key].(type) {
	case float64:
		return v
	case int:
		return float64(v)
	case string:
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return def
}

func describe(ctx context.Context, err error, timeout time.Duration) string {
	if ctx.Err() == context.DeadlineExceeded {
		return fmt.Sprintf("timed out after %d ms", timeout.Milliseconds())
	}
	return err.Error()
}

func humanBytes(b uint64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := uint64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(b)/float64(div), "KMGTPE"[exp])
}
