package cli

import (
	"context"
	"fmt"
	"strings"
	"time"

	"svcmon/internal/hubapi"
)

func (e *env) status(args []string) int {
	fs := e.flags("status")
	cluster := fs.String("cluster", "", "show only this cluster")
	asJSON := fs.Bool("json", false, "print the hub's JSON instead of a table")
	watch := fs.Bool("watch", false, "refresh until interrupted")
	every := fs.Int("every", 5, "seconds between refreshes with --watch")
	if _, code, ok := e.parse(fs, args); !ok {
		return code
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}

	render := func() int {
		var st hubapi.Status
		if err := c.Do(context.Background(), "GET", "/status", nil, &st); err != nil {
			return e.fail(err)
		}
		overall := st.Summary.Overall
		if *cluster != "" {
			var kept []hubapi.ClusterView
			for _, cl := range st.Clusters {
				if strings.EqualFold(cl.Name, *cluster) {
					kept = append(kept, cl)
					overall = cl.Status
				}
			}
			if len(kept) == 0 {
				return e.fail(fmt.Errorf("no cluster named %q", *cluster))
			}
			st.Clusters, st.HubChecks = kept, nil
		}
		if *asJSON {
			printJSON(e.out, st)
		} else {
			printStatus(e.out, st)
		}
		return statusExit(overall)
	}

	if !*watch {
		return render()
	}
	enableANSI()
	for {
		fmt.Fprint(e.out, "\033[H\033[2J")
		render()
		time.Sleep(time.Duration(max(*every, 1)) * time.Second)
	}
}

func (e *env) server(args []string) int {
	fs := e.flags("server")
	asJSON := fs.Bool("json", false, "print JSON")
	pos, code, ok := e.parse(fs, args)
	if !ok {
		return code
	}
	if len(pos) != 1 {
		return e.fail(fmt.Errorf("usage: svcmon server <name|id>"))
	}
	c, err := e.client()
	if err != nil {
		return e.fail(err)
	}
	var st hubapi.Status
	if err := c.Do(context.Background(), "GET", "/status", nil, &st); err != nil {
		return e.fail(err)
	}
	for _, cl := range st.Clusters {
		for _, sv := range cl.Servers {
			if strings.EqualFold(sv.Name, pos[0]) || sv.ID == pos[0] || (len(pos[0]) >= 4 && strings.HasPrefix(sv.ID, pos[0])) {
				if *asJSON {
					printJSON(e.out, sv)
				} else {
					fmt.Fprintf(e.out, "CLUSTER %s\n", cl.Name)
					printServer(e.out, sv, "")
				}
				return statusExit(sv.Status)
			}
		}
	}
	return e.fail(fmt.Errorf("no server matches %q", pos[0]))
}
