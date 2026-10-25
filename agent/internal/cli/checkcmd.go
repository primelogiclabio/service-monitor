package cli

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"svcmon/internal/hubapi"
)

func (e *env) check(args []string) int {
	if len(args) == 0 {
		return e.checkList(nil)
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "list", "ls":
		return e.checkList(rest)
	case "show":
		return e.checkShow(rest)
	case "add":
		return e.checkAdd(rest)
	case "rm", "remove", "delete":
		return e.checkRemove(rest)
	case "run":
		return e.checkRun(rest)
	case "history":
		return e.checkHistory(rest)
	default:
		return e.fail(fmt.Errorf("unknown check command %q (list, show, add, rm, run, history)", sub))
	}
}

func (e *env) checkList(args []string) int {
	fs := e.flags("check list")
	asJSON := fs.Bool("json", false, "print the hub's JSON")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ctx := context.Background()
	if *asJSON {
		var defs []hubapi.CheckDef
		if err := c.Do(ctx, "GET", "/checks", nil, &defs); err != nil {
			return e.fail(err)
		}
		printJSON(e.out, defs)
		return exitOK
	}
	var st hubapi.Status
	if err := c.Do(ctx, "GET", "/status", nil, &st); err != nil {
		return e.fail(err)
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\tSTATUS\tTYPE\tSERVER\tNAME\tTARGET\tRUNS ON")
	row := func(server string, v hubapi.CheckView) {
		st := v.Status
		if !v.Enabled {
			st = "OFF"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", shortID(v.ID), st, v.Type, server, v.Name, v.Target, v.RunFrom)
	}
	for _, cl := range st.Clusters {
		for _, sv := range cl.Servers {
			for _, v := range sv.Checks {
				row(sv.Name, v)
			}
		}
	}
	for _, v := range st.HubChecks {
		row("-", v)
	}
	tw.Flush()
	return exitOK
}

func (e *env) checkShow(args []string) int {
	pos, code, ok := e.parse(e.flags("check show"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon check show <check>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	def, err := resolveCheck(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	printJSON(e.out, def)
	return exitOK
}

func (e *env) checkRemove(args []string) int {
	pos, code, ok := e.parse(e.flags("check rm"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon check rm <check>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	def, err := resolveCheck(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	if err := c.Do(context.Background(), "DELETE", "/checks/"+url.PathEscape(def.ID), nil, nil); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Deleted check %q (%s)\n", def.Name, shortID(def.ID))
	return exitOK
}

func (e *env) checkRun(args []string) int {
	pos, code, ok := e.parse(e.flags("check run"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon check run <check>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	def, err := resolveCheck(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	var res struct {
		Status    string   `json:"status"`
		LatencyMs *float64 `json:"latencyMs"`
		Message   string   `json:"message"`
	}
	if err := c.Do(context.Background(), "POST", "/checks/"+url.PathEscape(def.ID)+"/run", nil, &res); err != nil {
		return e.fail(err)
	}
	lat := ""
	if res.LatencyMs != nil {
		lat = fmt.Sprintf("  %.0f ms", *res.LatencyMs)
	}
	fmt.Fprintf(e.out, "%s  %s%s  %s\n", res.Status, def.Name, lat, res.Message)
	return statusExit(res.Status)
}

func (e *env) checkHistory(args []string) int {
	fs := e.flags("check history")
	limit := fs.Int("limit", 20, "maximum rows")
	since := fs.String("since", "", "only results newer than this age, e.g. 30m, 2h, 7d")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon check history <check> [--limit N] [--since 1h]"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	def, err := resolveCheck(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	q := url.Values{"limit": {strconv.Itoa(*limit)}}
	if *since != "" {
		d, err := parseAge(*since)
		if err != nil {
			return e.fail(err)
		}
		q.Set("since", strconv.FormatInt(time.Now().Add(-d).UnixMilli(), 10))
	}
	var rows []struct {
		Ts        int64    `json:"ts"`
		Status    string   `json:"status"`
		LatencyMs *float64 `json:"latencyMs"`
		Value     *float64 `json:"value"`
		Message   string   `json:"message"`
	}
	if err := c.Do(context.Background(), "GET", "/checks/"+url.PathEscape(def.ID)+"/results?"+q.Encode(), nil, &rows); err != nil {
		return e.fail(err)
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	for _, r := range rows {
		m := ""
		switch {
		case r.Value != nil:
			m = strconv.FormatFloat(*r.Value, 'f', 1, 64) + "%"
		case r.LatencyMs != nil:
			m = strconv.FormatFloat(*r.LatencyMs, 'f', 0, 64) + " ms"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", fmtMillis(r.Ts), r.Status, m, r.Message)
	}
	tw.Flush()
	return exitOK
}

// parseAge accepts Go durations plus a "d" suffix for days.
func parseAge(s string) (time.Duration, error) {
	if days, ok := strings.CutSuffix(s, "d"); ok {
		n, err := strconv.ParseFloat(days, 64)
		if err != nil {
			return 0, fmt.Errorf("bad age %q", s)
		}
		return time.Duration(n * 24 * float64(time.Hour)), nil
	}
	d, err := time.ParseDuration(s)
	if err != nil {
		return 0, fmt.Errorf("bad age %q (examples: 30m, 2h, 7d)", s)
	}
	return d, nil
}

// kvFlag collects repeated --set key=value options into check config.
type kvFlag map[string]any

func (k kvFlag) String() string { return fmt.Sprint(map[string]any(k)) }

func (k kvFlag) Set(s string) error {
	key, val, ok := strings.Cut(s, "=")
	if !ok || key == "" {
		return fmt.Errorf("expected key=value, got %q", s)
	}
	switch {
	case val == "true" || val == "false":
		k[key] = val == "true"
	default:
		if f, err := strconv.ParseFloat(val, 64); err == nil {
			k[key] = f
		} else {
			k[key] = val
		}
	}
	return nil
}

func (e *env) checkAdd(args []string) int {
	fs := e.flags("check add")
	name := fs.String("name", "", "display name")
	interval := fs.Int("interval", 0, "seconds between runs")
	timeout := fs.Int("timeout", 0, "timeout in milliseconds")
	failures := fs.Int("failures", 0, "consecutive failures before the check is DOWN")
	server := fs.String("server", "", "server (name or ID) this check belongs to; required for agent checks")
	runFrom := fs.String("run-from", "", "hub or agent")
	warn := fs.Float64("warn", -1, "warning threshold, percent used (cpu, memory, disk)")
	crit := fs.Float64("crit", -1, "critical threshold, percent used (cpu, memory, disk)")
	keyword := fs.String("keyword", "", "http: text the body must contain")
	expect := fs.String("expect-status", "", "http: accepted status codes, e.g. 200-299,301")
	ignoreTLS := fs.Bool("ignore-tls", false, "http: accept any TLS certificate")
	noRedirects := fs.Bool("no-redirects", false, "http: do not follow redirects")
	certDays := fs.Int("cert-warn-days", 0, "http: warn this many days before the certificate expires")
	socket := fs.String("socket", "", "container: runtime socket, if it is not in the usual place")
	disabled := fs.Bool("disabled", false, "create the check disabled")
	extra := kvFlag{}
	fs.Var(extra, "set", "extra config as key=value (repeatable)")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) < 1 || len(pos) > 2 {
		return e.fail(errors.New("usage: svcmon check add <type> [target] [flags]"))
	}
	typ := strings.ToUpper(pos[0])
	target := ""
	if len(pos) == 2 {
		target = pos[1]
	}

	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ctx := context.Background()
	body := map[string]any{"type": typ}
	if target != "" {
		body["target"] = target
	}
	cfg := map[string]any{}
	for k, v := range extra {
		cfg[k] = v
	}
	if *warn >= 0 {
		cfg["warn"] = *warn
	}
	if *crit >= 0 {
		cfg["crit"] = *crit
	}
	if *keyword != "" {
		cfg["keyword"] = *keyword
	}
	if *expect != "" {
		cfg["expectStatus"] = *expect
	}
	if *ignoreTLS {
		cfg["ignoreTls"] = true
	}
	if *noRedirects {
		cfg["followRedirects"] = false
	}
	if *certDays > 0 {
		cfg["certWarnDays"] = *certDays
	}
	if *socket != "" {
		cfg["socket"] = *socket
	}
	if len(cfg) > 0 {
		body["config"] = cfg
	}
	if *interval > 0 {
		body["intervalSeconds"] = *interval
	}
	if *timeout > 0 {
		body["timeoutMs"] = *timeout
	}
	if *failures > 0 {
		body["failuresBeforeDown"] = *failures
	}
	if *runFrom != "" {
		body["runFrom"] = strings.ToUpper(*runFrom)
	}
	if *disabled {
		body["enabled"] = false
	}
	serverName := ""
	if *server != "" {
		sv, err := resolveServer(c, *server)
		if err != nil {
			return e.fail(err)
		}
		body["serverId"] = sv.ID
		serverName = sv.Name
	}
	n := *name
	if n == "" {
		switch {
		case target != "":
			n = strings.ToLower(typ) + " " + target
		case serverName != "":
			n = strings.ToLower(typ) + " on " + serverName
		default:
			n = strings.ToLower(typ)
		}
	}
	body["name"] = n

	var created hubapi.CheckDef
	if err := c.Do(ctx, "POST", "/checks", body, &created); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Created check %q (%s), %s, runs on %s\n", created.Name, shortID(created.ID),
		created.Type, strings.ToLower(created.RunFrom))
	return exitOK
}

func resolveCheck(c *hubapi.Client, ref string) (*hubapi.CheckDef, error) {
	var defs []hubapi.CheckDef
	if err := c.Do(context.Background(), "GET", "/checks", nil, &defs); err != nil {
		return nil, err
	}
	for i := range defs {
		if defs[i].ID == ref {
			return &defs[i], nil
		}
	}
	var matches []*hubapi.CheckDef
	for i := range defs {
		if strings.EqualFold(defs[i].Name, ref) {
			matches = append(matches, &defs[i])
		}
	}
	if len(matches) == 0 && len(ref) >= 4 {
		for i := range defs {
			if strings.HasPrefix(defs[i].ID, ref) {
				matches = append(matches, &defs[i])
			}
		}
	}
	if len(matches) == 0 {
		needle := strings.ToLower(ref)
		for i := range defs {
			if strings.Contains(strings.ToLower(defs[i].Name), needle) {
				matches = append(matches, &defs[i])
			}
		}
	}
	switch len(matches) {
	case 0:
		return nil, fmt.Errorf("no check matches %q (see 'svcmon check list')", ref)
	case 1:
		return matches[0], nil
	default:
		return nil, fmt.Errorf("%q matches %d checks; use the ID from 'svcmon check list'", ref, len(matches))
	}
}

func resolveServer(c *hubapi.Client, ref string) (*hubapi.ServerInfo, error) {
	var servers []hubapi.ServerInfo
	if err := c.Do(context.Background(), "GET", "/servers", nil, &servers); err != nil {
		return nil, err
	}
	for i := range servers {
		if servers[i].ID == ref || strings.EqualFold(servers[i].Name, ref) {
			return &servers[i], nil
		}
	}
	if len(ref) >= 4 {
		for i := range servers {
			if strings.HasPrefix(servers[i].ID, ref) {
				return &servers[i], nil
			}
		}
	}
	return nil, fmt.Errorf("no registered server matches %q", ref)
}
