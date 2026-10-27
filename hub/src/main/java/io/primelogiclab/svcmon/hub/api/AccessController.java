package io.primelogiclab.svcmon.hub.api;

import java.util.List;

import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where people may use the hub from (svcmon.access), and how the hub sees the caller, so a person can check the
 * setting from a phone outside the network instead of trusting it. Read-only: the settings live in application.yml.
 */
@RestController
@RequestMapping("/api/v1")
public class AccessController {

    /** {@code secure} says whether this request reached the hub over HTTPS, as the load balancer reported it. */
    public record AccessView(boolean allowOutside, List<String> internalNetworks, List<String> publicProxies,
                             String network, String address, boolean secure) {
    }

    private final NetworkPolicy network;

    public AccessController(NetworkPolicy network) {
        this.network = network;
    }

    @GetMapping("/access")
    public AccessView access(HttpServletRequest req) {
        return new AccessView(network.allowOutside(), network.internalNetworks(), network.publicProxies(),
                network.isInside(req) ? "inside" : "outside", req.getRemoteAddr(), req.isSecure());
    }
}
