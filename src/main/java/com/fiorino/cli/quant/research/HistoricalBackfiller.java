package com.fiorino.cli.quant.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fiorino.cli.quant.BtcQuantAnalyzer.SignalResult;
import com.fiorino.cli.quant.BtcQuantAnalyzer.Verdict;
import com.fiorino.cli.quant.BtcQuantAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * ============================================================
 * HistoricalBackfiller — Track B1 歷史回填
 * ============================================================
 *
 * 把「有長期免費歷史」的 3 個信號回填進 H2（source=BACKFILL），建立回測基準：
 *   - fear_greed       ← alternative.me（2018 起）
 *   - funding_rate     ← 幣安 fapi/v1/fundingRate（分頁回溯）
 *   - coinbase_premium ← Coinbase 日 K − 幣安日 K 重建
 *   - btc_price        ← 幣安現貨日 K（收盤價）
 *
 * 其餘 4 個衍生品信號（OI/散戶/大戶/Taker）免費僅 30 天，無法回填，
 * 只能靠 Track A 前向採集（見 QUANT_BACKTEST_PLAN.md §0）。
 *
 * 評分一律走 BtcQuantAnalyzer 的 static scorer，與 live 完全一致。
 * 每日綜合分數為「可得信號的權重正規化加權平均」（非完整 8 信號）。
 */
public final class HistoricalBackfiller {

