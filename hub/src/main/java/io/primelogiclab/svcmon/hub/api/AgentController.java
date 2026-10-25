package io.primelogiclab.svcmon.hub.api;

import io.primelogiclab.svcmon.hub.core.AgentService;
import io.primelogiclab.svcmon.hub.core.AgentService.AgentConfig;
import io.primelogiclab.svcmon.hub.core.AgentService.RegisterRequest;
import io.primelogiclab.svcmon.hub.core.AgentService.RegisterResponse;
import io.primelogiclab.svcmon.hub.core.AgentService.ReportRequest;
import io.primelogiclab.svcmon.hub.core.AgentService.ReportResponse;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.TokenAuthFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AgentController(AgentService agents) {
        this.agents = agents;
    }

    /** Authenticated with a one-time enrollment token; returns the agent's own credential. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestAttribute(TokenAuthFilter.ATTR) TokenRecord token,
                                     @RequestBody RegisterRequest request) {
        return agents.register(token, request);
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
