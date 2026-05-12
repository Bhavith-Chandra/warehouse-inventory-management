package com.warehouse.client;

import com.warehouse.protocol.Request;
import com.warehouse.protocol.Response;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The client's view of the network connection to the server.
 *
 * <p>This class hides the socket / serialisation plumbing behind a
 * small, future-based API: the UI calls {@link #send(Request)} and
 * gets a {@link CompletableFuture} that completes when the matching
 * {@link Response} arrives. A dedicated background "reader" thread
 * dispatches incoming messages either to that future (correlated by
 * {@code requestId}) or to the registered {@link #setEventListener}
 * callback (for unsolicited push events).
 *
 * <p>Keeping the request/response correlation explicit means the UI
 * never blocks the JavaFX Application Thread waiting for I/O. All
 * UI updates are marshalled back via {@code Platform.runLater} in
 * the calling code.
 */
public class ServerConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ServerConnection.class.getName());

    private final String host;
    private final int port;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Thread readerThread;

    private final Map<Long, CompletableFuture<Response>> pending =
            new ConcurrentHashMap<>();

    private volatile Consumer<Response> eventListener;
    private volatile Consumer<Throwable> disconnectListener;
    private volatile boolean connected;

    public ServerConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public synchronized void connect() throws IOException {
        if (connected) return;
        socket = new Socket(host, port);
        // ObjectOutputStream MUST be created and flushed before
        // ObjectInputStream because the constructor writes a header
        // that the other side's input stream constructor reads.
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());
        connected = true;

        readerThread = new Thread(this::readerLoop, "server-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public boolean isConnected() {
        return connected;
    }

    public void setEventListener(Consumer<Response> listener) {
        this.eventListener = listener;
    }

    public void setDisconnectListener(Consumer<Throwable> listener) {
        this.disconnectListener = listener;
    }

    /**
     * Send a request and obtain a future that completes with the
     * matching response. The future never completes on the JavaFX
     * thread, so callers that want to update the UI from the result
     * must hop back to it themselves (e.g. {@code thenAccept(r ->
     * Platform.runLater(...))}).
     */
    public CompletableFuture<Response> send(Request request) {
        if (!connected) {
            return CompletableFuture.failedFuture(
                new IOException("Not connected to server"));
        }
        CompletableFuture<Response> future = new CompletableFuture<>();
        pending.put(request.getRequestId(), future);
        try {
            synchronized (out) {
                out.writeObject(request);
                out.reset();
                out.flush();
            }
        } catch (IOException e) {
            pending.remove(request.getRequestId());
            future.completeExceptionally(e);
            handleDisconnect(e);
        }
        return future;
    }

    /** Convenience: send and wait synchronously with a timeout. */
    public Response sendSync(Request request, long timeoutMs)
            throws IOException, TimeoutException, InterruptedException {
        try {
            return send(request).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause());
        }
    }

    private void readerLoop() {
        try {
            while (connected) {
                Object obj = in.readObject();
                if (!(obj instanceof Response resp)) continue;

                if (resp.isEvent()) {
                    Consumer<Response> l = eventListener;
                    if (l != null) {
                        try {
                            l.accept(resp);
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "event listener threw", e);
                        }
                    }
                } else {
                    CompletableFuture<Response> f = pending.remove(resp.getRequestId());
                    if (f != null) {
                        f.complete(resp);
                    } else {
                        LOG.fine("Orphaned response (no pending future): " + resp);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            handleDisconnect(e);
        }
    }

    private void handleDisconnect(Throwable cause) {
        if (!connected) return;
        connected = false;
        // Fail all pending requests so UI doesn't hang forever.
        for (CompletableFuture<Response> f : new HashMap<>(pending).values()) {
            f.completeExceptionally(cause);
        }
        pending.clear();
        Consumer<Throwable> l = disconnectListener;
        if (l != null) {
            try { l.accept(cause); } catch (Exception ignored) { }
        }
    }

    @Override
    public synchronized void close() {
        if (!connected) return;
        try {
            send(new Request(Request.Type.DISCONNECT));
        } catch (Exception ignored) { }
        connected = false;
        try { if (in != null) in.close(); } catch (IOException ignored) { }
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }
}
