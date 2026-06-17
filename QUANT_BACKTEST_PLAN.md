# Project Fiorino — 量化預測升級路線圖（規則式 → 可信回測模型）

> 目標：把選項 2 的「8 信號規則式加權評分」儀表板，升級成**經回測驗證、權重校準、有真實 track record** 的量化預測。
> 已拍板：**Track A + Track B 一起進行**、儲存用 **H2 file-mode**（專案已有依賴，等同 SQLite 的單檔 SQL DB）、回測引擎**純 Java**。

---

## 0. 關鍵約束（實測 2026-05-29，決定整個計畫形狀）

| 信號來源 | 免費歷史深度 | 可否歷史回測「下月」 |
|---------|------------|---------------------|
| 😱 恐懼貪婪 alternative.me | 2018 至今（3036 天） | ✅ |
| 💰 資金費率 fapi/v1/fundingRate | 可分頁回溯數年 | ✅ |
| 📈 價格/波動率 klines | 1000 天+（可分頁更早） | ✅ |
| 🏦 Coinbase 溢價 | 由雙邊日 K **重建** | ✅ |
| 📊 OI / ⚖️散戶 / 🐋大戶 / 💥Taker `futures/data/*` | **僅 30 天**（limit 設 500 仍只回 30） | ❌ 只能前向採集 |

**結論**：8 個信號中 4 個衍生品微結構信號免費只給 30 天 → 無法歷史回測 → 唯一解是「從今天起每天存」（Track A）。其餘 4 個有長歷史 → 可立刻回測（Track B）。

---

## 三軌架構

### Track A — 前向採集（最高優先，已部署並驗證 ✅）
- `--collect` 模式：headless 跑一次 `BtcQuantAnalyzer.analyze()`，把 8 信號分數 + 價格 + 綜合分數寫入 H2，並 `logPrediction` 落地當日預測。
- 排程：Mac mini `launchd` 每日 08:05 自動跑，已掛載驗證（kickstart exit 0、累積 366 筆）。
- 產出：`quant_snapshot` + `quant_signal` + `quant_prediction` 逐日累積。3~6 個月後才有足夠樣本回測衍生品信號。

> **⚠️ macOS TCC 關鍵運維**：launchd 排程行程對 `~/Documents`（TCC 保護區）無存取權，
> 直接跑會 `Operation not permitted` / exit 126，且 headless（Termius/SSH）無法用 GUI 授予「完全磁碟存取」。
> 解法：採集器 runtime（jar/H2/logs）全部放 **`~/.fiorino`**（非保護區）。
> H2 預設路徑已改為 `~/.fiorino/fiorino_quant`（可用 `FIORINO_QUANT_DB` 覆寫），dev 指令與排程採集器共用同一 DB。
> 部署：`./scripts/deploy-collector.sh`（建置→複製 jar→遷移舊 DB→安裝並 bootstrap agent，一鍵完成、可重複執行）。

### Track B — 長歷史子集回測（建立基準，進行中）
- **B1 回填**：✅ 已完成。分頁抓 FNG / funding / klines，重建 Coinbase 溢價，寫入 H2（source=BACKFILL）。
  - 評分走 `BtcQuantAnalyzer` 抽出的 static scorer（`scoreFearGreed/scoreFunding/scoreCoinbasePremium` + `Verdict` record），與 live 100% 一致。
  - 觸發：`java -jar ... --backfill [天數]`（預設 365）。
  - **B1 發現（2026-05-29 實測 365 天）**：`funding_rate` 信號近一年恆為 +20 分（過去 300 天 8h 費率超過 0.01% 的比例為 0%，幣安把費率釘在基準線）→ 此信號在當前格局**零變異、零資訊量**，門檻是為 2021 高槓桿牛市設計。B4 校準時應大幅降權或改門檻。fear_greed（−56~78）與 coinbase_premium（−80~80）變異健康。
