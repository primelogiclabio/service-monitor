package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	"svcmon/internal/agent"
	"svcmon/internal/checks"
	"svcmon/internal/hubapi"
	"svcmon/internal/osservice"
)

func (e *env) agentCmd(args []string) int {
	if len(args) == 0 {
		return e.fail(errors.New("usage: svcmon agent token|enroll|install|uninstall|run|services"))
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "token":
		return e.agentToken(rest)
	case "enroll":
		return e.agentEnroll(rest)
	case "install":
		return e.agentInstall(rest)
	case "uninstall":
		return e.agentUninstall(rest)
	case "run":
		return e.agentRun(rest)
	case "services":
		return e.agentServices(rest)
	default:
		return e.fail(fmt.Errorf("unknown agent command %q", sub))
	}
}

// agentToken runs on an admin's machine: it asks the hub for a one-time enrollment token.
func (e *env) agentToken(args []string) int {
	fs := e.flags("agent token")
	name := fs.String("name", "", "label for the token, e.g. the server it is for")
	ttl := fs.Int("ttl", 60, "minutes until the token expires (max 1440)")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	hub, token, err := e.resolve()
	if err != nil {
		return e.fail(err)
	}
	c := hubapi.New(hub, token)
	var issued hubapi.IssuedToken
	err = c.Do(context.Background(), "POST", "/enrollment-tokens", map[string]any{"name": *name, "ttlMinutes": *ttl}, &issued)
	if err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Enrollment token (single use, expires in %d min):\n  %s\n\n", *ttl, issued.Token)
	fmt.Fprintf(e.out, "On the server, as Administrator/root (use the hub's internal address if it differs from %s):\n", c.Base)
	fmt.Fprintf(e.out, "  svcmon agent install --hub %s --token %s\n", c.Base, issued.Token)
	return exitOK
}

func (e *env) agentEnroll(args []string) int {
	fs := e.flags("agent enroll")
	hub := fs.String("hub", "", "hub base URL (internal address)")
	token := fs.String("token", "", "enrollment token from 'svcmon agent token', or a join key")
	name := fs.String("name", "", "name for this server (default: host name)")
	cluster := fs.String("cluster", "", "cluster to place the server in")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "where to store the agent's config")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	if *hub == "" || *token == "" {
		return e.fail(errors.New("--hub and --token are required"))
	}
	cfg, err := e.enroll(*hub, *token, *name, *cluster, *cfgPath)
	if err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Registered as %q (server %s). Config saved to %s\n", cfg.Name, shortID(cfg.ServerID), *cfgPath)
	fmt.Fprintln(e.out, "Next: ask an admin to list the checks for this server, then run 'svcmon agent install' or 'svcmon agent run'.")
	return exitOK
}

// enroll registers this machine and writes the agent config. The target location is tested first, because
// an enrollment token can only be used once.
func (e *env) enroll(hub, enrollToken, name, cluster, cfgPath string) (*agent.FileConfig, error) {
	if name == "" {
		host, err := os.Hostname()
		if err != nil || host == "" {
			return nil, errors.New("cannot determine the host name; pass --name")
		}
		name = host
	}
	if err := checkWritable(cfgPath); err != nil {
		return nil, fmt.Errorf("cannot write %s (run as Administrator/root, or pass --config): %w", cfgPath, err)
	}
	c := hubapi.New(hub, enrollToken)
	var resp hubapi.RegisterResponse
	req := hubapi.RegisterRequest{Name: name, Cluster: cluster, OS: agent.DescribeOS(), AgentVersion: Version}
	if err := c.Do(context.Background(), "POST", "/agent/register", req, &resp); err != nil {
		return nil, err
	}
	cfg := &agent.FileConfig{Hub: c.Base, Name: name, ServerID: resp.ServerID, Token: resp.Token}
	if err := cfg.Save(cfgPath); err != nil {
		return nil, fmt.Errorf("registered, but saving %s failed: %w (delete the server %q on the hub and enroll again)", cfgPath, err, name)
	}
	return cfg, nil
}

// ensureEnrolled returns the agent's config, enrolling first when needed. An existing config is used as it is
// unless an enrollment token was given: that is a request to enroll again, for example after the server was
// deleted on the hub or moved to a new one, and --hub then defaults to the hub in the old config. The old config
// is only replaced once the hub has accepted the new enrollment.
func (e *env) ensureEnrolled(hub, token, name, cluster, cfgPath string) (*agent.FileConfig, error) {
	old, loadErr := agent.LoadConfig(cfgPath)
	if token == "" {
		if loadErr != nil {
			return nil, errors.New("not enrolled yet: --hub and --token are required")
		}
		fmt.Fprintf(e.out, "Already enrolled as %q; using %s\n", old.Name, cfgPath)
		return old, nil
	}
	if hub == "" {
		if loadErr != nil {
			return nil, errors.New("not enrolled yet: --hub and --token are required")
		}
		hub = old.Hub
	}
	if loadErr == nil {
		fmt.Fprintf(e.out, "Replacing the enrollment of %q in %s\n", old.Name, cfgPath)
	}
	cfg, err := e.enroll(hub, token, name, cluster, cfgPath)
	if err != nil {
		return nil, err
	}
	fmt.Fprintf(e.out, "Registered as %q (server %s)\n", cfg.Name, shortID(cfg.ServerID))
	return cfg, nil
}

