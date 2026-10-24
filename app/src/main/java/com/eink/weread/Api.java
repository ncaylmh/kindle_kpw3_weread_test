package com.eink.weread;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 网络接口封装：书架 / 章节目录 / 章节正文 / 阅读器状态(psvts)。
 *  - 书架走官方 Skill 网关（Bearer API Key，不带 Cookie）
 *  - 章节相关走网页版接口（带 Cookie）
 */
final class Api {

    private Api() {}

    // ------------------------------------------------------------------ 书架

    static class ShelfBook {
        String bookId;
        String title;
        String author;
    }

    /** 网页版通道请求头（部分接口缺 Referer 会被拒） */
    private static Map<String, String> webHeaders() {
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", Http.ORIGIN);
        h.put("Referer", "https://weread.qq.com/");
        return h;
    }

    /**
     * 书架优先走网页版 cookie 通道（POST /web/shelf/sync，扫码登录即可用，无需 API Key），
     * 失败再回退官方 Skill 网关（需 API Key）。失败信息带具体原因，不再静默。
     */
    static List<ShelfBook> fetchShelf() throws Exception {
        // 通道 1：网页版 cookie 通道
        String webErr = null;
        try {
            Http.Resp r = Http.postJson("https://weread.qq.com/web/shelf/sync",
                    "{}", webHeaders());
            if (r.ok()) {
                List<ShelfBook> out = parseShelfJson(r.body);
                if (!out.isEmpty()) return out;
                webErr = "书架返回为空";
            } else {
                webErr = "HTTP " + r.code + " " + r.body;
            }
        } catch (Throwable e) {
            webErr = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        // 通道 2：官方 Skill 网关（需要 API Key）
        if (Session.getApiKey().length() == 0) {
            throw new IllegalStateException("网页书架失败(" + webErr + ")，且未配置 API Key 兜底");
        }
        JSONObject body = new JSONObject();
        body.put("api_name", "/shelf/sync");
        body.put("skill_version", WeRead.SKILL_VERSION);

        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Authorization", "Bearer " + Session.getApiKey());
        h.put("Origin", Http.ORIGIN);

        Http.Resp r = Http.postJson(WeRead.GATEWAY_URL, body.toString(), h);
        if (!r.ok()) {
            throw new Exception("书架接口 HTTP " + r.code + "（请检查 API Key）");
        }
        return parseShelfJson(r.body);
    }

    private static List<ShelfBook> parseShelfJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray books = root.optJSONArray("books");
        if (books == null) {
            JSONObject data = root.optJSONObject("data");
            books = data == null ? null : data.optJSONArray("books");
        }
        List<ShelfBook> out = new ArrayList<ShelfBook>();
        if (books != null) {
            for (int i = 0; i < books.length(); i++) {
                JSONObject b = books.optJSONObject(i);
                if (b == null) continue;
                JSONObject book = b.optJSONObject("book");
                if (book == null) book = b;
                String id = book.optString("bookId", "");
                if (id.length() == 0) continue;
                ShelfBook sb = new ShelfBook();
                sb.bookId = id;
                sb.title = book.optString("title", "未命名");
                sb.author = book.optString("author", "");
                out.add(sb);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 章节目录

    static class Chapter {
        String chapterUid;
        String title;
        int chapterIdx;
    }

    /** POST /web/book/chapterInfos  body: {bookIds:[...]} */
    static List<Chapter> fetchChapters(String bookId) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray ids = new JSONArray();
        ids.put(bookId);
        body.put("bookIds", ids);

        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", Http.ORIGIN);
        h.put("Referer", "https://weread.qq.com/");

        Http.Resp r = Http.postJson("https://weread.qq.com/web/book/chapterInfos",
                body.toString(), h);
        if (!r.ok()) throw new Exception("目录接口 HTTP " + r.code);
        if (!Session.isLoggedIn()) throw new IllegalStateException("登录已失效，请重新登录");

        JSONObject root = new JSONObject(r.body);
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) throw new Exception("目录为空");

        JSONArray updated = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject rec = data.optJSONObject(i);
            if (rec != null && bookId.equals(rec.optString("bookId"))) {
                updated = rec.optJSONArray("updated");
                if (updated == null) updated = rec.optJSONArray("chapterInfos");
                if (updated == null) updated = rec.optJSONArray("chapters");
                break;
            }
        }
        if (updated == null) throw new Exception("未找到章节信息");

        List<Chapter> out = new ArrayList<Chapter>();
        for (int i = 0; i < updated.length(); i++) {
            JSONObject c = updated.optJSONObject(i);
            if (c == null) continue;
            Chapter ch = new Chapter();
            ch.chapterUid = c.optString("chapterUid", c.optString("uid", ""));
            ch.title = c.optString("title", "");
            ch.chapterIdx = c.optInt("chapterIdx", c.optInt("idx", out.size()));
            if (ch.chapterUid.length() > 0) out.add(ch);
        }
        return out;
    }

