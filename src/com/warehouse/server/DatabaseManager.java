package com.warehouse.server;

import com.warehouse.model.User;
import com.warehouse.util.PasswordHasher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the JDBC connection to the SQLite database and the schema.
 *
 * <h3>Connection model</h3>
 * SQLite is a serverless, file-backed database. We keep a <em>single</em>
 * shared {@link Connection} for the lifetime of the server. SQLite's
 * own connection-level lock plus our application-level
 * synchronisation in {@link InventoryService} provide the necessary
 * write serialisation. WAL journal mode is enabled so that readers
 * never block writers, which keeps the LIST_PRODUCTS query fast even
 * while a stock-deduction transaction is in flight.
 *
 * <h3>Threading</h3>
 * JDBC {@link Connection} objects are not generally guaranteed to be
 * thread-safe, so every method that operates on the connection is
 * either {@code synchronized} on this manager or runs inside a
 * service-level lock that the caller already holds.
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());

    public static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String jdbcUrl;
    private Connection connection;

    public DatabaseManager(String dbFilePath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath;
    }

    /**
     * Opens the database connection, creates the schema if missing,
     * and seeds a default admin user the first time it is run.
     */
    public synchronized void initialise() throws SQLException {
        try {
            // Force the JDBC driver to register before we try to connect.
            // Modern JDBC auto-discovers via SPI, but loading the class
            // explicitly fails fast if the driver JAR is missing from
            // the classpath.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc driver not on classpath", e);
        }

        connection = DriverManager.getConnection(jdbcUrl);

        try (Statement st = connection.createStatement()) {
            // WAL journal mode allows concurrent reads while one writer
            // is in progress -- important for our live-query UI.
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }

        createSchema();
        seedDefaultAdminIfEmpty();
        LOG.info("Database initialised at " + jdbcUrl);
    }

    public synchronized Connection getConnection() {
        if (connection == null) {
            throw new IllegalStateException("DatabaseManager not initialised");
        }
        return connection;
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "Error closing DB connection", e);
            }
            connection = null;
        }
    }

    /* ---------------------------------------------------------------- */
    /*                              Schema                                */
    /* ---------------------------------------------------------------- */

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    username      TEXT    NOT NULL UNIQUE,
                    password_hash TEXT    NOT NULL,
                    role          TEXT    NOT NULL CHECK (role IN ('ADMIN','STAFF')),
                    created_at    TEXT    NOT NULL
                )
            """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS products (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    sku            TEXT    NOT NULL UNIQUE,
                    name           TEXT    NOT NULL,
                    category       TEXT,
                    quantity       INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
                    reorder_level  INTEGER NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
                    unit_price     REAL    NOT NULL DEFAULT 0,
                    location       TEXT,
                    last_updated   TEXT    NOT NULL
                )
            """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id          INTEGER,
                    product_name        TEXT    NOT NULL,
                    type                TEXT    NOT NULL,
                    quantity_change     INTEGER NOT NULL,
                    resulting_quantity  INTEGER NOT NULL,
                    performed_by        TEXT    NOT NULL,
                    timestamp           TEXT    NOT NULL,
                    reason              TEXT,
                    FOREIGN KEY (product_id) REFERENCES products(id)
                        ON DELETE SET NULL
                )
            """);

            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_tx_product ON transactions(product_id)");
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_tx_time ON transactions(timestamp)");
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_products_name ON products(name)");
        }
    }

    /**
     * Inserts an {@code admin / admin123} account on a brand-new database
     * so the application is usable on first run. Production deployments
     * would obviously rotate or remove this credential.
     */
    private void seedDefaultAdminIfEmpty() throws SQLException {
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) == 0) {
                try (var ps = connection.prepareStatement(
                        "INSERT INTO users (username, password_hash, role, created_at) " +
                        "VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, "admin");
                    ps.setString(2, PasswordHasher.hash("admin123"));
                    ps.setString(3, User.Role.ADMIN.name());
                    ps.setString(4, LocalDateTime.now().format(ISO));
                    ps.executeUpdate();
                }
                try (var ps = connection.prepareStatement(
                        "INSERT INTO users (username, password_hash, role, created_at) " +
                        "VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, "staff");
                    ps.setString(2, PasswordHasher.hash("staff123"));
                    ps.setString(3, User.Role.STAFF.name());
                    ps.setString(4, LocalDateTime.now().format(ISO));
                    ps.executeUpdate();
                }
                LOG.info("Seeded default admin/staff users.");
                seedSampleProducts();
            }
        }
    }

    private void seedSampleProducts() throws SQLException {
        String now = LocalDateTime.now().format(ISO);
        Object[][] rows = new Object[][] {
            {"SKU-1001", "Cordless Drill 18V",     "Power Tools",   25, 5,  89.99,  "A1-01"},
            {"SKU-1002", "Hammer 16oz",            "Hand Tools",    50, 10, 14.50,  "A1-02"},
            {"SKU-1003", "Safety Goggles",         "PPE",          120, 30,  6.25,  "B2-04"},
            {"SKU-1004", "Work Gloves (L)",        "PPE",           80, 25,  9.99,  "B2-05"},
            {"SKU-1005", "LED Flashlight",         "Electronics",   15, 8,  19.95,  "C3-01"},
            {"SKU-1006", "Extension Cord 25ft",    "Electronics",   30, 5,  22.40,  "C3-02"},
            {"SKU-1007", "Duct Tape (roll)",       "Consumables",  200, 50,  4.75,  "D1-01"},
            {"SKU-1008", "Box Cutter",             "Hand Tools",    60, 15,  3.50,  "A1-03"},
            {"SKU-1009", "Storage Bin 20L",        "Storage",       40, 10, 12.00,  "E2-01"},
            {"SKU-1010", "Pallet Jack",            "Equipment",      4, 2, 350.00,  "F1-01"}
        };

        try (var ps = connection.prepareStatement(
                "INSERT INTO products (sku, name, category, quantity, reorder_level, " +
                "unit_price, location, last_updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Object[] r : rows) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setString(3, (String) r[2]);
                ps.setInt(4,    (int)    r[3]);
                ps.setInt(5,    (int)    r[4]);
                ps.setDouble(6, (double) r[5]);
                ps.setString(7, (String) r[6]);
                ps.setString(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        LOG.info("Seeded " + rows.length + " sample products.");
    }
}
