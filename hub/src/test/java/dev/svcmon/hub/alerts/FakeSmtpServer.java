package dev.svcmon.hub.alerts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Just enough SMTP to receive mail without TLS or login: records sender, recipients and the message text.
 * Used with security NONE, so no certificates are needed.
 */
final class FakeSmtpServer implements AutoCloseable {

    record Mail(String from, List<String> to, String data) {
    }

    private final ServerSocket server;
    private final List<Mail> mails = new CopyOnWriteArrayList<>();

    FakeSmtpServer() throws IOException {
        server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-smtp");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    List<Mail> mails() {
        return mails;
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                handle(socket);
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        reply(out, "220 fake ESMTP ready");
        String from = null;
        List<String> to = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                reply(out, "250 fake");
            } else if (upper.startsWith("MAIL FROM:")) {
                from = address(line);
                to = new ArrayList<>();
                reply(out, "250 OK");
            } else if (upper.startsWith("RCPT TO:")) {
                to.add(address(line));
                reply(out, "250 OK");
            } else if (upper.equals("DATA")) {
                reply(out, "354 end with <CRLF>.<CRLF>");
                StringBuilder data = new StringBuilder();
                while ((line = in.readLine()) != null && !line.equals(".")) {
                    data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                }
                mails.add(new Mail(from, to, data.toString()));
                reply(out, "250 queued");
            } else if (upper.equals("QUIT")) {
                reply(out, "221 bye");
                return;
            } else {
                reply(out, "250 OK"); // RSET, NOOP
            }
        }
    }

    private static String address(String line) {
        int open = line.indexOf('<');
        int close = line.indexOf('>');
        return open >= 0 && close > open ? line.substring(open + 1, close) : line;
    }

    private static void reply(Writer out, String text) throws IOException {
        out.write(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
