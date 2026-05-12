# Design Notes — Warehouse Inventory Management System

This document explains the more interesting design decisions in the
project. It is intended as a companion to the source code, not a
duplicate of it.

## 1. Three-tier architecture

Following the proposal, the system splits cleanly into:

* **Presentation tier** — JavaFX (`com.warehouse.client.InventoryClient`).
  Contains zero business logic; every operation is a request to the
  server.
* **Application tier** — Java server
  (`com.warehouse.server.{InventoryServer, ClientHandler, InventoryService}`).
  Holds the threading model, the locks, and the transaction
  boundaries.
* **Data tier** — SQLite via `DatabaseManager`. Schema, PRAGMAs, and
  the JDBC connection.

This separation keeps the JavaFX layer simple to reason about (no
synchronisation primitives anywhere on the FX thread) and means the
server could be re-pointed at another DB engine by replacing only
`DatabaseManager`.

## 2. Wire protocol

A single `Request` class with a `Type` enum, plus a parameter map,
plus a single `Response` class. The alternative — one
`Serializable` request class per operation — was rejected because:

* the protocol is small (~12 verbs);
* the server is the only consumer, so static typing on the wire adds
  little safety; and
* adding a new operation would otherwise require a new class on
  both sides.

Every request carries a `requestId` set to `System.nanoTime()`. The
client correlates incoming responses to outstanding `CompletableFuture`s
by that id, which lets the same socket multiplex many in-flight
requests **and** carry unsolicited push events
(`Response.Status.EVENT`).

## 3. Threading model on the server

* One `accept()` loop on the main thread.
* One `ClientHandler` thread per connection. Reads serially, writes
  through a `synchronized (writeLock)` block because the broadcaster
  may also write to the same socket.
* Business logic is invoked from the handler thread directly; no
  thread pool. Keeping one thread per client is what the proposal
  describes and is correct for a classroom workload (tens of users).
  For thousands of clients we would switch to NIO and a
  pool — but that is far outside the project's scope.

## 4. Concurrency control on writes

The single most important property is:

> No matter how many clients race on the same product, the sum of
> successful deductions equals the starting stock and the final
> quantity never goes negative.

We enforce this with two layers, in order:

1. **Per-product `ReentrantLock`.** A `ConcurrentHashMap<Integer,
   ReentrantLock>` makes lock creation thread-safe via
   `computeIfAbsent`. Threads contending on the *same* product
   serialise; threads on different products do not.
2. **JDBC transaction.** Inside the lock we
   `setAutoCommit(false)`, **re-read** the current quantity inside
   the same transaction (so we do not act on a stale value cached
   before we acquired the lock), validate, update, write the audit
   row, and commit. Any throw triggers `rollback` in a `try/catch`.

We considered three alternatives and rejected each:

* **DB-only protection (CHECK constraint, no app lock).** Two
  clients would both attempt the deduction; one would see a
  `SQLException` from the constraint. The error string the user sees
  would be implementation-specific and confusing. The lock layer
  gives us a clean `InsufficientStockException` with a
  human-readable message instead.
* **One global `synchronized` mutex on the service.** Trivially
  correct, but serialises *all* writes across *all* products, which
  destroys throughput as the catalogue grows.
* **Optimistic concurrency (UPDATE ... WHERE quantity = old).**
  Works, but on contention the loser must retry. With the
  per-product lock the loser instead either succeeds (when stock is
  available) or gets a clear, immediate "insufficient stock"
  rejection. We avoid spurious retry loops.

The DB-level `CHECK (quantity >= 0)` is still defined; it is a belt
*and* braces guarantee that catches any future bug that bypasses the
service.

## 5. Connection management on the JavaFX client

JavaFX has a single Application Thread, and any UI mutation from
another thread crashes the runtime. So:

* `ServerConnection` runs a dedicated reader thread.
* Every callback from `CompletableFuture.whenComplete` wraps its UI
  work in `Platform.runLater(...)`.
* The reader thread also delivers push events the same way.

The login dialog is the only place where we deliberately call a
synchronous `sendSync(...)` — at that point the main UI does not
exist yet, so blocking is safe and gives us straight-line code.

## 6. Push events and live updates

After every successful mutation, the handler calls
`server.broadcast(Response.event(...))` which iterates the registered
handlers and writes the same event to each client that has issued
`SUBSCRIBE_UPDATES`. The client's reader thread routes events to a
listener that issues `LIST_PRODUCTS`, `LOW_STOCK_REPORT`, and
`LIST_TRANSACTIONS`, refreshing all three tabs.

A more sophisticated implementation would diff the change and patch
the in-memory model directly. We deliberately chose the simpler
"refresh the whole list" approach because it is obviously correct
and the data volume in a classroom demo never exceeds a few hundred
rows.

## 7. Persistence considerations

* **WAL journal mode.** Set in `DatabaseManager.initialise()`. WAL
  lets long-running readers (e.g. a UI streaming the transaction
  log) coexist with short writers (a stock deduction) without one
  blocking the other.
* **Foreign keys are explicitly enabled** (`PRAGMA foreign_keys =
  ON`) because SQLite leaves them off by default.
* **`busy_timeout = 5000`** so any incidental SQLite-internal
  contention waits up to 5 s instead of failing immediately.
* The schema uses `CHECK (quantity >= 0)` and `CHECK (role IN
  ('ADMIN','STAFF'))` so corrupt data is impossible even if a
  hypothetical buggy client bypassed the service.

## 8. Authentication

A salted SHA-256 hash is stored per user. The stored format is
`base64(salt) + ':' + base64(sha256(salt || password))`, verified in
constant time. SHA-256 is not as slow as bcrypt or Argon2, but for a
classroom project this avoids pulling in a third-party crypto
dependency while still being demonstrably better than plaintext.

The seeded `admin / admin123` and `staff / staff123` accounts are an
intentional convenience for grading and demos.

## 9. Testing

`ConcurrencyStressTest` is a *headless* end-to-end test that
exercises the entire stack — TCP, serialisation, server threading,
service lock, JDBC transaction. It connects N real clients, races
them on a single product, and checks two assertions:

* `successes == startingStock`
* `final stock == 0`

If either fails, the test exits non-zero, so it can be wired into a
CI pipeline. With 25 threads on 50 units it completes in well under
a second on a 2024 laptop and produces stable results across
hundreds of runs.

## 10. What is intentionally out of scope

* Full role-based authorisation beyond ADMIN / STAFF.
* TLS for the socket.
* Connection retry / reconnect on the client.
* HiDPI tuning, theming, and i18n.
* A REST or web frontend.

These are sensible next steps but were not part of the proposal.
