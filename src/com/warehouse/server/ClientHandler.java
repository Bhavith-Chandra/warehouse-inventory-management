package com.warehouse.server;

import com.warehouse.model.Product;
import com.warehouse.model.TransactionLog;
import com.warehouse.model.User;
import com.warehouse.protocol.Request;
import com.warehouse.protocol.Response;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One {@code ClientHandler} runs per connected client, on its own
 * thread. It reads {@link Request} objects from the socket, dispatches
 * them through {@link InventoryService}, and writes {@link Response}
 * objects back. Push events from other clients arrive on the same
 * stream via {@link #send(Response)}.
 *
 * <h3>Authentication</h3>
 * The first request <em>must</em> be a {@link Request.Type#LOGIN}.
 * Anything else is rejected. Once authenticated, the handler stores
 * the user identity and uses it as the {@code performedBy} value for
 * every audit-log entry.
 *
 * <h3>Stream contention</h3>
 * Two threads can want to write to the same client at the same time:
 * this handler's request thread (replying to a request) and the
 * server's broadcast machinery (pushing an event from another client).
 * All writes therefore go through {@link #writeResponse(Response)},
 * which is {@code synchronized} on the output stream.
 */
public class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final long connectionId;
    private final Socket socket;
    private final InventoryService service;
    private final InventoryServer server;

    private ObjectInputStream in;
    private ObjectOutputStream out;
    private final Object writeLock = new Object();

    private volatile User authenticatedUser;
    private volatile boolean subscribed;
    private volatile boolean running = true;

    public ClientHandler(long connectionId, Socket socket,
                         InventoryService service, InventoryServer server) {
        this.connectionId = connectionId;
        this.socket = socket;
        this.service = service;
        this.server = server;
    }

    public boolean isSubscribed() {
        return subscribed && authenticatedUser != null;
    }

    @Override
    public void run() {
        LOG.info("[conn-" + connectionId + "] connected from " +
                 socket.getRemoteSocketAddress());
        try {
            // ObjectOutputStream MUST be created and flushed before
            // the matching ObjectInputStream on the other side, since
            // the constructor writes the stream header. We do the
            // same here to mirror the client.
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            while (running) {
                Request req;
                try {
                    req = (Request) in.readObject();
                } catch (IOException eof) {
                    // Normal close from client end.
                    break;
                } catch (ClassNotFoundException e) {
                    writeResponse(Response.error(
                        "Unrecognised request class: " + e.getMessage(), 0L));
                    continue;
                }
                handleRequest(req);
            }
        } catch (IOException e) {
            if (running) {
                LOG.log(Level.WARNING, "[conn-" + connectionId +
                        "] socket error", e);
            }
        } finally {
            cleanup();
        }
    }

    private void handleRequest(Request req) {
        if (req == null) return;

        // Enforce login-first protocol.
        if (authenticatedUser == null && req.getType() != Request.Type.LOGIN) {
            writeResponse(Response.error(
                "Not authenticated; LOGIN required first.", req.getRequestId()));
            return;
        }

        try {
            switch (req.getType()) {
                case LOGIN             -> handleLogin(req);
                case LIST_PRODUCTS     -> writeResponse(Response.ok(
                                              service.listProducts(),
                                              req.getRequestId()));
                case SEARCH_PRODUCTS   -> writeResponse(Response.ok(
                                              service.searchProducts(req.getString("term")),
                                              req.getRequestId()));
                case GET_PRODUCT       -> writeResponse(Response.ok(
                                              service.getProduct(req.getInt("id")),
                                              req.getRequestId()));
                case ADD_PRODUCT       -> handleAddProduct(req);
                case UPDATE_PRODUCT    -> handleUpdateProduct(req);
                case REMOVE_PRODUCT    -> handleRemoveProduct(req);
                case ADD_STOCK         -> handleStockChange(req, +1);
                case REMOVE_STOCK      -> handleStockChange(req, -1);
                case ADJUST_STOCK      -> handleAdjustAbsolute(req);
                case LOW_STOCK_REPORT  -> writeResponse(Response.ok(
                                              service.lowStockProducts(),
                                              req.getRequestId()));
                case LIST_TRANSACTIONS -> handleListTransactions(req);
                case SUBSCRIBE_UPDATES -> {
                    subscribed = true;
                    writeResponse(Response.ok("subscribed", req.getRequestId()));
                }
                case DISCONNECT        -> {
                    writeResponse(Response.ok("bye", req.getRequestId()));
                    running = false;
                }
                default -> writeResponse(Response.error(
                    "Unsupported request type: " + req.getType(),
                    req.getRequestId()));
            }
        } catch (InventoryService.InsufficientStockException e) {
            writeResponse(Response.error(e.getMessage(), req.getRequestId()));
        } catch (IllegalArgumentException e) {
            writeResponse(Response.error(e.getMessage(), req.getRequestId()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[conn-" + connectionId + "] error handling " +
                    req.getType(), e);
            writeResponse(Response.error(
                "Server error: " + e.getMessage(), req.getRequestId()));
        }
    }

    /* --------------------------- handlers --------------------------- */

    private void handleLogin(Request req) throws Exception {
        String username = req.getString("username");
        String password = req.getString("password");
        if (username == null || password == null) {
            writeResponse(Response.error("username and password required",
                    req.getRequestId()));
            return;
        }
        User user = service.authenticate(username, password);
        if (user == null) {
            writeResponse(Response.error("Invalid username or password",
                    req.getRequestId()));
            return;
        }
        authenticatedUser = user;
        LOG.info("[conn-" + connectionId + "] authenticated as " +
                 user.getUsername() + " (" + user.getRole() + ")");
        writeResponse(Response.ok(user.sanitised(), req.getRequestId()));
    }

    private void handleAddProduct(Request req) throws Exception {
        requireRole(User.Role.ADMIN);
        Product p = (Product) req.get("product");
        Product saved = service.addProduct(p, authenticatedUser.getUsername());
        writeResponse(Response.ok(saved, req.getRequestId()));
        server.broadcast(Response.event(Response.EventType.PRODUCT_ADDED, saved));
    }

    private void handleUpdateProduct(Request req) throws Exception {
        requireRole(User.Role.ADMIN);
        Product p = (Product) req.get("product");
        Product saved = service.updateProduct(p, authenticatedUser.getUsername());
        writeResponse(Response.ok(saved, req.getRequestId()));
        server.broadcast(Response.event(Response.EventType.STOCK_CHANGED, saved));
    }

    private void handleRemoveProduct(Request req) throws Exception {
        requireRole(User.Role.ADMIN);
        int id = req.getInt("id");
        service.removeProduct(id, authenticatedUser.getUsername());
        writeResponse(Response.ok("removed", req.getRequestId()));
        server.broadcast(Response.event(Response.EventType.PRODUCT_REMOVED, id));
    }

    /**
     * Apply +qty (when sign=+1, type=ADD_STOCK) or -qty
     * (when sign=-1, type=REMOVE_STOCK) to a product's stock.
     * The "two clients race for the last unit" scenario flows through
     * here.
     */
    private void handleStockChange(Request req, int sign) throws Exception {
        int productId = req.getInt("productId");
        int amount = req.getInt("quantity");
        if (amount <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        String reason = req.getString("reason");
        Product updated = service.adjustStock(productId, sign * amount,
                authenticatedUser.getUsername(), reason);
        writeResponse(Response.ok(updated, req.getRequestId()));
        server.broadcast(Response.event(Response.EventType.STOCK_CHANGED, updated));
    }

    private void handleAdjustAbsolute(Request req) throws Exception {
        int productId = req.getInt("productId");
        int newQty = req.getInt("newQuantity");
        String reason = req.getString("reason");
        Product updated = service.setStock(productId, newQty,
                authenticatedUser.getUsername(), reason);
        writeResponse(Response.ok(updated, req.getRequestId()));
        server.broadcast(Response.event(Response.EventType.STOCK_CHANGED, updated));
    }

    private void handleListTransactions(Request req) throws Exception {
        Object limitVal = req.get("limit");
        int limit = limitVal != null ? ((Number) limitVal).intValue() : 100;
        Object pid = req.get("productId");
        List<TransactionLog> logs = (pid != null)
                ? service.transactionsForProduct(((Number) pid).intValue(), limit)
                : service.recentTransactions(limit);
        writeResponse(Response.ok(logs, req.getRequestId()));
    }

    /* ---------------------------- helpers --------------------------- */

    private void requireRole(User.Role required) {
        if (authenticatedUser.getRole() != required) {
            throw new IllegalArgumentException(
                "Operation requires " + required + " role; you are " +
                authenticatedUser.getRole());
        }
    }

    /**
     * Write a response to the client. Synchronised on {@link #writeLock}
     * because both this handler's thread and the server broadcast
     * thread may try to write at the same time.
     */
    void send(Response r) {
        writeResponse(r);
    }

    private void writeResponse(Response r) {
        synchronized (writeLock) {
            try {
                out.writeObject(r);
                // reset() prevents ObjectOutputStream from caching
                // previously-sent objects (which would cause stale
                // data to leak when we mutate and re-send a Product).
                out.reset();
                out.flush();
            } catch (IOException e) {
                LOG.log(Level.FINE, "[conn-" + connectionId + "] write failed", e);
                running = false;
            }
        }
    }

    void shutdown() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) { }
    }

    private void cleanup() {
        running = false;
        server.unregister(this);
        try {
            if (in != null) in.close();
        } catch (IOException ignored) { }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) { }
        try {
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) { }
        LOG.info("[conn-" + connectionId + "] disconnected" +
                 (authenticatedUser != null
                    ? " (" + authenticatedUser.getUsername() + ")"
                    : ""));
    }
}
