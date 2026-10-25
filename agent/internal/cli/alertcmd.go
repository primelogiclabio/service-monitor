package cli

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/url"
	"os"
	"strings"
	"text/tabwriter"

	"svcmon/internal/hubapi"
)

var (
	channelTypes = []string{"webhook", "slack", "discord", "email"}
	allStatuses  = []string{"DOWN", "WARN", "UP", "UNKNOWN"}
	flagsByType  = map[string][]string{
		"webhook": {"url", "header", "signing-secret"},
		"slack":   {"url"},
		"discord": {"url"},
		"email":   {"smtp-host", "smtp-port", "security", "format", "smtp-user", "smtp-password", "from", "to"},
	}
	typeSpecific = []string{"url", "header", "signing-secret", "smtp-host", "smtp-port", "security", "format", "smtp-user", "smtp-password", "from", "to"}
)

// listFlag collects a repeatable flag.
type listFlag []string

func (l *listFlag) String() string { return strings.Join(*l, ",") }
func (l *listFlag) Set(s string) error {
	*l = append(*l, s)
	return nil
}

type channelFlags struct {
	name, on, url, signingSecret           *string
	smtpHost, security, smtpUser, smtpPass *string
	from, format                           *string
	smtpPort                               *int
	headers, to                            *listFlag
	disabled                               *bool
}

func addChannelFlags(fs *flag.FlagSet, forAdd bool) *channelFlags {
	f := &channelFlags{headers: &listFlag{}, to: &listFlag{}}
	f.name = fs.String("name", "", "channel name")
	f.on = fs.String("on", "", "statuses that trigger a message: down,warn,up,unknown or all (default down,warn,up)")
	f.url = fs.String("url", "", "webhook, Slack or Discord URL (a secret; or set SVCMON_ALERT_URL)")
	fs.Var(f.headers, "header", `webhook: extra header "Name: value" (repeatable; replaces existing headers on set)`)
	f.signingSecret = fs.String("signing-secret", "", "webhook: sign the body with HMAC-SHA256 (or set SVCMON_ALERT_SIGNING_SECRET)")
	f.smtpHost = fs.String("smtp-host", "", "email: SMTP server")
	f.smtpPort = fs.Int("smtp-port", 0, "email: SMTP port (default 587)")
	f.security = fs.String("security", "", "email: starttls (default), ssl or none")
	f.format = fs.String("format", "", "email: html (default, plain text plus HTML) or text (plain text only, for pagers and SMS gateways)")
	f.smtpUser = fs.String("smtp-user", "", "email: SMTP login")
	f.smtpPass = fs.String("smtp-password", "", "email: SMTP password (or set SVCMON_SMTP_PASSWORD; a flag shows in process lists)")
	f.from = fs.String("from", "", "email: sender address")
	fs.Var(f.to, "to", "email: recipient address (repeatable or comma-separated)")
	if forAdd {
		f.disabled = fs.Bool("disabled", false, "create the channel switched off")
	}
	return f
}

func visited(fs *flag.FlagSet) map[string]bool {
	seen := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { seen[f.Name] = true })
	return seen
}