- **B2 特徵矩陣**：✅ 已完成。`FeatureMatrixBuilder` 從 H2 讀每日(信號分數+綜合分+價格),對齊未來 N 日報酬(N=1/7/14/30),產出 `FeatureMatrix` 並匯出 `data/feature_matrix.csv`。觸發 `--features`。天然無未來函數(predictor 用 D、label 用 D+N)。實測 366 列,ret_1/7/14/30 樣本數 365/359/352/336,手動驗證報酬計算正確。
- **B3 回測現有規則**：✅ 部分完成（IC + 命中率 + 策略回測 + 歷史區間覆蓋率）。`BacktestEngine`（`--backtest`）：Spearman IC + 命中率 + 每日方向策略(累積報酬/年化夏普/最大回撤/勝率,用次日報酬→樣本不重疊)。對可得樣本運算,隨數據增厚自動納入更多信號。
  - **歷史區間覆蓋率（2026-06-11 新增,T020 歷史重建版）**：不等 live 預測到期,對每個歷史日用「該日及之前」最多 90 收盤算滾動月度波動率,以 live 同一份 `predictPriceRange` static（單一真相來源）重建當時的 low/mid/high 區間,對照 30 天後實際價。**實測 349 樣本覆蓋率 70.8%**（理論 ±1σ 基準 ≈68% → 區間校準大致合理）,平均區間 ±13.6%,中位預測 MAPE 11.3%,9 天波動率樣本不足退用 0.18 預設。⚠️ 歷史重建估計（重疊樣本/單一格局）,真實 track record 仍待 Track C 到期驗證。
  - **策略回測結果(366天,死區±10,成本0.06%/換倉)**：策略 **+25.2%** vs 買入持有 **−30.4%**(該段 BTC 大跌),夏普 0.88,最大回撤 −19.5%,勝率 54.2%(換倉82次)。⚠️ **單一下跌格局的運氣**:策略多在做空故獲利,只證明這次方向對、非跨格局技能;僅2有效信號;勝率54%代表靠少數大賭;未計做空資金費率/滑點。最該看夏普0.88(體面非驚艷),未到可信賴。
  - **B3 初步結果（365 天，僅 3 個有歷史信號）**：composite IC 隨 horizon 遞增(1d +0.107 → 30d **+0.248**,命中率 54%→56%);coinbase_premium 為主要貢獻(IC +0.07~+0.15);fear_greed 為慢信號(短期≈0、30d +0.10);**funding_rate 零變異 IC=—,回測證實 B1 發現**。衍生品 5 信號 n=0 待累積。⚠️ 部分模型/單一格局/重疊樣本,鼓舞但未定論。
- **B4 權重校準**：Ridge/Logistic 回歸重算權重，**walk-forward 樣本外驗證**防過擬合。

### Track C — 全信號驗證 + 上線追蹤（累積數月後）
- C1 納入 Track A 採集的 4 個衍生品信號重跑回測。
- C2 規則式 vs 回歸 vs 集成，留 OOS 贏家。
- C3 `quant_prediction` 每次預測落地，事後對實際結果評分 → 真實 track record。

---

## 架構驗證後的修正（2026-05-31）

資深 QA／架構審查後執行：
- **M1 修正**：`analyze()` 綜合分數改為**權重正規化**（跳過 N/A 信號），與回填一致、避免失敗信號被當中性拉低。
- **L1 修正**：OI 信號 `oi30dAgo = get(size−size)` 索引 bug → 改 `get(0)` 並正名 `oiOldest/changeFull`，標籤更正為「全期(~15天)」（原誤標 30 天；僅用於顯示）。
- **資料豐富度（核心架構決策）**：`SignalResult` 加 `rawValue`、`quant_signal` 加 `raw_numeric DOUBLE`，**保存每個信號的原始連續值**。理由：現行評分把連續信號離散化成粗桶 → 資訊損失、設下準確度天花板；衍生品 4 信號僅 30 天歷史，原始值今天不存則永久遺失。已驗證 8 信號 raw_numeric 正確寫入、歷史 3 信號重新回填。
- **未做（刻意）**：權重／係數 0.30／門檻的手動調參 → 屬過擬合，保留給數據成熟後的 B4 資料驅動校準。

## H2 Schema（`~/.fiorino/fiorino_quant`）

```sql
-- 每次觀測的總表（Track A 與 B 共用）
CREATE TABLE quant_snapshot (
  ts          TIMESTAMP   NOT NULL,   -- 觀測時間（UTC）
  source      VARCHAR(16) NOT NULL,   -- LIVE | BACKFILL
  btc_price   DOUBLE,
  composite   DOUBLE,                 -- 綜合分數 -100..100
  PRIMARY KEY (ts, source)
);

-- 各信號明細（long format，便於信號集合演進）
CREATE TABLE quant_signal (
  ts          TIMESTAMP   NOT NULL,
  source      VARCHAR(16) NOT NULL,
  signal_id   VARCHAR(32) NOT NULL,   -- fear_greed / funding_rate / top_trader ...
  score       DOUBLE,                 -- -100..100
  weight      DOUBLE,
  raw_value   VARCHAR(64),
  PRIMARY KEY (ts, source, signal_id)
);

-- Track C 預測追蹤（事後回填實際值）
CREATE TABLE quant_prediction (
  ts             TIMESTAMP NOT NULL PRIMARY KEY,
  btc_price      DOUBLE,
  composite      DOUBLE,
  pred_low       DOUBLE,
  pred_mid       DOUBLE,
  pred_high      DOUBLE,
  confidence     DOUBLE,
  target_date    DATE,                -- ts + 1 month
  realized_price DOUBLE,              -- 到期後回填
  hit_in_band    BOOLEAN              -- 實際是否落在 low~high
);
```

