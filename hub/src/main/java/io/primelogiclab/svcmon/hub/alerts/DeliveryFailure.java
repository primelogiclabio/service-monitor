package io.primelogiclab.svcmon.hub.alerts;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;

import javax.net.ssl.SSLException;

import jakarta.mail.MessagingException;

/**
 * Says where a delivery failed and why. Java's own text for a failed connection is often empty (an alert to a
 * closed port read only "ConnectException"), so the receiver is named here: its host and port, which the API shows
 * in the masked URL too, never a webhook URL's path or query, where its secret is.
 */
final class DeliveryFailure {

    private DeliveryFailure() {
    }

    /** For a POST to {@code uri} (webhook, Slack, Discord) that ended in {@code e}. */
    static String webhook(IOException e, URI uri) {
        String host = String.valueOf(uri.getHost());
        String where = host + ":" + (uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        String url = uri.toString();
        if (e instanceof HttpConnectTimeoutException) {
            return "Could not connect to " + where + ": no answer in time";
        }
        if (e instanceof HttpTimeoutException) {
            return where + " took the request but did not answer in time";
        }
        // The client reports an unknown name as a ConnectException too, so this comes first.
        if (cause(e, UnresolvedAddressException.class) != null || cause(e, UnknownHostException.class) != null) {
            return "Could not find the host " + host;
        }
        SSLException tls = cause(e, SSLException.class);
        if (tls != null) {
            return "TLS with " + where + " failed" + detail(tls, url);
        }
        ConnectException connect = cause(e, ConnectException.class);
        if (connect != null) {
            return "Could not connect to " + where + ": " + reason(connect);
        }
        return "Could not deliver to " + where + ": " + e.getClass().getSimpleName() + detail(e, url);
    }

    /** For mail to the SMTP server at {@code host}:{@code port} that ended in {@code e}. */
    static String mail(MessagingException e, String host, int port) {
        String where = host + ":" + port;
        if (cause(e, UnknownHostException.class) != null) {
            return "Could not find the mail server " + host;
        }
        if (cause(e, SocketTimeoutException.class) != null) {
            return "The mail server " + where + " did not answer in time";
        }
        SSLException tls = cause(e, SSLException.class);
        if (tls != null) {
            return "TLS with the mail server " + where + " failed" + detail(tls, null);
        }
        ConnectException connect = cause(e, ConnectException.class);
        if (connect != null) {
            return "Could not connect to the mail server " + where + ": " + reason(connect);
        }
        return "Could not send the email" + detail(e, null);
    }

    /** {@code e} itself or the first of its causes of that type, else null. */
    static <T extends Throwable> T cause(Throwable e, Class<T> type) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /** Java says nothing, or "Connection refused" (with ": connect" on Windows), for a port nothing listens on. */
    private static String reason(ConnectException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank() || message.toLowerCase(Locale.ROOT).contains("refused")) {
            return "the connection was refused";
        }
        return AlertFormat.truncate(AlertFormat.oneLine(message), 200);
    }

    private static String detail(Throwable e, String url) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        return ": " + AlertFormat.truncate(HttpPoster.scrub(AlertFormat.oneLine(message), url), 200);
    }
}
