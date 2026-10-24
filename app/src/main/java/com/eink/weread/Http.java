package com.eink.weread;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 HTTP 层：
 *  - Android 4.4 的 HttpURLConnection 默认不启用 TLSv1.2，这里强制开启
 *    （微信读书服务端要求 TLS 1.2+，不开启会握手失败）
 *  - 内置 weread 证书链（assets/weread-chain.pem）：老 Kindle ROM 的系统信任库
 *    缺少 DigiCert Global Root G2，会报"没有安全证书"，这里做系统+内置双通道校验
 *  - 手动管理 weread.qq.com 的 Cookie（读取 set-cookie 并随请求回传）
 */
final class Http {

    private static volatile boolean trustReady = false;

    /** 由 Session.init 在应用启动时调用一次 */
    static void initTrust(android.content.Context ctx) {
        if (trustReady || ctx == null) return;
        try {
            final X509TrustManager systemTm = defaultTrustManager();
            final X509TrustManager bundledTm = bundledTrustManager(ctx);
            TrustManager merged = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    try {
                        systemTm.checkClientTrusted(chain, authType);
                    } catch (CertificateException e) {
                        bundledTm.checkClientTrusted(chain, authType);
                    }
                }
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    try {
                        systemTm.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        bundledTm.checkServerTrusted(chain, authType);
                    }
                }
                public X509Certificate[] getAcceptedIssuers() {
                    return systemTm.getAcceptedIssuers();
                }
            };
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, new TrustManager[]{merged}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            trustReady = true;
        } catch (Throwable t) {
            // 兜底：保持系统默认
        }
    }

    static {
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Throwable t) {
            // 老设备兜底：保持系统默认
        }
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** 从 assets/weread-chain.pem 构建内置信任库 */
    private static X509TrustManager bundledTrustManager(android.content.Context ctx)
            throws Exception {
        InputStream is = ctx.getAssets().open("weread-chain.pem");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Object c : cf.generateCertificates(is)) {
            ks.setCertificateEntry("weread-" + (i++), (X509Certificate) c);
        }
        is.close();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager in bundled store");
    }

    static final String ORIGIN = "https://weread.qq.com";

    static class Resp {
        int code;
        String body;
        Map<String, String> setCookies = new LinkedHashMap<String, String>();
        boolean ok() { return code >= 200 && code < 300; }
    }

    private Http() {}

    static Resp get(String url) throws Exception {
        return get(url, null);
    }

    static Resp get(String url, Map<String, String> extraHeaders) throws Exception {
        return request("GET", url, null, extraHeaders);
    }

    static Resp postJson(String url, String jsonBody) throws Exception {
        return postJson(url, jsonBody, null);
    }

    static Resp postJson(String url, String jsonBody, Map<String, String> extraHeaders)
            throws Exception {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json;charset=UTF-8");
        if (extraHeaders != null) h.putAll(extraHeaders);
        return request("POST", url, jsonBody, h);
    }

    static Resp request(String method, String url, String body, Map<String, String> extraHeaders)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", WeRead.USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            String cookie = Session.cookieHeader();
            if (cookie.length() > 0 && extraHeadersAllowedCookie(extraHeaders)) {
                conn.setRequestProperty("Cookie", cookie);
                // 网页版接口需要携带续期凭据（Skill 网关带 Authorization，不带）
                String ticket = Session.getTicket();
                if (ticket.length() > 0) conn.setRequestProperty("x-wr-ticket", ticket);
                String wrpa = Session.getWrpa0();
                if (wrpa.length() > 0) conn.setRequestProperty("x-wrpa-0", wrpa);
            }
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(Const.UTF8));
                os.flush();
                os.close();
            }

            Resp resp = new Resp();
            resp.code = conn.getResponseCode();

            // 捕获 set-cookie（登录流程依赖 session cookie）与续期凭据
            Map<String, List<String>> headers = conn.getHeaderFields();
            if (headers != null) {
                List<String> scs = headers.get("Set-Cookie");
                if (scs != null) {
                    for (String sc : scs) {
                        String pair = sc.split(";", 2)[0];
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            resp.setCookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                            Session.setCookie(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                        }
                    }
                }
                List<String> t = headers.get("x-wr-ticket");
                if (t != null && !t.isEmpty()) Session.setTicket(t.get(0));
                List<String> w = headers.get("x-wrpa-0");
                if (w != null && !w.isEmpty()) Session.setWrpa0(w.get(0));
            }

            InputStream is = null;
            try {
                is = conn.getInputStream();
            } catch (Exception e) {
                is = conn.getErrorStream();
            }
            resp.body = is == null ? "" : readAll(is);
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /** Skill 网关请求明确不带 Cookie（与官方插件 skip_cookie 行为一致） */
    private static boolean extraHeadersAllowedCookie(Map<String, String> extraHeaders) {
        return extraHeaders == null || extraHeaders.get("Authorization") == null;
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), Const.UTF8);
    }
}
