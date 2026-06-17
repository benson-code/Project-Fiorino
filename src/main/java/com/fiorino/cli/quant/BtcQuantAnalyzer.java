package com.fiorino.cli.quant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ============================================================
 * BtcQuantAnalyzer — BTC 量化分析引擎
 * ============================================================
 *
 * 從多個免費公開市場 API 抓取多維市場數據，透過加權評分模型
 * 生成下個月 BTC 價格的量化預測報告。所有數據源皆無需 API Key。
 *
 * 分析維度（共 8 個信號，權重總和 = 1.00）與數據來源：
 *   1. Fear & Greed Index（市場情緒）        權重 12%  ← alternative.me
 *   2. Open Interest Trend（未平倉量趨勢）   權重 18%  ← Binance fapi
 *   3. Funding Rate（資金費率）              權重 13%  ← Binance fapi
 *   4. Retail Long/Short（散戶多空比，逆向） 權重 10%  ← Binance fapi globalLongShortAccountRatio
 *   5. Top Trader L/S（大戶持倉多空比，順勢）權重 15%  ← Binance fapi topLongShortPositionRatio
 *   6. Coinbase Premium（機構資金流向）      權重 13%  ← Coinbase + Binance 現貨
 *   7. Taker Buy/Sell（主動買賣盤壓力）      權重 12%  ← Binance fapi takerlongshortRatio
 *   8. BTC Dominance（比特幣主導地位）       權重 07%  ← CoinGecko
 *
 * 價格預測區間採幣安日 K 線實算的歷史月度波動率（非寫死常數）。
 *
 * 預測邏輯：
 *   - 每個信號輸出 [-100, +100] 的看漲/看跌分數
 *   - 加權平均得出「市場綜合分數」
 *   - 根據綜合分數計算價格預測區間（使用歷史波動率修正）
 *
 * @author benson-code
 * @version 2.0.0
 */
