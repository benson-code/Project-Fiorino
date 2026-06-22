# Project Fiorino

A Java 21 Bitcoin grid-trading bot — a personal sandbox for practicing **concurrency** and **software testing**.

> ⚠️ **Personal Learning Project / WIP — Not a Production System.** This repository is an experimental playground where I practice Java 21 Virtual Threads, state machine design, and unit testing. It is not intended for real-money live trading. It connects to the **Binance Spot Testnet** by default. Please do not connect to the mainnet without a full understanding of the codebase.

---

## What is this?

A spot grid trading bot that splits a specified price range into equal intervals, places buy/sell orders, and captures arbitrage from price fluctuations. I built this not to "make money," but to dive deep into several advanced topics:

- **Java 21 Virtual Threads** — Implementing concurrent order placement in a straightforward, intuitive "one-thread-per-order" model.
- **State Machine & Crash Recovery** — Using a local embedded H2 database to persist order states, simulating recovery and reconciliation after sudden process interruptions.
- **Test Design** — Writing robust unit tests for domain logic (grid configurations, state transitions) and numerical calculations.
- **Rate Limiting (Token Bucket)** — Implementing a CAS-based lock-free token bucket rate limiter to prevent API weight rate-limit violations.

This project also includes an independent "Quant Analysis" experimental module (aggregating metrics from several free public APIs to calculate market sentiment scores), which is purely for personal exploration and learning.

---

## Tech Stack

Libraries and frameworks utilized (from `pom.xml`):

| Category | Component / Tool |
|------|------|
| Language / Build | Java 21, Maven, Single fat JAR (Maven Shade Plugin) |
| HTTP Client | JDK's built-in `java.net.http.HttpClient` (integrated with Virtual Threads) |
| JSON | Jackson (`jackson-databind`, `jackson-datatype-jsr310`) |
| Embedded DB | H2 (file-mode) + HikariCP connection pool |
| Logging | SLF4J + Logback (+ `logstash-logback-encoder`) |
| Testing | JUnit 5 (Jupiter) + Mockito |

This project **deliberately avoids Spring or any IoC container**. All dependencies are wired manually in `Main` to ensure ultra-fast startup times and transparent dependency tracing.

---

## Directory Structure

```
src/main/java/com/fiorino/
├── Main.java              Entry point: Manual dependency injection & CLI router
├── cli/                   Presentation layer (Main menu & Quant analysis sub-module)
├── application/           Application layer (Orchestrator, Order executor, Rate limiter)
├── domain/                Domain layer (Grid config, Cell model, State machine — zero external dependencies)
└── infrastructure/        Infrastructure layer (Binance API adapter, H2 persistence, Live dashboard)

src/test/java/com/fiorino/ Unit tests (Domain logic & backtesting numerical calculations)
```

---

## Build & Run (Testnet)

Requires **JDK 21** and **Maven**.

```bash
# Build and run tests
mvn clean package

# Start the interactive CLI main menu
java -jar target/project-fiorino-1.0.0-SNAPSHOT.jar
```

To run unit tests only:

```bash
mvn test
```

### Grid Trading Configuration

The trading mode requires your **own Binance Testnet API credentials**, supplied via environment variables. **This repository does not (and will never) contain any API keys.** The program reads keys exclusively from the environment:

```bash
export FIORINO_API_KEY=your_own_testnet_key
export FIORINO_SECRET_KEY=your_own_testnet_secret
export FIORINO_TESTNET=true        # Default is true; set explicitly to false for mainnet
export FIORINO_LOWER_PRICE=60000   # Lower boundary of the grid
export FIORINO_UPPER_PRICE=70000   # Upper boundary of the grid
export FIORINO_GRID_COUNT=20       # Number of grids
export FIORINO_INVESTMENT=1000     # Total investment in USDT
```

Testnet keys can be requested for free on the [Binance Spot Testnet](https://testnet.binance.vision/).

> The Quant Analysis mode (Main menu option 2) is completely free and does not require any API keys.

---

## Known Limitations

As a learning project, some simplifications and TODOs are deliberately kept:

- Supports only a single trading pair (`BTC/USDT`).
- Pure CLI console application, no GUI.
- The order execution flow can be further hardened (e.g., order idempotency and more efficient polling mechanisms). These are areas of ongoing improvements.
- The quant analysis weights are from an initial manual tuning and have not been statistically calibrated; it is purely exploratory.

---

## Disclaimer

This project is for **personal learning and technical practice only** and **does not constitute any investment advice**. Cryptocurrencies are highly volatile; use at your own risk for any real trading.
