package io.primelogiclab.svcmon.hub.api;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import io.primelogiclab.svcmon.hub.security.HubIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HubController {

    /** JDK quarterly updates plus slack; an older runtime is reported as needing an update. */
    private static final long RUNTIME_MAX_AGE_DAYS = 120;

    private final HubIdentity identity;
    private final String version;
    private final long startedAt = System.currentTimeMillis();

    public HubController(HubIdentity identity, @Value("${spring.application.name}") String name,
                         @Value("${svcmon.version:dev}") String version) {
        this.identity = identity;
        this.version = version;
    }

    /** Unauthenticated liveness probe. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/hub")
    public Map<String, Object> hub() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hubId", identity.id());
        m.put("version", version);
        m.put("startedAt", startedAt);
        m.put("time", System.currentTimeMillis());
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaVendor", System.getProperty("java.vendor"));
        String released = System.getProperty("java.version.date");
        m.put("javaReleaseDate", released);
        if (released != null) {
            try {
                long age = ChronoUnit.DAYS.between(LocalDate.parse(released), LocalDate.now());
                m.put("javaRuntimeAgeDays", age);
                if (age > RUNTIME_MAX_AGE_DAYS) {
                    m.put("runtimeWarning", "Java runtime is " + age
                            + " days old; update to the latest security release");
                }
            } catch (RuntimeException ignored) {
                // unparseable release date: skip the age check
            }
        }
        return m;
    }
}
