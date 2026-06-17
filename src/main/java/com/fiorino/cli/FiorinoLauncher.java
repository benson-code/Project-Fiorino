package com.fiorino.cli;

import com.fiorino.Main.FiorinoConfig;

import java.util.Scanner;

/**
 * ============================================================
 * FiorinoLauncher — CLI 主選單入口介面
 * ============================================================
 *
 * 啟動時顯示的互動式選單，讓使用者選擇：
 *   [1] 網格交易機器人（Grid Trading Bot）
 *   [2] BTC 量化分析 & 價格預測（CoinGlass AI Analysis）
 *   [0] 退出
 *
 * 設計原則：
 *   - 純 ASCII/ANSI 渲染，無外部依賴
 *   - 優雅的輸入驗證與錯誤提示
 *   - 快速響應（選單渲染 < 10ms）
 *
 * @author benson-code
 * @version 2.0.0
 */
public final class FiorinoLauncher {

    // ============================================================
    // ANSI 色彩碼
    // ============================================================

    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String DIM     = "\033[2m";
    private static final String RED     = "\033[31m";
    private static final String GREEN   = "\033[32m";
    private static final String YELLOW  = "\033[33m";
    private static final String BLUE    = "\033[34m";
    private static final String MAGENTA = "\033[35m";
    private static final String CYAN    = "\033[36m";
    private static final String WHITE   = "\033[37m";
    private static final String BRIGHT_WHITE  = "\033[97m";
    private static final String BG_BLACK = "\033[40m";
    private static final String BG_BLUE  = "\033[44m";

    // 256-color / bright variants
    private static final String ORANGE  = "\033[38;5;214m";
    private static final String GOLD    = "\033[38;5;220m";
    private static final String PURPLE  = "\033[38;5;135m";
    private static final String TEAL    = "\033[38;5;51m";
    private static final String LIME    = "\033[38;5;118m";

    // ============================================================
    // 選單結果枚舉
    // ============================================================

    public enum LaunchMode {
        GRID_TRADING,
        BTC_QUANT_ANALYSIS,
        QUANT_RESEARCH,
        EXIT
    }

    // ============================================================
    // 主選單顯示
    // ============================================================

    /**
     * 顯示主選單並等待使用者選擇。
     *
     * @param config 已載入的配置（用於顯示 API 狀態）
     * @return 使用者選擇的模式
     */
    public static LaunchMode showMainMenu(FiorinoConfig config) {
        Scanner scanner = ConsoleIO.IN;

        while (true) {
            renderMenu(config);

            System.out.print(GOLD + BOLD + "  ❯ " + RESET + WHITE + "請輸入選項 [0-3]: " + RESET);
            String input = scanner.nextLine().trim();

            switch (input) {
                case "1" -> {
                    renderModeSelected("🤖 網格交易機器人", CYAN);
                    return LaunchMode.GRID_TRADING;
                }
                case "2" -> {
                    renderModeSelected("🔮 BTC 量化分析 & 價格預測", PURPLE);
                    return LaunchMode.BTC_QUANT_ANALYSIS;
                }
                case "3" -> {
                    renderModeSelected("🧪 量化研究控制台", LIME);
                    return LaunchMode.QUANT_RESEARCH;
                }
                case "0", "q", "Q", "exit", "quit" -> {
                    renderExit();
                    return LaunchMode.EXIT;
                }
                default -> {
                    System.out.println(RED + "  ✗ 無效選項「" + input + "」，請輸入 0、1、2 或 3" + RESET);
                    System.out.println();
                    sleep(600);
                }
            }
        }
    }

    // ============================================================
    // 渲染方法
    // ============================================================

    private static void renderMenu(FiorinoConfig config) {
        clearScreen();
        printBanner();
        printApiStatus(config);
        printMenuOptions();
        printFooter();
    }

    private static void clearScreen() {
        System.out.print("\033[2J\033[H");
        System.out.flush();
    }

    private static void printBanner() {
        System.out.println();
        System.out.println(GOLD + BOLD +
            "  ██████╗ ██████╗  ██████╗      ██╗███████╗ ██████╗████████╗" + RESET);
        System.out.println(GOLD + BOLD +
            "  ██╔══██╗██╔══██╗██╔═══██╗     ██║██╔════╝██╔════╝╚══██╔══╝" + RESET);
        System.out.println(GOLD + BOLD +
            "  ██████╔╝██████╔╝██║   ██║     ██║█████╗  ██║        ██║   " + RESET);
        System.out.println(ORANGE + BOLD +
            "  ██╔═══╝ ██╔══██╗██║   ██║██   ██║██╔══╝  ██║        ██║   " + RESET);
        System.out.println(ORANGE + BOLD +
            "  ██║     ██║  ██║╚██████╔╝╚█████╔╝███████╗╚██████╗   ██║   " + RESET);
        System.out.println(RED + BOLD +
            "  ╚═╝     ╚═╝  ╚═╝ ╚═════╝  ╚════╝ ╚══════╝ ╚═════╝   ╚═╝   " + RESET);
        System.out.println();

        // 副標題
        String subtitle = "  ₿  BTC Quantitative Trading & Analysis Platform  ₿";
        System.out.println(BG_BLUE + BOLD + BRIGHT_WHITE + centerPad(subtitle, 65) + RESET);
        System.out.println(DIM + CYAN +
            "  Java 21 Virtual Threads  |  Binance API  |  CoinGlass V4  |  v2.0.0" + RESET);
        System.out.println();
        System.out.println(BLUE + "  " + "═".repeat(63) + RESET);
        System.out.println();
    }

