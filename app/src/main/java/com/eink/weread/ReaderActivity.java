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
    private ProgressDialog loading;
    private final Handler handler = new Handler();

    private List<Api.Chapter> chapters;
    private String psvts;
    private int chapterIndex = -1;
    private int charPos = 0;
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
        pageView.setSelectionCallback(new PageView.SelectionCallback() {
            @Override
            public void onSelectionMenu() {
                showSelectionMenu();
            }
        });
        setContentView(pageView);

        loading = new ProgressDialog(this);
        loading.setMessage("加载中…");

        loadChapters();
        fetchProgressInBackground();
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ 目录

    private void loadChapters() {
        loading.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    chapters = Api.fetchChapters(bookId);
                    if (chapters.isEmpty()) {
                        err = "章节为空";
                    } else {
                        psvts = Api.fetchPsvts(bookId, chapters.get(0).chapterUid);
                    }
                } catch (Exception e) {
                    err = e.getMessage();
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

    // ------------------------------------------------------------------ 章节

    private void loadChapter(final int index) {
        if (chapters == null || index < 0 || index >= chapters.size()) return;
        chapterIndex = index;
        charPos = 0;
        loading.setMessage("下载章节…");
        loading.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Api.ChapterText ct = null;
                String err = null;
                try {
                    ct = Api.fetchChapterText(bookId,
                            chapters.get(index).chapterUid, psvts);
                } catch (Exception e) {
                    err = e.getMessage();
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
                        } else {
                            currentText = fCt.text;
                            coordMap = fCt.coords;
                            charPos = restoreCharPos(chapters.get(index).chapterUid);
                            pageView.setText(fCt.text, charPos);
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
        loading.show();
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
                } catch (Exception e) {
                    err = e.getMessage();
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
                                } catch (final Exception e) {
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
                } catch (Exception e) {
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
                "本章划线",
                "本章想法",
                "全书书评",
                "热门划线",
                "发表想法",
                reporting ? "时长上报：开（点击关闭）" : "时长上报：关（点击开启）"
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) showChapterList();
                        else if (w == 1) showUnderlines(false);
                        else if (w == 2) showChapterThoughts();
                        else if (w == 3) showBookReviews();
                        else if (w == 4) showUnderlines(true);
                        else if (w == 5) askThought(null, null);
                        else toggleReporting();
                    }
                }).show();
    }

    private void showChapterList() {
        if (chapters == null) return;
        String[] names = new String[chapters.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i + 1) + ". " + chapters.get(i).title;
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
        loading.show();
        new Thread(new Runnable() {
            private List<Api.Underline> result;
            private String err;

            @Override
            public void run() {
                try {
                    result = best
                            ? Api.fetchBestBookmarks(bookId)
                            : Api.fetchChapterUnderlines(bookId, chapters.get(chapterIndex).chapterUid);
                } catch (Exception e) {
                    err = e.getMessage();
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
        loading.show();
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
                } catch (Exception e) {
                    err = e.getMessage();
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
        loading.show();
        new Thread(new Runnable() {
            private List<Api.Thought> reviews;
            private String err;

            @Override
            public void run() {
                try {
                    reviews = Api.fetchBookReviews(bookId, 1, 20);
                } catch (Exception e) {
                    err = e.getMessage();
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                pageView.prev();
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                pageView.next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                pageView.prev();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                pageView.next();
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