public final class BtcQuantAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BtcQuantAnalyzer.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    // ============================================================
    // 分析結果容器
    // ============================================================

    public record SignalResult(
        String name,
        String emoji,
        double score,          // -100（極度看跌）到 +100（極度看漲）
        double weight,
        String value,          // 原始數值（顯示用字串）
        String interpretation, // 解讀文字
        String trend,          // 趨勢方向
        double rawValue        // 原始連續數值（供未來校準；無值時為 NaN）
    ) {
        /** 相容建構子：未提供原始連續值時以 NaN 表示（例如 N/A 信號）。 */
        public SignalResult(String name, String emoji, double score, double weight,
                            String value, String interpretation, String trend) {
            this(name, emoji, score, weight, value, interpretation, trend, Double.NaN);
        }
    }

    public record AnalysisReport(
        double compositeScore,      // 綜合分數 -100~+100
        String marketSentiment,     // 市場情緒標籤
        double currentPrice,        // 當前 BTC 價格（USD）
        double predictedLow,        // 下月預測最低價
        double predictedMid,        // 下月預測中位價
        double predictedHigh,       // 下月預測最高價
        double confidence,          // 預測信心度 0~100%
        String direction,           // BULLISH / BEARISH / NEUTRAL
        List<SignalResult> signals, // 各信號詳情
        LocalDate targetMonth,      // 預測目標月份
        String analysisTime,        // 分析時間
        boolean hasFullData         // 是否有完整 API 數據
    ) {}

    // ============================================================
    // 核心欄位
    // ============================================================

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BtcQuantAnalyzer(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    // ============================================================
    // 主分析方法
    // ============================================================

    /**
     * 執行完整的 BTC 量化分析並生成預測報告。
     * 此方法會阻塞直到所有 API 請求完成。
     */
    public AnalysisReport analyze() {
        log.info("開始 BTC 量化分析...");

        List<SignalResult> signals = new ArrayList<>();
        boolean hasFullData = true;

        // ── 1. 抓取當前 BTC 價格 ──
        double currentPrice = fetchCurrentPrice();
        if (currentPrice <= 0) {
            currentPrice = 0;
            hasFullData = false;
        }

        // ── 2. 逐一分析各信號 ──
        SignalResult fearGreed = analyzeFearGreed();
        signals.add(fearGreed);
        if (fearGreed.score() == 0 && fearGreed.value().equals("N/A")) hasFullData = false;

        SignalResult oiTrend = analyzeOpenInterest(currentPrice);
        signals.add(oiTrend);

        SignalResult fundingRate = analyzeFundingRate();
        signals.add(fundingRate);

        SignalResult longShort = analyzeLongShortRatio();
        signals.add(longShort);

        SignalResult topTrader = analyzeTopTraderRatio();
        signals.add(topTrader);

        SignalResult coinbasePremium = analyzeCoinbasePremium();
        signals.add(coinbasePremium);

        SignalResult takerPressure = analyzeTakerPressure();
        signals.add(takerPressure);

        SignalResult dominance = analyzeBtcDominance();
        signals.add(dominance);

        // ── 3. 計算加權綜合分數（權重正規化：跳過 N/A 信號，避免失敗信號被當中性拉低）──
        //     與 HistoricalBackfiller 一致；8 信號全在時 weightTotal=1.0，等同原加權和。
        double weightedSum = 0, weightTotal = 0;
        for (SignalResult s : signals) {
            if ("N/A".equals(s.value())) continue;
            weightedSum += s.score() * s.weight();
            weightTotal += s.weight();
        }
        double compositeScore = weightTotal > 0 ? weightedSum / weightTotal : 0;
        compositeScore = Math.max(-100, Math.min(100, compositeScore));

        // ── 4. 生成價格預測 ──
        double monthlyVol = fetchHistoricalVolatility();
        double[] priceRange = predictPriceRange(currentPrice, compositeScore, monthlyVol);

        // ── 5. 判斷市場情緒 & 信心度 ──
        String sentiment = classifySentiment(compositeScore);
        String direction = compositeScore > 10 ? "BULLISH" :
                           compositeScore < -10 ? "BEARISH" : "NEUTRAL";
        double confidence = calculateConfidence(signals, compositeScore);

        String analysisTime = java.time.LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        log.info("分析完成 | 綜合分數: {} | 方向: {} | 信心度: {}%",
            String.format("%.1f", compositeScore), direction, String.format("%.0f", confidence));

        return new AnalysisReport(
            compositeScore, sentiment, currentPrice,
            priceRange[0], priceRange[1], priceRange[2],
            confidence, direction, signals,
            LocalDate.now().plusMonths(1),
            analysisTime, hasFullData
        );
    }

    // ============================================================
    // 評分核心（單一真相來源）
    // ------------------------------------------------------------
    // 純函數，不碰 I/O：給 live 分析與 Track B 歷史回填共用，
    // 確保回測評分與即時評分 100% 一致（見 HistoricalBackfiller）。
    // ============================================================

    /** 評分結果：分數 + 解讀 + 趨勢標籤。 */
    public record Verdict(double score, String interp, String trend) {}

    /** 恐懼貪婪評分（含 7日 vs 30日趨勢微調）。 */
    public static Verdict scoreFearGreed(double latest, double avg7d, double avg30d) {
        double score; String interp; String trend;
        if (latest <= 20) {
            score = 70; interp = "極度恐懼區間，歷史上往往是買入好時機"; trend = "▲ 逆向看漲";
        } else if (latest <= 35) {
            score = 40; interp = "恐懼情緒明顯，市場可能正在底部區域"; trend = "↗ 偏向看漲";
        } else if (latest <= 55) {
            score = 5; interp = "市場情緒中性，方向不明確"; trend = "→ 中性";
        } else if (latest <= 70) {
            score = -25; interp = "貪婪情緒升溫，短期需注意高位風險"; trend = "↘ 偏向看跌";
        } else if (latest <= 85) {
            score = -50; interp = "過度貪婪，歷史上常出現在頂部附近"; trend = "▼ 偏向看跌";
        } else {
            score = -70; interp = "極度貪婪，市場可能過熱，回調風險高"; trend = "▼▼ 強烈看跌";
        }
        double trendBonus = (avg7d - avg30d) / 10.0;
        score = Math.max(-100, Math.min(100, score - trendBonus * 5));
        return new Verdict(score, interp, trend);
    }

    /** 資金費率評分（入參為百分比，例如 0.01 代表 0.01%）。 */
    public static Verdict scoreFunding(double avgFrPercent) {
        if (avgFrPercent > 0.1)   return new Verdict(-70, "資金費率極高，多頭槓桿嚴重過熱，回調風險大", "▼▼ 強烈看跌");
        if (avgFrPercent > 0.05)  return new Verdict(-40, "資金費率偏高，多頭付出高昂持倉成本", "▼ 偏向看跌");
        if (avgFrPercent > 0.01)  return new Verdict(-10, "資金費率正常偏高，多頭略佔優勢", "→ 輕微偏空");
        if (avgFrPercent >= -0.01) return new Verdict(20, "資金費率中性，市場健康，不偏不抑", "→ 中性健康");
        if (avgFrPercent >= -0.05) return new Verdict(50, "資金費率為負，空頭支付，多頭佔優勢", "▲ 看漲");
        return new Verdict(75, "資金費率大幅為負，空頭過熱，可能觸發軋空行情", "▲▲ 強烈看漲");
    }

    /** Coinbase 溢價評分（入參為 Coinbase - 幣安 的 USDT 價差）。 */
    public static Verdict scoreCoinbasePremium(double premium) {
        if (premium > 50)  return new Verdict(80, "Coinbase 溢價極高，機構積極買入，強烈看漲信號", "▲▲ 強烈看漲");
        if (premium > 15)  return new Verdict(50, "Coinbase 溢價顯著，機構資金正在流入", "▲ 看漲");
        if (premium > 0)   return new Verdict(20, "Coinbase 輕微溢價，機構態度略顯樂觀", "↗ 輕微看漲");
        if (premium > -15) return new Verdict(-20, "Coinbase 輕微折價，機構略顯謹慎", "↘ 輕微看跌");
        if (premium > -50) return new Verdict(-50, "Coinbase 明顯折價，機構正在拋售", "▼ 看跌");
        return new Verdict(-80, "Coinbase 折價嚴重，機構大規模拋壓，強烈看跌", "▼▼ 強烈看跌");
    }

    // ============================================================
    // 各信號分析方法
    // ============================================================

    private SignalResult analyzeFearGreed() {
        try {
            // 使用 Alternative.me 免費公開 API（無須 Key）
            JsonNode root = get("https://api.alternative.me/fng/?limit=30");
            if (root == null) return naSignal("😱 恐懼貪婪指數", "fear_greed", 0.12);

            JsonNode dataNode = root.path("data");
            if (!dataNode.isArray() || dataNode.isEmpty()) return naSignal("😱 恐懼貪婪指數", "fear_greed", 0.12);

            // 讀取最近 30 天數據（Alternative.me 最新的排在最前面，需要反轉）
            List<Double> values = new ArrayList<>();
            for (int i = dataNode.size() - 1; i >= 0; i--) {
                double val = dataNode.get(i).path("value").asDouble(-1);
                if (val >= 0 && val <= 100) values.add(val);
            }
            if (values.isEmpty()) return naSignal("😱 恐懼貪婪指數", "fear_greed", 0.12);

            double latest = values.get(values.size() - 1);
            double avg7d = values.subList(Math.max(0, values.size() - 7), values.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(latest);
            double avg30d = values.stream().mapToDouble(Double::doubleValue).average().orElse(latest);

            Verdict v = scoreFearGreed(latest, avg7d, avg30d);

            String label = latest <= 20 ? "Extreme Fear" : latest <= 40 ? "Fear" :
                           latest <= 60 ? "Neutral" : latest <= 80 ? "Greed" : "Extreme Greed";

            return new SignalResult(
                "😱 恐懼貪婪指數", "fear_greed", v.score(), 0.12,
                String.format("%.0f (%s)", latest, label),
                v.interp() + String.format("（7日均: %.0f | 30日均: %.0f，資料源：Alternative.me）", avg7d, avg30d),
                v.trend(), latest
            );
        } catch (Exception e) {
            log.debug("Fear & Greed 分析失敗: {}", e.getMessage());
            return naSignal("😱 恐懼貪婪指數", "fear_greed", 0.12);
        }
    }

    private SignalResult analyzeOpenInterest(double currentPrice) {
        try {
            // 使用幣安合約公開數據 API（無須 Key）
            JsonNode root = get("https://fapi.binance.com/futures/data/openInterestHist?symbol=BTCUSDT&period=4h&limit=90");
            if (root == null || !root.isArray()) return naSignal("📊 未平倉量趨勢", "open_interest", 0.18);

            List<Double> oiValues = new ArrayList<>();
            for (JsonNode entry : root) {
                double oiVal = entry.path("sumOpenInterestValue").asDouble(-1);
                if (oiVal > 0) oiValues.add(oiVal);
            }
            if (oiValues.size() < 10) return naSignal("📊 未平倉量趨勢", "open_interest", 0.18);

            double latestOi  = oiValues.get(oiValues.size() - 1);
            double oi7dAgo   = oiValues.get(Math.max(0, oiValues.size() - 42)); // 42 * 4h = 7天
            double oiOldest  = oiValues.get(0); // 最早可用（窗口約 90*4h≈15天）

            double change7d   = (latestOi - oi7dAgo)  / oi7dAgo  * 100;
            double changeFull = (latestOi - oiOldest) / oiOldest * 100;

            double score;
            String interp;
            String trend;

            if (change7d > 15) {
                score = 60;
                interp = "未平倉量大幅增加，新資金湧入市場，看漲動能強";
                trend = "▲▲ 強勢看漲";
            } else if (change7d > 5) {
                score = 35;
                interp = "未平倉量穩步上升，多頭積極入場";
                trend = "▲ 看漲";
            } else if (change7d > -5) {
                score = 0;
                interp = "未平倉量橫盤，市場處於觀望狀態";
                trend = "→ 中性";
            } else if (change7d > -15) {
                score = -35;
                interp = "未平倉量下降，空頭獲利了結或多頭撤離";
                trend = "▼ 看跌";
            } else {
                score = -60;
                interp = "未平倉量大幅下降，市場去槓桿，謹慎看跌";
                trend = "▼▼ 強勢看跌";
            }

            String oiStr = formatBigNumber(latestOi);
            return new SignalResult(
                "📊 未平倉量趨勢", "open_interest", score, 0.18,
                String.format("%s USD", oiStr),
                interp + String.format("（7日變化: %+.1f%% | 全期(~15天)變化: %+.1f%%，資料源：幣安合約）", change7d, changeFull),
                trend, change7d
            );
        } catch (Exception e) {
            log.debug("OI 分析失敗: {}", e.getMessage());
            return naSignal("📊 未平倉量趨勢", "open_interest", 0.18);
        }
    }

    private SignalResult analyzeFundingRate() {
        try {
            // 使用幣安合約公開 Premium Index API 獲取即時資金費率
            JsonNode root = get("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=BTCUSDT");
            if (root == null) return naSignal("💰 資金費率", "funding_rate", 0.13);

            double lastFundingRate = root.path("lastFundingRate").asDouble(Double.NaN);
            if (Double.isNaN(lastFundingRate)) return naSignal("💰 資金費率", "funding_rate", 0.13);

            double avgFr = lastFundingRate * 100; // 轉換為百分比

            Verdict v = scoreFunding(avgFr);

            return new SignalResult(
                "💰 資金費率", "funding_rate", v.score(), 0.13,
                String.format("%+.4f%%", avgFr),
                v.interp() + "（幣安永續合約實時費率）",
                v.trend(), avgFr
            );
        } catch (Exception e) {
            log.debug("Funding Rate 分析失敗: {}", e.getMessage());
            return naSignal("💰 資金費率", "funding_rate", 0.13);
        }
    }

    private SignalResult analyzeLongShortRatio() {
        try {
            // 使用幣安合約公開多空比歷史 API
            JsonNode root = get("https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol=BTCUSDT&period=4h&limit=60");
            if (root == null || !root.isArray() || root.isEmpty()) return naSignal("⚖️  散戶多空比", "long_short", 0.10);

            List<Double> lsValues = new ArrayList<>();
            for (JsonNode entry : root) {
                double ls = entry.path("longShortRatio").asDouble(-1);
                if (ls > 0) lsValues.add(ls);
            }
            if (lsValues.isEmpty()) return naSignal("⚖️  散戶多空比", "long_short", 0.10);

            double latest = lsValues.get(lsValues.size() - 1);
            double avg = lsValues.stream().mapToDouble(Double::doubleValue).average().orElse(latest);
            double latestLong = root.get(root.size() - 1).path("longAccount").asDouble(0) * 100;
            double latestShort = root.get(root.size() - 1).path("shortAccount").asDouble(0) * 100;

            double score;
            String interp;
            String trend;

            double deviation = latest - avg;

            if (latest > 1.5) {
                score = -50; // 多頭過擠，逆向看跌
                interp = "多頭佔絕對優勢但偏離均值，可能面臨多頭踩踏";
                trend = "▼ 逆向看跌";
            } else if (latest > 1.2) {
                score = -20;
                interp = "多頭略佔優勢，需警惕多頭過熱";
                trend = "↘ 輕微看跌";
            } else if (latest >= 0.9 && latest <= 1.1) {
                score = 15;
                interp = "多空勢均力敵，市場均衡健康，方向待定";
                trend = "→ 中性";
            } else if (latest < 0.7) {
                score = 60; // 空頭過多，逆向看漲
                interp = "空頭佔絕對優勢，可能觸發大規模軋空";
                trend = "▲▲ 逆向看漲";
            } else {
                score = 35;
                interp = "空頭略佔優勢，逆向多頭機會";
                trend = "▲ 偏向看漲";
            }

            return new SignalResult(
                "⚖️  散戶多空比", "long_short", score, 0.10,
                String.format("%.4f (多%.1f%% / 空%.1f%%)", latest, latestLong, latestShort),
                interp + String.format("（散戶帳戶比，均值: %.4f, 偏差: %+.4f，資料源：幣安全市場帳戶多空比）", avg, deviation),
                trend, latest
            );
        } catch (Exception e) {
            log.debug("Long/Short 分析失敗: {}", e.getMessage());
            return naSignal("⚖️  散戶多空比", "long_short", 0.10);
        }
    }

    private SignalResult analyzeCoinbasePremium() {
        try {
            // 使用 Coinbase 與幣安的即時現貨價差計算溢價
            double binancePrice = fetchCurrentPrice();
            JsonNode root = get("https://api.coinbase.com/v2/prices/BTC-USD/spot");
            if (root == null || binancePrice <= 0) return naSignal("🏦 機構資金流向", "coinbase_premium", 0.13);

            double coinbasePrice = root.path("data").path("amount").asDouble(0);
            if (coinbasePrice <= 0) return naSignal("🏦 機構資金流向", "coinbase_premium", 0.13);

            // 溢價 = Coinbase 價格 - 幣安價格 (正數代表美股機構正在強力購買)
            double latestPrem = coinbasePrice - binancePrice;

            Verdict v = scoreCoinbasePremium(latestPrem);

            return new SignalResult(
                "🏦 機構資金流向", "coinbase_premium", v.score(), 0.13,
                String.format("%+.2f USDT", latestPrem),
                v.interp() + String.format("（實時溢價，資料源：Coinbase / 幣安現貨）"),
                v.trend(), latestPrem
            );
        } catch (Exception e) {
            log.debug("Coinbase Premium 分析失敗: {}", e.getMessage());
            return naSignal("🏦 機構資金流向", "coinbase_premium", 0.13);
        }
    }

    /**
     * 主動買賣盤壓力（Taker Buy/Sell Volume Ratio）。
     * 取代舊版以 Math.sin() 模擬的「爆倉壓力」，改用真實成交數據。
     * buySellRatio = 主動買量 / 主動賣量：>1 表示吃單方積極做多（看漲動能），
     * <1 表示積極做空（看跌動能）。資料源：幣安合約公開 takerlongshortRatio（無須 Key）。
     */
    private SignalResult analyzeTakerPressure() {
        try {
            JsonNode root = get("https://fapi.binance.com/futures/data/takerlongshortRatio?symbol=BTCUSDT&period=4h&limit=42");
            if (root == null || !root.isArray() || root.isEmpty())
                return naSignal("💥 主動買賣盤壓力", "taker_flow", 0.12);

            List<Double> ratios = new ArrayList<>();
            for (JsonNode e : root) {
                double r = e.path("buySellRatio").asDouble(-1);
                if (r > 0) ratios.add(r);
            }
            if (ratios.isEmpty()) return naSignal("💥 主動買賣盤壓力", "taker_flow", 0.12);

            double latest = ratios.get(ratios.size() - 1);
            double avg = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(latest);
            JsonNode last = root.get(root.size() - 1);
            double buyVol = last.path("buyVol").asDouble(0);
            double sellVol = last.path("sellVol").asDouble(0);
            double buyShare = (buyVol + sellVol) > 0 ? buyVol / (buyVol + sellVol) * 100 : 50;

            double score;
            String interp;
            String trend;
            if (latest > 1.20) {
                score = 60;
                interp = "主動買盤遠強於賣盤，吃單方積極做多";
                trend = "▲▲ 強烈看漲";
            } else if (latest > 1.05) {
                score = 35;
                interp = "主動買盤偏強，多頭吃單動能佔優";
                trend = "▲ 看漲";
            } else if (latest >= 0.95) {
                score = 5;
                interp = "主動買賣盤大致均衡，方向不明";
                trend = "→ 中性";
            } else if (latest >= 0.80) {
                score = -35;
                interp = "主動賣盤偏強，空頭吃單動能佔優";
                trend = "▼ 看跌";
            } else {
                score = -60;
                interp = "主動賣盤遠強於買盤，吃單方積極做空";
                trend = "▼▼ 強烈看跌";
            }

            return new SignalResult(
                "💥 主動買賣盤壓力", "taker_flow", score, 0.12,
                String.format("%.4f (買盤 %.1f%%)", latest, buyShare),
                interp + String.format("（4h 主動買/賣量比，30期均值: %.4f，資料源：幣安 taker 買賣量）", avg),
                trend, latest
            );
        } catch (Exception e) {
            log.debug("Taker 買賣壓力分析失敗: {}", e.getMessage());
            return naSignal("💥 主動買賣盤壓力", "taker_flow", 0.12);
        }
    }

    /**
     * 大戶持倉多空比（Top Trader Long/Short Position Ratio）。
     * 與散戶帳戶比相反，大戶被視為「聰明錢」，採順勢解讀（跟隨大戶方向），
     * 但在極端擁擠（>2.5 或 <0.5）時調降分數以反映反轉/軋空風險。
     * 資料源：幣安合約公開 topLongShortPositionRatio（無須 Key）。
     */
    private SignalResult analyzeTopTraderRatio() {
        try {
            JsonNode root = get("https://fapi.binance.com/futures/data/topLongShortPositionRatio?symbol=BTCUSDT&period=4h&limit=30");
            if (root == null || !root.isArray() || root.isEmpty())
                return naSignal("🐋 大戶持倉多空比", "top_trader", 0.15);

            JsonNode last = root.get(root.size() - 1);
            double ratio = last.path("longShortRatio").asDouble(-1);
            if (ratio <= 0) return naSignal("🐋 大戶持倉多空比", "top_trader", 0.15);
            double longPct = last.path("longAccount").asDouble(0) * 100;
            double shortPct = last.path("shortAccount").asDouble(0) * 100;

            double score;
            String interp;
            String trend;
            if (ratio >= 2.5) {
                score = 25;
                interp = "大戶極度偏多，倉位擁擠，順勢偏漲但留意反轉";
                trend = "▲ 看漲（留意擁擠）";
            } else if (ratio >= 1.5) {
                score = 50;
                interp = "大戶顯著做多，聰明錢看漲";
                trend = "▲▲ 強烈看漲";
            } else if (ratio >= 1.1) {
                score = 30;
                interp = "大戶淨多頭，偏向看漲";
                trend = "▲ 看漲";
            } else if (ratio >= 0.9) {
                score = 0;
                interp = "大戶多空均衡，方向中性";
                trend = "→ 中性";
            } else if (ratio >= 0.7) {
                score = -30;
                interp = "大戶淨空頭，偏向看跌";
                trend = "▼ 看跌";
            } else if (ratio >= 0.5) {
                score = -50;
                interp = "大戶顯著做空，聰明錢看跌";
                trend = "▼▼ 強烈看跌";
            } else {
                score = -25;
                interp = "大戶極度偏空，倉位擁擠，順勢偏跌但留意軋空";
                trend = "▼ 看跌（留意軋空）";
            }

            return new SignalResult(
                "🐋 大戶持倉多空比", "top_trader", score, 0.15,
                String.format("%.4f (多%.1f%% / 空%.1f%%)", ratio, longPct, shortPct),
                interp + "（順勢解讀，資料源：幣安 topLongShortPositionRatio）",
                trend, ratio
            );
        } catch (Exception e) {
            log.debug("大戶多空比分析失敗: {}", e.getMessage());
            return naSignal("🐋 大戶持倉多空比", "top_trader", 0.15);
        }
    }

    private SignalResult analyzeBtcDominance() {
        try {
            // 使用 CoinGecko 免費公開 API 獲取 BTC 市佔率（比特幣主導地位）
            JsonNode root = get("https://api.coingecko.com/api/v3/global");
            if (root == null) return naSignal("🌐 BTC 主導地位", "dominance", 0.07);

            double latest = root.path("data").path("market_cap_percentage").path("btc").asDouble(-1);
            if (latest < 0) return naSignal("🌐 BTC 主導地位", "dominance", 0.07);

            double change = 0.0; // 實時基準
            double score;
            String interp;
            String trend;

            if (latest > 60) {
                score = 55;
                interp = "BTC 主導率極高，市場高度共識，資金集中看漲";
                trend = "▲ 看漲";
            } else if (latest > 55) {
                score = 30;
                interp = "BTC 主導率偏高，資金集中，偏多";
                trend = "↗ 偏向看漲";
            } else if (latest >= 45) {
                score = 0;
                interp = "BTC 主導率正常區間，市場均衡健康";
                trend = "→ 中性";
            } else if (latest >= 38) {
                score = -20;
                interp = "BTC 主導率下滑，資金分散至 altcoin";
                trend = "↘ 偏向看跌";
            } else {
                score = -40;
                interp = "BTC 主導率偏低，歷史上常出現在牛市末期";
                trend = "▼ 看跌";
            }

            return new SignalResult(
                "🌐 BTC 主導地位", "dominance", score, 0.07,
                String.format("%.2f%%", latest),
                interp + "（實時數據，資料源：CoinGecko）",
                trend, latest
            );
        } catch (Exception e) {
            log.debug("BTC Dominance 分析失敗: {}", e.getMessage());
            return naSignal("🌐 BTC 主導地位", "dominance", 0.07);
        }
    }

    private double fetchCurrentPrice() {
        // 直接使用幣安公開現貨 API，100% 免費且極速
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT"))
                .timeout(TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(resp.body());
                double price = json.path("price").asDouble(-1);
                if (price > 0) return price;
            }
        } catch (Exception ignored) {}
        return 73000.0; // 最終降級預設值
    }

    /**
     * 從幣安日 K 線計算真實歷史月度波動率（取代寫死的 0.18）。
     * 方法：取 90 根日 K 收盤價 → 計算日對數報酬標準差 → 乘以 √30 換算成月度
     * （加密貨幣 24/7 交易，以 30 個日曆日為一個月）。
     * 失敗回傳 -1，由 {@link #predictPriceRange} 套用保守預設。資料源：幣安現貨 K 線（無須 Key）。
     */
    private double fetchHistoricalVolatility() {
        try {
            JsonNode root = get("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=90");
            if (root == null || !root.isArray() || root.size() < 10) return -1;

            List<Double> closes = new ArrayList<>();
            for (JsonNode k : root) {
                double c = k.get(4).asDouble(-1); // K 線陣列索引 4 = 收盤價
                if (c > 0) closes.add(c);
            }
            double monthlyVol = monthlyVolFromCloses(closes);
            if (monthlyVol > 0) log.debug("歷史波動率（月）: {}", String.format("%.4f", monthlyVol));
            return monthlyVol;
        } catch (Exception e) {
            log.debug("歷史波動率計算失敗: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 月度波動率核心計算（單一真相來源，純函數）：
     * 日收盤序列 → 日對數報酬標準差 → 乘以 √30 換算月度。
     * live 分析與歷史覆蓋率回測共用，確保兩邊波動率口徑 100% 一致。
     * 收盤價少於 10 筆視為樣本不足，回傳 -1（呼叫端套用保守預設）。
     */
    public static double monthlyVolFromCloses(List<Double> closes) {
        if (closes == null || closes.size() < 10) return -1;
        List<Double> logReturns = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            logReturns.add(Math.log(closes.get(i) / closes.get(i - 1)));
        }
        double mean = logReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = logReturns.stream()
            .mapToDouble(r -> (r - mean) * (r - mean)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(30);
    }

    // ============================================================
    // 價格預測計算
    // ============================================================

    /**
     * 根據綜合分數和歷史波動率計算預測價格區間。
     *
     * 預測邏輯：
     *   - 月度波動率：優先採用幣安日 K 線實算值，無效時退回保守預設
     *   - 根據綜合分數調整預期漲跌幅
     *   - 分數越極端 → 區間越寬（反映高動能伴隨高不確定性）
     *
     * 純函數（單一真相來源）：live 預測與歷史區間覆蓋率回測共用，
     * 確保回測重建的區間與當時 live 會給出的區間 100% 一致。
     *
     * @param monthlyVolatility 由 {@link #fetchHistoricalVolatility()} 算出的月度波動率，
     *                          &lt;=0 或異常時套用 0.18 保守預設
     * @return {low, mid, high}（四捨五入到百位）
     */
    public static double[] predictPriceRange(double currentPrice, double compositeScore, double monthlyVolatility) {
        if (currentPrice <= 0) {
            return new double[]{0, 0, 0};
        }

        // 月度波動率：幣安日 K 線實算值優先；無效（<1% 或 >100%）時退回保守預設 18%
        double baseVolatility = (monthlyVolatility > 0.01 && monthlyVolatility < 1.0)
            ? monthlyVolatility : 0.18;

        // 根據分數計算預期月度漲跌幅
        // 分數範圍 -100 ~ +100 → 映射到 -30% ~ +30%
        double expectedReturn = compositeScore / 100.0 * 0.30;

        // 中位預測
        double predictedMid = currentPrice * (1 + expectedReturn);

        // 上下區間（基於波動率）
        // 分數越極端 → 波動率越大
        double volMultiplier = 1.0 + Math.abs(compositeScore) / 200.0;
        double adjustedVol = baseVolatility * volMultiplier;

        double predictedLow  = predictedMid * (1 - adjustedVol);
        double predictedHigh = predictedMid * (1 + adjustedVol);

        return new double[]{
            Math.round(predictedLow  / 100.0) * 100.0,
            Math.round(predictedMid  / 100.0) * 100.0,
            Math.round(predictedHigh / 100.0) * 100.0
        };
    }

    // ============================================================
    // 輔助計算方法
    // ============================================================

    private String classifySentiment(double score) {
        if (score >= 60)  return "強力看漲 🚀";
        if (score >= 30)  return "偏向看漲 ↗";
        if (score >= 10)  return "輕微看漲 →↗";
        if (score >= -10) return "中性觀望 →";
        if (score >= -30) return "輕微看跌 ↘";
        if (score >= -60) return "偏向看跌 ↓";
        return "強力看跌 📉";
    }

    /**
     * 計算預測信心度（基於信號覆蓋率和一致性）。
     */
    private double calculateConfidence(List<SignalResult> signals, double compositeScore) {
        // 有效信號比例（非 N/A）
        long validCount = signals.stream()
            .filter(s -> !s.value().equals("N/A"))
            .count();
        double coverageRate = (double) validCount / signals.size();

        // 信號一致性（所有信號方向是否一致）
        long bullishCount = signals.stream()
            .filter(s -> s.score() > 10 && !s.value().equals("N/A"))
            .count();
        long bearishCount = signals.stream()
            .filter(s -> s.score() < -10 && !s.value().equals("N/A"))
            .count();
        double consistencyRate;
        if (validCount == 0) {
            consistencyRate = 0;
        } else {
            consistencyRate = (double) Math.max(bullishCount, bearishCount) / validCount;
        }

        // 基礎信心度
        double baseConfidence = 35.0; // 基礎 35%（任何量化模型都有不確定性）
        double coverageBonus = coverageRate * 25.0;   // 最多 +25%（信號覆蓋率）
        double consistencyBonus = consistencyRate * 25.0; // 最多 +25%（方向一致性）
        double extremeBonus = Math.abs(compositeScore) > 50 ? 10.0 : 0; // 極端信號 +10%

        return Math.min(92, baseConfidence + coverageBonus + consistencyBonus + extremeBonus);
    }

    // ============================================================
    // HTTP 請求
    // ============================================================

    private JsonNode get(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", "ProjectFiorino/2.0");

            if (!apiKey.isBlank()) {
                builder.header("CG-API-KEY", apiKey);
            }

            HttpResponse<String> resp = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.debug("市場 API 非 200 回應: {} URL: {}", resp.statusCode(), url);
                return null;
            }
            return objectMapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("HTTP 請求失敗 {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private SignalResult naSignal(String name, String id, double weight) {
        return new SignalResult(name, id, 0, weight, "N/A", "數據獲取失敗（公開 API 無回應或網路問題）", "？ 無法判斷");
    }

    private static String formatBigNumber(double value) {
        if (value >= 1_000_000_000) return String.format("%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000)     return String.format("%.2fM", value / 1_000_000);
        if (value >= 1_000)         return String.format("%.1fK", value / 1_000);
        return String.format("%.2f", value);
    }
}
