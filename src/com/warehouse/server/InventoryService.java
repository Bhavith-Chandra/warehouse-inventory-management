package com.warehouse.server;

import com.warehouse.model.Product;
import com.warehouse.model.TransactionLog;
import com.warehouse.model.User;
import com.warehouse.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The business-logic layer. Sits between {@link ClientHandler} threads
 * and {@link DatabaseManager}.
 *
 * <h2>Concurrency strategy</h2>
 *
 * The most interesting concurrency case is the one called out in the
 * project proposal: two staff users trying to deduct the last unit of
 * a product at the same time. Naive code would let both clients read
 * "quantity = 1", both write "quantity = 0", and both believe they
 * have shipped the unit. We prevent that with a two-layer defence:
 *
 * <ol>
 *   <li><b>Per-product application lock.</b> A {@link ReentrantLock}
 *       is keyed by product id in {@link #productLocks}. Stock
 *       mutations acquire that lock before doing anything, so writes
 *       to the same product are strictly serialised in-process. The
 *       lock is fine-grained, so writes to <em>different</em> products
 *       still proceed in parallel.</li>
 *   <li><b>JDBC transaction.</b> Inside the lock we open a transaction
 *       with {@code setAutoCommit(false)}, re-read the current
 *       quantity from disk inside the same transaction, validate the
 *       business rule (no negative stock), apply the update, and
 *       {@code commit}. On any failure we {@code rollback} so partial
 *       state is never persisted.</li>
 * </ol>
 *
 * The DB layer also enforces a {@code CHECK (quantity >= 0)} so the
 * invariant is defended even if the application logic ever drifts.
 *
 * <h3>Why two layers and not just the DB constraint?</h3>
 * The DB constraint alone would let both clients <em>attempt</em> the
 * deduction; one would get a SQLException, and we would report a
 * confusing error. The application lock ensures that one client gets
 * a clean "out of stock" message and the other a successful response,
 * which is what users expect.
 */
public class InventoryService {

    private static final Logger LOG = Logger.getLogger(InventoryService.class.getName());

    private final DatabaseManager db;

    /**
     * Per-product lock map. {@link ConcurrentHashMap#computeIfAbsent}
     * gives us atomic lazy creation, so two threads racing on the
     * <em>first</em> mutation of a product still end up sharing the
     * same lock instance.
     */
    private final ConcurrentHashMap<Integer, ReentrantLock> productLocks =
            new ConcurrentHashMap<>();

    public InventoryService(DatabaseManager db) {
        this.db = db;
    }

    private ReentrantLock lockFor(int productId) {
        return productLocks.computeIfAbsent(productId, id -> new ReentrantLock());
    }

    /* ================================================================ */
    /*                          Authentication                            */
    /* ================================================================ */

    public User authenticate(String username, String password) throws SQLException {
        String sql = "SELECT id, username, password_hash, role FROM users WHERE username = ?";
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    String storedHash = rs.getString("password_hash");
                    if (!PasswordHasher.verify(password, storedHash)) return null;
                    return new User(
                        rs.getInt("id"),
                        rs.getString("username"),
                        storedHash,
                        User.Role.valueOf(rs.getString("role"))
                    );
                }
            }
        }
    }

    /* ================================================================ */
    /*                      Product read operations                       */
    /* ================================================================ */

    public List<Product> listProducts() throws SQLException {
        return queryProducts("SELECT * FROM products ORDER BY name", null);
    }

    public List<Product> searchProducts(String term) throws SQLException {
        String like = "%" + term.toLowerCase() + "%";
        return queryProducts(
            "SELECT * FROM products WHERE LOWER(name) LIKE ? OR LOWER(sku) LIKE ? " +
            "OR LOWER(category) LIKE ? ORDER BY name",
            ps -> {
                ps.setString(1, like);
                ps.setString(2, like);
                ps.setString(3, like);
            });
    }

    public List<Product> lowStockProducts() throws SQLException {
        return queryProducts(
            "SELECT * FROM products WHERE quantity <= reorder_level ORDER BY quantity ASC",
            null);
    }

    public Product getProduct(int id) throws SQLException {
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM products WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapProduct(rs) : null;
                }
            }
        }
    }

    /* ================================================================ */
    /*                     Product CRUD operations                        */
    /* ================================================================ */

    /**
     * Create a new product in the catalogue. Records a CREATE_PRODUCT
     * transaction in the audit log.
     */
    public Product addProduct(Product p, String performedBy) throws SQLException {
        if (p.getQuantity() < 0) {
            throw new IllegalArgumentException("Initial quantity cannot be negative");
        }

        Connection c = db.getConnection();
        synchronized (db) {
            boolean prevAuto = c.getAutoCommit();
            try {
                c.setAutoCommit(false);

                String now = LocalDateTime.now().format(DatabaseManager.ISO);

                int newId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO products (sku, name, category, quantity, " +
                        "reorder_level, unit_price, location, last_updated) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, p.getSku());
                    ps.setString(2, p.getName());
                    ps.setString(3, p.getCategory());
                    ps.setInt(4, p.getQuantity());
                    ps.setInt(5, p.getReorderLevel());
                    ps.setDouble(6, p.getUnitPrice());
                    ps.setString(7, p.getLocation());
                    ps.setString(8, now);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Failed to retrieve generated id");
                        }
                        newId = keys.getInt(1);
                    }
                }

                writeTransaction(c, newId, p.getName(), TransactionLog.Type.CREATE_PRODUCT,
                        p.getQuantity(), p.getQuantity(), performedBy,
                        "New product registered");

                c.commit();

                p.setId(newId);
                p.setLastUpdated(LocalDateTime.parse(now, DatabaseManager.ISO));
                return p;

            } catch (SQLException e) {
                safeRollback(c);
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        }
    }

    /**
     * Update non-stock fields of a product (name, price, location, etc).
     * Stock changes go through {@link #adjustStock} so that they are
     * always audited.
     */
    public Product updateProduct(Product updated, String performedBy) throws SQLException {
        ReentrantLock lock = lockFor(updated.getId());
        lock.lock();
        try {
            synchronized (db) {
                Connection c = db.getConnection();
                boolean prevAuto = c.getAutoCommit();
                try {
                    c.setAutoCommit(false);

                    String now = LocalDateTime.now().format(DatabaseManager.ISO);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE products SET sku=?, name=?, category=?, " +
                            "reorder_level=?, unit_price=?, location=?, " +
                            "last_updated=? WHERE id=?")) {
                        ps.setString(1, updated.getSku());
                        ps.setString(2, updated.getName());
                        ps.setString(3, updated.getCategory());
                        ps.setInt(4, updated.getReorderLevel());
                        ps.setDouble(5, updated.getUnitPrice());
                        ps.setString(6, updated.getLocation());
                        ps.setString(7, now);
                        ps.setInt(8, updated.getId());
                        if (ps.executeUpdate() == 0) {
                            throw new SQLException("Product " + updated.getId() + " not found");
                        }
                    }
                    c.commit();
                    return getProduct(updated.getId());
                } catch (SQLException e) {
                    safeRollback(c);
                    throw e;
                } finally {
                    c.setAutoCommit(prevAuto);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeProduct(int productId, String performedBy) throws SQLException {
        ReentrantLock lock = lockFor(productId);
        lock.lock();
        try {
            synchronized (db) {
                Connection c = db.getConnection();
                boolean prevAuto = c.getAutoCommit();
                try {
                    c.setAutoCommit(false);

                    Product existing = getProduct(productId);
                    if (existing == null) {
                        throw new SQLException("Product " + productId + " not found");
                    }

                    // Log first while the FK still resolves, then delete.
                    writeTransaction(c, productId, existing.getName(),
                            TransactionLog.Type.DELETE_PRODUCT,
                            -existing.getQuantity(), 0, performedBy,
                            "Product removed from catalogue");

                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM products WHERE id = ?")) {
                        ps.setInt(1, productId);
                        ps.executeUpdate();
                    }

                    c.commit();
                } catch (SQLException e) {
                    safeRollback(c);
                    throw e;
                } finally {
                    c.setAutoCommit(prevAuto);
                }
            }
        } finally {
            lock.unlock();
            productLocks.remove(productId);
        }
    }

    /* ================================================================ */
    /*                    Stock mutation (concurrency)                    */
    /* ================================================================ */

    /**
     * Atomically apply a signed delta to a product's stock. Positive
     * deltas correspond to {@link TransactionLog.Type#ADD}, negative
     * to {@link TransactionLog.Type#REMOVE}. The {@code adjust}
     * variant ({@link #setStock}) is for cycle-count corrections.
     *
     * @throws InsufficientStockException if the resulting quantity
     *         would go negative.
     */
    public Product adjustStock(int productId, int delta, String performedBy,
                               String reason)
            throws SQLException, InsufficientStockException {

        if (delta == 0) {
            // Nothing to do; just return the current state.
            return getProduct(productId);
        }

        ReentrantLock lock = lockFor(productId);
        lock.lock();
        try {
            synchronized (db) {
                Connection c = db.getConnection();
                boolean prevAuto = c.getAutoCommit();
                try {
                    c.setAutoCommit(false);

                    int current;
                    String name;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT quantity, name FROM products WHERE id = ?")) {
                        ps.setInt(1, productId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("Product " + productId + " not found");
                            }
                            current = rs.getInt(1);
                            name = rs.getString(2);
                        }
                    }

                    int next = current + delta;
                    if (next < 0) {
                        throw new InsufficientStockException(
                            "Cannot deduct " + (-delta) + " from '" + name +
                            "': only " + current + " in stock.");
                    }

                    String now = LocalDateTime.now().format(DatabaseManager.ISO);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE products SET quantity = ?, last_updated = ? " +
                            "WHERE id = ?")) {
                        ps.setInt(1, next);
                        ps.setString(2, now);
                        ps.setInt(3, productId);
                        ps.executeUpdate();
                    }

                    TransactionLog.Type type = delta > 0
                            ? TransactionLog.Type.ADD
                            : TransactionLog.Type.REMOVE;
                    writeTransaction(c, productId, name, type, delta, next,
                            performedBy, reason);

                    c.commit();

                    return new Product(productId, null, name, null, next, 0, 0, null,
                            LocalDateTime.parse(now, DatabaseManager.ISO));

                } catch (SQLException | InsufficientStockException e) {
                    safeRollback(c);
                    throw e;
                } finally {
                    c.setAutoCommit(prevAuto);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Set absolute stock to a specific value (cycle-count correction).
     * Logged as ADJUST so it's distinguishable from receiving/shipping.
     */
    public Product setStock(int productId, int newQuantity, String performedBy,
                            String reason) throws SQLException {
        if (newQuantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        ReentrantLock lock = lockFor(productId);
        lock.lock();
        try {
            synchronized (db) {
                Connection c = db.getConnection();
                boolean prevAuto = c.getAutoCommit();
                try {
                    c.setAutoCommit(false);

                    int current;
                    String name;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT quantity, name FROM products WHERE id = ?")) {
                        ps.setInt(1, productId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("Product " + productId + " not found");
                            }
                            current = rs.getInt(1);
                            name = rs.getString(2);
                        }
                    }

                    String now = LocalDateTime.now().format(DatabaseManager.ISO);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE products SET quantity = ?, last_updated = ? " +
                            "WHERE id = ?")) {
                        ps.setInt(1, newQuantity);
                        ps.setString(2, now);
                        ps.setInt(3, productId);
                        ps.executeUpdate();
                    }

                    int delta = newQuantity - current;
                    writeTransaction(c, productId, name, TransactionLog.Type.ADJUST,
                            delta, newQuantity, performedBy, reason);

                    c.commit();
                    return getProduct(productId);
                } catch (SQLException e) {
                    safeRollback(c);
                    throw e;
                } finally {
                    c.setAutoCommit(prevAuto);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /* ================================================================ */
    /*                    Transaction-log read operations                 */
    /* ================================================================ */

    public List<TransactionLog> recentTransactions(int limit) throws SQLException {
        List<TransactionLog> out = new ArrayList<>();
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM transactions ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapTransaction(rs));
                }
            }
        }
        return out;
    }

    public List<TransactionLog> transactionsForProduct(int productId, int limit)
            throws SQLException {
        List<TransactionLog> out = new ArrayList<>();
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM transactions WHERE product_id = ? " +
                    "ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, productId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapTransaction(rs));
                }
            }
        }
        return out;
    }

    /* ================================================================ */
    /*                            Internals                               */
    /* ================================================================ */

    @FunctionalInterface
    private interface ParamBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Product> queryProducts(String sql, ParamBinder binder) throws SQLException {
        List<Product> out = new ArrayList<>();
        synchronized (db) {
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                if (binder != null) binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapProduct(rs));
                }
            }
        }
        return out;
    }

    private Product mapProduct(ResultSet rs) throws SQLException {
        return new Product(
            rs.getInt("id"),
            rs.getString("sku"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getInt("quantity"),
            rs.getInt("reorder_level"),
            rs.getDouble("unit_price"),
            rs.getString("location"),
            LocalDateTime.parse(rs.getString("last_updated"), DatabaseManager.ISO)
        );
    }

    private TransactionLog mapTransaction(ResultSet rs) throws SQLException {
        return new TransactionLog(
            rs.getInt("id"),
            rs.getInt("product_id"),
            rs.getString("product_name"),
            TransactionLog.Type.valueOf(rs.getString("type")),
            rs.getInt("quantity_change"),
            rs.getInt("resulting_quantity"),
            rs.getString("performed_by"),
            LocalDateTime.parse(rs.getString("timestamp"), DatabaseManager.ISO),
            rs.getString("reason")
        );
    }

    private void writeTransaction(Connection c, int productId, String productName,
                                  TransactionLog.Type type, int delta, int resulting,
                                  String performedBy, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions (product_id, product_name, type, " +
                "quantity_change, resulting_quantity, performed_by, timestamp, reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, productId);
            ps.setString(2, productName);
            ps.setString(3, type.name());
            ps.setInt(4, delta);
            ps.setInt(5, resulting);
            ps.setString(6, performedBy);
            ps.setString(7, LocalDateTime.now().format(DatabaseManager.ISO));
            ps.setString(8, reason);
            ps.executeUpdate();
        }
    }

    private void safeRollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            LOG.log(Level.WARNING, "Rollback failed", ignored);
        }
    }

    /* ================================================================ */
    /*                          Custom exceptions                         */
    /* ================================================================ */

    /**
     * Thrown when a stock-deduction would drive quantity below zero.
     * This is the application-level signal for the "two clients race
     * to ship the last unit" case described in the project proposal.
     */
    public static class InsufficientStockException extends Exception {
        private static final long serialVersionUID = 1L;
        public InsufficientStockException(String msg) { super(msg); }
    }
}
