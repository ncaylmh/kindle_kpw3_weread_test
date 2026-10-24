package com.eink.weread;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 轻量诊断日志：记录最近 40 次网络请求（URL / 状态码 / 响应片段），
 * 阅读页菜单里可直接查看，便于在墨水屏设备上定位失败原因。
 */
final class Diag {

    private static final int MAX = 40;
    private static final LinkedList<String> LINES = new LinkedList<String>();

    static synchronized void add(String line) {
        LINES.add(line);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    static synchronized List<String> snapshot() {
        return new ArrayList<String>(LINES);
    }

    static synchronized void clear() {
        LINES.clear();
    }

    static String snippet(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private Diag() {}
}
