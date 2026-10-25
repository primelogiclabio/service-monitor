package cli

import (
	"errors"
	"fmt"
	"slices"
	"strings"
	"text/tabwriter"

	"svcmon/internal/agent"
	"svcmon/internal/checks"
	"svcmon/internal/hubapi"
)

// agentCheck manages the checks file beside agent.json, so what a server monitors can be changed on the server
// itself, with no login to the hub. After a change it hands the file to the hub at once; a running agent would
// do the same within a minute.
func (e *env) agentCheck(args []string) int {
	if len(args) == 0 {
		return e.agentCheckList(nil)
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "list", "ls":
		return e.agentCheckList(rest)
	case "add":
		return e.agentCheckAdd(rest)
	case "rm", "remove", "delete":
		return e.agentCheckRemove(rest)
	default:
		return e.fail(fmt.Errorf("unknown agent check command %q (list, add, rm)", sub))
	}
}

func (e *env) agentCheckList(args []string) int {
	fs := e.flags("agent check list")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "agent config file; the checks file sits beside it")
	asJSON := fs.Bool("json", false, "print the checks file's contents")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	path := agent.ChecksPath(*cfgPath)
	list, sum, err := agent.LoadChecks(path)
	if err != nil {
		return e.fail(err)
	}
	if sum == "" {
		fmt.Fprintf(e.out, "No checks file at %s yet. Add a check with: svcmon agent check add <type> [target]\n", path)
		return exitOK
	}
	if *asJSON {
		printJSON(e.out, map[string]any{"checks": list})
		return exitOK
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "TYPE\tTARGET\tNAME\tID\tEVERY")
	for _, c := range list {
		every := "default"
		if c.IntervalSeconds > 0 {
			every = fmt.Sprintf("%ds", c.IntervalSeconds)
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\n", strings.ToUpper(strings.TrimSpace(c.Type)), c.Target, c.Name, c.ID, every)
	}
	tw.Flush()
	return exitOK
}

func (e *env) agentCheckAdd(args []string) int {
	fs := e.flags("agent check add")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "agent config file; the checks file sits beside it")
	noDeclare := fs.Bool("no-declare", false, "only change the file; do not send it to the hub now")
	name := fs.String("name", "", "display name (made up from the type and target when missing)")
	id := fs.String("id", "", "tells two checks of one type and target apart")
	interval := fs.Int("interval", 0, "seconds between runs")
	timeout := fs.Int("timeout", 0, "timeout in milliseconds")
	failures := fs.Int("failures", 0, "consecutive failures before the check is DOWN")
	settings := addSettingFlags(fs)
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) < 1 || len(pos) > 2 {
		return e.fail(errors.New("usage: svcmon agent check add <type> [target] [flags]"))
	}
	typ := strings.ToUpper(pos[0])
	if !slices.Contains(checks.Types, typ) {
		return e.fail(fmt.Errorf("unknown check type %q; use one of %s", pos[0], strings.Join(checks.Types, ", ")))
	}
	if *interval < 0 || *timeout < 0 || *failures < 0 {
		return e.fail(errors.New("--interval, --timeout and --failures cannot be negative"))
	}
	entry := hubapi.DeclaredCheck{
		ID:                 strings.TrimSpace(*id),
		Name:               strings.TrimSpace(*name),
		Type:               strings.ToLower(typ),
		Config:             settings.config(),
		IntervalSeconds:    *interval,
		TimeoutMs:          *timeout,
		FailuresBeforeDown: *failures,
	}
	if len(pos) == 2 {
		entry.Target = pos[1]
	}

	path := agent.ChecksPath(*cfgPath)
	list, _, err := agent.LoadChecks(path)
	if err != nil {
		return e.fail(err)
	}
	for _, have := range list {
		if agent.CheckKey(have) != agent.CheckKey(entry) {
			continue
		}
		if entry.ID != "" {
			return e.fail(fmt.Errorf("the id %q is already used in %s", entry.ID, path))
		}
		return e.fail(fmt.Errorf("%s is already in %s; remove it first with 'svcmon agent check rm', or give --id to add a second check of the same type and target",
			describeDeclared(entry), path))
	}
	list = append(list, entry)
	if err := agent.SaveChecks(path, list); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Added %s to %s (%d check(s))\n", describeDeclared(entry), path, len(list))
	return e.sendEdit(*cfgPath, list, *noDeclare)
}

