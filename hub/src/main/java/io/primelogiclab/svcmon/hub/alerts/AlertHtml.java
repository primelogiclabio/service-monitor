package io.primelogiclab.svcmon.hub.alerts;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.model.Status;

/**
 * The HTML part of an alert email. Email clients render a small, old subset of HTML, so this is tables and inline
 * styles only (plus one block for dark mode), with no images, scripts, fonts or tracking. Names, targets and messages
 * can come from agents, so every value is escaped, text-direction tricks are removed, and the only link is built from
 * the configured public address and the server's id.
 */
final class AlertHtml {

    private static final int MAX_MESSAGE = 2000;
    private static final int TEST_COLOR = 0x475569;
    private static final String DARK_TEXT = "#111827";
    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";
    /** Left-to-right and right-to-left marks, embeddings, overrides and isolates, and the byte order mark. */
    private static final Pattern DIRECTION = Pattern.compile("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

    private AlertHtml() {
    }

    static String email(AlertEvent e, String hubId, String channelName, String publicUrl) {
        boolean test = e.kind() == AlertEvent.Kind.TEST;
        int color = test ? TEST_COLOR : AlertFormat.color(e.to());
        boolean recovered = !test && e.to() == Status.UP && (e.from() == Status.DOWN || e.from() == Status.WARN);
        String band = test ? "TEST" : recovered ? "UP &middot; recovered" : e.to().name();
        String title = line(AlertFormat.title(e));
        String message = e.message() == null ? "" : e.message().trim();
        String link = dashboardLink(publicUrl, e);

        StringBuilder h = new StringBuilder(6000);
        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\">\n")
                .append("<meta name=\"supported-color-schemes\" content=\"light dark\">\n")
                .append("<title>").append(esc(title)).append("</title>\n")
                .append("<style>@media (prefers-color-scheme: dark) {")
                .append(" .bg { background-color: #14161a !important; }")
                .append(" .card { background-color: #1e2127 !important; border-color: #343a43 !important; }")
                .append(" .text { color: #e8eaed !important; }")
                .append(" .muted { color: #a3a9b3 !important; }")
                .append(" .box { background-color: #14161a !important; border-color: #343a43 !important; color: #e8eaed !important; }")
                .append(" .rule { border-color: #343a43 !important; } }</style>\n")
                .append("</head>\n<body class=\"bg\" style=\"margin:0;padding:0;background-color:#f3f4f6;\">\n");

        // What the inbox list shows after the subject; the filler keeps the first lines of the body out of it.
        h.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;\">")
                .append(esc(title + (message.isBlank() ? "" : " - " + AlertFormat.truncate(line(message), 100))))
                .append("&nbsp;&zwnj;".repeat(40)).append("</div>\n");

        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" class=\"bg\"")
                .append(" style=\"background-color:#f3f4f6;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">\n")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" class=\"card\"")
                .append(" style=\"width:100%;max-width:600px;background-color:#ffffff;border:1px solid #e3e5e8;border-radius:8px;\">\n");

        // The status band: the word is always written out, so colour is never the only signal.
        h.append("<tr><td bgcolor=\"").append(hex(color)).append("\" style=\"background-color:").append(hex(color))
                .append(";color:").append(textColorFor(color)).append(";padding:14px 24px;border-radius:7px 7px 0 0;")
                .append("font:700 14px/1.2 ").append(FONT).append(";letter-spacing:.08em;\">").append(band).append("</td></tr>\n");

        h.append("<tr><td style=\"padding:24px 24px 16px 24px;\"><div class=\"text\" style=\"font:600 20px/1.35 ").append(FONT)
                .append(";color:").append(DARK_TEXT).append(";\">").append(esc(line(AlertFormat.headline(e)))).append("</div></td></tr>\n");

        if (!message.isBlank()) {
            h.append("<tr><td style=\"padding:0 24px 16px 24px;\"><div class=\"box\" style=\"background-color:#f6f7f9;")
                    .append("border:1px solid #e3e5e8;border-radius:6px;padding:12px 14px;font:13px/1.5 ").append(MONO)
                    .append(";color:").append(DARK_TEXT).append(";word-break:break-word;\">")
                    .append(esc(multiline(message)).replace("\n", "<br>")).append("</div></td></tr>\n");
        }

        h.append("<tr><td style=\"padding:0 24px 16px 24px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\"")
                .append(" cellspacing=\"0\" border=\"0\">\n");
        if (e.serverName() != null) {
            row(h, "Server", esc(line(e.serverName())));
        }
        if (e.kind() == AlertEvent.Kind.CHECK) {
            // "CPU (CPU)" says nothing twice; show the type only when the name is something else.
            row(h, "Check", line(e.name()).equalsIgnoreCase(line(e.type())) ? esc(line(e.name()))
                    : esc(line(e.name())) + " (" + esc(line(e.type())) + ")");
            if (e.target() != null && !e.target().isBlank()) {
                row(h, "Target", esc(line(e.target())));
            }
        }
        if (!test) {
            row(h, "Change", e.from() + " &rarr; " + e.to());
        }
        row(h, "Time", AlertFormat.human(e.ts()));
        h.append("</table></td></tr>\n");

        if (link != null) {
            h.append("<tr><td style=\"padding:0 24px 24px 24px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">")
                    .append("<tr><td bgcolor=\"#2563eb\" style=\"background-color:#2563eb;border-radius:6px;\">")
                    .append("<a href=\"").append(esc(link)).append("\" style=\"display:inline-block;padding:11px 20px;color:#ffffff;")
                    .append("text-decoration:none;font:600 14px/1 ").append(FONT).append(";\">Open in dashboard</a></td></tr></table></td></tr>\n");
        }

        String hub = hubId == null ? "" : " <span style=\"white-space:nowrap;\">" + esc(hubId.length() > 8 ? hubId.substring(0, 8) : hubId) + "</span>";
        h.append("<tr><td class=\"rule\" style=\"padding:16px 24px;border-top:1px solid #e3e5e8;\"><div class=\"muted\" style=\"font:12px/1.5 ")
                .append(FONT).append(";color:#6b7280;\">Sent by the svcmon hub").append(hub).append(" through the alert channel &ldquo;")
                .append(esc(line(channelName))).append("&rdquo;.</div></td></tr>\n");

        h.append("</table>\n</td></tr></table>\n</body>\n</html>\n");
        return h.toString();
    }

    private static void row(StringBuilder h, String label, String valueHtml) {
        h.append("<tr><td class=\"muted\" valign=\"top\" style=\"width:96px;padding:6px 12px 6px 0;font:13px/1.4 ").append(FONT)
                .append(";color:#6b7280;\">").append(label).append("</td><td class=\"text\" valign=\"top\" style=\"padding:6px 0;font:14px/1.4 ")
                .append(FONT).append(";color:").append(DARK_TEXT).append(";word-break:break-word;\">").append(valueHtml).append("</td></tr>\n");
    }

    /** Where "Open in dashboard" goes: the server's page, or the dashboard itself. Null without a public address. */
    static String dashboardLink(String publicUrl, AlertEvent e) {
        if (publicUrl == null) {
            return null;
        }
        if (e.kind() == AlertEvent.Kind.TEST || e.serverId() == null) {
            return publicUrl + "/";
        }
        return publicUrl + "/#/server/" + URLEncoder.encode(e.serverId(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** White or near-black text, whichever reads better on the colour (WCAG contrast). */
    static String textColorFor(int rgb) {
        double l = luminance(rgb);
        double onWhite = 1.05 / (l + 0.05);
        double onDark = (l + 0.05) / (luminance(0x111827) + 0.05);
        return onWhite >= onDark ? "#ffffff" : DARK_TEXT;
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static String hex(int rgb) {
        return String.format("#%06x", rgb);
    }

    /** One line of text with no control characters and no text-direction tricks. */
    private static String line(String s) {
        return DIRECTION.matcher(AlertFormat.oneLine(s)).replaceAll("");
    }

    /** Text that may span lines: line ends become \n, other control characters and direction tricks go, long text is cut. */
    private static String multiline(String s) {
        String text = s.replace("\r\n", "\n").replace('\r', '\n');
        text = DIRECTION.matcher(CONTROL.matcher(text).replaceAll("")).replaceAll("");
        return AlertFormat.truncate(text, MAX_MESSAGE);
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
