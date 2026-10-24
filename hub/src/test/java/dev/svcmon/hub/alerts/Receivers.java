package dev.svcmon.hub.alerts;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server that records every POST. Paths starting with /fail answer 500, everything else 204 (the
 * answer Discord gives), so tests can see both delivery and failure handling.
 */
final class Receivers implements AutoCloseable {

    record Hit(String path, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<Hit> hits = new CopyOnWriteArrayList<>();

    Receivers() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
            String path = exchange.getRequestURI().getPath();
            hits.add(new Hit(path, headers, body));
            if (path.startsWith("/fail")) {
                byte[] answer = "receiver says no".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, answer.length);
                exchange.getResponseBody().write(answer);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    List<Hit> hits(String path) {
        return hits.stream().filter(h -> h.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