func (e *env) agentCheckRemove(args []string) int {
	fs := e.flags("agent check rm")
	cfgPath := fs.String("config", agent.DefaultConfigPath(), "agent config file; the checks file sits beside it")
	noDeclare := fs.Bool("no-declare", false, "only change the file; do not send it to the hub now")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) < 1 || len(pos) > 2 {
		return e.fail(errors.New("usage: svcmon agent check rm <name|id> | <type> [target]"))
	}
	path := agent.ChecksPath(*cfgPath)
	list, sum, err := agent.LoadChecks(path)
	if err != nil {
		return e.fail(err)
	}
	if sum == "" {
		return e.fail(fmt.Errorf("no checks file at %s", path))
	}
	i, err := findDeclared(list, pos)
	if err != nil {
		return e.fail(err)
	}
	gone := list[i]
	list = slices.Delete(list, i, i+1)
	if err := agent.SaveChecks(path, list); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Removed %s from %s (%d check(s) left)\n", describeDeclared(gone), path, len(list))
	return e.sendEdit(*cfgPath, list, *noDeclare)
}

// sendEdit hands the changed file to the hub, which shows what it did with each entry. The file is saved
// either way: a hub that cannot be reached, or that has agent-declared checks off, only means the hub does
// not know yet, and a running agent sends the file by itself once it can.
func (e *env) sendEdit(cfgPath string, list []hubapi.DeclaredCheck, noDeclare bool) int {
	if noDeclare {
		fmt.Fprintln(e.out, "Not sent to the hub (--no-declare). A running agent sends the file within a minute, or run 'svcmon agent declare'.")
		return exitOK
	}
	resp, err := declareNow(cfgPath, list)
	if err != nil {
		fmt.Fprintf(e.err, "The file is saved, but the hub did not take it: %v\nA running agent sends the file by itself once the hub takes it.\n", err)
		return exitWarn
	}
	return e.printDeclared(list, resp)
}

// findDeclared picks the one entry that pos names: an id or a name, or a type with its target. A type alone
// names the entry when there is only one of it.
func findDeclared(list []hubapi.DeclaredCheck, pos []string) (int, error) {
	var found []int
	switch len(pos) {
	case 2:
		typ, target := strings.ToUpper(strings.TrimSpace(pos[0])), strings.TrimSpace(pos[1])
		for i, c := range list {
			if strings.ToUpper(strings.TrimSpace(c.Type)) == typ && strings.TrimSpace(c.Target) == target {
				found = append(found, i)
			}
		}
	default:
		ref := strings.TrimSpace(pos[0])
		for _, match := range []func(hubapi.DeclaredCheck) bool{
			func(c hubapi.DeclaredCheck) bool { return c.ID == ref },
			func(c hubapi.DeclaredCheck) bool { return strings.EqualFold(c.Name, ref) },
			func(c hubapi.DeclaredCheck) bool { return strings.EqualFold(strings.TrimSpace(c.Type), ref) },
		} {
			for i, c := range list {
				if match(c) {
					found = append(found, i)
				}
			}
			if len(found) > 0 {
				break
			}
		}
	}
	switch len(found) {
	case 0:
		return 0, fmt.Errorf("no check in the file matches %q (see 'svcmon agent check list')", strings.Join(pos, " "))
	case 1:
		return found[0], nil
	default:
		return 0, fmt.Errorf("%q matches %d checks; use the id, or the type and the target", strings.Join(pos, " "), len(found))
	}
}

func describeDeclared(c hubapi.DeclaredCheck) string {
	typ := strings.ToUpper(strings.TrimSpace(c.Type))
	if c.Target == "" {
		return typ
	}
	return typ + " " + c.Target
}
