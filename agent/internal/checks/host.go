package checks

import (
	"context"
	"fmt"
	"runtime"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/mem"
)

// cpuCheck samples overall CPU use. Config: warn (85), crit (95), in percent used.
func cpuCheck(ctx context.Context, c Check) Result {
	sample := time.Second
	if half := c.Timeout / 2; half < sample {
		sample = half
	}
	pct, err := cpu.PercentWithContext(ctx, sample, false)
	if err != nil || len(pct) == 0 {
		return result(c, Unknown, nil, nil, "cannot read CPU use: "+fmt.Sprint(err))
	}
	v := round1(pct[0])
	status := level(v, cfgFloat(c.Config, "warn", 85), cfgFloat(c.Config, "crit", 95))
	return result(c, status, nil, ptr(v), fmt.Sprintf("CPU %.1f%% used", v))
}

// memoryCheck reports physical memory use. Config: warn (85), crit (95).
func memoryCheck(ctx context.Context, c Check) Result {
	vm, err := mem.VirtualMemoryWithContext(ctx)
	if err != nil {
		return result(c, Unknown, nil, nil, "cannot read memory use: "+err.Error())
	}
	v := round1(vm.UsedPercent)
	status := level(v, cfgFloat(c.Config, "warn", 85), cfgFloat(c.Config, "crit", 95))
	return result(c, status, nil, ptr(v),
		fmt.Sprintf("memory %.1f%% used (%s free of %s)", v, humanBytes(vm.Available), humanBytes(vm.Total)))
}

// diskCheck reports space used on the volume or path in Target. Config: warn (80), crit (90).
func diskCheck(ctx context.Context, c Check) Result {
	path := strings.TrimSpace(c.Target)
	if runtime.GOOS == "windows" && len(path) == 2 && path[1] == ':' {
		path += `\`
	}
	u, err := disk.UsageWithContext(ctx, path)
	if err != nil {
		return result(c, Unknown, nil, nil, "cannot read disk "+path+": "+err.Error())
	}
	v := round1(u.UsedPercent)
	status := level(v, cfgFloat(c.Config, "warn", 80), cfgFloat(c.Config, "crit", 90))
	return result(c, status, nil, ptr(v),
		fmt.Sprintf("%s %.1f%% used (%s free of %s)", path, v, humanBytes(u.Free), humanBytes(u.Total)))
}
