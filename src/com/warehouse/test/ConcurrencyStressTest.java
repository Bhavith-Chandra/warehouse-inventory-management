package com.warehouse.test;

import com.warehouse.model.Product;
import com.warehouse.protocol.Request;
import com.warehouse.protocol.Response;
import com.warehouse.client.ServerConnection;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless stress test that demonstrates the concurrency guarantee
 * called out in the project proposal:
 *
 * <blockquote>
 *   "If two users try to deduct the last unit of a product at the
 *    same time, only one should succeed."
 * </blockquote>
 *
 * <p>The test:
 * <ol>
 *   <li>Connects to a running {@code InventoryServer} on localhost:5555.</li>
 *   <li>Resets one product's stock to a known starting value (default 100).</li>
 *   <li>Launches N client threads, each opening its own socket and
 *       repeatedly attempting REMOVE_STOCK of 1 unit until the server
 *       reports insufficient stock.</li>
 *   <li>Counts the successful deductions across all threads and
 *       verifies that {@code successes == startingQuantity} -- i.e.
 *       no double-deduction occurred.</li>
 *   <li>Reads the stock back and asserts it landed at exactly zero.</li>
 * </ol>
 *
 * <p>Run with the server already started:
 * <pre>
 *   java -cp ... com.warehouse.test.ConcurrencyStressTest
 *       [productId=1] [startStock=100] [threads=10]
 * </pre>
 */
public class ConcurrencyStressTest {

    public static void main(String[] args) throws Exception {
        int productId   = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int startStock  = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int threadCount = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String host     = "localhost";
        int port        = 5555;

        System.out.println("=== Warehouse Concurrency Stress Test ===");
        System.out.println("productId = " + productId +
                ", startStock = " + startStock +
                ", threads = " + threadCount);

        // Step 1: reset stock as admin to a known value.
        try (ServerConnection admin = new ServerConnection(host, port)) {
            admin.connect();
            login(admin, "admin", "admin123");
            Response r = admin.sendSync(new Request(Request.Type.ADJUST_STOCK)
                    .put("productId", productId)
                    .put("newQuantity", startStock)
                    .put("reason", "stress test reset"), 5000);
            assertOk(r, "ADJUST_STOCK reset");
            System.out.println("Stock reset to " + startStock);
        }

        // Step 2: launch N threads that each repeatedly try to remove 1.
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficientErrors = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            new Thread(() -> {
                try (ServerConnection c = new ServerConnection(host, port)) {
                    c.connect();
                    login(c, "staff", "staff123");
                    start.await();

                    while (true) {
                        Response r;
                        try {
                            r = c.sendSync(new Request(Request.Type.REMOVE_STOCK)
                                    .put("productId", productId)
                                    .put("quantity", 1)
                                    .put("reason", "stress thread " + tid),
                                5000);
                        } catch (Exception e) {
                            otherErrors.incrementAndGet();
                            break;
                        }
                        if (r.isOk()) {
                            successes.incrementAndGet();
                        } else if (r.getMessage() != null
                                   && r.getMessage().contains("Cannot deduct")) {
                            insufficientErrors.incrementAndGet();
                            break;
                        } else {
                            otherErrors.incrementAndGet();
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("thread " + tid + ": " + e);
                    otherErrors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "stress-" + t).start();
        }

        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // Step 3: verify final stock.
        int finalStock = -1;
        try (ServerConnection admin = new ServerConnection(host, port)) {
            admin.connect();
            login(admin, "admin", "admin123");
            Response r = admin.sendSync(
                new Request(Request.Type.GET_PRODUCT).put("id", productId), 5000);
            assertOk(r, "GET_PRODUCT");
            Product p = (Product) r.getPayload();
            finalStock = p.getQuantity();
        }

        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("elapsed:                 " + elapsedMs + " ms");
        System.out.println("successful deductions:   " + successes.get());
        System.out.println("'insufficient' rejects:  " + insufficientErrors.get());
        System.out.println("other errors:            " + otherErrors.get());
        System.out.println("final stock:             " + finalStock);
        System.out.println();

        boolean ok = successes.get() == startStock
                  && finalStock == 0
                  && otherErrors.get() == 0;
        if (ok) {
            System.out.println("PASS: exactly " + startStock +
                    " units deducted, no double-deduction, final stock 0.");
        } else {
            System.err.println("FAIL: expected " + startStock +
                    " successes and final stock 0, but got " +
                    successes.get() + " successes and final stock " + finalStock);
            System.exit(1);
        }

        // Optional: list a few products to verify other products were
        // unaffected.
        try (ServerConnection admin = new ServerConnection(host, port)) {
            admin.connect();
            login(admin, "admin", "admin123");
            Response r = admin.sendSync(
                new Request(Request.Type.LIST_PRODUCTS), 5000);
            assertOk(r, "LIST_PRODUCTS");
            @SuppressWarnings("unchecked")
            List<Product> products = (List<Product>) r.getPayload();
            System.out.println("All products after stress run:");
            products.forEach(p -> System.out.printf(
                    "  #%d  %-12s %-30s qty=%d%n",
                    p.getId(), p.getSku(), p.getName(), p.getQuantity()));
        }
    }

    private static void login(ServerConnection c, String user, String pass)
            throws Exception {
        Response r = c.sendSync(new Request(Request.Type.LOGIN)
                .put("username", user).put("password", pass), 5000);
        assertOk(r, "LOGIN " + user);
    }

    private static void assertOk(Response r, String label) {
        if (!r.isOk()) {
            throw new RuntimeException(label + " failed: " + r.getMessage());
        }
    }
}
