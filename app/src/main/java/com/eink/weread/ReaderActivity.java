package com.eink.weread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 阅读器：目录 → psvts → 分片解码 → 原生分页。
 * 音量键翻页；长按选中段落 → 划线 / 划线并写想法 / 写想法 / 调整选区。
 * 菜单键：选章 / 划线 / 想法 / 书评 / 发表想法 / 时长上报。
 * 时长上报：首次 enter_read，之后每 30 秒 report_read（同时同步进度）；
 * 凭据过期自动 renewal 续期（10 分钟冷却）。
 */
public class ReaderActivity extends Activity {

    private static final long REPORT_INTERVAL_MS = 30_000L;

    private String bookId;
    private String bookTitle;

    private PageView pageView;
    private android.widget.ImageView imageView;   // 插图页
    private List<String> chapterImages = new ArrayList<String>();
    private List<Integer> chapterImageOffsets = new ArrayList<Integer>();
    private final java.util.LinkedList<Integer> pendingImages = new java.util.LinkedList<Integer>();
    private final java.util.HashSet<Integer> imagesShownPages = new java.util.HashSet<Integer>();
    private ProgressDialog loading;
    private final Handler handler = new Handler();

    private List<Api.Chapter> chapters;
    private String psvts;
    private int chapterIndex = -1;
    private int charPos = 0;
    /** 连续跳过空章节（封面/插图页）的计数，避免死循环 */
    private int emptySkips = 0;
    private String currentText = "";
    private int[] coordMap = new int[0]; // 显示下标 → 官方坐标（划线 range）

    private final Map<String, Integer> progressMap = new HashMap<String, Integer>();

    private boolean reporting = false;
    private boolean enteredSession = false;
    private long lastReportOkAt = 0;

