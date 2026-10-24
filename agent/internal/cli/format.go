package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"svcmon/internal/hubapi"
)

func statusExit(s string) int {
	switch strings.ToUpper(s) {
	case "UP":
		return exitOK
	case "WARN":
		return exitWarn
	case "DOWN":
		return exitDown
	default:
		return exitUnknown
	}
}

func printJSON(w io.Writer, v any) {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func fmtMillis(ms int64) string {
	return time.UnixMilli(ms).Local().Format("2006-01-02 15:04:05")
}

// ago renders an epoch-millisecond timestamp as "5s ago".
func ago(ms *int64) string {
	if ms == nil {
		return "never"
	}
	d := time.Since(time.UnixMilli(*ms))
	switch {
	case d < 0:
		return "just now"
	case d < time.Minute:
		return fmt.Sprintf("%ds ago", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	default:
		return fmt.Sprintf("%dd ago", int(d.Hours()/24))
	}
}

func metric(c hubapi.CheckView) string {
	switch {
	case c.Value != nil:
		return strconv.FormatFloat(*c.Value, 'f', 1, 64) + "%"
	case c.LatencyMs != nil:
		return strconv.FormatFloat(*c.LatencyMs, 'f', 0, 64) + " ms"
	default:
		return ""
	}
}

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func printStatus(w io.Writer, st hubapi.Status) {
	s := st.Summary
	fmt.Fprintf(w, "Overall: %s   servers: %d up, %d warn, %d down   checks: %d up, %d warn, %d down, %d unknown\n",
		s.Overall, s.Servers.Up, s.Servers.Warn, s.Servers.Down,
		s.Checks.Up, s.Checks.Warn, s.Checks.Down, s.Checks.Unknown)
	for _, cl := range st.Clusters {
		fmt.Fprintf(w, "\nCLUSTER %s [%s]\n", cl.Name, cl.Status)
		for _, sv := range cl.Servers {
			printServer(w, sv, "  ")
		}
	}
	if len(st.HubChecks) > 0 {
		fmt.Fprintf(w, "\nHUB CHECKS\n")
		printChecks(w, st.HubChecks, "  ")
	}
}

func printServer(w io.Writer, sv hubapi.ServerView, indent string) {
	agent := "agent online"
	if !sv.AgentOnline {
		agent = "AGENT OFFLINE"
	}
	details := []string{agent, "seen " + ago(sv.LastSeen)}
	if sv.OS != "" {
		details = append([]string{sv.OS}, details...)
	}
	fmt.Fprintf(w, "%s%s [%s]  %s\n", indent, sv.Name, sv.Status, strings.Join(details, ", "))
	printChecks(w, sv.Checks, indent+"  ")
}

func printChecks(w io.Writer, cs []hubapi.CheckView, indent string) {
	tw := tabwriter.NewWriter(w, 0, 4, 2, ' ', 0)
	for _, c := range cs {
		st := c.Status
		if !c.Enabled {
			st = "OFF"
		}
		fmt.Fprintf(tw, "%s%s\t%s\t%s\t%s\t%s\n", indent, st, c.Type, c.Name, metric(c), c.Message)
	}
	tw.Flush()
}
