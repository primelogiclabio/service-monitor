package cli

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"text/tabwriter"

	"svcmon/internal/hubapi"
)

// A join key registers any number of agents with one install command, until it is revoked or expires.
func (e *env) joinKeyCmd(args []string) int {
	if len(args) == 0 {
		return e.fail(errors.New("usage: svcmon join-key create|list|revoke"))
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "create":
		return e.joinKeyCreate(rest)
	case "list", "ls":
		return e.joinKeyList(rest)
	case "revoke", "rm":
		return e.joinKeyRevoke(rest)
	default:
		return e.fail(fmt.Errorf("unknown join-key command %q (create, list, revoke)", sub))
	}
}

func (e *env) joinKeyCreate(args []string) int {
	fs := e.flags("join-key create")
	name := fs.String("name", "", "what the key is for, e.g. 'app servers'")
	cluster := fs.String("cluster", "", "put every server it registers in this cluster (default: any)")
	days := fs.Int("expires-days", 0, "days until the key stops working (default: never, until revoked)")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	if *name == "" {
		return e.fail(errors.New("--name is required"))
	}
	if *days < 0 {
		return e.fail(errors.New("--expires-days must be 1 or more, or left out for no expiry"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	body := map[string]any{"name": *name}
	if strings.TrimSpace(*cluster) != "" {
		body["cluster"] = strings.TrimSpace(*cluster)
	}
	if *days > 0 {
		body["expiresInDays"] = *days
	}
	var issued hubapi.IssuedToken
	if err := c.Do(context.Background(), "POST", "/join-keys", body, &issued); err != nil {
		return e.fail(err)
	}
	life := "no expiry; revoke it with 'svcmon join-key revoke'"
	if issued.ExpiresAt != nil {
		life = "expires " + fmtMillis(*issued.ExpiresAt)
	}
	fmt.Fprintf(e.out, "Join key %q (%s). It is shown only now; keep it like a password:\n  %s\n\n", issued.Name, life, issued.Token)
	if issued.Cluster != "" {
		fmt.Fprintf(e.out, "Every server it registers goes in cluster %q.\n", issued.Cluster)
	}
	fmt.Fprintf(e.out, "On each server, as Administrator/root (use the hub's internal address if it differs from %s):\n", c.Base)
	fmt.Fprintf(e.out, "  svcmon agent install --hub %s --token %s\n", c.Base, issued.Token)
	return exitOK
}

func (e *env) joinKeys(c *hubapi.Client) ([]hubapi.TokenView, error) {
	var keys []hubapi.TokenView
	err := c.Do(context.Background(), "GET", "/join-keys", nil, &keys)
	return keys, err
}

func (e *env) joinKeyList(args []string) int {
	if _, code, ok := e.parse(e.flags("join-key list"), args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	keys, err := e.joinKeys(c)
	if err != nil {
		return e.fail(err)
	}
	if len(keys) == 0 {
		fmt.Fprintln(e.out, "No join keys. Create one with: svcmon join-key create --name N")
		return exitOK
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\tNAME\tCLUSTER\tSERVERS\tLAST USED\tCREATED\tEXPIRES")
	for _, k := range keys {
		cluster, used, exp := "any", "-", "never"
		if k.Cluster != "" {
			cluster = k.Cluster
		}
		if k.UsedAt != nil {
			used = fmtMillis(*k.UsedAt)
		}
		if k.ExpiresAt != nil {
			exp = fmtMillis(*k.ExpiresAt)
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%d\t%s\t%s\t%s\n", shortID(k.ID), k.Name, cluster, k.UseCount, used, fmtMillis(k.CreatedAt), exp)
	}
	tw.Flush()
	return exitOK
}

func (e *env) joinKeyRevoke(args []string) int {
	pos, code, ok := e.parse(e.flags("join-key revoke"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 || len(pos[0]) < 4 {
		return e.fail(errors.New("usage: svcmon join-key revoke <id>   (at least 4 characters of the ID from 'join-key list')"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	keys, err := e.joinKeys(c)
	if err != nil {
		return e.fail(err)
	}
	var match []hubapi.TokenView
	for _, k := range keys {
		if k.ID == pos[0] || strings.HasPrefix(k.ID, pos[0]) {
			match = append(match, k)
		}
	}
	if len(match) != 1 {
		return e.fail(fmt.Errorf("%q matches %d join keys", pos[0], len(match)))
	}
	if err := c.Do(context.Background(), "DELETE", "/join-keys/"+url.PathEscape(match[0].ID), nil, nil); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Revoked join key %q. Servers it registered keep working.\n", match[0].Name)
	return exitOK
}
