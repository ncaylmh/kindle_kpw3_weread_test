package com.eink.weread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 章节正文本地缓存：读过的章节存成 JSON，下次打开直接命中，
 * 既省流量也避免频繁请求接口。
 */
final class Cache {

    private static File dir;
    private static File imgDir;

    static synchronized void init(android.content.Context ctx) {
        if (dir == null && ctx != null) {
            dir = new File(ctx.getFilesDir(), "chapters");
            if (!dir.exists()) dir.mkdirs();
            imgDir = new File(ctx.getFilesDir(), "images");
            if (!imgDir.exists()) imgDir.mkdirs();
        }
    }

    // ------------------------------------------------------------------ 通用文本缓存（目录/书架）

    private static File metaFile(String key) {
        if (dir == null) return null;
        return new File(dir, "meta_" + WeRead.md5Hex(key) + ".json");
    }

    static synchronized String loadMeta(String key) {
        File f = metaFile(key);
        if (f == null || !f.exists()) return null;
        try {
            return read(f);
        } catch (Throwable t) {
            return null;
        }
    }

    static synchronized void saveMeta(String key, String json) {
        File f = metaFile(key);
        if (f == null || json == null) return;
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(Const.UTF8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 插图缓存

    private static File imgFile(String url) {
        if (imgDir == null) return null;
        return new File(imgDir, WeRead.md5Hex(url) + ".img");
    }

    static synchronized byte[] loadImage(String url) {
        File f = imgFile(url);
        if (f == null || !f.exists() || f.length() == 0) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
            fis.close();
            return off == data.length ? data : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static synchronized void saveImage(String url, byte[] data) {
        File f = imgFile(url);
        if (f == null || data == null || data.length == 0) return;
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static File file(String bookId, String chapterUid) {
        if (dir == null) return null;
        return new File(dir, bookId + "_" + chapterUid + ".json");
    }

    static synchronized Api.ChapterText load(String bookId, String chapterUid) {
        File f = file(bookId, chapterUid);
        if (f == null || !f.exists()) return null;
        try {
            String json = read(f);
            JSONObject o = new JSONObject(json);
            Api.ChapterText ct = new Api.ChapterText();
            ct.text = o.optString("text", "");
            JSONArray co = o.optJSONArray("coords");
            if (co != null) {
                ct.coords = new int[co.length()];
                for (int i = 0; i < co.length(); i++) ct.coords[i] = co.optInt(i);
            }
            JSONArray im = o.optJSONArray("images");
            JSONArray io2 = o.optJSONArray("offsets");
            if (im != null) {
                for (int i = 0; i < im.length(); i++) ct.images.add(im.optString(i));
            }
            if (io2 != null) {
                for (int i = 0; i < io2.length(); i++) ct.imageOffsets.add(io2.optInt(i));
            }
            return ct.text.length() > 0 ? ct : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static synchronized void save(String bookId, String chapterUid, Api.ChapterText ct) {
        File f = file(bookId, chapterUid);
        if (f == null || ct == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("text", ct.text);
            JSONArray co = new JSONArray();
            for (int v : ct.coords) co.put(v);
            o.put("coords", co);
            JSONArray im = new JSONArray();
            for (String s : ct.images) im.put(s);
            o.put("images", im);
            JSONArray of = new JSONArray();
            for (int v : ct.imageOffsets) of.put(v);
            o.put("offsets", of);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(o.toString().getBytes(Const.UTF8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    static synchronized void clearAll() {
        deleteChildren(dir);
        deleteChildren(imgDir);
    }

    private static void deleteChildren(File d) {
        if (d == null || !d.exists()) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    static synchronized long sizeBytes() {
        return dirSize(dir) + dirSize(imgDir);
    }

    static synchronized long imageSizeBytes() {
        return dirSize(imgDir);
    }

    static synchronized int imageCount() {
        if (imgDir == null || !imgDir.exists()) return 0;
        File[] fs = imgDir.listFiles();
        return fs == null ? 0 : fs.length;
    }

    private static long dirSize(File d) {
        if (d == null || !d.exists()) return 0;
        long n = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    private static String read(File f) throws Exception {
        InputStream is = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), Const.UTF8);
    }

    private Cache() {}
}
