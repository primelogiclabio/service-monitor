// Package cli implements the svcmon command line: hub queries for admins and the agent's own commands.
package cli

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"

	"svcmon/internal/hubapi"
)

// Version is set at build time with -ldflags "-X svcmon/internal/cli.Version=...".
var Version = "0.1.0"

// Exit codes follow the monitoring convention so svcmon works in scripts and cron jobs.
const (
	exitOK      = 0
	exitWarn    = 1
	exitDown    = 2
	exitUnknown = 3 // also used for errors: the answer could not be determined
)

type env struct {
	out, err io.Writer
	hub      string
	token    string
}

// Run executes one svcmon command line and returns the process exit code.
func Run(args []string, out, errw io.Writer) int {
	fs := flag.NewFlagSet("svcmon", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	fs.Usage = func() {}
	hub := fs.String("hub", "", "")
	token := fs.String("token", "", "")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			usage(out)
			return exitOK
		}
		fmt.Fprintln(errw, "error:", err)
		usage(errw)
		return exitUnknown
	}
	rest := fs.Args()
	if len(rest) == 0 {
		usage(errw)
		return exitUnknown
	}
	e := &env{out: out, err: errw, hub: *hub, token: *token}
	cmd, cargs := rest[0], rest[1:]
	switch cmd {
	case "help", "-h", "--help":
		usage(out)
		return exitOK
	case "version":
		fmt.Fprintf(out, "svcmon %s (%s/%s)\n", Version, runtime.GOOS, runtime.GOARCH)
		return exitOK
	case "login":
		return e.login(cargs)
	case "hub":
		return e.hubInfo(cargs)
	case "status":
		return e.status(cargs)
	case "server":
		return e.server(cargs)
	case "check", "checks":
		return e.check(cargs)
	case "token":
		return e.tokenCmd(cargs)
	case "join-key", "join-keys":
		return e.joinKeyCmd(cargs)
	case "alert", "alerts":
		return e.alertCmd(cargs)
	case "agent":
		return e.agentCmd(cargs)
	default:
		fmt.Fprintf(errw, "error: unknown command %q\n\n", cmd)
		usage(errw)
		return exitUnknown
	}
}

func usage(w io.Writer) {
	fmt.Fprint(w, `svcmon - service monitor client and agent

Usage: svcmon [--hub URL] [--token TOKEN] <command> [arguments]

Hub commands (need a hub URL and token: 'svcmon login', or SVCMON_HUB / SVCMON_TOKEN):
  login --hub URL --token TOKEN   Save credentials after checking them
  hub                             Show hub identity, version and Java runtime age
  status [--cluster NAME] [--json] [--watch]
                                  Roll-up of clusters, servers and checks.
                                  Exit code: 0 ok, 1 warning, 2 down, 3 unknown or error
  server <name|id>                One server with its checks
  check list                      All checks
  check show <check>              One check as JSON
  check add <type> [target] [flags]
                                  Types: http tcp ping (hub or agent), service container cpu memory disk (agent)
                                  Flags: --name --server --run-from hub|agent --interval S --timeout MS
                                         --failures N --warn P --crit P --keyword TEXT --expect-status 200-299
                                         --ignore-tls --no-redirects --cert-warn-days N --socket PATH
                                         --set key=value --disabled
  check rm <check>                Delete a check
  check run <check>               Run a hub-side check now
  check history <check> [--limit N] [--since 1h|30m|2d]
  token create --name N --scope read|admin
  token list | token revoke <id>
  join-key create --name N [--cluster C] [--expires-days D]
                                  A reusable key that registers any number of servers (no expiry
                                  unless --expires-days); with --cluster they all go in that cluster
  join-key list | join-key revoke <id>
  alert list                      Alert channels (where status changes are sent)
  alert add <webhook|slack|discord|email> --name N [flags]
                                  webhook/slack/discord: --url (or SVCMON_ALERT_URL); webhook also --header
                                  "Name: value" and --signing-secret; email: --smtp-host --smtp-port
                                  --security starttls|ssl|none --smtp-user --smtp-password --from --to
                                  all: --on down,warn,up,unknown|all  --disabled
  alert set <channel> [flags]     Change only the flags you give; stored secrets are kept
  alert enable|disable <channel>
  alert test <channel>            Send one test alert now
  alert show <channel> | alert rm <channel>

Agent commands (run on a monitored server):
  agent token [--name N] [--ttl MIN]   Create a one-time enrollment token (hub side)
  agent enroll --hub URL --token TOKEN [--name N] [--cluster C] [--config FILE]
  agent install --hub URL --token TOKEN [--name N] [--cluster C]
                                  Enroll if needed, then install and start the OS service (admin/root).
                                  TOKEN is a one-time enrollment token or a join key
  agent uninstall                 Stop and remove the OS service
  agent run [--config FILE] [--once]
                                  Run in the foreground (what the service runs)
  agent services [--filter TEXT]  List services on this machine (nothing is monitored automatically)
  agent containers [--filter TEXT] [--socket PATH]
                                  List Docker/Podman containers on this machine (nothing is monitored
                                  automatically); --socket if the runtime is not in the usual place

Other: version, help
`)
}

