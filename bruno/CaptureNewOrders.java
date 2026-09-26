// Run from the repository root with JDK 21+ (no dependencies):
//   java bruno/CaptureNewOrders.java 30 > /tmp/wfm-socket-raw.json
// Stdout is a JSON array of complete received text messages, in arrival order. Diagnostics go
// to stderr. The raw capture contains trader identities; scrub it before keeping it as a fixture.
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class CaptureNewOrders implements WebSocket.Listener {
    private final StringBuilder fragments = new StringBuilder();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private boolean capturing = true;
    private int messages;

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].matches("[1-9][0-9]*"))) {
            throw new IllegalArgumentException("Usage: java bruno/CaptureNewOrders.java [seconds=30]");
        }
        int seconds = args.length == 0 ? 30 : Integer.parseInt(args[0]);
        var listener = new CaptureNewOrders();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        WebSocket socket = null;
        System.out.println("[");
        try {
            socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .subprotocols("wfm")
                .header("User-Agent",
                    "wf-market-watchdawg/0.1 (+https://github.com/j-ameswong/wf-market-watchdawg)")
                .buildAsync(URI.create("wss://ws.warframe.market/socket"), listener)
                .get(15, TimeUnit.SECONDS);
            if (!socket.getSubprotocol().equals("wfm")) {
                throw new IOException("Server did not negotiate the wfm subprotocol");
            }
            socket.sendText("""
                {"route":"@wfm|cmd/subscribe/newOrders","id":"t1-capture","payload":{"platform":"pc","crossplay":true}}
                """.strip(), true).get(5, TimeUnit.SECONDS);
            System.err.println(Instant.now() + " subscribe/newOrders sent: platform=pc, crossplay=true, subprotocol=wfm");
            try {
                listener.closed.get(seconds, TimeUnit.SECONDS);
                throw new IOException("Socket closed before the capture duration elapsed");
            } catch (TimeoutException expected) {
                // The requested capture duration elapsed; close this single connection without retrying.
            }
        } finally {
            listener.finish();
            if (socket != null) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "capture complete").get(5, TimeUnit.SECONDS);
                    listener.closed.get(5, TimeUnit.SECONDS);
                } finally {
                    socket.abort();
                    client.shutdownNow();
                }
            } else {
                client.shutdownNow();
            }
        }
        System.err.println(Instant.now() + " captured " + listener.messages + " text messages");
        if (listener.messages == 0 || System.out.checkError()) {
            throw new IOException("Capture is empty or could not be written");
        }
    }

    @Override
    public synchronized CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        if (capturing) {
            fragments.append(data);
            if (last) {
                if (messages++ > 0) System.out.println(",");
                System.out.print(fragments);
                System.out.flush();
                fragments.setLength(0);
                if (System.out.checkError()) {
                    closed.completeExceptionally(new IOException("Could not write the capture"));
                    socket.abort();
                }
            }
        }
        socket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
        closed.completeExceptionally(new IOException("Unexpected binary message"));
        socket.abort();
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        closed.complete(null);
        return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        closed.completeExceptionally(error);
    }

    private synchronized void finish() {
        capturing = false;
        System.out.println("\n]");
    }
}