    // ------------------------------------------------------------------ psvts

    /** GET 章节阅读页，提取 psvts 会话令牌（Lua: ensure_reader_state） */
    static String fetchPsvts(String bookId, String firstChapterUid) throws Exception {
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Referer", "https://weread.qq.com/");
        Http.Resp r = Http.get(WeRead.readerUrl(bookId, firstChapterUid), h);
        if (!r.ok()) throw new Exception("阅读页 HTTP " + r.code);
        String psvts = WeRead.extractPsvts(r.body);
        if (psvts == null || psvts.length() == 0) {
            throw new Exception("未从阅读页提取到 psvts（协议可能已变更）");
        }
        return psvts;
    }

    // ------------------------------------------------------------------ 章节正文

    /** 章节内容：显示文本 + 显示下标→官方坐标 映射（划线 range 依赖） */
    static class ChapterText {
        String text = "";
        int[] coords = new int[0];
    }

    /** 请求单个分片（e_0/e_1/e_3，e_2 为样式） */
    private static String fetchShard(String endpoint, String bookId, String chapterUid,
                                     String psvts, boolean style) throws Exception {
        JSONObject params = WeRead.makeContentParams(bookId, chapterUid, psvts, style);
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", Http.ORIGIN);
        h.put("Referer", WeRead.readerUrl(bookId, chapterUid));
        Http.Resp r = Http.postJson("https://weread.qq.com" + endpoint,
                params.toString(), h);
        if (!r.ok()) throw new Exception(endpoint + " HTTP " + r.code);
        if ("{}".equals(r.body)) return "";
        return r.body;
    }

    /** 拉取并解码一章，返回纯文本 + 坐标映射 */
    static ChapterText fetchChapterText(String bookId, String chapterUid, String psvts) throws Exception {
        String e0 = fetchShard("/web/book/chapter/e_0", bookId, chapterUid, psvts, false);
        String e1 = fetchShard("/web/book/chapter/e_1", bookId, chapterUid, psvts, false);
        String e3 = fetchShard("/web/book/chapter/e_3", bookId, chapterUid, psvts, false);
        String html;
        if (e0.length() == 0 && e1.length() == 0 && e3.length() == 0) {
            // txt 回退路径
            String t0 = fetchShard("/web/book/chapter/t_0", bookId, chapterUid, psvts, false);
            String t1 = fetchShard("/web/book/chapter/t_1", bookId, chapterUid, psvts, false);
            Diag.add("txt回退 t_0=" + t0.length() + " t_1=" + t1.length());
            if (t0.length() == 0 && t1.length() == 0) throw new Exception("章节内容为空");
            html = WeRead.decodeShards(t0, t1, "");
        } else {
            html = WeRead.decodeShards(e0, e1, e3);
        }
        ChapterText ct = new ChapterText();
        List<Integer> map = new ArrayList<Integer>();
        ct.text = WeRead.htmlToText(html, map);
        int[] coords = new int[map.size()];
        for (int i = 0; i < coords.length; i++) coords[i] = map.get(i);
        ct.coords = coords;
        Diag.add("解码 e0=" + e0.length() + " e1=" + e1.length() + " e3=" + e3.length()
                + " → html=" + html.length() + " 文本=" + ct.text.length() + "字");
        if (ct.text.length() == 0) {
            Diag.add("⚠ 解码结果为空，原始片段：" + Diag.snippet(html, 200));
        }
        if (html.length() > 0 && ct.text.length() > 0 && ct.text.length() < 50) {
            Diag.add("⚠ 文本过短片段：" + Diag.snippet(ct.text, 100));
        }
        return ct;
    }

    // ================================================================== 互动功能

    static class Underline {
        String markText = "";
        String range = "";
    }

    static class Thought {
        String reviewId = "";
        String author = "";
        String content = "";
        String range = "";
        long createTime;
    }

    // ------------------------------------------------------------------ 划线

