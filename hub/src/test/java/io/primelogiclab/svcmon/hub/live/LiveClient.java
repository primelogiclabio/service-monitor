package io.primelogiclab.svcmon.hub.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A bare WebSocket client for the live view tests: collects text frames and remembers how the hub closed. */
final class LiveClient implements WebSocket.Listener {

    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    final CompletableFuture<String> closeReason = new CompletableFuture<>();

    private final StringBuilder partial = new StringBuilder();
    private WebSocket socket;

    /** Opens the socket with no Authorization header, as a browser would. */
    static LiveClient connect(int port) {
        LiveClient client = new LiveClient();
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + LiveHandler.PATH), client)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
        return client;
    }

    void send(String text) {
        socket.sendText(text, true).join();
    }

    void auth(String token) {
        send("{\"type\":\"auth\",\"token\":\"" + token + "\"}");
    }

    /** The next frame, or null if none arrives in time. */
    String next(Duration wait) throws InterruptedException {
        return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    int awaitClose(Duration wait) throws Exception {
        return closeCode.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            frames.add(partial.toString());
            partial.setLength(0);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        closeCode.complete(statusCode);
        closeReason.complete(reason);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        closeCode.complete(-1);
        closeReason.complete(String.valueOf(error));
    }
}
