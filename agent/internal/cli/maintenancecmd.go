package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"svcmon/internal/agent"
	"svcmon/internal/hubapi"
)

// maxWindow is the longest maintenance window an admin may open; the hub sets a shorter one for agents.
const maxWindow = 24 * time.Hour

// maintenanceCmd runs on an admin's machine: it holds back a server's alerts while it is restarted.
func (e *env) maintenanceCmd(args []string) int {
	if len(args) == 0 {
		return e.fail(errors.New("usage: svcmon maintenance start|end|list"))
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "start":
		return e.maintenanceStart(rest)
	case "end":
		return e.maintenanceEnd(rest)
	case "list":
		return e.maintenanceList(rest)
	default:
		return e.fail(fmt.Errorf("unknown maintenance command %q", sub))
	}
}

func (e *env) maintenanceStart(args []string) int {
	fs := e.flags("maintenance start")
	reason := fs.String("reason", "", "why, shown on the dashboard (not sent in alerts)")
	full := fs.Bool("full", false, "stay open for the whole time, not only until everything is back")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) != 2 {
		return e.fail(errors.New("usage: svcmon maintenance start <server> <duration> [--reason R] [--full]"))
	}
	minutes, err := windowMinutes(pos[1], maxWindow)
	if err != nil {
		return e.fail(err)
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	sv, err := findServer(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	var m hubapi.Maintenance
	req := hubapi.MaintenanceRequest{Minutes: minutes, Reason: *reason, UntilBack: !*full}
	if err := c.Do(context.Background(), "POST", "/servers/"+sv.ID+"/maintenance", req, &m); err != nil {
		return e.fail(err)
	}
	printOpened(e.out, sv.Name, m)
	return exitOK
}

func (e *env) maintenanceEnd(args []string) int {
	fs := e.flags("maintenance end")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon maintenance end <server>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	sv, err := findServer(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	if err := c.Do(context.Background(), "DELETE", "/servers/"+sv.ID+"/maintenance", nil, nil); err != nil {
		return e.fail(err)
	}
	printEnded(e.out, sv.Name)
	return exitOK
}

func (e *env) maintenanceList(args []string) int {
	fs := e.flags("maintenance list")
	asJSON := fs.Bool("json", false, "print JSON")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var st hubapi.Status
	if err := c.Do(context.Background(), "GET", "/status", nil, &st); err != nil {
		return e.fail(err)
	}
	type row struct {
		Server string `json:"server"`
		hubapi.Maintenance
	}
	var rows []row
	for _, cl := range st.Clusters {
		for _, sv := range cl.Servers {
			if sv.Maintenance != nil {
				rows = append(rows, row{sv.Name, *sv.Maintenance})
			}
		}
	}
	if *asJSON {
		if rows == nil {
			rows = []row{}
		}
		printJSON(e.out, rows)
		return exitOK
	}
	if len(rows) == 0 {
		fmt.Fprintln(e.out, "No server is in maintenance.")
		return exitOK
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "SERVER\tUNTIL\tENDS\tBY\tREASON")
	for _, r := range rows {
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s %s\t%s\n", r.Server, fmtMillis(r.EndsAt), endsHow(r.UntilBack),
			r.StartedByKind, r.StartedBy, r.Reason)
	}
	tw.Flush()
	return exitOK
}

// agentMaintenance runs on a monitored server, with the agent's own credential from agent.json (so as root or an
// administrator): "svcmon agent maintenance 20m && reboot".
func (e *env) agentMaintenance(args []string) int {
	end := len(args) > 0 && args[0] == "end"
	if len(args) > 0 && (args[0] == "start" || end) {
		args = args[1:]
	}
	fs := e.flags("agent maintenance")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "agent config file")
	reason := fs.String("reason", "", "why, shown on the dashboard (not sent in alerts)")
	full := fs.Bool("full", false, "stay open for the whole time, not only until everything is back")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if (end && len(pos) != 0) || (!end && len(pos) != 1) {
		return e.fail(errors.New("usage: svcmon agent maintenance [start] <duration> [--reason R] [--full] | svcmon agent maintenance end"))
	}
	cfg, err := agent.LoadConfig(*cfgPath)
	if err != nil {
		return e.fail(err)
	}
	a := agent.New(cfg, log.New(io.Discard, "", 0), Version)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if end {
		ended, err := a.EndMaintenance(ctx)
		if err != nil {
			return e.fail(err)
		}
		if !ended {
			return e.fail(errors.New("no maintenance window is open for this server"))
		}
		printEnded(e.out, "this server")
		return exitOK
	}
	minutes, err := windowMinutes(pos[0], maxWindow)
	if err != nil {
		return e.fail(err)
	}
	m, err := a.OpenMaintenance(ctx, hubapi.MaintenanceRequest{Minutes: minutes, Reason: *reason, UntilBack: !*full})
	if err != nil {
		var apiErr *hubapi.APIError
		if errors.As(err, &apiErr) && apiErr.Status == http.StatusForbidden {
			return e.fail(fmt.Errorf("%v; ask an admin to run 'svcmon maintenance start'", err))
		}
		return e.fail(err)
	}
	printOpened(e.out, "this server", *m)
	return exitOK
}

// windowMinutes reads a maintenance window's length: a duration (20m, 1h30m) or a number of minutes, rounded up to
// whole minutes, at least one and at most max.
func windowMinutes(s string, max time.Duration) (int, error) {
	d, err := time.ParseDuration(s)
	if err != nil {
		n, nerr := strconv.Atoi(s)
		if nerr != nil {
			return 0, fmt.Errorf("invalid duration %q: use minutes or a duration like 20m or 1h30m", s)
		}
		d = time.Duration(n) * time.Minute
	}
	if d < time.Minute {
		return 0, fmt.Errorf("invalid duration %q: at least one minute", s)
	}
	if d > max {
		return 0, fmt.Errorf("invalid duration %q: at most %.0f hours", s, max.Hours())
	}
	return int((d + time.Minute - 1) / time.Minute), nil
}

// findServer finds a server by name (any case), id or an id prefix of at least four characters.
func findServer(c *hubapi.Client, key string) (hubapi.ServerView, error) {
	var st hubapi.Status
	if err := c.Do(context.Background(), "GET", "/status", nil, &st); err != nil {
		return hubapi.ServerView{}, err
	}
	for _, cl := range st.Clusters {
		for _, sv := range cl.Servers {
			if serverMatches(sv, key) {
				return sv, nil
			}
		}
	}
	return hubapi.ServerView{}, fmt.Errorf("no server matches %q", key)
}

func serverMatches(sv hubapi.ServerView, key string) bool {
	return strings.EqualFold(sv.Name, key) || sv.ID == key || (len(key) >= 4 && strings.HasPrefix(sv.ID, key))
}

func endsHow(untilBack bool) string {
	if untilBack {
		return "once all back"
	}
	return "at the time"
}

func printOpened(w io.Writer, server string, m hubapi.Maintenance) {
	how := "or earlier, once everything that goes down is back"
	if !m.UntilBack {
		how = "or when ended with 'end'"
	}
	fmt.Fprintf(w, "Alerts for %s are held back until %s (%s).\n", server, fmtMillis(m.EndsAt), how)
	fmt.Fprintln(w, "Anything not back to how it was when the window ends alerts then, once.")
}

func printEnded(w io.Writer, server string) {
	fmt.Fprintf(w, "Maintenance of %s ended: its alerts go out again, and anything not back to how it was alerts now.\n", server)
}