    private static final Logger log = LoggerFactory.getLogger(HistoricalBackfiller.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 回填最近 lookbackDays 天，回傳實際寫入的天數。 */
    public int backfill(int lookbackDays) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(lookbackDays);
        log.info("Track B1 回填啟動 | 範圍 {} ~ {}（{} 天）", from, today.minusDays(1), lookbackDays);

        TreeMap<LocalDate, Double> binClose = fetchBinanceDailyCloses(from);
        TreeMap<LocalDate, Double> fng       = fetchFngAll();
        TreeMap<LocalDate, Double> funding    = fetchFundingDaily(from);
        TreeMap<LocalDate, Double> cbClose    = fetchCoinbaseDailyCloses(from);
        log.info("抓取完成 | 幣安K:{} FNG:{} Funding:{} Coinbase:{}",
            binClose.size(), fng.size(), funding.size(), cbClose.size());

        int written = 0;
        try (QuantDataStore store = new QuantDataStore()) {
            for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
                Double price = binClose.get(d);
                if (price == null) continue;               // 沒價格就跳過

                List<SignalResult> signals = new ArrayList<>();
                double num = 0, wsum = 0;

                // 1) 恐懼貪婪
                Double fngVal = fng.get(d);
                if (fngVal != null) {
                    double avg7d  = windowAvg(fng, d, 7,  fngVal);
                    double avg30d = windowAvg(fng, d, 30, fngVal);
                    Verdict v = BtcQuantAnalyzer.scoreFearGreed(fngVal, avg7d, avg30d);
                    signals.add(new SignalResult("😱 恐懼貪婪指數", "fear_greed", v.score(), 0.12,
                        String.format("%.0f", fngVal), v.interp(), v.trend(), fngVal));
                    num += v.score() * 0.12; wsum += 0.12;
                }
                // 2) 資金費率
                Double frPct = funding.get(d);
                if (frPct != null) {
                    Verdict v = BtcQuantAnalyzer.scoreFunding(frPct);
                    signals.add(new SignalResult("💰 資金費率", "funding_rate", v.score(), 0.13,
                        String.format("%+.4f%%", frPct), v.interp(), v.trend(), frPct));
                    num += v.score() * 0.13; wsum += 0.13;
                }
                // 3) Coinbase 溢價（重建）
                Double cb = cbClose.get(d);
                if (cb != null) {
                    double prem = cb - price;
                    Verdict v = BtcQuantAnalyzer.scoreCoinbasePremium(prem);
                    signals.add(new SignalResult("🏦 機構資金流向", "coinbase_premium", v.score(), 0.13,
                        String.format("%+.2f USDT", prem), v.interp(), v.trend(), prem));
                    num += v.score() * 0.13; wsum += 0.13;
                }

                if (signals.isEmpty()) continue;
                double composite = wsum > 0 ? num / wsum : 0;  // 權重正規化的部分綜合分
                store.saveDay(d.atStartOfDay(ZoneOffset.UTC).toInstant(), "BACKFILL",
                    price, composite, signals);
                written++;
            }
        }
        log.info("Track B1 回填完成 | 寫入 {} 天 (source=BACKFILL)", written);
        return written;
    }

    /** 計算以 d 結尾、回看 window 天的均值（只計可得值；無資料時退回 fallback）。 */
    private static double windowAvg(TreeMap<LocalDate, Double> map, LocalDate d, int window, double fallback) {
        double sum = 0; int n = 0;
        for (int i = 0; i < window; i++) {
            Double v = map.get(d.minusDays(i));
            if (v != null) { sum += v; n++; }
        }
        return n > 0 ? sum / n : fallback;
    }

    // ============================================================
    // 數據抓取（皆免費公開、分頁）
    // ============================================================

    private TreeMap<LocalDate, Double> fetchBinanceDailyCloses(LocalDate from) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        long startMs = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long nowMs = System.currentTimeMillis();
        while (startMs < nowMs) {
            JsonNode arr = getJson("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=1000&startTime=" + startMs);
            if (arr == null || !arr.isArray() || arr.isEmpty()) break;
            long lastOpen = startMs;
            for (JsonNode k : arr) {
                long openMs = k.get(0).asLong();
                double close = k.get(4).asDouble();
                LocalDate d = java.time.Instant.ofEpochMilli(openMs).atZone(ZoneOffset.UTC).toLocalDate();
                out.put(d, close);
                lastOpen = openMs;
            }
            if (arr.size() < 1000) break;
            startMs = lastOpen + 86_400_000L;   // 下一天
        }
        return out;
    }

    private TreeMap<LocalDate, Double> fetchFngAll() {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        JsonNode root = getJson("https://api.alternative.me/fng/?limit=0");
        if (root == null) return out;
        for (JsonNode e : root.path("data")) {
            double val = e.path("value").asDouble(-1);
            long sec = e.path("timestamp").asLong(-1);
            if (val >= 0 && sec > 0) {
                LocalDate d = java.time.Instant.ofEpochSecond(sec).atZone(ZoneOffset.UTC).toLocalDate();
                out.put(d, val);
            }
        }
        return out;
    }

    private TreeMap<LocalDate, Double> fetchFundingDaily(LocalDate from) {
        // 先收每日所有 funding（每 8h 一筆），再取當日平均（轉百分比）
        TreeMap<LocalDate, double[]> agg = new TreeMap<>();  // [sum, count]
        long startMs = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long nowMs = System.currentTimeMillis();
        while (startMs < nowMs) {
            JsonNode arr = getJson("https://fapi.binance.com/fapi/v1/fundingRate?symbol=BTCUSDT&limit=1000&startTime=" + startMs);
            if (arr == null || !arr.isArray() || arr.isEmpty()) break;
            long lastTime = startMs;
            for (JsonNode e : arr) {
                long t = e.path("fundingTime").asLong();
                double fr = e.path("fundingRate").asDouble() * 100.0;  // 轉百分比
                LocalDate d = java.time.Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate();
                double[] a = agg.computeIfAbsent(d, k -> new double[2]);
                a[0] += fr; a[1]++;
                lastTime = t;
            }
            if (arr.size() < 1000) break;
            startMs = lastTime + 1;
        }
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        agg.forEach((d, a) -> out.put(d, a[0] / a[1]));
        return out;
    }

    private TreeMap<LocalDate, Double> fetchCoinbaseDailyCloses(LocalDate from) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // Coinbase 每次最多約 300 根 → 以 290 天為一段，往後推進
        for (LocalDate chunkStart = from; chunkStart.isBefore(today); chunkStart = chunkStart.plusDays(290)) {
            LocalDate chunkEnd = chunkStart.plusDays(290);
            if (chunkEnd.isAfter(today)) chunkEnd = today;
            String url = String.format(
                "https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400&start=%s&end=%s",
                chunkStart, chunkEnd);
            JsonNode arr = getJson(url);
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {       // [time, low, high, open, close, vol]
                    long sec = c.get(0).asLong();
                    double close = c.get(4).asDouble();
                    LocalDate d = java.time.Instant.ofEpochSecond(sec).atZone(ZoneOffset.UTC).toLocalDate();
                    out.put(d, close);
                }
            }
        }
        return out;
    }

    private JsonNode getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url)).timeout(TIMEOUT).GET()
                .header("Accept", "application/json")
                .header("User-Agent", "ProjectFiorino/2.0")
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("回填請求非 200: {} | {}", resp.statusCode(), url);
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("回填請求失敗 {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // CLI 進入點
    // ============================================================

    public static void runHeadless(int lookbackDays) {
        System.out.printf("📦 Project Fiorino — Track B1 歷史回填（最近 %d 天）%n", lookbackDays);
        int n = new HistoricalBackfiller().backfill(lookbackDays);
        System.out.printf("✅ 回填完成，寫入 %d 天歷史觀測（source=BACKFILL）到 %s%n", n, QuantDataStore.defaultDbPath());
        System.out.println("   可回填信號：fear_greed / funding_rate / coinbase_premium（其餘 4 信號靠 Track A 累積）");
    }
}
