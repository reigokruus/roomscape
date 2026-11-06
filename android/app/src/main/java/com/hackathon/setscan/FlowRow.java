package com.hackathon.setscan;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays buttons out left to right and wraps onto new lines, each line centred; hidden buttons take no space. */
final class FlowRow extends ViewGroup {
    private final int gap;

    FlowRow(Context c, int gapPx) { super(c); gap = gapPx; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = v.getMeasuredWidth();
            if (x > 0 && x + w > maxW) { x = 0; y += lineH + gap; lineH = 0; }
            x += w + gap; lineH = Math.max(lineH, v.getMeasuredHeight());
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + getPaddingTop() + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int start = 0, y = getPaddingTop();
        while (start < getChildCount()) {
            int x = 0, lineH = 0, end = start;
            for (; end < getChildCount(); end++) {
                View v = getChildAt(end);
                if (v.getVisibility() == GONE) continue;
                if (x > 0 && x + v.getMeasuredWidth() > maxW) break;
                x += v.getMeasuredWidth() + gap; lineH = Math.max(lineH, v.getMeasuredHeight());
            }
            int left = getPaddingLeft() + Math.max(0, (maxW - (x - gap)) / 2);
            for (int i = start; i < end; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.layout(left, y, left + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                left += v.getMeasuredWidth() + gap;
            }
            y += lineH + gap;
            start = end;
        }
    }
}