func checkWritable(path string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".write-test-*")
	if err != nil {
		return err
	}
	name := f.Name()
	_ = f.Close()
	return os.Remove(name)
}

func (e *env) agentInstall(args []string) int {
	fs := e.flags("agent install")
	hub := fs.String("hub", "", "hub base URL (internal address); not needed if already enrolled")
	token := fs.String("token", "", "enrollment token or join key; not needed if already enrolled, and enrolls again if given")
	name := fs.String("name", "", "name for this server (default: host name)")
	cluster := fs.String("cluster", "", "cluster to place the server in")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "agent config file")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	if _, err := e.ensureEnrolled(*hub, *token, *name, *cluster, *cfgPath); err != nil {
		return e.fail(err)
	}
	exe, err := os.Executable()
	if err != nil {
		return e.fail(err)
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	cfgAbs, err := filepath.Abs(*cfgPath)
	if err != nil {
		return e.fail(err)
	}
	opts := osservice.DefaultOptions(exe, []string{"agent", "run", "--config", cfgAbs})
	if err := osservice.Install(opts); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Service %q installed and started, running %s. Do not move or delete that file.\n", opts.Name, exe)
	return exitOK
}

func (e *env) agentUninstall(args []string) int {
	if _, code, ok := e.parse(e.flags("agent uninstall"), args); !ok {
		return code
	}
	if err := osservice.Uninstall(osservice.Name); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Service %q removed. The server still appears on the hub until an admin deletes it.\n", osservice.Name)
	return exitOK
}

func (e *env) agentRun(args []string) int {
	fs := e.flags("agent run")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "agent config file")
	once := fs.Bool("once", false, "run every assigned check once, report, print the results and exit")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	cfg, err := agent.LoadConfig(*cfgPath)
	if err != nil {
		return e.fail(err)
	}

	if osservice.IsService() {
		logger, closer := serviceLogger(*cfgPath)
		if closer != nil {
			defer closer.Close()
		}
		a := agent.New(cfg, logger, Version)
		if err := osservice.RunService(osservice.Name, a.Run); err != nil {
			logger.Printf("service stopped with error: %v", err)
			return exitUnknown
		}
		return exitOK
	}

	logger := log.New(e.err, "", log.LstdFlags)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	a := agent.New(cfg, logger, Version)
	if *once {
		results, err := a.RunOnce(ctx)
		for _, r := range results {
			fmt.Fprintf(e.out, "%-7s %s  %s\n", r.Status, shortID(r.CheckID), r.Message)
		}
		if err != nil {
			return e.fail(err)
		}
		if len(results) == 0 {
			fmt.Fprintln(e.out, "No checks are assigned to this server yet.")
		}
		return exitOK
	}
	if err := a.Run(ctx); err != nil {
		return e.fail(err)
	}
	return exitOK
}

// serviceLogger sends the agent's log to agent.log next to its config, since a service has no console.
func serviceLogger(cfgPath string) (*log.Logger, io.Closer) {
	p := filepath.Join(filepath.Dir(cfgPath), "agent.log")
	if fi, err := os.Stat(p); err == nil && fi.Size() > 10<<20 {
		_ = os.Rename(p, p+".1")
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640)
	if err != nil {
		return log.New(io.Discard, "", 0), nil
	}
	return log.New(f, "", log.LstdFlags), f
}

// agentServices is the discovery helper: it lists what exists so an admin can choose, and monitors nothing.
func (e *env) agentServices(args []string) int {
	fs := e.flags("agent services")
	filter := fs.String("filter", "", "only names or descriptions containing this text")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	list, err := checks.ListServices(ctx)
	if err != nil {
		return e.fail(err)
	}
	needle := strings.ToLower(*filter)
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "NAME\tSTATE\tDESCRIPTION")
	for _, s := range list {
		if needle != "" && !strings.Contains(strings.ToLower(s.Name+" "+s.Display), needle) {
			continue
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\n", s.Name, s.State, s.Display)
	}
	tw.Flush()
	fmt.Fprintln(e.err, "\nNothing here is monitored. Add a check with: svcmon check add service <name> --server <server>")
	return exitOK
}
