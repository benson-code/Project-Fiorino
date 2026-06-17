# Project Fiorino

A Java 21 Bitcoin grid-trading bot — a personal sandbox for practising **concurrency** and **test design**.

> ⚠️ **個人學習專案 / WIP — 非 production 系統。** 這是我用來練習 Java 21 虛擬執行緒、狀態機設計與單元測試的實驗場，並非可直接用於真實資金的交易軟體。預設連接 **Binance Testnet**，請勿在未充分理解程式碼的情況下接觸主網。

---

## 這是什麼

一個現貨網格交易機器人:在指定價格區間內等分掛買賣單,擷取震盪利差。我寫它的目的不是「賺錢」,而是練習幾個我想深入的主題:

- **Java 21 虛擬執行緒(Virtual Threads)** —— 以「一單一執行緒」的直觀寫法達成並發下單。
- **狀態機與崩潰恢復** —— 用本地 H2 持久化掛單狀態,模擬行程被中斷後的對賬還原。
- **測試設計** —— 對領域邏輯(網格配置、狀態轉移)與數值計算撰寫單元測試。
- **限流(Token Bucket)** —— 練習以 CAS 無鎖方式實作 API 權重限流。

專案另含一個獨立的「量化分析」實驗模組(整合數個免費公開 API 計算市場情緒分數),屬個人興趣探索,同樣僅供學習。

---

## 技術棧

實際使用的函式庫(取自 `pom.xml`):

| 類別 | 使用 |
|------|------|
| 語言 / 建置 | Java 21、Maven、單一 fat JAR(Maven Shade Plugin) |
| HTTP | JDK 內建 `java.net.http.HttpClient`(搭配虛擬執行緒) |
| JSON | Jackson(`jackson-databind`、`jackson-datatype-jsr310`) |
| 嵌入式資料庫 | H2(file-mode)+ HikariCP 連線池 |
| 日誌 | SLF4J + Logback(+ `logstash-logback-encoder`) |
| 測試 | JUnit 5(Jupiter)+ Mockito |

刻意**不使用 Spring 或任何 IoC 容器** —— 依賴在 `Main` 手動組裝,藉此保持啟動快速、依賴關係一目了然。

---

## 專案結構

```
src/main/java/com/fiorino/
├── Main.java              入口:手動依賴組裝 + CLI 路由
├── cli/                   介面層(主選單、量化分析子模組)
├── application/           應用層(協調器、下單執行器、限流器)
├── domain/                領域層(網格配置、格子、狀態機 —— 零外部依賴)
└── infrastructure/        基礎設施層(Binance API 適配器、H2 持久化、儀表板)

src/test/java/com/fiorino/ 單元測試(領域邏輯 + 回測數值計算)
```

---

## 建置與執行(Testnet)

需要 **JDK 21** 與 **Maven**。

```bash
# 建置 + 跑測試
mvn clean package

# 啟動(互動式主選單)
java -jar target/project-fiorino-1.0.0-SNAPSHOT.jar
```

只跑單元測試:

```bash
mvn test
```

### 網格交易模式設定

交易模式需要你**自己的 Binance Testnet API Key**,透過環境變數提供。**本專案不含、也從未含任何金鑰** —— 程式只從環境變數讀取:

```bash
export FIORINO_API_KEY=你自己的TestnetKey
export FIORINO_SECRET_KEY=你自己的TestnetSecret
export FIORINO_TESTNET=true        # 預設 true;切到主網需自行明確設為 false
export FIORINO_LOWER_PRICE=60000   # 網格下界
export FIORINO_UPPER_PRICE=70000   # 網格上界
export FIORINO_GRID_COUNT=20
export FIORINO_INVESTMENT=1000     # 投入 USDT
```

Testnet 金鑰可於 [Binance Spot Testnet](https://testnet.binance.vision/) 免費申請。

> 量化分析模式(主選單選項 2)完全免費、不需要任何金鑰。

---

## 已知限制

這是學習專案,刻意保留了一些「待辦」與簡化:

- 僅支援單一交易對(BTC/USDT)。
- 純 CLI,無圖形介面。
- 交易執行路徑仍有待強化之處(例如下單冪等性、訂單輪詢效率),屬個人持續改進中的項目。
- 量化分析的權重為手工初版,未經資料校準 —— 純屬探索性質。

---

## 免責聲明

本專案僅供**個人學習與技術練習**用途,**不構成任何投資建議**。加密資產波動劇烈,任何據此進行的真實交易,風險自負。
