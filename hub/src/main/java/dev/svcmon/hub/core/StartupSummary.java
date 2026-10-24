package dev.svcmon.hub.core;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.svcmon.hub.config.HubProperties;
import dev.svcmon.hub.model.AlertChannel;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.RunFrom;
import dev.svcmon.hub.model.ServerInfo;
import dev.svcmon.hub.store.AlertChannelRepo;
import dev.svcmon.hub.store.CheckRepo;
import dev.svcmon.hub.store.ServerRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs what this hub is, where it listens and what it watches, once it is ready, so the service log (journal,
 * WinSW log) answers "is it up, on which address, watching what" without calling the API. It prints names and
 * types only, never check targets, alert settings or tokens: those can hold credentials. Names come from agents
 * and admins, so control characters are stripped before they reach the log.
 */
@Component
public class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private static final int MAX_LISTED = 20;
    private static final int MAX_NAMES_PER_SERVER = 8;
    private static final int MAX_NAME_LENGTH = 60;

    /** Everything the summary prints, so the layout can be tested without a running hub. */
    record Facts(String version, List<String> urls, Path database, Path uiDir, List<String> corsOrigins,
                 List<CheckDef> checks, List<ServerInfo> servers, List<AlertChannel> channels) {
    }

    private final HubProperties props;
    private final Environment env;
    private final String version;
    private final ServerRepo servers;
    private final CheckRepo checks;
    private final AlertChannelRepo channels;

    public StartupSummary(HubProperties props, Environment env, @Value("${svcmon.version:0.1.0}") String version,
                          ServerRepo servers, CheckRepo checks, AlertChannelRepo channels) {
        this.props = props;
        this.env = env;
        this.version = version;
        this.servers = servers;
        this.checks = checks;
        this.channels = channels;
    }

    /** Last of the ready listeners, so it follows the scheduler's and the admin token's own start-up lines. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void logSummary() {
        try {
            log.info("{}", describe());
        } catch (RuntimeException e) {
            // A log line must never stop the hub from monitoring.
            log.warn("Could not build the start-up summary: {}", e.toString());
        }
    }

    String describe() {
        Path ui = props.uiDir() == null ? null : props.uiDir().toAbsolutePath().normalize();
        return render(new Facts(version, urls(), props.dataDir().toAbsolutePath().normalize().resolve("svcmon.db"),
                ui != null && Files.isDirectory(ui) ? ui : null, props.corsAllowedOrigins(),
                checks.findAll(), servers.findAll(), channels.findAll()));
    }

    private List<String> urls() {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        String address = env.getProperty("server.address", "");
        if (!address.isBlank() && !address.equals("0.0.0.0") && !address.equals("::")) {
            return List.of("http://" + (address.contains(":") ? "[" + address + "]" : address) + ":" + port);
        }
        List<String> urls = new ArrayList<>();
        urls.add("http://localhost:" + port);
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (!host.isBlank() && !host.equalsIgnoreCase("localhost")) {
                urls.add("http://" + host + ":" + port);
            }
        } catch (IOException ignored) {
            // no resolvable host name: localhost is still correct
        }
        return urls;
    }

    static String render(Facts f) {
        StringBuilder out = new StringBuilder("Service Monitor hub ").append(f.version()).append(" is ready\n");
        line(out, "URL", String.join(", ", f.urls()) + " (plain HTTP; TLS belongs on the load balancer)");
        line(out, "Database", f.database().toString());
        line(out, "Dashboard", f.uiDir() == null
                ? "not served (set svcmon.ui-dir to serve the web UI)"
                : "served from " + f.uiDir());
        line(out, "CORS", f.corsOrigins().isEmpty()
                ? "off"
                : "browser access allowed from " + String.join(", ", f.corsOrigins()));

        List<AlertChannel> enabledChannels = f.channels().stream().filter(AlertChannel::enabled).toList();
        line(out, "Alerts", enabledChannels.isEmpty()
                ? "none enabled (add a channel through the API to be told when something goes down)"
                : enabledChannels.size() + " enabled of " + f.channels().size() + ": "
                + enabledChannels.stream().map(c -> clean(c.name()) + " (" + c.type() + ")")
                .collect(Collectors.joining(", ")));

        List<CheckDef> enabled = f.checks().stream().filter(CheckDef::enabled).toList();
        long disabled = f.checks().size() - enabled.size();
        if (enabled.isEmpty()) {
            line(out, "Watching", "nothing yet" + (disabled > 0 ? " (" + disabled + " disabled check(s))" : "")
                    + "; add servers and checks through the API or the svcmon CLI");
            return out.toString().stripTrailing();
        }
        Map<CheckType, Integer> byType = new EnumMap<>(CheckType.class);
        enabled.forEach(c -> byType.merge(c.type(), 1, Integer::sum));
        long onHub = enabled.stream().filter(c -> c.runFrom() == RunFrom.HUB).count();
        long onAgents = enabled.size() - onHub;
        line(out, "Watching", enabled.size() + " check(s) ("
                + byType.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "))
                + "): " + onHub + " run by the hub, " + onAgents + " by agents on " + f.servers().size()
                + " enrolled server(s)" + (disabled > 0 ? "; " + disabled + " disabled" : ""));

        List<CheckDef> hubChecks = enabled.stream().filter(c -> c.runFrom() == RunFrom.HUB).toList();
        if (!hubChecks.isEmpty()) {
            out.append("  Hub checks:\n");
            hubChecks.stream().limit(MAX_LISTED)
                    .forEach(c -> out.append("    ").append(c.type()).append("  ").append(clean(c.name())).append('\n'));
            more(out, hubChecks.size());
        }

        Map<String, List<CheckDef>> agentChecks = new LinkedHashMap<>();
        enabled.stream().filter(c -> c.runFrom() == RunFrom.AGENT && c.serverId() != null)
                .forEach(c -> agentChecks.computeIfAbsent(c.serverId(), k -> new ArrayList<>()).add(c));
        if (!f.servers().isEmpty()) {
            out.append("  Servers:\n");
            f.servers().stream().limit(MAX_LISTED).forEach(s -> {
                List<CheckDef> mine = agentChecks.getOrDefault(s.id(), List.of());
                out.append("    ").append(clean(s.name()));
                if (s.cluster() != null && !s.cluster().isBlank()) {
                    out.append(" [").append(clean(s.cluster())).append(']');
                }
                if (mine.isEmpty()) {
                    out.append(": no checks yet\n");
                    return;
                }
                out.append(": ").append(mine.size()).append(" check(s): ")
                        .append(mine.stream().limit(MAX_NAMES_PER_SERVER)
                                .map(c -> clean(c.name()) + " (" + c.type() + ")")
                                .collect(Collectors.joining(", ")))
                        .append(mine.size() > MAX_NAMES_PER_SERVER ? ", ..." : "").append('\n');
            });
            more(out, f.servers().size());
        }
        return out.toString().stripTrailing();
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append("  ").append(String.format("%-11s", label + ":")).append(value).append('\n');
    }

    private static void more(StringBuilder out, int total) {
        if (total > MAX_LISTED) {
            out.append("    ... and ").append(total - MAX_LISTED).append(" more\n");
        }
    }

    /** Names are free text from agents and admins; keep them to one printable line. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        return one.length() > MAX_NAME_LENGTH ? one.substring(0, MAX_NAME_LENGTH) + "..." : one;
    }
}
