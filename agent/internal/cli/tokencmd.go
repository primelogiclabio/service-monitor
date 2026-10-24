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

func (e *env) tokenCmd(args []string) int {
	if len(args) == 0 {
		return e.fail(errors.New("usage: svcmon token create|list|revoke"))
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "create":
		return e.tokenCreate(rest)
	case "list", "ls":
		return e.tokenList(rest)
	case "revoke", "rm":
		return e.tokenRevoke(rest)
	default:
		return e.fail(fmt.Errorf("unknown token command %q (create, list, revoke)", sub))
	}
}

func (e *env) tokenCreate(args []string) int {
	fs := e.flags("token create")
	name := fs.String("name", "", "what the token is for, e.g. 'ops laptop'")
	scope := fs.String("scope", "read", "read or admin")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	if *name == "" {
		return e.fail(errors.New("--name is required"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var issued hubapi.IssuedToken
	err = c.Do(context.Background(), "POST", "/tokens", map[string]any{
		"name": *name, "scope": strings.ToUpper(*scope),
	}, &issued)
	if err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Token %q (%s). It is shown only now; store it safely:\n  %s\n", issued.Name, issued.Scope, issued.Token)
	return exitOK
}

func (e *env) tokenList(args []string) int {
	if _, code, ok := e.parse(e.flags("token list"), args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var tokens []hubapi.TokenView
	if err := c.Do(context.Background(), "GET", "/tokens", nil, &tokens); err != nil {
		return e.fail(err)
	}
	tw := tabwriter.NewWriter(e.out, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\tSCOPE\tNAME\tCREATED\tEXPIRES\tUSED")
	for _, t := range tokens {
		exp, used := "-", "-"
		if t.ExpiresAt != nil {
			exp = fmtMillis(*t.ExpiresAt)
		}
		if t.UsedAt != nil {
			used = fmtMillis(*t.UsedAt)
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\n", shortID(t.ID), t.Scope, t.Name, fmtMillis(t.CreatedAt), exp, used)
	}
	tw.Flush()
	return exitOK
}

func (e *env) tokenRevoke(args []string) int {
	pos, code, ok := e.parse(e.flags("token revoke"), args)
	if !ok {
		return code
	}
	if len(pos) != 1 || len(pos[0]) < 4 {
		return e.fail(errors.New("usage: svcmon token revoke <id>   (at least 4 characters of the ID from 'token list')"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	ctx := context.Background()
	var tokens []hubapi.TokenView
	if err := c.Do(ctx, "GET", "/tokens", nil, &tokens); err != nil {
		return e.fail(err)
	}
	var match []hubapi.TokenView
	for _, t := range tokens {
		if t.ID == pos[0] || strings.HasPrefix(t.ID, pos[0]) {
			match = append(match, t)
		}
	}
	if len(match) != 1 {
		return e.fail(fmt.Errorf("%q matches %d tokens", pos[0], len(match)))
	}
	if err := c.Do(ctx, "DELETE", "/tokens/"+url.PathEscape(match[0].ID), nil, nil); err != nil {
		return e.fail(err)
	}
	fmt.Fprintf(e.out, "Revoked token %q (%s)\n", match[0].Name, match[0].Scope)
	return exitOK
}
