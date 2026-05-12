package com.warehouse.test;

import com.warehouse.model.Product;
import com.warehouse.model.TransactionLog;
import com.warehouse.model.User;
import com.warehouse.protocol.Request;
import com.warehouse.protocol.Response;
import com.warehouse.client.ServerConnection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end verification of every §2.x requirement in the project proposal.
 * Connects to a running InventoryServer on localhost:5555.
 *
 * Usage:
 *   java -cp bin com.warehouse.test.ProposalVerify
 */
public class ProposalVerify {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("  Warehouse Inventory – Proposal Verification     ");
        System.out.println("=================================================\n");

        try (ServerConnection admin = new ServerConnection("localhost", 5555)) {
            admin.connect();
            loginAs(admin, "admin", "admin123");

            // ── §2.1 Product Management ────────────────────────────────────
            section("§2.1  Product Management");

            // ADD_PRODUCT
            Product newProd = new Product(0, "VERIFY-001", "Verify Widget",
                    "Testing", 50, 10, 9.99, "A1", LocalDateTime.now());
            Response addResp = admin.sendSync(
                    new Request(Request.Type.ADD_PRODUCT).put("product", newProd), 5000);
            check("ADD_PRODUCT creates product", addResp.isOk() &&
                    addResp.getPayload() instanceof Product p && p.getId() > 0);
            int testId = addResp.isOk() ? ((Product) addResp.getPayload()).getId() : -1;

            // LIST_PRODUCTS
            Response listResp = admin.sendSync(new Request(Request.Type.LIST_PRODUCTS), 5000);
            check("LIST_PRODUCTS returns all products",
                    listResp.isOk() && listResp.getPayload() instanceof List<?> l && !l.isEmpty());

            // SEARCH_PRODUCTS
            Response searchResp = admin.sendSync(
                    new Request(Request.Type.SEARCH_PRODUCTS).put("term", "VERIFY-001"), 5000);
            check("SEARCH_PRODUCTS finds by SKU",
                    searchResp.isOk() &&
                    searchResp.getPayload() instanceof List<?> sl && !sl.isEmpty());

            // GET_PRODUCT
            Response getResp = admin.sendSync(
                    new Request(Request.Type.GET_PRODUCT).put("id", testId), 5000);
            check("GET_PRODUCT retrieves by id",
                    getResp.isOk() && getResp.getPayload() instanceof Product gp
                    && gp.getId() == testId);

            // UPDATE_PRODUCT
            if (getResp.isOk()) {
                Product toUpdate = (Product) getResp.getPayload();
                toUpdate.setName("Verify Widget Updated");
                toUpdate.setUnitPrice(12.50);
                Response updResp = admin.sendSync(
                        new Request(Request.Type.UPDATE_PRODUCT).put("product", toUpdate), 5000);
                check("UPDATE_PRODUCT modifies fields",
                        updResp.isOk() && updResp.getPayload() instanceof Product up
                        && up.getName().equals("Verify Widget Updated"));
            } else {
                failCheck("UPDATE_PRODUCT modifies fields");
            }

            // REMOVE_PRODUCT — use a dedicated product to avoid any state contamination
            Product removeProd = new Product(0, "REMOVE-TMP", "Delete Me",
                    "Temp", 1, 0, 1.00, "Z9", LocalDateTime.now());
            Response addRemoveResp = admin.sendSync(
                    new Request(Request.Type.ADD_PRODUCT).put("product", removeProd), 5000);
            if (addRemoveResp.isOk()) {
                int removeId = ((Product) addRemoveResp.getPayload()).getId();
                Response delResp = admin.sendSync(
                        new Request(Request.Type.REMOVE_PRODUCT).put("id", removeId), 5000);
                check("REMOVE_PRODUCT deletes product", delResp.isOk());

                // Confirm it's gone
                Response goneResp = admin.sendSync(
                        new Request(Request.Type.GET_PRODUCT).put("id", removeId), 5000);
                check("REMOVE_PRODUCT: product no longer retrievable",
                        goneResp.isOk() && goneResp.getPayload() == null);
            } else {
                failCheck("REMOVE_PRODUCT deletes product");
                failCheck("REMOVE_PRODUCT: product no longer retrievable");
            }

            // ── §2.2 Stock Operations ──────────────────────────────────────
            section("§2.2  Stock Operations");

            // ADD_STOCK
            Response addStkResp = admin.sendSync(
                    new Request(Request.Type.ADD_STOCK)
                        .put("productId", testId)
                        .put("quantity", 30)
                        .put("reason", "verify add"), 5000);
            check("ADD_STOCK increases quantity by exact amount",
                    addStkResp.isOk() && addStkResp.getPayload() instanceof Product ap
                    && ap.getQuantity() == 80); // 50 original + 30

            // REMOVE_STOCK
            Response rmStkResp = admin.sendSync(
                    new Request(Request.Type.REMOVE_STOCK)
                        .put("productId", testId)
                        .put("quantity", 10)
                        .put("reason", "verify remove"), 5000);
            check("REMOVE_STOCK decreases quantity by exact amount",
                    rmStkResp.isOk() && rmStkResp.getPayload() instanceof Product rp
                    && rp.getQuantity() == 70);

            // ADJUST_STOCK (absolute set)
            Response adjResp = admin.sendSync(
                    new Request(Request.Type.ADJUST_STOCK)
                        .put("productId", testId)
                        .put("newQuantity", 25)
                        .put("reason", "cycle count"), 5000);
            check("ADJUST_STOCK sets absolute quantity",
                    adjResp.isOk() && adjResp.getPayload() instanceof Product adjp
                    && adjp.getQuantity() == 25);

            // Negative stock prevention
            Response overRemove = admin.sendSync(
                    new Request(Request.Type.REMOVE_STOCK)
                        .put("productId", testId)
                        .put("quantity", 999)
                        .put("reason", "over-remove test"), 5000);
            check("REMOVE_STOCK rejects over-deduction (insufficient stock guard)",
                    !overRemove.isOk() && overRemove.getMessage() != null
                    && overRemove.getMessage().contains("Cannot deduct"));

            // ── §2.2 Low-stock report ──────────────────────────────────────
            // Set stock below reorder level (reorder=10, set qty=5)
            admin.sendSync(new Request(Request.Type.ADJUST_STOCK)
                    .put("productId", testId).put("newQuantity", 5).put("reason", "low test"), 5000);
            Response lowResp = admin.sendSync(
                    new Request(Request.Type.LOW_STOCK_REPORT), 5000);
            check("LOW_STOCK_REPORT includes product below reorder level",
                    lowResp.isOk() && lowResp.getPayload() instanceof List<?> lsl
                    && lsl.stream().anyMatch(o -> o instanceof Product lp && lp.getId() == testId));

            // Reset to healthy stock
            admin.sendSync(new Request(Request.Type.ADJUST_STOCK)
                    .put("productId", testId).put("newQuantity", 50).put("reason", "restore"), 5000);

            // ── §2.3 Transaction Logging ───────────────────────────────────
            section("§2.3  Transaction Logging");

            Response txResp = admin.sendSync(
                    new Request(Request.Type.LIST_TRANSACTIONS).put("limit", 50), 5000);
            check("LIST_TRANSACTIONS returns log entries",
                    txResp.isOk() && txResp.getPayload() instanceof List<?> tl && !tl.isEmpty());

            if (txResp.isOk()) {
                @SuppressWarnings("unchecked")
                List<TransactionLog> logs = (List<TransactionLog>) txResp.getPayload();
                check("Transaction log includes ADD entries",
                        logs.stream().anyMatch(t -> t.getType() == TransactionLog.Type.ADD));
                check("Transaction log includes REMOVE entries",
                        logs.stream().anyMatch(t -> t.getType() == TransactionLog.Type.REMOVE));
                check("Transaction log includes ADJUST entries",
                        logs.stream().anyMatch(t -> t.getType() == TransactionLog.Type.ADJUST));
                check("Transaction entries carry performer name",
                        logs.stream().allMatch(t -> t.getPerformedBy() != null && !t.getPerformedBy().isEmpty()));
                check("Transaction entries carry timestamp",
                        logs.stream().allMatch(t -> t.getTimestamp() != null));
            } else {
                failCheck("Transaction log includes ADD entries");
                failCheck("Transaction log includes REMOVE entries");
                failCheck("Transaction log includes ADJUST entries");
                failCheck("Transaction entries carry performer name");
                failCheck("Transaction entries carry timestamp");
            }

            // Transaction by product
            Response txProdResp = admin.sendSync(
                    new Request(Request.Type.LIST_TRANSACTIONS)
                        .put("productId", testId).put("limit", 20), 5000);
            check("LIST_TRANSACTIONS filtered by productId",
                    txProdResp.isOk() && txProdResp.getPayload() instanceof List<?> ptl
                    && !ptl.isEmpty());

            // ── §2.4 Authentication & Authorization ────────────────────────
            section("§2.4  Authentication & Authorization");

            // Staff login
            try (ServerConnection staff = new ServerConnection("localhost", 5555)) {
                staff.connect();
                Response staffLoginResp = staff.sendSync(
                        new Request(Request.Type.LOGIN)
                            .put("username", "staff")
                            .put("password", "staff123"), 5000);
                check("Staff user can login", staffLoginResp.isOk() &&
                        staffLoginResp.getPayload() instanceof User su
                        && su.getRole() == User.Role.STAFF);

                // Staff can add/remove stock
                Response staffAdd = staff.sendSync(
                        new Request(Request.Type.ADD_STOCK)
                            .put("productId", testId)
                            .put("quantity", 5)
                            .put("reason", "staff add test"), 5000);
                check("STAFF can ADD_STOCK", staffAdd.isOk());

                // Staff cannot add a new product (admin only)
                Product staffProd = new Product(0, "STAFF-001", "Staff Product",
                        "Test", 1, 0, 1.00, "X1", LocalDateTime.now());
                Response staffAddProd = staff.sendSync(
                        new Request(Request.Type.ADD_PRODUCT).put("product", staffProd), 5000);
                check("STAFF is rejected from ADD_PRODUCT (admin only)",
                        !staffAddProd.isOk());

                // Staff cannot delete a product
                Response staffDel = staff.sendSync(
                        new Request(Request.Type.REMOVE_PRODUCT).put("id", testId), 5000);
                check("STAFF is rejected from REMOVE_PRODUCT (admin only)",
                        !staffDel.isOk());
            }

            // Bad credentials
            try (ServerConnection bad = new ServerConnection("localhost", 5555)) {
                bad.connect();
                Response badLogin = bad.sendSync(
                        new Request(Request.Type.LOGIN)
                            .put("username", "admin")
                            .put("password", "wrongpassword"), 5000);
                check("Bad credentials rejected at login", !badLogin.isOk());
            }

            // Unauthenticated request
            try (ServerConnection unauth = new ServerConnection("localhost", 5555)) {
                unauth.connect();
                Response unauthResp = unauth.sendSync(
                        new Request(Request.Type.LIST_PRODUCTS), 5000);
                check("Unauthenticated request rejected (login required first)",
                        !unauthResp.isOk());
            }

            // ── §2.5 Concurrency guarantee ─────────────────────────────────
            section("§2.5  Concurrency (Two-client race on last unit)");
            concurrencyCheck(testId);

            // ── §2.6 Subscriptions / push events ──────────────────────────
            section("§2.6  Push Event Subscriptions");
            subscriptionCheck(testId);

            // ── Cleanup ────────────────────────────────────────────────────
            admin.sendSync(
                    new Request(Request.Type.REMOVE_PRODUCT).put("id", testId), 5000);
        }

