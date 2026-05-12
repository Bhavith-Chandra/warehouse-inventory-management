# Warehouse Inventory Management System

**CS6103 — Introduction to Java, Spring 2026 — Final Project**
Bhavith Chandra (Net ID `bc4066`, NYU ID `N13272568`)

***NOTE : Claude has been used to write this documentation and comment in the code respectively***

A networked, multi-user, JavaFX desktop application that lets warehouse
staff view, add, update, and remove product stock in real time.
Multiple clients connect to a central Java server simultaneously, and
the server guarantees that concurrent stock updates are serialised
correctly using **per-product application locks** combined with
**JDBC transactions**.

The headline correctness property — *"if two users try to deduct the
last unit of a product at the same time, only one should succeed"* —
is verified by the included `ConcurrencyStressTest`, which races 10
(or more) client threads against a single product and confirms that
the number of successful deductions is exactly equal to the starting
stock, and that the final stock is exactly zero.

---

## 1. Architecture

```
                  +-------------------+
                  |   JavaFX Client   |  <-- one process per user
                  |  (InventoryClient)|
                  +---------+---------+
                            |
              ObjectInputStream / ObjectOutputStream
                  (Java serialisation over TCP)
                            |
                  +---------v---------+
                  |  InventoryServer  |  one ServerSocket on :5555
                  |   accept() loop   |
                  +---------+---------+
                            |  spawns one thread per connection
        +-------------------+-------------------+
        |                   |                   |
   +----v-----+        +----v-----+        +----v-----+
   |ClientHand|        |ClientHand|        |ClientHand|
   |  ler #1  |        |  ler #2  |        |  ler #N  |
   +----+-----+        +----+-----+        +----+-----+
        |                   |                   |
        +-------------------+-------------------+
                            |
                            v
                  +-------------------+
                  | InventoryService  |  per-product ReentrantLock
                  | (synchronized BL) |  + JDBC transactions
                  +---------+---------+
                            |
                  +---------v---------+
                  |  DatabaseManager  |  single JDBC Connection
                  +---------+---------+
                            |
                  +---------v---------+
                  | SQLite (WAL mode) |  data/warehouse.db
                  +-------------------+
```

The proposal called for six key classes; all of them exist in the
`src/com/warehouse/` tree:

| Proposal class            | Implementation file                                    |
|--------------------------|--------------------------------------------------------|
| `InventoryServer.java`   | `src/com/warehouse/server/InventoryServer.java`        |
| `ClientHandler.java`     | `src/com/warehouse/server/ClientHandler.java`          |
| `InventoryService.java`  | `src/com/warehouse/server/InventoryService.java`       |
| `DatabaseManager.java`   | `src/com/warehouse/server/DatabaseManager.java`        |
| `InventoryClient.java`   | `src/com/warehouse/client/InventoryClient.java`        |
| `Product.java` / `TransactionLog.java` | `src/com/warehouse/model/{Product,TransactionLog}.java` |

Plus a few supporting classes:

* `User.java` — sanitised user model returned after login
* `Request.java` / `Response.java` — the wire protocol
* `ServerConnection.java` — async client networking layer
* `PasswordHasher.java` — salted SHA-256 for stored passwords
* `ConcurrencyStressTest.java` — automated proof of correctness

---

## 2. How concurrency is handled

The proposal calls out the two-clients-deduct-the-last-unit race.
`InventoryService.adjustStock` defends against it with a two-layer
strategy:

1. **Per-product `ReentrantLock`** keyed by product id in a
   `ConcurrentHashMap`. Two threads mutating the *same* product are
   strictly serialised. Two threads mutating *different* products
   proceed in parallel — the lock is fine-grained.

2. **JDBC transaction** (`setAutoCommit(false)` → re-read quantity →
   validate → update → write audit log → `commit`; `rollback` on any
   exception). The DB-level `CHECK (quantity >= 0)` is a final
   safety net.

This is why the stress test always reports `successes == startingStock`
and `final stock == 0` even with 25 threads racing for 50 units.

---

## 3. Tools & libraries

* **JDK:** Java 17+ (tested on OpenJDK 25)
* **GUI:** JavaFX 21 (OpenJFX SDK in `lib/javafx-sdk-21.0.5/`)
* **Database:** SQLite via `sqlite-jdbc 3.46.1.3`
* **Logging:** `slf4j-api` + `slf4j-simple` (drives sqlite-jdbc warnings)
* **Build:** plain `javac` via `scripts/compile.sh`. Eclipse
  `.project` / `.classpath` are also included so the project can be
  imported directly into Eclipse IDE for Java Developers.

All third-party JARs live in `lib/` so no internet is required at
build time after the initial download.

---

## 4. Quick start

### 4.1 Compile

```bash
bash scripts/compile.sh
```

### 4.2 Run the server

