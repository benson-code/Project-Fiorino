package com.fiorino.cli;

import java.util.Scanner;

/**
 * ConsoleIO — 全應用程式共用的單一 stdin Scanner。
 *
 * 為什麼必要：System.in 只能有「一個」Scanner。多個 Scanner 各自包裝
 * System.in 時，先讀的那個會 buffer-ahead 把後續輸入吞掉，導致後建立的
 * Scanner 拿不到輸入（NoSuchElementException）。各互動選單一律用此實例。
 */
public final class ConsoleIO {

    /** 全程唯一的 stdin Scanner。 */
    public static final Scanner IN = new Scanner(System.in);

    private ConsoleIO() {}

    /** 讀一行（去除前後空白）。 */
    public static String readLine() {
        return IN.nextLine();
    }
}