        // ── Summary ─────────────────────────────────────────────────────────
        System.out.println("\n=================================================");
        System.out.printf("  PASS: %-3d   FAIL: %-3d%n", pass, fail);
        System.out.println("=================================================\n");
        if (fail > 0) System.exit(1);
    }

    /* ─── helpers ─────────────────────────────────────────────────── */

    private static void loginAs(ServerConnection c, String user, String pass) throws Exception {
        Response r = c.sendSync(
                new Request(Request.Type.LOGIN).put("username", user).put("password", pass), 5000);
        if (!r.isOk()) throw new RuntimeException("Login failed for " + user + ": " + r.getMessage());
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  PASS  " + label);
        } else {
            fail++;
            System.out.println("  FAIL  " + label);
        }
    }

    private static void failCheck(String label) {
        fail++;
        System.out.println("  FAIL  " + label + "  (prerequisite step failed)");
    }

    /* ─── §2.5 concurrency sub-test ──────────────────────────────── */

    private static void concurrencyCheck(int productId) throws Exception {
        int startStock  = 50;
        int threadCount = 15;

        // Reset stock
        try (ServerConnection setup = new ServerConnection("localhost", 5555)) {
            setup.connect();
            loginAs(setup, "admin", "admin123");
            Response r = setup.sendSync(new Request(Request.Type.ADJUST_STOCK)
                    .put("productId", productId)
                    .put("newQuantity", startStock)
                    .put("reason", "concurrency test reset"), 5000);
            if (!r.isOk()) { failCheck("Concurrency: stock reset"); return; }
        }

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go    = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            new Thread(() -> {
                try (ServerConnection c = new ServerConnection("localhost", 5555)) {
                    c.connect();
                    loginAs(c, "staff", "staff123");
                    ready.countDown();
                    go.await();
                    while (true) {
                        Response r = c.sendSync(new Request(Request.Type.REMOVE_STOCK)
                                .put("productId", productId)
                                .put("quantity", 1)
                                .put("reason", "race-" + tid), 5000);
                        if (r.isOk()) {
                            successes.incrementAndGet();
                        } else if (r.getMessage() != null && r.getMessage().contains("Cannot deduct")) {
                            insufficient.incrementAndGet();
                            break;
                        } else {
                            errors.incrementAndGet();
                            break;
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "race-" + t).start();
        }

        ready.await();
        go.countDown();
        done.await();

        // Verify final stock
        int finalStock = -1;
        try (ServerConnection verify = new ServerConnection("localhost", 5555)) {
            verify.connect();
            loginAs(verify, "admin", "admin123");
            Response r = verify.sendSync(
                    new Request(Request.Type.GET_PRODUCT).put("id", productId), 5000);
            if (r.isOk() && r.getPayload() instanceof Product p) {
                finalStock = p.getQuantity();
            }
        }

        check("Concurrency: exactly " + startStock + " successful deductions (no double-deduct)",
                successes.get() == startStock && errors.get() == 0);
        check("Concurrency: final stock is 0 after all units claimed",
                finalStock == 0);

        System.out.println("    successes=" + successes.get()
                + "  insufficient=" + insufficient.get()
                + "  errors=" + errors.get()
                + "  finalStock=" + finalStock);

        // restore stock for cleanup
        try (ServerConnection restore = new ServerConnection("localhost", 5555)) {
            restore.connect();
            loginAs(restore, "admin", "admin123");
            restore.sendSync(new Request(Request.Type.ADJUST_STOCK)
                    .put("productId", productId)
                    .put("newQuantity", 50)
                    .put("reason", "post-concurrency restore"), 5000);
        }
    }

    /* ─── §2.6 push-event sub-test ───────────────────────────────── */

    private static void subscriptionCheck(int productId) throws Exception {
        AtomicInteger eventsReceived = new AtomicInteger();
        CountDownLatch eventLatch = new CountDownLatch(1);

        try (ServerConnection listener = new ServerConnection("localhost", 5555)) {
            listener.connect();
            loginAs(listener, "staff", "staff123");

            // Subscribe to push events
            Response subResp = listener.sendSync(
                    new Request(Request.Type.SUBSCRIBE_UPDATES), 5000);
            check("SUBSCRIBE_UPDATES accepted", subResp.isOk());

            // Set event handler BEFORE the mutation
            listener.setEventListener(event -> {
                if (event.getEventType() == Response.EventType.STOCK_CHANGED) {
                    eventsReceived.incrementAndGet();
                    eventLatch.countDown();
                }
            });

            // Trigger a mutation from a different connection
            try (ServerConnection mutator = new ServerConnection("localhost", 5555)) {
                mutator.connect();
                loginAs(mutator, "admin", "admin123");
                mutator.sendSync(new Request(Request.Type.ADD_STOCK)
                        .put("productId", productId)
                        .put("quantity", 1)
                        .put("reason", "push event test"), 5000);
            }

            // Wait up to 2 seconds for the event to arrive
            boolean gotEvent = eventLatch.await(2, java.util.concurrent.TimeUnit.SECONDS);
            check("Push event delivered to subscribed client after remote stock change", gotEvent);
        }
    }
}
