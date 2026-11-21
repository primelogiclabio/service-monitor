package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;

import javax.net.ssl.SSLHandshakeException;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;

class DeliveryFailureTest {

    private static final URI HOOK = URI.create("https://hooks.example/services/T000/B000/secret-token?x=1");

    /** What Java's HttpClient throws: a message-less ConnectException wrapping the real cause. */
    private static IOException wrapped(Throwable cause) {
        ConnectException inner = new ConnectException();
        inner.initCause(cause);
        ConnectException outer = new ConnectException();
        outer.initCause(inner);
        return outer;
    }

    @Test
    void aRefusedWebhookNamesTheHostAndPortAndSaysWhy() {
        assertEquals("Could not connect to hooks.example:443: the connection was refused",
                DeliveryFailure.webhook(wrapped(new ClosedChannelException()), HOOK));
        assertEquals("Could not connect to 127.0.0.1:9: the connection was refused",
                DeliveryFailure.webhook(new ConnectException("Connection refused"), URI.create("http://127.0.0.1:9/hook/abc")));
        assertEquals("Could not connect to receiver.internal:80: the connection was refused",
                DeliveryFailure.webhook(new ConnectException("Connection refused: connect"), URI.create("http://receiver.internal/hook")));
        assertEquals("Could not connect to hooks.example:443: No route to host",
                DeliveryFailure.webhook(new ConnectException("No route to host"), HOOK));
    }

    @Test
    void anUnknownNameTimeoutsAndTlsSayWhatWentWrong() {
        assertEquals("Could not find the host hooks.example",
                DeliveryFailure.webhook(wrapped(new UnresolvedAddressException()), HOOK));
        assertEquals("Could not connect to hooks.example:443: no answer in time",
                DeliveryFailure.webhook(new HttpConnectTimeoutException("HTTP connect timed out"), HOOK));
        assertEquals("hooks.example:443 took the request but did not answer in time",
                DeliveryFailure.webhook(new HttpTimeoutException("request timed out"), HOOK));
        String tls = DeliveryFailure.webhook(new IOException(new SSLHandshakeException("PKIX path building failed")), HOOK);
        assertEquals("TLS with hooks.example:443 failed: PKIX path building failed", tls);
    }

    @Test
    void errorTextNeverCarriesTheUrlsSecretParts() {
        String other = DeliveryFailure.webhook(new IOException("failed for " + HOOK + " twice\nand again"), HOOK);
        assertEquals("Could not deliver to hooks.example:443: IOException: failed for <url> twice and again", other);
        String tls = DeliveryFailure.webhook(new SSLHandshakeException("bad certificate for " + HOOK), HOOK);
        for (String text : new String[] {other, tls}) {
            assertFalse(text.contains("secret-token") || text.contains("/services/") || text.contains("x=1"), text);
        }
        String quiet = DeliveryFailure.webhook(new IOException(), HOOK);
        assertEquals("Could not deliver to hooks.example:443: IOException", quiet);
        String loud = DeliveryFailure.webhook(new IOException("x".repeat(1000)), HOOK);
        assertTrue(loud.length() < 300, loud);
    }

    @Test
    void mailFailuresNameTheMailServer() {
        assertEquals("Could not connect to the mail server smtp.example:587: the connection was refused",
                DeliveryFailure.mail(new MessagingException("Couldn't connect to host, port: smtp.example, 587; timeout 10000",
                        new ConnectException("Connection refused: connect")), "smtp.example", 587));
        assertEquals("Could not find the mail server nope.invalid",
                DeliveryFailure.mail(new MessagingException("Couldn't connect", new UnknownHostException("nope.invalid")),
                        "nope.invalid", 25));
        assertEquals("The mail server smtp.example:465 did not answer in time",
                DeliveryFailure.mail(new MessagingException("Couldn't connect", new SocketTimeoutException("Connect timed out")),
                        "smtp.example", 465));
        assertEquals("TLS with the mail server smtp.example:465 failed: PKIX path building failed",
                DeliveryFailure.mail(new MessagingException("Could not convert socket to TLS",
                        new SSLHandshakeException("PKIX path building failed")), "smtp.example", 465));
        assertEquals("Could not send the email: STARTTLS is required but host does not support STARTTLS",
                DeliveryFailure.mail(new MessagingException("STARTTLS is required but host does not support STARTTLS"),
                        "smtp.example", 587));
    }
}