    private final Runnable reportTask = new Runnable() {
        @Override
        public void run() {
            if (reporting) doReport(false);
            handler.postDelayed(this, REPORT_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Session.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bookId = getIntent().getStringExtra("bookId");
        bookTitle = getIntent().getStringExtra("title");
        setTitle(bookTitle == null ? "阅读" : bookTitle);

        pageView = new PageView(this);
        pageView.setEdgeListener(new PageView.EdgeListener() {
            @Override
            public void onNextEdge() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        turnNext();
                    }
                });
            }

            @Override
            public void onPrevEdge() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        turnPrev();
                    }
                });
            }
        });
        pageView.setCenterTapListener(new PageView.CenterTapListener() {
            @Override
            public void onCenterTap() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showMenu();
                    }
                });
            }
        });
        pageView.setSelectionCallback(new PageView.SelectionCallback() {
            @Override
            public void onSelectionMenu() {
                showSelectionMenu();
            }
        });
        imageView = new android.widget.ImageView(this);
        imageView.setBackgroundColor(android.graphics.Color.WHITE);
        imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(android.view.View.GONE);
        imageView.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                turnNext();
            }
        });

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(pageView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(imageView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        applyTypesetting();

        loading = new ProgressDialog(this);
        loading.setMessage("加载中…");

        loadChapters();
        fetchProgressInBackground();
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ 目录

    /** 显示加载框并启动秒数计时；45 秒未完成自动提示超时（后台线程结果到达时忽略） */
    private void showLoading(final String step) {
        loading.setMessage(step + "（0 秒）");
        loading.show();
        final long start = System.currentTimeMillis();
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            @Override
            public void run() {
                if (!loading.isShowing()) return;
                long sec = (System.currentTimeMillis() - start) / 1000;
                if (sec >= 45) {
                    loading.dismiss();
                    Toast.makeText(ReaderActivity.this,
                            "加载超过 45 秒仍未完成：网络异常或接口失效，请重试",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                loading.setMessage(step + "（" + sec + " 秒）");
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(tick[0], 1000);
    }

    private void loadChapters() {
        showLoading("获取章节目录");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    chapters = Api.fetchChapters(bookId);
                    if (chapters.isEmpty()) {
                        err = "章节为空";
                    } else {
                        loadingStep("获取阅读会话…");
                        psvts = Api.fetchPsvts(bookId, chapters.get(0).chapterUid);
                    }
                } catch (Throwable e) {
                    err = describe(e);
                }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (fErr != null) {
                            Toast.makeText(ReaderActivity.this, fErr, Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            loadChapter(0);
                        }
                    }
                });
            }
        }).start();
    }

    private void loadingStep(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (loading.isShowing()) loading.setMessage(s);
            }
        });
    }

    /** 错误信息带异常类名，方便定位；避免 null 提示 */
    private static String describe(Throwable e) {
        String msg = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (msg == null || msg.length() == 0) return name;
        return name + ": " + msg;
    }

    // ------------------------------------------------------------------ 章节

    private void loadChapter(final int index) {
        if (chapters == null || index < 0 || index >= chapters.size()) return;
        chapterIndex = index;
        charPos = 0;
        showLoading("下载章节内容");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Api.ChapterText ct = null;
                String err = null;
                try {
                    ct = Api.fetchChapterText(bookId,
                            chapters.get(index).chapterUid, psvts);
                } catch (Throwable e) {
                    err = describe(e);
                }
                final Api.ChapterText fCt = ct;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (fErr != null || fCt == null) {
                            Toast.makeText(ReaderActivity.this,
                                    "章节加载失败：" + fErr, Toast.LENGTH_LONG).show();
                        } else if (fCt.text.trim().length() < 20) {
                            // 封面 / 插图页：有图先显示图，没有图才跳到下一章
                            Diag.add("无正文章节：" + chapters.get(index).title
                                    + "（" + fCt.text.trim().length() + "字，插图 "
                                    + fCt.images.size() + " 张）");
                            if (!fCt.images.isEmpty()) {
                                emptySkips = 0;
                                currentText = fCt.text;
                                coordMap = fCt.coords;
                                chapterImages = fCt.images;
                                chapterImageOffsets = fCt.imageOffsets;
                                pendingImages.clear();
                                imagesShownPages.clear();
                                for (int k = 0; k < chapterImages.size(); k++) {
                                    pendingImages.add(k);
                                }
                                pageView.setChapterTitle(chapters.get(index).title);
                                pageView.setImageBreaks(fCt.imageOffsets);
                                pageView.setText(fCt.text);
                                showImagePage(pendingImages.poll());
                                return;
                            }
                            if (emptySkips < 8 && index + 1 < chapters.size()) {
                                emptySkips++;
                                Toast.makeText(ReaderActivity.this,
                                        "「" + chapters.get(index).title + "」无正文，跳到下一章",
                                        Toast.LENGTH_SHORT).show();
                                loadChapter(index + 1);
                            } else {
                                Toast.makeText(ReaderActivity.this,
                                        "该章节没有正文（可能是封面/插图页）",
                                        Toast.LENGTH_LONG).show();
                            }
                        } else {
                            emptySkips = 0;
                            currentText = fCt.text;
                            coordMap = fCt.coords;
                            chapterImages = fCt.images;
                            chapterImageOffsets = fCt.imageOffsets;
                            pendingImages.clear();
                            imagesShownPages.clear();
                            hideImagePage();
                            charPos = restoreCharPos(chapters.get(index).chapterUid);
                            pageView.setChapterTitle(chapters.get(index).title);
                            pageView.setImageBreaks(fCt.imageOffsets);
                            pageView.setText(fCt.text, charPos);
                            collectPageImages();
                            Diag.add("渲染 文本=" + fCt.text.length() + "字 起始位置=" + charPos
                                    + " 章=" + chapters.get(index).title);
                        }
                    }
                });
            }
        }).start();
    }

    private int restoreCharPos(String chapterUid) {
        Integer pct = progressMap.get(chapterUid);
        if (pct == null || pct <= 0 || currentText.length() == 0) return 0;
        int target = (int) ((long) currentText.length() * pct / 100);
        while (target > 0 && target < currentText.length()
                && currentText.charAt(target) != '\n') target++;
        return Math.min(target, currentText.length());
    }

    private void fetchProgressInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api.Progress p = Api.fetchProgress(bookId);
                    if (p.chapterUid.length() > 0) {
                        progressMap.put(p.chapterUid, p.percent);
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ 选区：划线 / 想法

    private void showSelectionMenu() {
        String sel = pageView.getSelectedText();
        if (sel.length() == 0) return;
        final String preview = sel.length() > 80 ? sel.substring(0, 80) + "…" : sel;
        final String[] items = {"划线", "划线并写想法", "仅写想法", "调整选区", "取消选择"};
        new AlertDialog.Builder(this)
                .setTitle("「" + preview + "」")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            doUnderline(null); // 划线后清除选区
                        } else if (w == 1) {
                            doUnderline(preview); // 划线后继续写想法
                        } else if (w == 2) {
                            askThought(pageView.getSelectedText(), selectionRange());
                        } else if (w == 3) {
                            pageView.enterAdjustMode();
                            toast("单击可扩展/收缩选区，长按重新打开本菜单");
                        } else {
                            pageView.clearSelection();
                        }
                    }
                }).show();
    }

    /** 当前选区的官方 range；无效时返回 null */
    private String selectionRange() {
        if (!pageView.hasSelection()) return null;
        return WeRead.rangeFor(coordMap, pageView.getSelStart(), pageView.getSelEnd());
    }

    /**
     * 划线。afterThought 非空 = 划线成功后接着写想法（abstract 用选中文本）。
     * 写入后拉取"我的划线"做 range 校验：若服务器保存的 range 与本地计算不一致，
     * 会提示出真实值，便于按 README 校准坐标系。
     */
    private void doUnderline(final String afterThought) {
        if (chapters == null || chapterIndex < 0) return;
        final Api.Chapter ch = chapters.get(chapterIndex);
        final String sel = pageView.getSelectedText();
        final String range = selectionRange();
        if (range == null) {
            toast("无法计算选区坐标（见 README 坐标校准）");
            return;
        }
        showLoading("正在划线");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                String serverRange = null;
                try {
                    Api.addUnderline(bookId, ch.chapterUid, sel, range);
                    // 写入后校验：对比服务器保存的 range
                    try {
                        for (Api.Underline u : Api.fetchMyBookmarks(bookId)) {
                            if (sel.equals(u.markText)) {
                                serverRange = u.range;
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                } catch (Throwable e) {
                    err = describe(e);
                }
                final String fErr = err;
                final String fServerRange = serverRange;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (fErr != null) {
                            toast("划线失败：" + fErr);
                            return;
                        }
                        pageView.clearSelection();
                        if (fServerRange != null && !fServerRange.equals(range)) {
                            toast("已划线 ✓（注意：服务器 range=" + fServerRange
                                    + "，本地=" + range + "，需按 README 校准坐标）");
                        } else {
                            toast("已划线 ✓");
                        }
                        if (afterThought != null) {
                            askThought(afterThought, fServerRange != null ? fServerRange : range);
                        }
                    }
                });
            }
        }).start();
    }

    private void askThought(final String abstractText, final String range) {
        if (chapters == null || chapterIndex < 0) return;
        final Api.Chapter ch = chapters.get(chapterIndex);
        final EditText input = new EditText(this);
        input.setTextSize(16);
        input.setHint(abstractText != null && abstractText.length() > 0
                ? "对「" + (abstractText.length() > 20 ? abstractText.substring(0, 20) + "…" : abstractText)
                + "」的想法…" : "写下这一章的想法…");
        new AlertDialog.Builder(this)
                .setTitle("发表想法 · " + ch.title)
                .setView(input)
                .setPositiveButton("发表", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String content = input.getText().toString().trim();
                        if (content.length() == 0) return;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Api.addThought(bookId, ch.chapterUid, ch.chapterIdx,
                                            content, abstractText == null ? "" : abstractText,
                                            range == null ? "0-0" : range);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("想法已发表 ✓");
                                            pageView.clearSelection();
                                        }
                                    });
                                } catch (final Throwable e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            toast("发表失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 时长上报

    private void toggleReporting() {
        reporting = !reporting;
        if (reporting) {
            enteredSession = false;
            handler.removeCallbacks(reportTask);
            handler.post(reportTask);
            toast("时长上报：已开启（每 30 秒，同步进度）");
        } else {
            handler.removeCallbacks(reportTask);
            toast("时长上报：已关闭");
        }
    }

    private void doReport(final boolean retryAfterRenew) {
        if (chapters == null || chapterIndex < 0 || psvts == null || currentText.length() == 0) {
            return;
        }
        final Api.Chapter ch = chapters.get(chapterIndex);
        final int[] range = pageView.pageRange();
        final int percent = pageView.pagePercent();
        final long offset = range[0];
        final String summary = currentText.substring(range[0],
                Math.min(range[1], range[0] + 40));
        final boolean enter = !enteredSession;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api.reportRead(bookId, ch.chapterUid, ch.chapterIdx, offset,
                            percent, summary, psvts, enter);
                    enteredSession = true;
                    lastReportOkAt = System.currentTimeMillis();
                } catch (Throwable e) {
                    if (!retryAfterRenew && Session.renewalAllowed()) {
                        try {
                            Api.renewCookie();
                            doReport(true);
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                    showSlow("上报失败：" + e.getMessage());
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ 菜单

    private void showMenu() {
        final String[] items = {
                "选择章节",
                "上传划线（先长按选中文字）",
                "发表想法",
                reporting ? "时长上报：开（点击关闭）" : "时长上报：关（点击开启）",
                "字号 ＋",
                "字号 －",
                "行距 ＋",
                "行距 －",
                "边距 ＋",
                "边距 －",
                "下载全书（离线阅读）",
                "清理章节缓存",
                "诊断信息"
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) showChapterList();
                        else if (w == 1) doUnderline(null);
                        else if (w == 2) askThought(null, null);
                        else if (w == 3) toggleReporting();
                        else if (w == 4) changeFont(1);
                        else if (w == 5) changeFont(-1);
                        else if (w == 6) changeLine(0.1f);
                        else if (w == 7) changeLine(-0.1f);
                        else if (w == 8) changePad(1);
                        else if (w == 9) changePad(-1);
                        else if (w == 10) downloadWholeBook();
                        else if (w == 11) clearCache();
                        else showDiag();
                    }
                }).show();
    }

    // ------------------------------------------------------------------ 排版设置

    private void applyTypesetting() {
        float fontDp = Session.prefFloat("font_px", 19f);
        float lineMult = Session.prefFloat("line_mult", 1.30f);
        int padDp = Session.prefInt("pad_h", 18);
        pageView.setFontPx(dip(Math.round(fontDp)));
        pageView.setSpacingMult(lineMult);
        pageView.setPadH(dip(padDp));
    }

    private void changeFont(int dir) {
        float cur = Session.prefFloat("font_px", 19f);
        float v = Math.max(14f, Math.min(32f, cur + dir * 1.5f));
        Session.setPrefFloat("font_px", v);
        pageView.setFontPx(dip(Math.round(v)));
        toast("字号 " + Math.round(v));
    }

    private void changeLine(float delta) {
        float cur = Session.prefFloat("line_mult", 1.30f);
        float v = Math.max(1.05f, Math.min(2.1f, cur + delta));
        Session.setPrefFloat("line_mult", v);
        pageView.setSpacingMult(v);
        toast("行距 " + String.format("%.1f", v));
    }

    private void changePad(int dir) {
        int cur = Session.prefInt("pad_h", 18);
        int v = Math.max(8, Math.min(56, cur + dir * 4));
        Session.setPrefInt("pad_h", v);
        pageView.setPadH(dip(v));
        toast("边距 " + v);
    }

    // ------------------------------------------------------------------ 离线缓存

    private boolean downloading = false;

    /** 逐章下载整本书（已缓存的跳过），供离线阅读 */
    private void downloadWholeBook() {
        if (chapters == null || chapters.isEmpty()) {
            toast("目录尚未加载");
            return;
        }
        if (downloading) {
            toast("正在下载中…");
            return;
        }
        downloading = true;
        final int total = chapters.size();
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("下载全书");
        pd.setMessage("准备中…");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(total);
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                int done = 0, skipped = 0, failed = 0;
                try {
                    for (int i = 0; i < total; i++) {
                        final int cur = i + 1;
                        final String title = chapters.get(i).title;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                pd.setMessage("(" + cur + "/" + total + ") " + title);
                                pd.setProgress(cur);
                            }
                        });
                        String uid = chapters.get(i).chapterUid;
                        if (Cache.load(bookId, uid) != null) {
                            skipped++;
                            continue;
                        }
                        try {
                            if (psvts == null) psvts = Api.fetchPsvts(bookId, uid);
                            Api.ChapterText ct = Api.fetchChapterText(bookId, uid, psvts);
                            // 插图一起缓存，保证整本完全离线
                            if (ct.images != null) {
                                for (int k = 0; k < ct.images.size(); k++) {
                                    final String u = ct.images.get(k);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            pd.setMessage("(" + cur + "/" + total + ") 插图 "
                                                    + Diag.snippet(u, 30));
                                        }
                                    });
                                    Http.getBytes(u);
                                    Thread.sleep(120);
                                }
                            }
                            done++;
                        } catch (Throwable t) {
                            failed++;
                            Diag.add("下载失败 章=" + title + " " + t.getClass().getSimpleName());
                        }
                        Thread.sleep(300); // 限速，避免触发风控
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    downloading = false;
                    final int d = done, sk = skipped, fa = failed;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            toast("下载完成：新增 " + d + " 章，跳过 " + sk + " 章，失败 " + fa
                                    + " 章；插图 " + Cache.imageCount() + " 张；共占用 "
                                    + (Cache.sizeBytes() / 1024) + " KB");
                        }
                    });
                }
            }
        }).start();
    }

    private void clearCache() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("清理章节缓存")
                .setMessage("当前缓存 " + (Cache.sizeBytes() / 1024) + " KB（章节 + 插图 "
                        + Cache.imageCount() + " 张），清理后需重新下载。")
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Cache.clearAll();
                        toast("缓存已清理");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 诊断面板：最近 40 次请求的状态码与响应片段 */
    private void showDiag() {
        List<String> lines = Diag.snapshot();
        StringBuilder sb = new StringBuilder();
        if (lines.isEmpty()) {
            sb.append("本次启动还没有网络请求记录。\n");
        } else {
            for (String s : lines) sb.append(s).append("\n\n");
        }
        sb.append("--- 环境 ---\n");
        sb.append("Cookie：").append(Session.cookieHeader().length() > 0 ? "已获取" : "无").append("\n");
        sb.append("书名：").append(bookTitle).append("\n");
        sb.append("书籍ID：").append(bookId).append("\n");
        sb.append("章节数：").append(chapters == null ? 0 : chapters.size()).append("\n");
        sb.append("psvts：").append(psvts == null ? "无" : "有").append("\n");
        sb.append("缓存合计：").append(Cache.sizeBytes() / 1024).append(" KB（插图 "
                + Cache.imageCount() + " 张，"
                + Cache.imageSizeBytes() / 1024 + " KB）\n");
        showTextDialog("诊断信息", sb.toString());
    }

    private void showChapterList() {
        if (chapters == null) return;
        String[] names = new String[chapters.size()];
        for (int i = 0; i < names.length; i++) {
            String title = chapters.get(i).title;
            if (i == chapterIndex) title = "▶ " + title;
            Integer p = progressMap.get(chapters.get(i).chapterUid);
            names[i] = (i + 1) + ". " + title + (p != null && p > 0 ? "  ·  " + p + "%" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(bookTitle)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        loadChapter(w);
                    }
                }).show();
    }

    private void showUnderlines(final boolean best) {
        if (chapters == null || chapterIndex < 0) return;
        showLoading("获取划线");
        new Thread(new Runnable() {
            private List<Api.Underline> result;
            private String err;

            @Override
            public void run() {
                try {
                    result = best
                            ? Api.fetchBestBookmarks(bookId)
                            : Api.fetchChapterUnderlines(bookId, chapters.get(chapterIndex).chapterUid);
                } catch (Throwable e) {
                    err = describe(e);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (err != null) {
                            toast(err);
                        } else if (result.isEmpty()) {
                            toast(best ? "暂无热门划线" : "本章暂无划线");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (Api.Underline u : result) {
                                sb.append("—— ").append(u.markText).append("\n\n");
                            }
                            showTextDialog(best ? "热门划线" : "本章划线", sb.toString());
                        }
                    }
                });
            }
        }).start();
    }

    private void showChapterThoughts() {
        if (chapters == null || chapterIndex < 0) return;
        showLoading("获取本章想法");
        final Api.Chapter ch = chapters.get(chapterIndex);
        new Thread(new Runnable() {
            private List<Api.Thought> thoughts;
            private String err;

            @Override
            public void run() {
                try {
                    List<Api.Underline> us = Api.fetchChapterUnderlines(bookId, ch.chapterUid);
                    List<String> ranges = new ArrayList<String>();
                    for (Api.Underline u : us) ranges.add(u.range);
                    if (ranges.isEmpty()) {
                        thoughts = new ArrayList<Api.Thought>();
                    } else {
                        thoughts = Api.fetchChapterThoughts(bookId, ch.chapterUid, ranges);
                    }
                } catch (Throwable e) {
                    err = describe(e);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (err != null) {
                            toast(err);
                        } else if (thoughts.isEmpty()) {
                            toast("本章暂无想法");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (Api.Thought t : thoughts) {
                                sb.append(t.author).append("：").append(t.content).append("\n\n");
                            }
                            showTextDialog("本章想法", sb.toString());
                        }
                    }
                });
            }
        }).start();
    }

    private void showBookReviews() {
        showLoading("获取书评");
        new Thread(new Runnable() {
            private List<Api.Thought> reviews;
            private String err;

            @Override
            public void run() {
                try {
                    reviews = Api.fetchBookReviews(bookId, 1, 20);
                } catch (Throwable e) {
                    err = describe(e);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading.dismiss();
                        if (err != null) {
                            toast(err);
                        } else if (reviews.isEmpty()) {
                            toast("暂无书评");
                        } else {
                            StringBuilder sb = new StringBuilder();
                            for (Api.Thought t : reviews) {
                                sb.append(t.author).append("：").append(t.content).append("\n\n");
                            }
                            showTextDialog("书评", sb.toString());
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ UI 工具

    private void showTextDialog(String title, String content) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(0xFF111111);
        int pad = dip(16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(content);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ReaderActivity.this, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showSlow(final String s) {
        if (System.currentTimeMillis() - lastReportOkAt > 120_000L) {
            toast(s);
        }
    }

    // ------------------------------------------------------------------ 翻页

    /** 下一页：插图队列优先 → 文本翻页 → 跨章 */
    private void turnNext() {
        // 1) 正在看插图页：先看本页剩余插图
        if (imageView.getVisibility() == android.view.View.VISIBLE) {
            if (!pendingImages.isEmpty()) {
                showImagePage(pendingImages.poll());
            } else {
                hideImagePage();
                pageView.next();
            }
            return;
        }
        // 2) 本页还有未展示的插图
        if (!pendingImages.isEmpty()) {
            showImagePage(pendingImages.poll());
            return;
        }
        // 3) 文本翻页
        if (pageView.next()) {
            collectPageImages();
            return;
        }
        // 4) 跨章
        if (chapters == null) return;
        if (chapterIndex + 1 < chapters.size()) {
            loadChapter(chapterIndex + 1);
            Toast.makeText(this, "进入下一章：" + chapters.get(chapterIndex + 1).title,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show();
        }
    }

    /** 上一页：插图页回退 → 文本翻页 → 上一章末页 */
    private void turnPrev() {
        if (imageView.getVisibility() == android.view.View.VISIBLE) {
            hideImagePage();
            return;
        }
        if (pageView.prev()) {
            collectPageImages();
            return;
        }
        if (chapters == null) return;
        if (chapterIndex > 0) {
            loadChapter(chapterIndex - 1);
            pageView.goToLastPage();
            Toast.makeText(this, "回到上一章：" + chapters.get(chapterIndex - 1).title,
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ 插图

    /** 把当前页里未展示过的插图放入待展示队列 */
    private void collectPageImages() {
        if (chapterImages == null || chapterImages.isEmpty()) return;
        int[] r = pageView.pageRange();
        int page = pageView.pageIndexPublic();
        if (imagesShownPages.contains(page)) return;
        boolean any = false;
        for (int i = 0; i < chapterImageOffsets.size(); i++) {
            int off = chapterImageOffsets.get(i);
            if (off >= r[0] && off < r[1]) {
                pendingImages.add(i);
                any = true;
            }
        }
        if (any) imagesShownPages.add(page);
    }

    private void hideImagePage() {
        imageView.setVisibility(android.view.View.GONE);
    }

    /** 下载并灰度渲染一张插图 */
    private void showImagePage(final int index) {
        if (index < 0 || index >= chapterImages.size()) {
            hideImagePage();
            return;
        }
        final String url = chapterImages.get(index);
        Toast.makeText(this, "加载插图…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] data = Http.getBytes(url);
                android.graphics.Bitmap bmp = null;
                if (data != null) {
                    try {
                        android.graphics.BitmapFactory.Options o =
                                new android.graphics.BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        int sw = getResources().getDisplayMetrics().widthPixels;
                        int sample = 1;
                        while (o.outWidth / sample > sw && sample < 8) sample *= 2;
                        o.inJustDecodeBounds = false;
                        o.inSampleSize = sample;
                        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                        bmp = android.graphics.BitmapFactory.decodeByteArray(
                                data, 0, data.length, o);
                    } catch (Throwable t) {
                        Diag.add("IMG 解码失败 " + t.getClass().getSimpleName());
                    }
                }
                final android.graphics.Bitmap fBmp = bmp == null ? null : toGray(bmp);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fBmp == null) {
                            Toast.makeText(ReaderActivity.this, "插图加载失败（已跳过）",
                                    Toast.LENGTH_SHORT).show();
                            hideImagePage();
                        } else {
                            imageView.setImageBitmap(fBmp);
                            imageView.setVisibility(android.view.View.VISIBLE);
                        }
                    }
                });
            }
        }).start();
    }

    /** 转灰度（墨水屏显示更干净） */
    private static android.graphics.Bitmap toGray(android.graphics.Bitmap src) {
        try {
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    src.getWidth(), src.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint();
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0f);
            p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            c.drawBitmap(src, 0, 0, p);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                turnPrev();
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                turnNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                turnPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                turnNext();
                return true;
            case KeyEvent.KEYCODE_MENU:
                showMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (pageView.hasSelection()) {
                    pageView.clearSelection();
                    return true;
                }
                if (pageView.atLastPage()) {
                    finish();
                    return true;
                }
                Toast.makeText(this, "再按一次返回退出（菜单键更多功能）", Toast.LENGTH_SHORT).show();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        reporting = false;
        handler.removeCallbacks(reportTask);
        super.onDestroy();
    }
}
