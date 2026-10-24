package com.eink.weread;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信读书网页版/Skill 协议的 Java 移植。
 * 逻辑参考开源项目 finlater/weread.koplugin（protocol.lua / content.lua）。
 *
 * 注意移植细节：
 *  - Lua 字符串索引从 1 开始，hex 格式化不做零填充
 *  - sign() 的位运算均在 32 位有符号整数内进行（Lua bit 库语义）
 */
public final class WeRead {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0";
    public static final String SKILL_VERSION = "1.0.5";
    public static final String GATEWAY_URL = "https://i.weread.qq.com/api/agent/gateway";
    /** 网页版阅读器的固定 token，用于时长上报的 sg 签名（Lua: DEFAULT_READER_TOKEN） */
    public static final String DEFAULT_READER_TOKEN = "3c5c8717f3daf09iop3423zafeqoi";

    private WeRead() {}

    // ------------------------------------------------------------------ hash

    public static String md5Hex(String s) {
        return digestHex("MD5", s);
    }

    public static String sha256Hex(String s) {
        return digestHex("SHA-256", s);
    }

    private static String digestHex(String algo, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ urlencode

    /** 与 Lua 版一致：未保留字符之外逐 UTF-8 字节转 %XX（大写） */
    public static String urlencode(String v) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = v.getBytes(Const.UTF8);
        for (byte b : bytes) {
            char c = (char) (b & 0xff);
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                sb.append(c);
            } else {
                sb.append('%');
                sb.append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ sign

    /** Lua 版 sign：a/b 双游标从串尾向前按 2 字节步进做异或移位 */
    public static String sign(String query) {
        int a = 0x15051505;
        int b = a;
        int length = query.length();
        int i = length; // Lua 1-based
        while (i > 1) {
            int ca = query.charAt(i - 1) & 0xff; // query:byte(i)
            int cb = query.charAt(i - 2) & 0xff; // query:byte(i-1)
            a = (a ^ (ca << ((length - i + 1) % 30))) & 0x7fffffff;
            b = (b ^ (cb << ((i - 1) % 30))) & 0x7fffffff;
            i -= 2;
        }
        return Long.toHexString((long) a + (long) b);
    }

    /** 对参数表签名：按 key 排序（排除 s），拼 k=v&... 后调用 sign */
    public static String signParams(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!"s".equals(e.getKey())) {
                sorted.put(e.getKey(), urlencode(e.getValue()));
            }
        }
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        return sign(q.toString());
    }

    // ------------------------------------------------------------------ e(value)

    private static boolean isDigitString(String s) {
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 协议中的 e() 混淆函数（用于 b/c/pc/psvts 等）。
     * 纯数字走 %x 分块路径，其余走逐字节 hex 路径（均无零填充）。
     */
    public static String e(String s) {
        String h = md5Hex(s);
        StringBuilder result = new StringBuilder(h.substring(0, 3));
        String typeFlag;
        List<String> chunks = new ArrayList<String>();
        if (isDigitString(s)) {
            typeFlag = "3";
            for (int i = 0; i < s.length(); i += 9) {
                String part = s.substring(i, Math.min(i + 9, s.length()));
                chunks.add(Long.toHexString(Long.parseLong(part))); // Lua %x：无填充小写
            }
        } else {
            typeFlag = "4";
            StringBuilder sb = new StringBuilder();
            for (byte b : s.getBytes(Const.UTF8)) {
                sb.append(Integer.toHexString(b & 0xff)); // 无填充
            }
            chunks.add(sb.toString());
        }
        result.append(typeFlag).append('2').append(h.substring(h.length() - 2));
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            result.append(String.format("%02x", chunk.length())).append(chunk);
            if (i < chunks.size() - 1) result.append('g');
        }
        if (result.length() < 20) {
            result.append(h.substring(0, 20 - result.length()));
        }
        result.append(md5Hex(result.toString()).substring(0, 3));
        return result.toString();
    }

    // ------------------------------------------------------------------ 章节内容参数

