package com.eink.weread;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录态与配置：cookie（wr_vid/wr_skey/wr_rt/wr_fp 等）+ 官方 Skill API Key。
 * 全部存 SharedPreferences。
 */
final class Session {

    private static SharedPreferences prefs;
    private static Context appCtx;
    private static final Map<String, String> cookies = new HashMap<String, String>();

    static void init(Context ctx) {
        if (prefs == null) {
            appCtx = ctx.getApplicationContext();
            prefs = appCtx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
            loadCookies();
            try {
                Http.initTrust(appCtx); // 加载内置证书，兼容老 ROM 的信任库
            } catch (Throwable ignored) {
            }
        }
    }

    static Context appContext() {
        return appCtx;
    }

    private static void loadCookies() {
        cookies.clear();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey().startsWith("ck_")) {
                cookies.put(e.getKey().substring(3), String.valueOf(e.getValue()));
            }
        }
    }

    // ------------------------------------------------------------------ cookie

    static synchronized void setCookie(String name, String value) {
        cookies.put(name, value);
        prefs.edit().putString("ck_" + name, value).commit();
    }

    static synchronized String getCookie(String name) {
        return cookies.get(name);
    }

    static synchronized String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static synchronized boolean isLoggedIn() {
        String skey = cookies.get(Const.K_WR_SKEY);
        return skey != null && skey.length() > 0;
    }

    /** 登录成功后写入官方凭证（Lua: qr_login.lua 的 cookie jar 构建） */
    static synchronized void saveLogin(String vid, String accessToken, String refreshToken) {
        setCookie(Const.K_WR_VID, vid);
        setCookie(Const.K_WR_SKEY, accessToken);
        setCookie("wr_ql", "0");
        if (refreshToken != null && refreshToken.length() > 0) {
            try {
                setCookie("wr_rt", URLEncoder.encode(refreshToken, "UTF-8"));
            } catch (Exception e) {
                setCookie("wr_rt", refreshToken);
            }
        }
    }

    static synchronized void clearLogin() {
        cookies.clear();
        prefs.edit().clear().commit();
    }

    // ------------------------------------------------------------------ api key

    static String getApiKey() {
        return prefs.getString(Const.K_API_KEY, "");
    }

    static void setApiKey(String key) {
        prefs.edit().putString(Const.K_API_KEY, key == null ? "" : key.trim()).commit();
    }

    // ------------------------------------------------------------------ 续期凭据（x-wr-ticket / x-wrpa-0）

    static String getTicket() {
        return prefs.getString("wr_ticket", "");
    }

    static String getWrpa0() {
        return prefs.getString("wr_wrpa_0", "");
    }

    static void setTicket(String v) {
        prefs.edit().putString("wr_ticket", v == null ? "" : v).commit();
    }

    static void setWrpa0(String v) {
        prefs.edit().putString("wr_wrpa_0", v == null ? "" : v).commit();
    }

    static long getLastRenewalAt() {
        return prefs.getLong("last_renewal", 0L);
    }

    static void markRenewed() {
        prefs.edit().putLong("last_renewal", System.currentTimeMillis()).commit();
    }

    /** 续期冷却 10 分钟（与官方插件一致） */
    static boolean renewalAllowed() {
        return System.currentTimeMillis() - getLastRenewalAt() >= 10 * 60 * 1000L;
    }

    // ------------------------------------------------------------------ 排版偏好

    static float prefFloat(String key, float def) {
        return prefs == null ? def : prefs.getFloat(key, def);
    }

    static void setPrefFloat(String key, float v) {
        if (prefs != null) prefs.edit().putFloat(key, v).commit();
    }

    static int prefInt(String key, int def) {
        return prefs == null ? def : prefs.getInt(key, def);
    }

    static void setPrefInt(String key, int v) {
        if (prefs != null) prefs.edit().putInt(key, v).commit();
    }

    private Session() {}
}
