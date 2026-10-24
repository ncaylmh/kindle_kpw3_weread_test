package com.eink.weread;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/**
 * 墨水屏分页渲染：纯白底 + 近黑字，无任何动画/滚动。
 * 翻页：点击左 1/3 上一页，其余区域下一页。
 * 选中：长按选中所在段落 → 弹操作菜单（回调宿主）；"调整选区"模式下
 * 单击扩展/收缩选区，再次长按重新打开菜单。
 */
public class PageView extends View {

    /** 选中后由宿主弹出操作菜单（划线/想法） */
    public interface SelectionCallback {
        void onSelectionMenu();
    }

    private String text = "";
    private final List<int[]> pages = new ArrayList<int[]>(); // {start, end)
    private int pageIndex = 0;
    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint();
    private int padTop, padBottom, padH;
    private float spacingMult = 1.15f;
    private float spacingAdd;

    // 选区状态
    private int selStart = -1, selEnd = -1;
    private boolean adjusting = false;
    private SelectionCallback selectionCallback;

    public PageView(Context c) {
        super(c);
        paint.setColor(0xFF111111);
        paint.setTextSize(dip(19));
        metaPaint.setColor(0xFF999999);
        metaPaint.setTextSize(dip(11));
        highlight.setColor(0xFFDDDDDD); // 墨水屏友好的浅灰高亮
        padH = dip(18);
        padTop = dip(18);
        padBottom = dip(30);
        spacingAdd = dip(5);
        setBackgroundColor(Color.WHITE);
        setLongClickable(true);
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    public void setText(String t) {
        setText(t, 0);
    }

    /** charPos 用于尺寸变化后恢复阅读位置 */
    public void setText(String t, int charPos) {
        text = t == null ? "" : t;
        pageIndex = 0;
        selStart = selEnd = -1;
        adjusting = false;
        rebuild(charPos);
    }

    public int currentCharPos() {
        if (pageIndex >= 0 && pageIndex < pages.size()) return pages.get(pageIndex)[0];
        return 0;
    }

    /** 当前页字符区间 [start, end)，供时长上报的 chapter offset / summary 使用 */
    public int[] pageRange() {
        if (pageIndex >= 0 && pageIndex < pages.size()) return pages.get(pageIndex);
        return new int[]{0, 0};
    }

    /** 当前页在全书文本中的百分比位置，供时长上报的 progress 使用 */
    public int pagePercent() {
        if (pages.isEmpty() || text.length() == 0) return 0;
        int[] r = pages.get(pageIndex);
        return (int) Math.min(100L, (long) r[1] * 100 / text.length());
    }

    public boolean atLastPage() {
        return pageIndex >= pages.size() - 1;
    }

    private void rebuild(int charPos) {
        pages.clear();
        pageIndex = 0;
        if (text.length() == 0 || getWidth() == 0) {
            invalidate();
            return;
        }
        int w = getWidth() - padH * 2;
        int h = getHeight() - padTop - padBottom;
        StaticLayout layout = new StaticLayout(text, paint, w,
                Alignment.ALIGN_NORMAL, spacingMult, spacingAdd, false);
        int line = 0;
        int total = layout.getLineCount();
        while (line < total) {
            int start = layout.getLineStart(line);
            float y = 0;
            int end = start;
            while (line < total) {
                float lineH = layout.getLineBottom(line) - layout.getLineTop(line);
                if (y + lineH > h) break;
                y += lineH;
                end = layout.getLineEnd(line);
                line++;
            }
            pages.add(new int[]{start, end});
            if (end <= start) break; // 单行超高保护，避免死循环
        }
        // 恢复到包含 charPos 的页
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i)[0] <= charPos && charPos < pages.get(i)[1]) {
                pageIndex = i;
                break;
            }
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (text.length() > 0) rebuild(currentCharPos());
    }

    public boolean next() {
        if (pageIndex < pages.size() - 1) {
            pageIndex++;
            invalidate();
            return true;
        }
        return false;
    }

    public boolean prev() {
        if (pageIndex > 0) {
            pageIndex--;
            invalidate();
            return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pages.isEmpty() || text.length() == 0) {
            metaPaint.setColor(0xFF666666);
            canvas.drawText(pages.isEmpty() && text.length() == 0 ? "加载中…" : "",
                    padH, padTop + dip(20), metaPaint);
            metaPaint.setColor(0xFF999999);
            return;
        }
        int[] range = pages.get(pageIndex);
        int w = getWidth() - padH * 2;
        StaticLayout layout = new StaticLayout(text, range[0], range[1], paint, w,
                Alignment.ALIGN_NORMAL, spacingMult, spacingAdd, false);

        // 选区高亮（浅灰，墨水屏友好）
        if (hasSelection()) {
            for (int line = 0; line < layout.getLineCount(); line++) {
                int ls = layout.getLineStart(line);
                int le = layout.getLineEnd(line);
                if (le <= selStart || ls >= selEnd) continue;
                int from = Math.max(ls, selStart);
                int to = Math.min(le, selEnd);
                float x1 = layout.getPrimaryHorizontal(from);
                float x2 = layout.getPrimaryHorizontal(to);
                float top = layout.getLineTop(line);
                float bottom = layout.getLineBottom(line);
                canvas.drawRect(padH + x1, padTop + top, padH + x2, padTop + bottom, highlight);
            }
        }

        canvas.save();
        canvas.translate(padH, padTop);
        layout.draw(canvas);
        canvas.restore();

        String meta = (pageIndex + 1) + " / " + pages.size();
        float tw = metaPaint.measureText(meta);
        canvas.drawText(meta, (getWidth() - tw) / 2, getHeight() - dip(12), metaPaint);
    }

    // ------------------------------------------------------------------ 选区

    public void setSelectionCallback(SelectionCallback cb) {
        selectionCallback = cb;
    }

    public boolean hasSelection() {
        return selStart >= 0 && selEnd > selStart;
    }

    public String getSelectedText() {
        return hasSelection() ? text.substring(selStart, selEnd) : "";
    }

    public int getSelStart() {
        return selStart;
    }

    public int getSelEnd() {
        return selEnd;
    }

    public void clearSelection() {
        selStart = selEnd = -1;
        adjusting = false;
        invalidate();
    }

    /** 进入"单击调整选区"模式（操作菜单里的"调整选区"按钮调用） */
    public void enterAdjustMode() {
        adjusting = true;
    }

    /** 长按：选中按下位置所在的整个段落，然后回调宿主弹菜单 */
    private void onLongPress(float x, float y) {
        if (!hasSelection()) {
            int pos = charAt(x, y);
            if (pos < 0) return;
            int s = pos;
            while (s > 0 && text.charAt(s - 1) != '\n') s--;
            int e = pos;
            while (e < text.length() && text.charAt(e) != '\n') e++;
            selStart = s;
            selEnd = e;
            invalidate();
        }
        if (hasSelection() && selectionCallback != null) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            selectionCallback.onSelectionMenu();
        }
    }

    /** 单击：调整模式下扩展/收缩选区，否则清选区并翻页 */
    private void onTap(float x, float y) {
        if (adjusting && hasSelection()) {
            int pos = charAt(x, y);
            if (pos >= 0) {
                if (pos <= selStart) selStart = pos;
                else if (pos >= selEnd) selEnd = pos;
                else if (pos - selStart < selEnd - pos) selStart = pos;
                else selEnd = pos;
                invalidate();
            }
            return;
        }
        if (hasSelection()) {
            clearSelection();
            return;
        }
        if (x < getWidth() / 3f) {
            prev();
        } else {
            next();
        }
    }

    /** 命中检测：屏幕坐标 → 文本偏移 */
    private int charAt(float x, float y) {
        int[] r = pageRange();
        if (r[1] <= r[0]) return -1;
        int w = getWidth() - padH * 2;
        StaticLayout l = new StaticLayout(text, r[0], r[1], paint, w,
                Alignment.ALIGN_NORMAL, spacingMult, spacingAdd, false);
        int line = l.getLineForVertical((int) (y - padTop));
        return l.getOffsetForHorizontal(line, x - padH);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            boolean longPress = event.getEventTime() - event.getDownTime() >= 550;
            if (longPress) {
                onLongPress(event.getX(), event.getY());
            } else {
                onTap(event.getX(), event.getY());
            }
            return true;
        }
        return true;
    }
}
