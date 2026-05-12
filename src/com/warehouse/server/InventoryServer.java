package com.warehouse.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the warehouse-management server process.
 *
 * <p>Listens on a TCP port (default 5555), and for every accepted
 * connection spawns a dedicated {@link ClientHandler} thread, exactly
 * as described in the project proposal. The set of currently-connected
 * handlers is tracked so that we can broadcast push events
 * (stock-changed, etc.) to all of them.
 *
 * <p>The server is shut down cleanly on SIGINT (Ctrl+C) via a JVM
 * shutdown hook.
 */
public class InventoryServer {

    private static final Logger LOG = Logger.getLogger(InventoryServer.class.getName());

    public static final int DEFAULT_PORT = 5555;
    public static final String DEFAULT_DB_PATH = "data/warehouse.db";

    private final int port;
    private final String dbPath;

    private DatabaseManager databaseManager;
    private InventoryService inventoryService;
    private ServerSocket serverSocket;

    /** Thread-safe set of currently-connected client handlers. */
    private final Set<ClientHandler> handlers =
            Collections.synchronizedSet(new HashSet<>());

    private final AtomicLong connectionCounter = new AtomicLong();
    private volatile boolean running;

    public InventoryServer(int port, String dbPath) {
        this.port = port;
        this.dbPath = dbPath;
    }

    public void start() throws IOException, SQLException {
        databaseManager = new DatabaseManager(dbPath);
        databaseManager.initialise();
        inventoryService = new InventoryService(databaseManager);

        serverSocket = new ServerSocket(port);
        running = true;

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "shutdown-hook"));

        LOG.info("Warehouse server listening on port " + port +
                 " (db=" + dbPath + ")");

        // The main accept loop. Each iteration produces a new
        // ClientHandler thread, which reads requests serially from
        // its own socket. The accept call itself blocks until a
        // client connects, so this loop holds zero CPU when idle.
        try {
            while (running) {
                Socket socket = serverSocket.accept();
                long connId = connectionCounter.incrementAndGet();
                ClientHandler handler =
                        new ClientHandler(connId, socket, inventoryService, this);
                handlers.add(handler);
                Thread t = new Thread(handler, "client-" + connId);
                t.setDaemon(false);
                t.start();
            }
        } catch (IOException e) {
            if (running) {
                LOG.log(Level.SEVERE, "Server accept loop failed", e);
                throw e;
            }
            // Otherwise: stop() closed the socket on purpose.
        }
    }

    public void stop() {
        if (!running) return;
        running = false;
        LOG.info("Shutting down warehouse server...");

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error closing server socket", e);
        }

        // Disconnect all clients before closing the DB.
        synchronized (handlers) {
            for (ClientHandler h : handlers) {
                h.shutdown();
            }
            handlers.clear();
        }

        if (databaseManager != null) {
            databaseManager.close();
        }
        LOG.info("Server shutdown complete.");
    }

    void unregister(ClientHandler handler) {
        handlers.remove(handler);
    }

    /**
     * Push an event to every currently-connected client whose handler
     * has subscribed to live updates. Used after every stock change
     * so all open UIs refresh immediately.
     */
    void broadcast(com.warehouse.protocol.Response event) {
        // Snapshot the set under the lock, then iterate without
        // holding the lock so a slow socket on one client cannot
        // stall broadcasts to the others.
        ClientHandler[] snapshot;
        synchronized (handlers) {
            snapshot = handlers.toArray(new ClientHandler[0]);
        }
        for (ClientHandler h : snapshot) {
            if (h.isSubscribed()) {
                h.send(event);
            }
        }
    }

    public int connectedClientCount() {
        return handlers.size();
    }

    /* ----------------------------- main ----------------------------- */

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String db = DEFAULT_DB_PATH;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db"   -> db = args[++i];
                case "--help" -> {
                    System.out.println("Usage: InventoryServer [--port N] [--db PATH]");
                    return;
                }
            }
        }

        InventoryServer server = new InventoryServer(port, db);
        try {
            server.start();
        } catch (IOException | SQLException e) {
            LOG.log(Level.SEVERE, "Server failed to start", e);
            System.exit(1);
        }
    }
}