    /** 构造 POST /web/book/chapter/e_* 的 body（Lua: make_content_params） */
    public static JSONObject makeContentParams(String bookId, String chapterUid,
                                               String psvts, boolean style)
            throws JSONException {
        long ct = System.currentTimeMillis() / 1000L;
        if (e(String.valueOf(ct)).equals(psvts)) ct += 1;

        Map<String, String> m = new HashMap<String, String>();
        m.put("b", e(bookId));
        m.put("c", e(chapterUid));
        long r0 = (long) (Math.random() * 10000);
        m.put("r", String.valueOf(r0 * r0));
        m.put("ct", String.valueOf(ct));
        m.put("ps", psvts);
        m.put("pc", e(String.valueOf(ct)));
        m.put("sc", "1");
        m.put("prevChapter", "false");
        m.put("st", style ? "1" : "0");
        String s = signParams(m);

        JSONObject json = new JSONObject();
        json.put("b", m.get("b"));
        json.put("c", m.get("c"));
        json.put("r", m.get("r"));
        json.put("ct", m.get("ct"));
        json.put("ps", m.get("ps"));
        json.put("pc", m.get("pc"));
        json.put("sc", 1);
        json.put("prevChapter", false);
        json.put("st", style ? 1 : 0);
        json.put("s", s);
        return json;
    }

    public static String readerUrl(String bookId, String chapterUid) {
        String url = "https://weread.qq.com/web/reader/" + e(bookId);
        if (chapterUid != null && chapterUid.length() > 0) {
            url = url + "k" + e(chapterUid);
        }
        return url;
    }

    // ------------------------------------------------------------------ 分片解码