    /** 本章划线：网页 /web/book/bookmarklist 优先（免 Key），失败回退 gateway /book/underlines */
    static List<Underline> fetchChapterUnderlines(String bookId, String chapterUid) throws Exception {
        // 通道 1：网页 bookmarklist（全书我自己的划线，按章节过滤）
        try {
            Http.Resp r = Http.get("https://weread.qq.com/web/book/bookmarklist?bookId="
                    + WeRead.urlencode(bookId), webHeaders());
            if (r.ok()) {
                JSONArray arr = new JSONObject(r.body).optJSONArray("bookmarks");
                List<Underline> out = new ArrayList<Underline>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        if (!chapterUid.equals(o.optString("chapterUid", ""))) continue;
                        Underline u = new Underline();
                        u.range = o.optString("range", "");
                        u.markText = o.optString("markText", o.optString("abstract", ""));
                        if (u.markText.length() > 0) out.add(u);
                    }
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable ignored) {
        }
        // 通道 2：Skill 网关（含好友划线，需要 API Key）
        JSONObject body = new JSONObject();
        body.put("api_name", "/book/underlines");
        body.put("skill_version", WeRead.SKILL_VERSION);
        body.put("bookId", bookId);
        body.put("chapterUid", chapterUid);
        Http.Resp r = postGateway(body);
        if (!r.ok()) throw new Exception("划线接口 HTTP " + r.code);
        JSONArray arr = new JSONObject(r.body).optJSONArray("underlines");
        List<Underline> out = new ArrayList<Underline>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Underline u = new Underline();
                u.range = o.optString("range", "");
                u.markText = o.optString("markText", o.optString("quote", ""));
                if (u.markText.length() > 0) out.add(u);
            }
        }
        return out;
    }

    /** 全书热门划线：web 域优先，i 域回退（均 cookie 认证，免 Key） */
    static List<Underline> fetchBestBookmarks(String bookId) throws Exception {
        String[] urls = {
                "https://weread.qq.com/web/book/bestbookmarks?bookId="
                        + WeRead.urlencode(bookId) + "&maxIdx=0",
                "https://i.weread.qq.com/book/bestbookmarks?bookId="
                        + WeRead.urlencode(bookId)
        };
        Exception last = null;
        for (String url : urls) {
            try {
                Http.Resp r = Http.get(url, webHeaders());
                if (!r.ok()) {
                    last = new Exception("热门划线 HTTP " + r.code);
                    continue;
                }
                JSONArray arr = new JSONObject(r.body).optJSONArray("bookmarks");
                List<Underline> out = new ArrayList<Underline>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Underline u = new Underline();
                        u.range = o.optString("range", "");
                        u.markText = o.optString("markText", "");
                        if (u.markText.length() > 0) out.add(u);
                    }
                }
                return out;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("热门划线获取失败");
    }

    // ------------------------------------------------------------------ 想法 / 书评

    private static String authorName(JSONObject author) {
        if (author == null) return "";
        String n = author.optString("nick", "");
        if (n.length() == 0) n = author.optString("name", "");
        if (n.length() == 0) n = author.optString("userName", "");
        return n;
    }

    private static Thought parseReview(JSONObject o) throws JSONException {
        // 列表项可能嵌套 entry.review，最多解 4 层
        JSONObject review = o;
        for (int i = 0; i < 4 && review.optJSONObject("review") != null; i++) {
            review = review.optJSONObject("review");
        }
        Thought t = new Thought();
        t.reviewId = review.optString("reviewId", "");
        t.author = authorName(review.optJSONObject("author"));
        t.content = review.optString("content", "");
        if (t.content.length() == 0) t.content = review.optString("htmlContent", "");
        t.content = WeRead.htmlToText(t.content);
        t.range = review.optString("range", "");
        t.createTime = review.optLong("createTime", 0L);
        return t;
    }

    /** 本章想法：网页 /web/book/readreviews 优先（免 Key），失败回退 gateway */
    static List<Thought> fetchChapterThoughts(String bookId, String chapterUid,
                                              List<String> ranges) throws Exception {
        List<Thought> out = new ArrayList<Thought>();
        // 通道 1：网页 cookie 通道
        try {
            JSONObject body = new JSONObject();
            body.put("bookId", bookId);
            JSONArray cr = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("chapterUid", chapterUid);
            JSONArray rb = new JSONArray();
            for (String s : ranges) rb.put(s);
            one.put("ranges", rb);
            cr.put(one);
            body.put("chapterRanges", cr);
            Http.Resp r = Http.postJson("https://weread.qq.com/web/book/readreviews",
                    body.toString(), webHeaders());
            if (r.ok()) {
                JSONArray arr = new JSONObject(r.body).optJSONArray("reviews");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) out.add(parseReview(o));
                    }
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable ignored) {
        }
        // 通道 2：Skill 网关（需要 API Key）
        for (int from = 0; from < ranges.size(); from += 30) {
            JSONArray batch = new JSONArray();
            for (int i = from; i < Math.min(from + 30, ranges.size()); i++) {
                batch.put(ranges.get(i));
            }
            JSONObject body = new JSONObject();
            body.put("api_name", "/book/readreviews");
            body.put("skill_version", WeRead.SKILL_VERSION);
            body.put("bookId", bookId);
            body.put("chapterUid", chapterUid);
            body.put("reviews", batch);
            Http.Resp r = postGateway(body);
            if (!r.ok()) continue;
            JSONArray arr = new JSONObject(r.body).optJSONArray("reviews");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(parseReview(o));
            }
        }
        return out;
    }

    /** 书评列表（gateway /review/list；reviewListType: 1 热门等，count 默认 20） */
    static List<Thought> fetchBookReviews(String bookId, int reviewListType, int count) throws Exception {
        JSONObject body = new JSONObject();
        body.put("api_name", "/review/list");
        body.put("skill_version", WeRead.SKILL_VERSION);
        body.put("bookId", bookId);
        body.put("reviewListType", reviewListType);
        body.put("count", count);
        // 通道 1：网页 /web/review/list（免 Key）
        try {
            Http.Resp wr = Http.get("https://weread.qq.com/web/review/list?bookId="
                    + WeRead.urlencode(bookId)
                    + "&listType=5&maxIdx=0&sortType=1&count=" + count, webHeaders());
            if (wr.ok()) {
                JSONArray warr = new JSONObject(wr.body).optJSONArray("reviews");
                List<Thought> out = new ArrayList<Thought>();
                if (warr != null) {
                    for (int i = 0; i < warr.length(); i++) {
                        JSONObject o = warr.optJSONObject(i);
                        if (o != null) out.add(parseReview(o));
                    }
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable ignored) {
        }
        // 通道 2：Skill 网关
        Http.Resp r = postGateway(body);
        if (!r.ok()) throw new Exception("书评接口 HTTP " + r.code);
        JSONArray arr = new JSONObject(r.body).optJSONArray("reviews");
        List<Thought> out = new ArrayList<Thought>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(parseReview(o));
            }
        }
        return out;
    }

    /** 我的想法（GET /review/list?listType=11&mine=1，cookie 认证） */
    static List<Thought> fetchMyThoughts(String bookId) throws Exception {
        Http.Resp r = Http.get("https://i.weread.qq.com/review/list?bookId="
                + WeRead.urlencode(bookId)
                + "&listType=11&mine=1&synckey=0&listMode=0");
        if (!r.ok()) throw new Exception("我的想法 HTTP " + r.code);
        JSONArray arr = new JSONObject(r.body).optJSONArray("reviews");
        List<Thought> out = new ArrayList<Thought>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(parseReview(o));
            }
        }
        return out;
    }

    /**
     * 发表想法（写入通道走网页端 cookie，失败回退 i.weread 域）。
     * range：挂句子的想法传划线 range（"start-end"），章节级想法传 "0-0"。
     */
    static void addThought(String bookId, String chapterUid, int chapterIdx,
                           String content, String abstractText, String range) throws Exception {
        JSONObject body = new JSONObject();
        body.put("bookId", bookId);
        body.put("chapterUid", chapterUid);
        body.put("chapterIdx", chapterIdx);
        body.put("content", content);
        body.put("abstract", abstractText == null ? "" : abstractText);
        body.put("range", range == null || range.length() == 0 ? "0-0" : range);
        body.put("type", 1); // 1=想法
        postWrite("https://weread.qq.com/web/review/add",
                "https://i.weread.qq.com/review/add", body, "发表失败");
    }

    /**
     * 添加划线（POST /web/book/addBookmark，cookie 认证；失败回退 i 域）。
     * range 格式："start-end"，章节内字符偏移、左闭右开（coweread 实测）。
     */
    static void addUnderline(String bookId, String chapterUid, String markText, String range)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("bookId", bookId);
        body.put("chapterUid", chapterUid);
        body.put("markText", markText);
        body.put("range", range);
        postWrite("https://weread.qq.com/web/book/addBookmark",
                "https://i.weread.qq.com/book/addBookmark", body, "划线失败");
    }

    private static void postWrite(String webUrl, String iUrl, JSONObject body, String what)
            throws Exception {
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", "https://weread.qq.com");
        h.put("Referer", "https://weread.qq.com/");
        Exception last = null;
        for (String url : new String[]{webUrl, iUrl}) {
            try {
                Http.Resp r = Http.postJson(url, body.toString(), h);
                if (r.ok()) {
                    JSONObject resp = new JSONObject(r.body);
                    int errCode = resp.optInt("errCode", resp.optInt("errcode", 0));
                    if (errCode == 0 || resp.optBoolean("succ", false)) return;
                    last = new Exception(what + " errCode=" + errCode
                            + " " + resp.optString("errMsg"));
                } else {
                    last = new Exception(what + " HTTP " + r.code);
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception(what);
    }

    /** 我的划线（GET /web/book/bookmarklist），用于发表后校验 range */
    static List<Underline> fetchMyBookmarks(String bookId) throws Exception {
        Http.Resp r = Http.get("https://weread.qq.com/web/book/bookmarklist?bookId="
                + WeRead.urlencode(bookId));
        if (!r.ok()) throw new Exception("划线列表 HTTP " + r.code);
        JSONArray arr = new JSONObject(r.body).optJSONArray("bookmarks");
        List<Underline> out = new ArrayList<Underline>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Underline u = new Underline();
                u.range = o.optString("range", "");
                u.markText = o.optString("markText", "");
                if (u.markText.length() > 0) out.add(u);
            }
        }
        return out;
    }

    /** 点赞书评（POST /review/like） */
    static void likeReview(String reviewId, boolean like) throws Exception {
        JSONObject body = new JSONObject();
        body.put("reviewId", reviewId);
        body.put("addType", like ? 1 : 0);
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", "https://weread.qq.com");
        Http.Resp r = Http.postJson("https://i.weread.qq.com/review/like", body.toString(), h);
        if (!r.ok()) throw new Exception("点赞失败 HTTP " + r.code);
    }

    // ------------------------------------------------------------------ 进度 / 时长上报

    static class Progress {
        String chapterUid = "";
        int chapterIdx = 0;
        int percent = 0; // 章内百分比
        long offset = 0;
    }

    /** 阅读进度（GET /web/book/getProgress，cookie 认证） */
    static Progress fetchProgress(String bookId) throws Exception {
        Http.Resp r = Http.get("https://weread.qq.com/web/book/getProgress?bookId="
                + WeRead.urlencode(bookId));
        if (!r.ok()) throw new Exception("进度接口 HTTP " + r.code);
        JSONObject root = new JSONObject(r.body);
        JSONObject p = root.optJSONObject("progress");
        Progress out = new Progress();
        if (p != null) {
            out.chapterUid = p.optString("chapterUid", "");
            out.chapterIdx = p.optInt("chapterIdx", 0);
            out.percent = p.optInt("progress", 0);
            out.offset = p.optLong("offset", 0L);
        }
        return out;
    }

    /**
     * 上报时长+进度（POST /web/book/read，cookie 认证）。
     * @param enterRead true=进书会话（首次），false=常规时长上报
     * @return true 表示服务端接受
     */
    static boolean reportRead(String bookId, String chapterUid, int chapterIdx,
                              long chapterOffset, int progress, String summary,
                              String psvts, boolean enterRead) throws Exception {
        JSONObject body = enterRead
                ? WeRead.makeEnterReadPayload(bookId, chapterUid, chapterIdx, chapterOffset,
                        progress, summary, psvts, null)
                : WeRead.makeReadPayload(bookId, chapterUid, chapterIdx, chapterOffset,
                        progress, summary, psvts, null, 30);
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", "https://weread.qq.com");
        h.put("Referer", WeRead.readerUrl(bookId, chapterUid));
        Http.Resp r = Http.postJson("https://weread.qq.com/web/book/read", body.toString(), h);
        if (!r.ok()) throw new Exception("上报 HTTP " + r.code);
        JSONObject resp = new JSONObject(r.body);
        return resp.optBoolean("succ", false)
                || resp.optInt("errCode", -1) == 0
                || resp.optInt("synckey", -1) >= 0;
    }

    /** wr_skey 过期时续期（POST /web/login/renewal；新凭据由 Http 层自动捕获存储） */
    static void renewCookie() throws Exception {
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Origin", "https://weread.qq.com");
        h.put("Referer", "https://weread.qq.com/");
        Http.Resp r = Http.postJson("https://weread.qq.com/web/login/renewal", "{}", h);
        if (!r.ok()) throw new Exception("续期 HTTP " + r.code);
        Session.markRenewed();
    }

    private static Http.Resp postGateway(JSONObject body) throws Exception {
        if (Session.getApiKey().length() == 0) {
            throw new IllegalStateException("未配置 API Key（书架页菜单键 → 设置 API Key）");
        }
        Map<String, String> h = new java.util.HashMap<String, String>();
        h.put("Authorization", "Bearer " + Session.getApiKey());
        h.put("Origin", Http.ORIGIN);
        return Http.postJson(WeRead.GATEWAY_URL, body.toString(), h);
    }
}
