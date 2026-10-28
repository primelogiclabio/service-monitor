package io.primelogiclab.svcmon.hub.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("svcmon")
public record HubProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("30") int agentHeartbeatSeconds,
        @DefaultValue("90") int agentStaleSeconds,
        String bootstrapAdminToken,
        @DefaultValue("false") boolean newAdminToken,
        /** Folder with the built web UI (the UI project's dist folder). Unset means the hub serves no UI. */
        Path uiDir,
        /** Exact origins (scheme://host[:port]) allowed to call the API from a browser. Empty means CORS is off. */
        @DefaultValue List<String> corsAllowedOrigins,
        /** Lets Slack and Discord alert URLs use http. Off by default; only for tests and local receivers. */
        @DefaultValue("false") boolean allowInsecureAlertUrls,
        /** Most dashboards that may hold the live WebSocket open at once; more are told to try again later. */
        @DefaultValue("50") int liveMaxSessions,
        /** The address people open the dashboard at (https://monitor.example.com); gives alert emails an "Open in dashboard" link. */
        String publicUrl,
        /** Lets agents declare their own checks in a file beside their config. On unless the admin turns it off. */
        @DefaultValue("true") boolean agentDeclaredChecks,
        /** Most checks one server may declare. */
        @DefaultValue("200") int agentDeclaredChecksLimit,
        /** People who sign in to the dashboard with a name and password; on unless turned off. */
        @DefaultValue Users users,
        /** How long a sign-in lasts. */
        @DefaultValue Sessions sessions,
        /** False makes the hub headless: no dashboard is served, from ui-dir or from inside the jar. */
        @DefaultValue("true") boolean uiEnabled,
        /** Where inside the jar the dashboard sits; a jar built without one serves none. Only tests change it. */
        @DefaultValue("classpath:/ui/") String embeddedUi,
        /** Whether people may use the hub from outside the internal network; agents are never affected. */
        @DefaultValue Access access,
        /** How long the audit of sign-ins and changes is kept. */
        @DefaultValue Audit audit) {

    /** Days the audit keeps each kind of event, 1 to 3650; older events are deleted. */
    public record Audit(
            @DefaultValue("30") int signInDays,
            @DefaultValue("365") int changeDays) {

        public static final Audit DEFAULT = new Audit(30, 365);
    }

    /**
     * Where people may use the hub from. A request is outside when its address (after the load balancer's
     * X-Forwarded-For) is not in {@code internalNetworks}, or is one of {@code publicProxies}. From outside, an API
     * token and the initial password are always refused; a signed-in user only when {@code allowOutside} is true and
     * the request came over HTTPS. Values are IP addresses or ranges such as 10.0.0.0/8, never names.
     */
    public record Access(
            /** True lets people sign in from outside, over HTTPS only; meant for a hub behind a gateway's sign-in. */
            @DefaultValue("false") boolean allowOutside,
            /** The internal network. Setting it replaces the whole list, loopback included. */
            @DefaultValue({"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
            List<String> internalNetworks,
            /** Proxies that people outside come through; a request that shows up as one of them is outside. */
            @DefaultValue List<String> publicProxies) {

        public static final Access DEFAULT = new Access(false,
                List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"), List.of());
    }

    /**
     * The hub's own users. On a hub without any, the first start creates an administrator with the initial
     * password, which works only from the internal network and must be changed at the first sign-in. Passwords are
     * stored only as salted hashes.
     */
    public record Users(
            /** False means nobody signs in to the dashboard: only API tokens (the CLI, scripts) use the hub. */
            @DefaultValue("true") boolean enabled,
            /** The first administrator's name, 1 to 64 letters, digits and . _ @ -; used only to create it. */
            @DefaultValue("admin") String initialAdminUsername,
            /** The first administrator's password, used only to create it. */
            @DefaultValue("admin") String initialAdminPassword) {

        public static final Users DEFAULT = new Users(true, "admin", "admin");
    }

    /** A sign-in ends after {@code maxHours}, or sooner when it is not used for {@code idleMinutes}. */
    public record Sessions(
            /** 5 to 1440; the dashboard's own refreshing does not count as use. */
            @DefaultValue("60") int idleMinutes,
            /** 1 to 168. */
            @DefaultValue("12") int maxHours) {

        public static final Sessions DEFAULT = new Sessions(60, 12);
    }
}
