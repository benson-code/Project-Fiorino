package com.fiorino.cli.quant.research;

import com.fiorino.cli.quant.BtcQuantAnalyzer.AnalysisReport;
import com.fiorino.cli.quant.BtcQuantAnalyzer.SignalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/**
 * ============================================================
 * QuantDataStore — 量化研究數據存取層（Infrastructure / Research）
 * ============================================================
 *
 * 使用 H2 file-mode 嵌入式資料庫（專案既有依賴，等同 SQLite 的單檔 SQL DB），
 * 與網格交易的 fiorino_state 隔離，獨立存放於 ./data/fiorino_quant。
 *
 * 承載三軌升級計畫（見 QUANT_BACKTEST_PLAN.md）的持久化需求：
 *   - Track A 前向採集：每日寫入 quant_snapshot + quant_signal（source=LIVE）
 *   - Track B 歷史回填：批量寫入同表（source=BACKFILL）
 *   - Track C 預測追蹤：quant_prediction 落地，事後回填實際值
 *
 * 採用一次性 DriverManager 連線（採集器為短命行程，無需連線池）。
 * 所有寫入用 MERGE INTO 確保冪等：同一 (日期, source) 重複跑會覆蓋而非堆疊。
 */
public final class QuantDataStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QuantDataStore.class);

    /** H2 file-mode；WRITE_DELAY=0 確保 headless 行程退出前落盤 */
    private static final String URL_TEMPLATE =
        "jdbc:h2:file:%s;WRITE_DELAY=0;AUTO_RECONNECT=TRUE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0";

    private final Connection conn;

    public QuantDataStore(String dbPath) {
        try {
            // 確保父目錄存在（H2 不會自動建立巢狀目錄）
            java.io.File parent = new java.io.File(dbPath).getParentFile();
            if (parent != null) parent.mkdirs();
            this.conn = DriverManager.getConnection(String.format(URL_TEMPLATE, dbPath));
            initSchema();
            log.info("QuantDataStore 初始化完成: {}", dbPath);
        } catch (Exception e) {
            throw new RuntimeException("無法開啟量化數據庫: " + dbPath, e);
        }
    }

    /** 預設路徑建構子，使用 {@link #defaultDbPath()}。 */
    public QuantDataStore() {
        this(defaultDbPath());
    }

    /**
     * 量化資料庫的標準路徑。
     * <p>放在 ~/.fiorino（非 macOS TCC 保護區），讓 launchd 排程的採集器
     * 不需「完全磁碟存取」即可讀寫——這是 headless（Termius/SSH）運維的關鍵。
     * 開發指令（--backfill/--features/--backtest）與排程採集器共用同一個 DB。
     * 可用環境變數 {@code FIORINO_QUANT_DB} 覆寫。
     */
    public static String defaultDbPath() {
        String env = System.getenv("FIORINO_QUANT_DB");
        if (env != null && !env.isBlank()) return env;
        return System.getProperty("user.home") + "/.fiorino/fiorino_quant";
    }

    private void initSchema() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS quant_snapshot (
                  ts        TIMESTAMP   NOT NULL,
                  source    VARCHAR(16) NOT NULL,
                  btc_price DOUBLE,
                  composite DOUBLE,
                  PRIMARY KEY (ts, source)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS quant_signal (
                  ts        TIMESTAMP   NOT NULL,
                  source    VARCHAR(16) NOT NULL,
                  signal_id VARCHAR(32) NOT NULL,
                  score     DOUBLE,
                  weight    DOUBLE,
                  raw_value VARCHAR(64),
                  PRIMARY KEY (ts, source, signal_id)
                )""");
            // 原始連續值欄位（供未來校準；對既有資料庫冪等新增）
            st.execute("ALTER TABLE quant_signal ADD COLUMN IF NOT EXISTS raw_numeric DOUBLE");
            st.execute("""
                CREATE TABLE IF NOT EXISTS quant_prediction (
                  ts             TIMESTAMP NOT NULL PRIMARY KEY,
                  btc_price      DOUBLE,
                  composite      DOUBLE,
                  pred_low       DOUBLE,
                  pred_mid       DOUBLE,
                  pred_high      DOUBLE,
                  confidence     DOUBLE,
                  target_date    DATE,
                  realized_price DOUBLE,
                  hit_in_band    BOOLEAN
                )""");
        }
    }

    /**
     * 寫入一次完整觀測（snapshot + 各信號明細）。
     * ts 會截斷到「日」，使每日多次採集自動 MERGE 為同一筆。
     *
     * @param observedAt 觀測時間
     * @param source     LIVE（Track A 即時）或 BACKFILL（Track B 回填）
     * @param report     分析報告
     */
    public void saveSnapshot(Instant observedAt, String source, AnalysisReport report) {
        saveDay(observedAt, source, report.currentPrice(), report.compositeScore(), report.signals());
    }

    /**
     * 寫入一日觀測（snapshot + 各信號明細）。Track A 與 Track B 回填共用。
     * ts 截斷到「日」，使同日重複寫入自動 MERGE 覆蓋（冪等）。
     *
     * @param signals 該日可得的信號清單（回填時可能少於 8 個）
     */
    public void saveDay(Instant observedAt, String source, double btcPrice,
                        double composite, java.util.List<SignalResult> signals) {
        writeObservation(Timestamp.from(observedAt.truncatedTo(ChronoUnit.DAYS)),
            source, btcPrice, composite, signals);
    }

    /**
     * 寫入一次盤中觀測（--live 連續模式用），source=INTRADAY、ts 保留到分鐘。
     * 與每日序列嚴格隔離：研究載入器只讀 LIVE/BACKFILL，盤中資料不會污染
     * 日線回測樣本（高頻樣本高度重疊、無額外統計資訊），僅作原始數據累積。
     */
    public void saveIntraday(Instant observedAt, AnalysisReport report) {
        writeObservation(Timestamp.from(observedAt.truncatedTo(ChronoUnit.MINUTES)),
            "INTRADAY", report.currentPrice(), report.compositeScore(), report.signals());
    }

    private void writeObservation(Timestamp ts, String source, double btcPrice,
                                  double composite, java.util.List<SignalResult> signals) {
        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                    MERGE INTO quant_snapshot (ts, source, btc_price, composite)
                    KEY (ts, source) VALUES (?, ?, ?, ?)""")) {
                ps.setTimestamp(1, ts);
                ps.setString(2, source);
                ps.setDouble(3, btcPrice);
                ps.setDouble(4, composite);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    MERGE INTO quant_signal (ts, source, signal_id, score, weight, raw_value, raw_numeric)
                    KEY (ts, source, signal_id) VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                for (SignalResult s : signals) {
                    ps.setTimestamp(1, ts);
                    ps.setString(2, source);
                    ps.setString(3, s.emoji());      // emoji 欄位實際存放 signal_id
                    ps.setDouble(4, s.score());
                    ps.setDouble(5, s.weight());
                    ps.setString(6, s.value());
                    if (Double.isNaN(s.rawValue())) ps.setNull(7, java.sql.Types.DOUBLE);
                    else ps.setDouble(7, s.rawValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception e) {
            throw new RuntimeException("寫入觀測失敗 (ts=" + ts + ")", e);
        }
    }

    /**
     * 記錄一次預測供 Track C 事後評分。target_date = 觀測日 + 1 個月。
     */
    public void logPrediction(Instant observedAt, AnalysisReport report) {
        Timestamp ts = Timestamp.from(observedAt.truncatedTo(ChronoUnit.DAYS));
        LocalDate target = LocalDate.now().plusMonths(1);
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO quant_prediction
                  (ts, btc_price, composite, pred_low, pred_mid, pred_high, confidence, target_date)
                KEY (ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setTimestamp(1, ts);
            ps.setDouble(2, report.currentPrice());
            ps.setDouble(3, report.compositeScore());
            ps.setDouble(4, report.predictedLow());
            ps.setDouble(5, report.predictedMid());
            ps.setDouble(6, report.predictedHigh());
            ps.setDouble(7, report.confidence());
            ps.setObject(8, target);
            ps.executeUpdate();
            log.info("已記錄預測 | {} | 目標月: {}", ts, target);
        } catch (Exception e) {
            throw new RuntimeException("記錄預測失敗", e);
        }
    }

    // ============================================================
    // 讀取（Track B2 特徵矩陣用）
    // 只讀每日序列（LIVE/BACKFILL），排除 INTRADAY 盤中資料；
    // 同一日期若同時有 BACKFILL 與 LIVE，一律優先取 LIVE（信號較完整）。
    // ============================================================

    private static final String DAILY_SOURCES = "source IN ('LIVE','BACKFILL')";

    /** 日期 → 收盤/採集價（每日連續序列，供前瞻報酬計算）。 */
    public TreeMap<LocalDate, Double> loadPriceSeries() {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        String sql = "SELECT CAST(ts AS DATE) d, btc_price FROM quant_snapshot "
                   + "WHERE " + DAILY_SOURCES + " "
                   + "ORDER BY ts, CASE WHEN source='LIVE' THEN 1 ELSE 0 END";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.put(rs.getObject(1, LocalDate.class), rs.getDouble(2));
        } catch (Exception e) {
            throw new RuntimeException("讀取價格序列失敗", e);
        }
        return out;
    }

    /** 日期 → 綜合分數。 */
    public TreeMap<LocalDate, Double> loadComposites() {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        String sql = "SELECT CAST(ts AS DATE) d, composite FROM quant_snapshot "
                   + "WHERE " + DAILY_SOURCES + " "
                   + "ORDER BY ts, CASE WHEN source='LIVE' THEN 1 ELSE 0 END";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.put(rs.getObject(1, LocalDate.class), rs.getDouble(2));
        } catch (Exception e) {
            throw new RuntimeException("讀取綜合分數失敗", e);
        }
        return out;
    }

    /** 日期 → (signal_id → score)。BACKFILL 先讀、LIVE 後讀以覆蓋同日。 */
    public TreeMap<LocalDate, LinkedHashMap<String, Double>> loadSignalScores() {
        TreeMap<LocalDate, LinkedHashMap<String, Double>> out = new TreeMap<>();
        String sql = "SELECT CAST(ts AS DATE) d, signal_id, score FROM quant_signal "
                   + "WHERE " + DAILY_SOURCES + " "
                   + "ORDER BY ts, CASE WHEN source='LIVE' THEN 1 ELSE 0 END";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                LocalDate d = rs.getObject(1, LocalDate.class);
                out.computeIfAbsent(d, k -> new LinkedHashMap<>())
                   .put(rs.getString(2), rs.getDouble(3));
            }
        } catch (Exception e) {
            throw new RuntimeException("讀取信號分數失敗", e);
        }
        return out;
    }

    /** 產生人類可讀的狀態摘要（採集天數、日期範圍、最新預測）。 */
    public String statusReport() {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement()) {
            sb.append("  觀測資料（quant_snapshot）:\n");
            try (ResultSet rs = st.executeQuery(
                    "SELECT source, COUNT(*) n, MIN(CAST(ts AS DATE)) lo, MAX(CAST(ts AS DATE)) hi "
                  + "FROM quant_snapshot GROUP BY source ORDER BY source")) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    sb.append(String.format("    %-9s %4d 天   %s ~ %s%n",
                        rs.getString("source"), rs.getInt("n"),
                        rs.getObject("lo", LocalDate.class), rs.getObject("hi", LocalDate.class)));
                }
                if (!any) sb.append("    （尚無資料，請先 --collect 或 --backfill）\n");
            }
            sb.append("  最新預測（quant_prediction）:\n");
            try (ResultSet rs = st.executeQuery(
                    "SELECT CAST(ts AS DATE) d, composite, pred_low, pred_mid, pred_high, "
                  + "confidence, target_date, realized_price FROM quant_prediction ORDER BY ts DESC LIMIT 1")) {
                if (rs.next()) {
                    sb.append(String.format("    %s | 綜合分 %.1f | 預測 %.0f~%.0f (中位 %.0f) | 信心 %.0f%%%n",
                        rs.getObject("d", LocalDate.class), rs.getDouble("composite"),
                        rs.getDouble("pred_low"), rs.getDouble("pred_high"),
                        rs.getDouble("pred_mid"), rs.getDouble("confidence")));
                    Object target = rs.getObject("target_date");
                    Object realized = rs.getObject("realized_price");
                    sb.append(String.format("    目標月: %s | 到期實際價: %s%n",
                        target, realized == null ? "（尚未到期）" : realized));
                } else {
                    sb.append("    （尚無預測紀錄）\n");
                }
            }
        } catch (Exception e) {
            sb.append("    ⚠ 讀取狀態失敗: ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** 回傳 quant_snapshot 目前的筆數（驗證用）。 */
    public int snapshotCount() {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM quant_snapshot")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new RuntimeException("查詢筆數失敗", e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (Exception e) {
            log.warn("關閉量化數據庫時發生例外: {}", e.getMessage());
        }
    }
}