    private static void printApiStatus(FiorinoConfig config) {
        System.out.println(BOLD + WHITE + "  📋 系統狀態" + RESET);
        System.out.println();

        // Binance API 狀態
        boolean hasBinanceKey = config != null &&
            !config.getApiKey().isBlank() && !config.getSecretKey().isBlank();
        String binanceStatus = hasBinanceKey
            ? GREEN  + "✔  已設置（" + (config.isUseTestnet() ? "Testnet" : "⚠ Mainnet") + "）"
            : YELLOW + "⚠  未設置（需要設定 FIORINO_API_KEY 才能使用網格交易）";
        System.out.println("  " + BOLD + "Binance API:  " + RESET + binanceStatus + RESET);

        // CoinGlass API 狀態
        boolean hasCgKey = config != null && !config.getCoinGlassApiKey().isBlank();
        String cgStatus = hasCgKey
            ? GREEN  + "✔  已設置（網格交易市場面板啟用）"
            : YELLOW + "⚠  未設置（僅影響網格交易的市場面板；量化分析不需要）";
        System.out.println("  " + BOLD + "CoinGlass API:" + RESET + cgStatus + RESET);

        System.out.println();
        System.out.println(BLUE + "  " + "═".repeat(63) + RESET);
        System.out.println();
    }

    private static void printMenuOptions() {
        System.out.println(BOLD + WHITE + "  🚀 請選擇功能模式" + RESET);
        System.out.println();

        // 選項 1：網格交易
        System.out.println(
            "  " + BG_BLACK + CYAN + BOLD + " 1 " + RESET + "  " +
            CYAN + BOLD + "🤖 網格交易機器人 " + RESET + CYAN + "(Grid Trading Bot)" + RESET
        );
        System.out.println(DIM + "      自動在設定價格區間掛買賣單，賺取網格利差" + RESET);
        System.out.println(DIM + "      • 自動管理多個掛單   • 即時損益追蹤   • 智能補單機制" + RESET);
        System.out.println();

        // 選項 2：量化分析
        System.out.println(
            "  " + BG_BLACK + PURPLE + BOLD + " 2 " + RESET + "  " +
            PURPLE + BOLD + "🔮 BTC 量化分析 & 下月價格預測 " + RESET + PURPLE + "(AI Quant Analysis)" + RESET
        );
        System.out.println(DIM + "      整合 8 項免費公開市場指標，預測 BTC 下月價格走勢（無須 API Key）" + RESET);
        System.out.println(DIM + "      • 恐懼貪婪指數   • 未平倉量趨勢   • 資金費率分析   • 散戶多空比" + RESET);
        System.out.println(DIM + "      • 大戶持倉多空比 • 機構資金流向   • 主動買賣盤壓力 • BTC 主導地位" + RESET);
        System.out.println();

        // 選項 3：量化研究控制台
        System.out.println(
            "  " + BG_BLACK + LIME + BOLD + " 3 " + RESET + "  " +
            LIME + BOLD + "🧪 量化研究控制台 " + RESET + LIME + "(Quant Research / 回測)" + RESET
        );
        System.out.println(DIM + "      數據採集、歷史回填、特徵矩陣、回測 IC/命中率（Track A/B）" + RESET);
        System.out.println(DIM + "      • 即時採集   • 回填歷史   • 建特徵矩陣   • 回測   • 狀態/路徑" + RESET);
        System.out.println();

        // 選項 0：退出
        System.out.println(
            "  " + BG_BLACK + RED + BOLD + " 0 " + RESET + "  " +
            DIM + "退出程序" + RESET
        );
        System.out.println();
        System.out.println(BLUE + "  " + "─".repeat(63) + RESET);
        System.out.println();
    }

    private static void printFooter() {
        System.out.println(DIM +
            "  ⚠  量化預測僅供參考，不構成投資建議。加密貨幣投資有風險，請謹慎判斷。" + RESET);
        System.out.println();
    }

    private static void renderModeSelected(String modeName, String color) {
        System.out.println();
        System.out.println(color + BOLD +
            "  ┌─────────────────────────────────────────┐" + RESET);
        System.out.println(color + BOLD +
            "  │  正在啟動 " + modeName + "..." + RESET);
        System.out.println(color + BOLD +
            "  └─────────────────────────────────────────┘" + RESET);
        System.out.println();
        sleep(800);
    }

    private static void renderExit() {
        System.out.println();
        System.out.println(GOLD + BOLD + "  ₿  感謝使用 Project Fiorino！" + RESET);
        System.out.println(DIM + "  願您的交易長期穩定盈利 🚀" + RESET);
        System.out.println();
        sleep(500);
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static String centerPad(String text, int width) {
        int len = text.replaceAll("\033\\[[0-9;]*m", "").length();
        int pad = Math.max(0, (width - len) / 2);
        return " ".repeat(pad) + text + " ".repeat(Math.max(0, width - len - pad));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