    /** 校验 32 字节大写 MD5 头并取正文 */
    static String checkedBody(String resp) {
        if (resp == null || resp.length() <= 32) return "";
        String expected = resp.substring(0, 32);
        String body = resp.substring(32);
        String actual = md5Hex(body).toUpperCase();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Shard MD5 mismatch");
        }
        return body;
    }

    /** 解码一个分片（Lua: decode_content_shard） */
    public static String decodeShard(String resp) {
        return decodeEncodedBody(checkedBody(resp));
    }

    /** 拼接 e_0 + e_1 + e_3 后解码（Lua: decode_content_shards） */
    public static String decodeShards(String e0, String e1, String e3) {
        String body = checkedBody(e0) + checkedBody(e1) + checkedBody(e3);
        return decodeEncodedBody(body);
    }

    static String decodeEncodedBody(String body) {
        if (body.length() == 0) return "";
        String encoded = body.substring(1); // 丢弃首字符
        String restored = reverseSwaps(encoded, swapPositions(encoded));
        byte[] raw = base64Decode(restored);
        return new String(raw, Const.UTF8);
    }

    /** Lua: swap_positions —— 从编码串尾部取至多 4 字节按 4 进制解释生成交换位置 */
    static int[] swapPositions(String encoded) {
        int length = encoded.length(); // base64 全 ASCII，char 数 == 字节数
        if (length < 4) return new int[0];
        if (length < 11) return new int[]{0, 2};

        int n = Math.min(4, (length + 9) / 10);
        StringBuilder tmpSb = new StringBuilder();
        for (int i = length; i >= length - n + 1; i--) { // 1-based
            int byteVal = encoded.charAt(i - 1) & 0xff;
            String bin = Integer.toBinaryString(byteVal);
            long value = Long.parseLong(bin, 4); // 二进制串按 4 进制读
            tmpSb.append(value);
        }
        String tmp = tmpSb.toString();

        List<Integer> result = new ArrayList<Integer>();
        int m = length - n - 2;
        int step = String.valueOf(m).length();
        int i = 1; // 1-based
        while (result.size() < 10 && i + step - 1 < tmp.length()) {
            String first = tmp.substring(i - 1, i - 1 + step);
            result.add((int) (Long.parseLong(first) % m));
            int end2 = Math.min(i + step, tmp.length());
            if (i + 1 <= tmp.length()) {
                if (end2 >= i + 1) {
                    String second = tmp.substring(i, end2); // Lua sub(i+1, end2)
                    result.add((int) (Long.parseLong(second) % m));
                } else {
                    result.add(0); // Lua tonumber("") or 0
                }
            }
            i += step;
        }
        int[] out = new int[result.size()];
        for (int k = 0; k < out.length; k++) out[k] = result.get(k);
        return out;
    }

    /** Lua: reverse_swaps —— 从后向前按位置对两两交换字符（1-based +1 偏移） */
    static String reverseSwaps(String encoded, int[] positions) {
        char[] chars = encoded.toCharArray();
        for (int i = positions.length - 1; i >= 1; i -= 2) {
            for (int k = 1; k >= 0; k--) {
                int left = positions[i] + k;     // 1-based +1 再转 0-based
                int right = positions[i - 1] + k;
                if (left < chars.length && right < chars.length) {
                    char t = chars[left];
                    chars[left] = chars[right];
                    chars[right] = t;
                }
            }
        }
        return new String(chars);
    }

    static byte[] base64Decode(String data) {
        data = data.replace('-', '+').replace('_', '/');
        data = data.replaceAll("[^A-Za-z0-9+/=]", "");
        int pad = data.length() % 4;
        if (pad > 0) data = data + "====".substring(pad);
        return Base64.decode(data, Base64.DEFAULT);
    }

    // ------------------------------------------------------------------ 时长上报协议

    /** Lua: web_app_id —— 由 User-Agent 生成的设备标识 */
    public static String webAppId(String ua) {
        StringBuilder prefix = new StringBuilder();
        int count = 0;
        for (String part : ua.split("\\s+")) {
            count++;
            if (count > 12) break;
            prefix.append(part.length() % 10);
        }
        long hash = 0;
        for (byte b : ua.getBytes(Const.UTF8)) {
            hash = (0x83L * hash + (b & 0xff)) & 0x7fffffffL;
        }
        return "wb" + prefix + "h" + hash;
    }

    /** 按码点截断（Lua: utf8_substr），避免截断半个中文字符 */
    public static String utf8Substr(String s, int maxChars) {
        if (s == null) return "";
        int n = Math.min(maxChars, s.codePointCount(0, s.length()));
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    private static void putPositionFields(Map<String, String> m, String bookId, String chapterUid,
                                          int chapterIdx, long chapterOffset, int progress,
                                          String summary, String psvts, String pclts) {
        long now = System.currentTimeMillis() / 1000L;
        String pc = (pclts == null || pclts.length() == 0 || "0".equals(pclts))
                ? e(String.valueOf(now)) : pclts;
        m.put("appId", webAppId(USER_AGENT));
        m.put("b", e(bookId));
        m.put("c", e(chapterUid == null ? "0" : chapterUid));
        m.put("ci", String.valueOf(chapterIdx));
        m.put("co", String.valueOf(Math.max(0, chapterOffset)));
        m.put("sm", utf8Substr(summary, 20));
        m.put("pr", String.valueOf(progress < 0 ? 0 : Math.min(100, progress)));
        m.put("ct", String.valueOf(now));
        m.put("ps", psvts == null ? "" : psvts);
        m.put("pc", pc);
    }

    /** 进入阅读会话（Lua: make_enter_read_payload），首次上报前调用一次 */
    public static JSONObject makeEnterReadPayload(String bookId, String chapterUid, int chapterIdx,
                                                  long chapterOffset, int progress, String summary,
                                                  String psvts, String pclts) throws JSONException {
        Map<String, String> m = new HashMap<String, String>();
        putPositionFields(m, bookId, chapterUid, chapterIdx, chapterOffset, progress, summary, psvts, pclts);
        String s = signParams(m);
        JSONObject j = new JSONObject();
        fillPositionJson(j, m);
        j.put("s", s);
        return j;
    }

    /**
     * 阅读时长上报 body（Lua: make_read_payload）。
     * sg = sha256(ts .. rn .. token)，token 为网页版固定 token。
     */
    public static JSONObject makeReadPayload(String bookId, String chapterUid, int chapterIdx,
                                             long chapterOffset, int progress, String summary,
                                             String psvts, String pclts, long elapsedSeconds)
            throws JSONException {
        Map<String, String> m = new HashMap<String, String>();
        putPositionFields(m, bookId, chapterUid, chapterIdx, chapterOffset, progress, summary, psvts, pclts);
        long now = Long.parseLong(m.get("ct"));
        long ts = now * 1000L + (long) (Math.random() * 1000);
        long rn = (long) (Math.random() * 1000);
        m.put("rt", String.valueOf(Math.max(0, elapsedSeconds)));
        m.put("ts", String.valueOf(ts));
        m.put("rn", String.valueOf(rn));
        m.put("sg", sha256Hex(ts + "" + rn + "" + DEFAULT_READER_TOKEN));
        String s = signParams(m);
        JSONObject j = new JSONObject();
        fillPositionJson(j, m);
        j.put("rt", Math.max(0, elapsedSeconds));
        j.put("ts", ts);
        j.put("rn", rn);
        j.put("sg", m.get("sg"));
        j.put("s", s);
        return j;
    }

    private static void fillPositionJson(JSONObject j, Map<String, String> m) throws JSONException {
        j.put("appId", m.get("appId"));
        j.put("b", m.get("b"));
        j.put("c", m.get("c"));
        j.put("ci", Long.parseLong(m.get("ci")));
        j.put("co", Long.parseLong(m.get("co")));
        j.put("sm", m.get("sm"));
        j.put("pr", Long.parseLong(m.get("pr")));
        j.put("ct", Long.parseLong(m.get("ct")));
        j.put("ps", m.get("ps"));
        j.put("pc", m.get("pc"));
    }

    // ------------------------------------------------------------------ HTML 工具

    private static final Pattern PSVTS_PATTERN = Pattern.compile("\"psvts\"\\s*:\\s*\"([0-9A-Za-z]+)\"");

    /** 从阅读页 HTML 中提取 psvts（会话令牌） */
    public static String extractPsvts(String html) {
        Matcher m = PSVTS_PATTERN.matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    // ------------------------------------------------------------------ HTML → 文本 + 坐标映射

    /**
     * 章节正文 XHTML 转纯文本，同时产出「显示文本下标 → 官方坐标」映射。
     *
     * 官方坐标系（coweread 实测）：range="start-end" 左闭右开，为章节内字符偏移，
     * 每章从 0 计数，空格等不可见字符也计入坐标。因此坐标流 = 原始 HTML 剥去标签后
     * 保留的全部文本节点字符；显示用的段落分隔 \n\n 为合成字符，映射为 -1。
     */
    public static String htmlToText(String html, List<Integer> coordMap) {
        return htmlToText(html, coordMap, null, null);
    }

    /**
     * 解析正文：同时收集插图（src 与所在文本偏移）。
     * imgOffsets / imgUrls 可为 null。
     */
    public static String htmlToText(String html, List<Integer> coordMap,
                                    List<Integer> imgOffsets, List<String> imgUrls) {
        List<Integer> map = coordMap != null ? coordMap : new ArrayList<Integer>();
        map.clear();
        List<Integer> imgo = imgOffsets != null ? imgOffsets : new ArrayList<Integer>();
        List<String> imgu = imgUrls != null ? imgUrls : new ArrayList<String>();
        imgo.clear();
        imgu.clear();
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c == '<') {
                int close = html.indexOf('>', i);
                if (close < 0) close = n - 1;
                String raw = html.substring(i, Math.min(close + 1, n));
                String tag = raw.toLowerCase();
                if (tag.startsWith("<img") || tag.startsWith("<image")) {
                    String src = attr(raw, "src");
                    if (src == null || src.length() == 0) src = attr(raw, "xlink:href");
                    if (src != null && src.length() > 0) {
                        if (src.startsWith("//")) src = "https:" + src;
                        else if (src.startsWith("/")) src = "https://weread.qq.com" + src;
                        imgo.add(out.length());
                        imgu.add(src);
                        appendMapped(out, map, "\n[插图]\n", -1);
                    }
                } else if (tag.startsWith("<br")) {
                    appendMapped(out, map, "\n", -1);
                } else if (tag.startsWith("</p") || tag.startsWith("</h")
                        || tag.startsWith("</div") || tag.startsWith("</li")
                        || tag.startsWith("</blockquote")) {
                    appendMapped(out, map, "\n\n", -1);
                }
                i = close + 1;
            } else if (c == '&') {
                int semi = html.indexOf(';', i);
                String dec = null;
                if (semi > i && semi <= i + 10) {
                    dec = decodeEntity(html.substring(i + 1, semi));
                }
                if (dec != null) {
                    appendMapped(out, map, dec, i);
                    i = semi + 1;
                } else {
                    appendMapped(out, map, "&", i);
                    i++;
                }
            } else {
                appendMapped(out, map, String.valueOf(c), i);
                i++;
            }
        }
        // trim 与映射对齐
        String s = out.toString();
        int start = 0;
        while (start < s.length() && (s.charAt(start) == '\n' || s.charAt(start) == ' '
                || s.charAt(start) == '\r' || s.charAt(start) == '\t')) start++;
        int end = s.length();
        while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == ' '
                || s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\t')) end--;
        map.subList(0, Math.min(start, map.size())).clear();
        while (map.size() > end - start) map.remove(map.size() - 1);
        // 插图偏移同步到 trim 后的坐标系
        for (int k = 0; k < imgo.size(); k++) {
            int off = imgo.get(k) - start;
            if (off < 0) off = 0;
            if (off > end - start) off = end - start;
            imgo.set(k, off);
        }
        return s.substring(start, end);
    }

    /** 从标签片段里取属性值（支持双引号/单引号/无引号） */
    private static String attr(String tag, String name) {
        String lower = tag.toLowerCase();
        int p = lower.indexOf(name + "=");
        if (p < 0) return null;
        int v = p + name.length() + 1;
        while (v < tag.length() && (tag.charAt(v) == ' ')) v++;
        if (v >= tag.length()) return null;
        char q = tag.charAt(v);
        if (q == '"' || q == '\'') {
            int e = tag.indexOf(q, v + 1);
            return e > v ? tag.substring(v + 1, e) : null;
        }
        int e = v;
        while (e < tag.length() && tag.charAt(e) != ' ' && tag.charAt(e) != '>') e++;
        return tag.substring(v, e);
    }

    /** 旧签名（无坐标需求：书评/想法内容清洗） */
    public static String htmlToText(String html) {
        return htmlToText(html, null);
    }

    private static void appendMapped(StringBuilder out, List<Integer> map, String s, int coord) {
        for (int k = 0; k < s.length(); k++) {
            out.append(s.charAt(k));
            map.add(coord);
        }
    }

    private static String decodeEntity(String ent) {
        if (ent.length() == 0) return null;
        if (ent.charAt(0) == '#') {
            try {
                int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X'
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                return new String(Character.toChars(cp));
            } catch (Exception e) {
                return null;
            }
        }
        if ("nbsp".equals(ent)) return " ";
        if ("amp".equals(ent)) return "&";
        if ("lt".equals(ent)) return "<";
        if ("gt".equals(ent)) return ">";
        if ("quot".equals(ent)) return "\"";
        if ("apos".equals(ent)) return "'";
        return null;
    }

    /** 由显示文本选区 [selStart, selEnd) 计算官方 range（"start-end"） */
    public static String rangeFor(int[] coordMap, int selStart, int selEnd) {
        int start = coordAt(coordMap, selStart);
        int end = coordAt(coordMap, selEnd - 1);
        if (start < 0 || end < 0) return null;
        return start + "-" + (end + 1); // 左闭右开
    }

    private static int coordAt(int[] coords, int displayIndex) {
        if (coords == null || displayIndex < 0 || displayIndex >= coords.length) return -1;
        int c = coords[displayIndex];
        if (c >= 0) return c;
        // 合成换行（-1）：向前找最近的实体坐标
        for (int i = displayIndex - 1; i >= 0 && i >= displayIndex - 4; i--) {
            if (coords[i] >= 0) return coords[i];
        }
        return -1;
    }
}
