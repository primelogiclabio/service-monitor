package agent

import (
	"context"
	"errors"
	"net/http"

	"svcmon/internal/hubapi"
)

// OpenMaintenance asks the hub to hold back this server's alerts, before a planned restart. An open window only
// ever gets a later end.
func (a *Agent) OpenMaintenance(ctx context.Context, req hubapi.MaintenanceRequest) (*hubapi.Maintenance, error) {
	var m hubapi.Maintenance
	if err := a.client.Do(ctx, "POST", "/agent/maintenance", req, &m); err != nil {
		return nil, err
	}
	return &m, nil
}

// EndMaintenance ends this server's maintenance window now; it says false when none was open.
func (a *Agent) EndMaintenance(ctx context.Context) (bool, error) {
	err := a.client.Do(ctx, "DELETE", "/agent/maintenance", nil, nil)
	var apiErr *hubapi.APIError
	if errors.As(err, &apiErr) && apiErr.Status == http.StatusNotFound {
		return false, nil
	}
	return err == nil, err
}
