package io.primelogiclab.svcmon.hub.api;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.core.AgentService;
import io.primelogiclab.svcmon.hub.core.AgentService.AgentConfig;
import io.primelogiclab.svcmon.hub.core.AgentService.RegisterRequest;
import io.primelogiclab.svcmon.hub.core.AgentService.RegisterResponse;
import io.primelogiclab.svcmon.hub.core.AgentService.ReportRequest;
import io.primelogiclab.svcmon.hub.core.AgentService.ReportResponse;
import io.primelogiclab.svcmon.hub.core.DeclaredChecksService;
import io.primelogiclab.svcmon.hub.core.DeclaredChecksService.DeclareRequest;
import io.primelogiclab.svcmon.hub.core.DeclaredChecksService.DeclareResponse;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The protocol agents speak. Agents only ever call out to these endpoints. */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentService agents;

    private final DeclaredChecksService declared;

    private final AuditService audit;

    public AgentController(AgentService agents, DeclaredChecksService declared, AuditService audit) {
        this.agents = agents;
        this.declared = declared;
        this.audit = audit;
    }

    /** The agent's whole list of declared checks, from the checks file beside its config. Refused when the admin turned the setting off. */
    @PutMapping("/checks")
    public DeclareResponse declare(@RequestAttribute(TokenAuthFilter.ATTR) TokenRecord token,
                                   @RequestBody DeclareRequest request) {
        return declared.declare(token, request);
    }

    /**
     * Authenticated with a one-time enrollment token or a join key; returns the agent's own credential. The only
     * agent call the audit records: which server joined, with which token or key (by name).
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestAttribute(TokenAuthFilter.ATTR) TokenRecord token,
                                     @RequestBody RegisterRequest request, HttpServletRequest req) {
        RegisterResponse registered = agents.register(token, request);
        audit.change("agent_registered", "ok", new AuditService.Actor(token.name(),
                        token.scope() == Scope.JOIN ? "join_key" : "enrollment_token"), req.getRemoteAddr(),
                "POST /api/v1/agent/register", request.name(), request.cluster() == null ? null : "cluster " + request.cluster());
        return registered;
    }

    /** The checks this agent should run. Also counts as a heartbeat. */
    @GetMapping("/config")
    public AgentConfig config(@RequestAttribute(TokenAuthFilter.ATTR) TokenRecord token) {
        return agents.config(token);
    }

    /** Results from the agent; an empty list is a plain heartbeat. */
    @PostMapping("/report")
    public ReportResponse report(@RequestAttribute(TokenAuthFilter.ATTR) TokenRecord token,
                                 @RequestBody ReportRequest request) {
        return agents.report(token, request);
    }
}