---

## 量化驗證指標（B3 定義「可信」）
- **IC（資訊係數）**：綜合分數 vs 未來報酬的 Spearman 相關 — 核心
- **方向命中率**：漲跌方向猜對比例
- **區間覆蓋率（校準）**：實際落在 low~high 比例，理想 ≈ 標稱信心度
- **夏普 / Sortino / 最大回撤**：分數驅動多空策略績效
- **Walk-forward OOS**：只用「當下之前」數據定權重 — 最重要，杜絕未來函數

## 專業提醒
1. **樣本數**：非重疊月樣本 1 年才 12 筆，需 3~4 年才顯著 → 同時看 1d/7d/14d 多 horizon，「下月」當帶不確定度的外推。
2. **重疊樣本**：每日滾動 30 日報酬高度自相關，統計量用 **Newey-West** 修正，否則夏普虛高。
3. **未來函數**：回測任一時點只能用該時點之前可得的數據。

---

## 類別地圖（純 Java，com.fiorino.cli.quant.research）
- `QuantDataStore`        — H2 存取層（schema、saveDay、log prediction、查詢）✅
- `SnapshotCollector`     — Track A：呼叫 analyze() → 寫 store（由 `--collect` 觸發）✅
- `HistoricalBackfiller`  — Track B1：分頁抓 FNG/funding/klines + 重建 Coinbase 溢價 ✅
- `FeatureMatrix` / `FeatureMatrixBuilder` — Track B2：對齊信號分數 + 未來 N 日報酬 + CSV 匯出 ✅
- `BacktestEngine`        — Track B3：Spearman IC + 方向命中率 + 策略績效 + 歷史區間覆蓋率 ✅（walk-forward 待 B4）
- `LivePredictionRunner`  — 即時連續預測模式（--live，cli.quant 套件）✅
- `WeightCalibrator`      — Track B4：Ridge 回歸 + OOS 驗證（待做）
- `PredictionTracker`     — Track C3：落地預測、事後評分（待做）

> 評分單一真相來源：`BtcQuantAnalyzer.Verdict` + `scoreFearGreed/scoreFunding/scoreCoinbasePremium` static 方法，live 與回填共用。

## CLI 進入點

**互動選單**（`java -jar ... ` 不帶參數）：主選單選項 **[3] 🧪 量化研究控制台**
→ 子選單：1 採集 / 2 回填 / 3 特徵矩陣 / 4 回測 / 5 狀態·路徑 / 6 即時連續預測 / 0 返回。
畫面頂部固定顯示所有路徑（H2 / jar / 日誌 / CSV / launchd）。
（全 app 共用單一 stdin Scanner `com.fiorino.cli.ConsoleIO`，避免多 Scanner 吞輸入。）

**Headless 旗標**（給 launchd / 腳本用）
- `--collect`         Track A 每日採集 ✅
- `--backfill [天數]` Track B1 一次性回填歷史（預設 365）✅
- `--features`        Track B2 建特徵矩陣 + 匯出 CSV ✅
- `--backtest`        Track B3 跑回測報告（IC + 命中率 + 策略績效 + 歷史區間覆蓋率）✅
- `--live [分鐘]`     即時連續預測迴圈（預設 15 分；盤中觀測 source=INTRADAY、分鐘級 ts 落地，
                      研究載入器只讀 LIVE/BACKFILL → 不污染日線樣本；不寫 quant_prediction，
                      官方每日預測仍由 launchd --collect 落地）✅ 2026-06-11
- `--calibrate`       Track B4 權重校準（待做）

---
*最後更新：2026-06-11 — 新增歷史區間覆蓋率（349 樣本 70.8%，≈±1σ 理論值）與 --live 即時連續預測模式；B4 與 Track C（~2026-07 預測到期）待做。*