func (e *env) flags(name string) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(e.err)
	return fs
}

// parse reads flags and positional arguments in any order.
func (e *env) parse(fs *flag.FlagSet, args []string) (pos []string, code int, ok bool) {
	for {
		if err := fs.Parse(args); err != nil {
			if errors.Is(err, flag.ErrHelp) {
				return nil, exitOK, false
			}
			return nil, exitUnknown, false
		}
		args = fs.Args()
		if len(args) == 0 {
			return pos, 0, true
		}
		pos = append(pos, args[0])
		args = args[1:]
	}
}

func (e *env) fail(err error) int {
	fmt.Fprintln(e.err, "error:", err)
	return exitUnknown
}

type savedConfig struct {
	Hub   string `json:"hub"`
	Token string `json:"token"`
}

func cliConfigPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "svcmon", "cli.json"), nil
}

func (e *env) resolve() (hub, token string, err error) {
	hub, token = e.hub, e.token
	if hub == "" {
		hub = os.Getenv("SVCMON_HUB")
	}
	if token == "" {
		token = os.Getenv("SVCMON_TOKEN")
	}
	if hub == "" || token == "" {
		if p, perr := cliConfigPath(); perr == nil {
			if data, rerr := os.ReadFile(p); rerr == nil {
				var sc savedConfig
				if json.Unmarshal(data, &sc) == nil {
					if hub == "" {
						hub = sc.Hub
					}
					if token == "" {
						token = sc.Token
					}
				}
			}
		}
	}
	if hub == "" || token == "" {
		return "", "", errors.New("no hub configured: run 'svcmon login --hub URL --token TOKEN', " +
			"set SVCMON_HUB and SVCMON_TOKEN, or pass --hub and --token")
	}
	return hub, token, nil
}

func (e *env) client() (*hubapi.Client, error) {
	hub, token, err := e.resolve()
	if err != nil {
		return nil, err
	}
	return hubapi.New(hub, token), nil
}

func (e *env) login(args []string) int {
	fs := e.flags("login")
	hub := fs.String("hub", e.hub, "hub base URL, e.g. https://monitor.example.com")
	token := fs.String("token", e.token, "ADMIN or READ API token")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	if *hub == "" || *token == "" {
		return e.fail(errors.New("--hub and --token are required"))
	}
	c := hubapi.New(*hub, *token)
	var info map[string]any
	if err := c.Do(context.Background(), "GET", "/hub", nil, &info); err != nil {
		return e.fail(fmt.Errorf("cannot log in to %s: %w", c.Base, err))
	}
	path, err := cliConfigPath()
	if err != nil {
		return e.fail(err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return e.fail(err)
	}
	data, _ := json.MarshalIndent(savedConfig{Hub: c.Base, Token: *token}, "", "  ")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Logged in to hub %v (version %v). Saved to %s\n", info["hubId"], info["version"], path)
	return exitOK
}

func (e *env) hubInfo(args []string) int {
	fs := e.flags("hub")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var info map[string]any
	if err := c.Do(context.Background(), "GET", "/hub", nil, &info); err != nil {
		return e.fail(err)
	}
	keys := make([]string, 0, len(info))
	for k := range info {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		fmt.Fprintf(e.out, "%-18s %v\n", k, formatValue(k, info[k]))
	}
	if w, ok := info["runtimeWarning"]; ok {
		fmt.Fprintf(e.err, "warning: %v\n", w)
		return exitWarn
	}
	return exitOK
}

func formatValue(key string, v any) any {
	if f, ok := v.(float64); ok && (strings.HasSuffix(key, "At") || key == "time") {
		return fmtMillis(int64(f))
	}
	return v
}
