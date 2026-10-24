// Command svcmon is the agent and the command-line client for the svcmon hub.
package main

import (
	"os"

	"svcmon/internal/cli"
)

func main() {
	os.Exit(cli.Run(os.Args[1:], os.Stdout, os.Stderr))
}