In one terminal:

```bash
bash scripts/run-server.sh
# Optional: bash scripts/run-server.sh --port 6000 --db /tmp/warehouse.db
```

The first run creates `data/warehouse.db`, the schema (users,
products, transactions), seeds two default users, and inserts ten
sample products.

Default credentials:

| Username | Password   | Role  |
|----------|------------|-------|
| admin    | admin123   | ADMIN |
| staff    | staff123   | STAFF |

### 4.3 Run the client

In another terminal (or several, to demonstrate multi-user):

```bash
bash scripts/run-client.sh
```

A connection dialog appears. Defaults are `localhost:5555` and
`admin / admin123`. Click **Connect** and the main UI opens with
four tabs:

* **Inventory** — searchable product table, plus Add / Edit / Remove
  buttons (admin only). Low-stock rows are highlighted in red.
* **Stock Operations** — receive (Add), ship (Remove), or absolute
  cycle-count (Set). All three are audited.
* **Low Stock** — auto-filtered list of products at or below their
  reorder level.
* **Transaction History** — the live audit log.

Open two clients side-by-side and change stock in one — the other
refreshes within a second via the server's push-event broadcast.

### 4.4 Run the concurrency stress test

With the server running:

```bash
# default: product 1, 100 starting units, 10 client threads
bash scripts/run-stress.sh

# heavy contention: 25 threads on 50 units
bash scripts/run-stress.sh 1 50 25
```

Expected output ends with `PASS: exactly N units deducted, no
double-deduction, final stock 0.`

---

## 5. Project layout

```
.
├── README.md
├── .project / .classpath          # Eclipse project files
├── lib/                            # third-party JARs
│   ├── sqlite-jdbc-3.46.1.3.jar
│   ├── slf4j-api-2.0.13.jar
│   ├── slf4j-simple-2.0.13.jar
│   └── javafx-sdk-21.0.5/
├── src/com/warehouse/
│   ├── model/        # Product, TransactionLog, User
│   ├── protocol/     # Request, Response
│   ├── util/         # PasswordHasher
│   ├── server/       # InventoryServer, ClientHandler,
│   │                 # InventoryService, DatabaseManager
│   ├── client/       # InventoryClient (JavaFX), ServerConnection
│   └── test/         # ConcurrencyStressTest
├── scripts/
│   ├── compile.sh
│   ├── run-server.sh
│   ├── run-client.sh
│   └── run-stress.sh
├── bin/                            # compiled .class output
└── data/                           # runtime: warehouse.db etc.
```

---

## 6. Importing into Eclipse

1. **File → Import → General → Existing Projects into Workspace**.
2. Select this folder as the root and click *Finish*. Eclipse will
   pick up the included `.project` and `.classpath`.
3. The classpath already references all JARs in `lib/`, so the
   project should compile cleanly with no further configuration.
4. Run configurations:
   * **Server**: main class `com.warehouse.server.InventoryServer`,
     no VM args required.
   * **Client**: main class `com.warehouse.client.InventoryClient`,
     VM args:
     ```
     --module-path "${workspace_loc:WarehouseInventoryManagementSystem}/lib/javafx-sdk-21.0.5/lib"
     --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base
     ```
   * **Stress test**: main class
     `com.warehouse.test.ConcurrencyStressTest`.

---

## 7. Mapping the proposal's "Advanced Java Concepts" to the code

| Concept                                  | Where it lives                                                                 |
|------------------------------------------|--------------------------------------------------------------------------------|
| 2.1 JavaFX GUI                           | `client/InventoryClient.java` — TabPane, TableView, search bar, alerts, log    |
| 2.2 Java Sockets                         | `server/InventoryServer.java`, `client/ServerConnection.java` — `Socket`, `ServerSocket`, `ObjectIn/OutputStream` |
| 2.3 Multithreading                       | `server/InventoryServer.java` accept loop spawns one `Thread(ClientHandler)` per client |
| 2.4 SQLite + concurrency control         | `server/DatabaseManager.java` (JDBC, WAL, schema), `server/InventoryService.java` (`ReentrantLock`, `setAutoCommit(false)`, `commit`, `rollback`) |

Plus three things beyond the proposal that elevate it:

* **Push-event broadcast** so all open clients refresh live when any
  one of them changes stock.
* **Salted SHA-256 password hashing** so the seeded credentials are
  not stored in plaintext.
* **Automated headless concurrency test** that proves the
  no-double-deduction guarantee numerically.

---

## 8. Sample stress-test run

```
 Warehouse Concurrency Stress Test 
productId = 1, startStock = 100, threads = 10
Stock reset to 100

Results 
elapsed:                 580 ms
successful deductions:   100
'insufficient' rejects:  10
other errors:            0
final stock:             0

PASS: exactly 100 units deducted, no double-deduction, final stock 0.
```