// parseStatuses turns "down,warn" or "all" into the hub's status names.
func parseStatuses(on string) ([]string, error) {
	if strings.EqualFold(strings.TrimSpace(on), "all") {
		return append([]string(nil), allStatuses...), nil
	}
	var out []string
	seen := map[string]bool{}
	for _, part := range strings.Split(on, ",") {
		s := strings.ToUpper(strings.TrimSpace(part))
		if s == "" {
			continue
		}
		known := false
		for _, a := range allStatuses {
			known = known || a == s
		}
		if !known {
			return nil, fmt.Errorf("--on: %q is not a status (use down, warn, up, unknown or all)", part)
		}
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	if len(out) == 0 {
		return nil, errors.New("--on needs at least one status")
	}
	return out, nil
}

// parseHeader accepts "Name: value" or "Name=value".
func parseHeader(s string) (string, string, error) {
	i := strings.IndexAny(s, ":=")
	if i <= 0 {
		return "", "", fmt.Errorf("--header %q: use \"Name: value\"", s)
	}
	name, value := strings.TrimSpace(s[:i]), strings.TrimSpace(s[i+1:])
	if name == "" || value == "" {
		return "", "", fmt.Errorf("--header %q: use \"Name: value\"", s)
	}
	return name, value, nil
}

// buildConfig turns flags into a channel's config. On add, secrets may also come from the environment, which
// keeps them out of shell history and process lists. base is the existing (masked) config on update.
func buildConfig(typ string, f *channelFlags, seen map[string]bool, base map[string]any, forAdd bool) (map[string]any, error) {
	applies := map[string]bool{}
	for _, name := range flagsByType[typ] {
		applies[name] = true
	}
	for _, name := range typeSpecific {
		if seen[name] && !applies[name] {
			return nil, fmt.Errorf("--%s does not apply to %s channels", name, typ)
		}
	}

	cfg := map[string]any{}
	for k, v := range base {
		cfg[k] = v
	}
	text := func(flagName, key, value, env string) {
		if seen[flagName] {
			cfg[key] = value
		} else if forAdd && env != "" {
			if v := os.Getenv(env); v != "" {
				cfg[key] = v
			}
		}
	}
	text("url", "url", *f.url, "SVCMON_ALERT_URL")

	if typ == "webhook" {
		if seen["header"] {
			headers := map[string]any{}
			for _, h := range *f.headers {
				n, v, err := parseHeader(h)
				if err != nil {
					return nil, err
				}
				headers[n] = v
			}
			cfg["headers"] = headers
		}
		text("signing-secret", "signingSecret", *f.signingSecret, "SVCMON_ALERT_SIGNING_SECRET")
	}
	if typ == "email" {
		text("smtp-host", "smtpHost", *f.smtpHost, "")
		if seen["smtp-port"] {
			cfg["smtpPort"] = *f.smtpPort
		}
		text("security", "security", *f.security, "")
		text("format", "format", *f.format, "")
		text("smtp-user", "username", *f.smtpUser, "")
		text("smtp-password", "password", *f.smtpPass, "SVCMON_SMTP_PASSWORD")
		text("from", "from", *f.from, "")
		if seen["to"] {
			var to []string
			for _, entry := range *f.to {
				for _, a := range strings.Split(entry, ",") {
					if a = strings.TrimSpace(a); a != "" {
						to = append(to, a)
					}
				}
			}
			cfg["to"] = to
		}
	}
	return cfg, nil
}

func (e *env) alertCmd(args []string) int {
	if len(args) == 0 {
		return e.alertList(nil)
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "list", "ls":
		return e.alertList(rest)
	case "show":
		return e.alertShow(rest)
	case "add":
		return e.alertAdd(rest)
	case "set":
		return e.alertSet(rest)
	case "enable":
		return e.alertEnable(rest, true)
	case "disable":
		return e.alertEnable(rest, false)
	case "rm", "remove", "delete":
		return e.alertRemove(rest)
	case "test":
		return e.alertTest(rest)
	default:
		return e.fail(fmt.Errorf("unknown alert command %q (list, show, add, set, enable, disable, rm, test)", sub))
	}
}

func (e *env) alertList(args []string) int {
	fs := e.flags("alert list")
	asJSON := fs.Bool("json", false, "print the hub's JSON")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var channels []hubapi.AlertChannel
	if err := c.Do(context.Background(), "GET", "/alert-channels", nil, &channels); err != nil {
		return e.fail(err)
	}
	if *asJSON {
		printJSON(e.out, channels)
		return exitOK
	}
	if len(channels) == 0 {
		fmt.Fprintln(e.out, "No alert channels yet. Add one with: svcmon alert add slack --name ops --url <webhook URL>")
		return exitOK
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\tNAME\tTYPE\tSTATE\tSENDS ON\tLAST OK\tLAST ERROR")
	for _, ch := range channels {
		state := "enabled"
		if !ch.Enabled {
			state = "disabled"
		}
		lastOK := "never"
		if ch.LastSuccessAt != nil {
			lastOK = ago(ch.LastSuccessAt)
		}
		lastErr := ch.LastError
		if len(lastErr) > 60 {
			lastErr = lastErr[:59] + "…"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", shortID(ch.ID), ch.Name, ch.Type, state,
			strings.Join(ch.Statuses, ","), lastOK, lastErr)
	}
	tw.Flush()
	return exitOK
}

func (e *env) alertShow(args []string) int {
	pos, code, ok := e.parse(e.flags("alert show"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon alert show <channel>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ch, err := resolveChannel(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	printJSON(e.out, ch)
	return exitOK
}

func (e *env) alertAdd(args []string) int {
	fs := e.flags("alert add")
	f := addChannelFlags(fs, true)
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(fmt.Errorf("usage: svcmon alert add <%s> --name NAME [flags]", strings.Join(channelTypes, "|")))
	}
	typ := strings.ToLower(pos[0])
	if _, known := flagsByType[typ]; !known {
		return e.fail(fmt.Errorf("unknown channel type %q (use %s)", pos[0], strings.Join(channelTypes, ", ")))
	}
	if strings.TrimSpace(*f.name) == "" {
		return e.fail(errors.New("--name is required"))
	}
	seen := visited(fs)
	cfg, err := buildConfig(typ, f, seen, nil, true)
	if err != nil {
		return e.fail(err)
	}
	body := map[string]any{"name": strings.TrimSpace(*f.name), "type": strings.ToUpper(typ), "config": cfg}
	if seen["on"] {
		statuses, err := parseStatuses(*f.on)
		if err != nil {
			return e.fail(err)
		}
		body["statuses"] = statuses
	}
	if *f.disabled {
		body["enabled"] = false
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var created hubapi.AlertChannel
	if err := c.Do(context.Background(), "POST", "/alert-channels", body, &created); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Created %s channel %q (%s), sends on %s.\nTry it: svcmon alert test %q\n",
		strings.ToLower(created.Type), created.Name, shortID(created.ID), strings.Join(created.Statuses, ","), created.Name)
	return exitOK
}

func (e *env) alertSet(args []string) int {
	fs := e.flags("alert set")
	f := addChannelFlags(fs, false)
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon alert set <channel> [flags]; only the flags you give are changed"))
	}
	seen := visited(fs)
	if len(seen) == 0 {
		return e.fail(errors.New("nothing to change: pass at least one flag (see 'svcmon help')"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ch, err := resolveChannel(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	// The hub returns secrets masked and keeps the stored value when a masked one comes back.
	cfg, err := buildConfig(strings.ToLower(ch.Type), f, seen, ch.Config, false)
	if err != nil {
		return e.fail(err)
	}
	body := map[string]any{"config": cfg}
	if seen["name"] {
		body["name"] = strings.TrimSpace(*f.name)
	}
	if seen["on"] {
		statuses, err := parseStatuses(*f.on)
		if err != nil {
			return e.fail(err)
		}
		body["statuses"] = statuses
	}
	var updated hubapi.AlertChannel
	if err := c.Do(context.Background(), "PUT", "/alert-channels/"+url.PathEscape(ch.ID), body, &updated); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Updated %s channel %q, sends on %s.\n", strings.ToLower(updated.Type), updated.Name, strings.Join(updated.Statuses, ","))
	return exitOK
}

func (e *env) alertEnable(args []string, enable bool) int {
	pos, code, ok := e.parse(e.flags("alert enable"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon alert enable|disable <channel>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ch, err := resolveChannel(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	if err := c.Do(context.Background(), "PUT", "/alert-channels/"+url.PathEscape(ch.ID), map[string]any{"enabled": enable}, nil); err != nil {
		return e.fail(err)
	}
	state := "enabled"
	if !enable {
		state = "disabled"
	}
	fmt.Fprintf(e.out, "Channel %q is now %s.\n", ch.Name, state)
	return exitOK
}

func (e *env) alertRemove(args []string) int {
	pos, code, ok := e.parse(e.flags("alert rm"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon alert rm <channel>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ch, err := resolveChannel(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	if err := c.Do(context.Background(), "DELETE", "/alert-channels/"+url.PathEscape(ch.ID), nil, nil); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Deleted channel %q.\n", ch.Name)
	return exitOK
}

func (e *env) alertTest(args []string) int {
	pos, code, ok := e.parse(e.flags("alert test"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(errors.New("usage: svcmon alert test <channel>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ch, err := resolveChannel(c, pos[0])
	if err != nil {
		return e.fail(err)
	}
	var res hubapi.AlertTestResult
	if err := c.Do(context.Background(), "POST", "/alert-channels/"+url.PathEscape(ch.ID)+"/test", nil, &res); err != nil {
		return e.fail(err)
	}
	if !res.OK {
		fmt.Fprintf(e.err, "FAILED: %s\n", res.Error)
		return exitUnknown
	}
	fmt.Fprintf(e.out, "Delivered a test alert to %q.\n", ch.Name)
	return exitOK
}

// resolveChannel finds a channel by ID, ID prefix, or (unique, case-insensitive) name.
func resolveChannel(c *hubapi.Client, ref string) (*hubapi.AlertChannel, error) {
	var channels []hubapi.AlertChannel
	if err := c.Do(context.Background(), "GET", "/alert-channels", nil, &channels); err != nil {
		return nil, err
	}
	for i := range channels {
		if channels[i].ID == ref {
			return &channels[i], nil
		}
	}
	var matches []*hubapi.AlertChannel
	for i := range channels {
		if strings.EqualFold(channels[i].Name, ref) {
			matches = append(matches, &channels[i])
		}
	}
	if len(matches) == 0 && len(ref) >= 4 {
		for i := range channels {
			if strings.HasPrefix(channels[i].ID, ref) {
				matches = append(matches, &channels[i])
			}
		}
	}
	switch len(matches) {
	case 0:
		return nil, fmt.Errorf("no alert channel matches %q (see 'svcmon alert list')", ref)
	case 1:
		return matches[0], nil
	default:
		return nil, fmt.Errorf("%q matches %d channels; use the ID from 'svcmon alert list'", ref, len(matches))
	}
}
