package io.primelogiclab.svcmon.hub.alerts;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.Status;

/**
 * Builds the text and JSON each channel type sends. Names and messages can come from agents, so anything that
 * could be read as markup, a mention or a mail header is neutralised here.
 */
final class AlertFormat {

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private AlertFormat() {
    }

    static String human(long ts) {
        return HUMAN.format(Instant.ofEpochMilli(ts));
    }

    static String iso(long ts) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ts));
    }

    /** One line, no control characters: safe for a mail subject or a chat title. */
    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }

    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** "[DOWN] web01 / CPU". */
    static String title(AlertEvent e) {
        return e.kind() == AlertEvent.Kind.TEST ? "[TEST] svcmon alert channel" : "[" + e.to() + "] " + oneLine(e.subject());
    }

    /** A sentence saying what happened. */
    static String headline(AlertEvent e) {
        String subject = oneLine(e.subject());
        return switch (e.kind()) {
            case TEST -> "This is a test alert from svcmon.";
            case SERVER -> e.to() == Status.UP
                    ? oneLine(e.serverName()) + " is reporting again"
                    : oneLine(e.serverName()) + " stopped reporting";
            case CHECK -> subject + " is now " + e.to() + " (was " + e.from() + ")";
        };
    }

    static String plainBody(AlertEvent e, String hubId) {
        List<String> lines = new ArrayList<>();
        lines.add(headline(e));
        if (e.message() != null && !e.message().isBlank()) {
            lines.add(e.message().trim());
        }
        lines.add("");
        if (e.kind() == AlertEvent.Kind.CHECK) {
            lines.add("Check: " + oneLine(e.name()) + " (" + e.type() + ")");
            if (e.target() != null && !e.target().isBlank()) {
                lines.add("Target: " + oneLine(e.target()));
            }
        }
        if (e.serverName() != null) {
            lines.add("Server: " + oneLine(e.serverName()));
        }
        lines.add("Status: " + e.from() + " -> " + e.to());
        lines.add("Time: " + human(e.ts()));
        if (hubId != null) {
            lines.add("Hub: " + hubId);
        }
        return String.join("\n", lines) + "\n";
    }

    // ---- Slack ----

    /** Slack treats &, < and > as markup (links, @channel); escape them. */
    static String slackEscape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String emoji(Status s) {
        return switch (s) {
            case DOWN -> ":red_circle:";
            case WARN -> ":warning:";
            case UP -> ":large_green_circle:";
            case UNKNOWN -> ":white_circle:";
        };
    }

    static String hexColor(Status s) {
        return String.format("#%06x", color(s));
    }

    static int color(Status s) {
        return switch (s) {
            case DOWN -> 0xD03B3B;
            case WARN -> 0xFAB219;
            case UP -> 0x0CA30C;
            case UNKNOWN -> 0x898781;
        };
    }

    static Map<String, Object> slackPayload(AlertEvent e) {
        String heading = emoji(e.to()) + " *" + e.to() + "* " + slackEscape(oneLine(e.subject()));
        StringBuilder detail = new StringBuilder();
        if (e.message() != null && !e.message().isBlank()) {
            detail.append(slackEscape(truncate(e.message().trim(), 2500))).append('\n');
        }
        detail.append("_").append(slackEscape(e.from() + " -> " + e.to())).append(" · ").append(human(e.ts())).append("_");

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", hexColor(e.to()));
        attachment.put("text", detail.toString());
        attachment.put("mrkdwn_in", List.of("text"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", heading);
        payload.put("attachments", List.of(attachment));
        return payload;
    }

    // ---- Discord ----

    static Map<String, Object> discordPayload(AlertEvent e) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", truncate(title(e), 256));
        embed.put("description", truncate(e.message() == null ? headline(e) : e.message().trim(), 4000));
        embed.put("color", color(e.to()));
        embed.put("timestamp", iso(e.ts()));
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Change", e.from() + " -> " + e.to(), true));
        if (e.kind() == AlertEvent.Kind.CHECK) {
            fields.add(field("Type", e.type(), true));
        }
        if (e.serverName() != null) {
            fields.add(field("Server", truncate(oneLine(e.serverName()), 1000), true));
        }
        embed.put("fields", fields);

        Map<String, Object> payload = new LinkedHashMap<>();
        // Names and messages are not trusted: never let them ping @everyone or roles.
        payload.put("allowed_mentions", Map.of("parse", List.of()));
        payload.put("embeds", List.of(embed));
        return payload;
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", value == null || value.isBlank() ? "-" : value);
        f.put("inline", inline);
        return f;
    }

    // ---- Webhook ----

    static Map<String, Object> webhookPayload(AlertEvent e, String hubId) {
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("id", e.id());
        subject.put("name", e.name());
        subject.put("type", e.type());
        if (e.target() != null) {
            subject.put("target", e.target());
        }
        if (e.serverId() != null) {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("id", e.serverId());
            server.put("name", e.serverName());
            subject.put("server", server);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "status_changed");
        payload.put("kind", e.kind().name().toLowerCase(java.util.Locale.ROOT));
        payload.put("hub", hubId);
        payload.put("time", e.ts());
        payload.put("subject", subject);
        payload.put("from", e.from().name());
        payload.put("to", e.to().name());
        payload.put("message", e.message());
        return payload;
    }
}
