package io.primelogiclab.svcmon.hub.api;

import java.util.LinkedHashMap;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who the caller is, so a dashboard signed in through the proxy can show the name and check the hub accepts it. */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (req.getAttribute(TokenAuthFilter.JWT_USER_ATTR) instanceof String user) {
            m.put("method", "jwt");
            m.put("name", user);
            m.put("scope", "ADMIN");
        } else {
            TokenRecord token = (TokenRecord) req.getAttribute(TokenAuthFilter.ATTR);
            m.put("method", "token");
            m.put("name", token.name());
            m.put("scope", token.scope().name());
        }
        return m;
    }
}
