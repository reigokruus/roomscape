package com.hackathon.setscan;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look of the iPhone app (SwiftUI inset-grouped lists in dark mode), so both apps feel the same: black
 * background, rounded dark-grey cards, RoomScape's amber accent for actions, grey captions, and thin separators.
 */
final class Ios {
    static final int BACKGROUND = Color.BLACK;
    /** iOS secondarySystemGroupedBackground, dark. */
    static final int CARD = 0xFF1C1C1E;
    /** RoomScape's accent (the iPhone app's AccentColor). */
    static final int ACCENT = 0xFFEDB14D;
    /** iOS secondaryLabel, dark. */
    static final int SECONDARY = 0xFF8E8E93;
    static final int SEPARATOR = 0xFF38383A;
    static final int RADIUS_DP = 10;

    private Ios() { }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Rows in one rounded card, with inset separators between them. */
    static View card(Context c, View... rows) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(c, RADIUS_DP));
        card.setBackground(bg);
        card.setClipToOutline(true);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View sep = new View(c);
                sep.setBackgroundColor(SEPARATOR);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
                lp.leftMargin = dp(c, 16);
                card.addView(sep, lp);
            }
            card.addView(rows[i]);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * A tappable row: an accent icon (drawable id, 0 for none) and label, like a SwiftUI Button in a List. `bold` is
     * the headline weight; `chevron` marks rows that open another screen (navigation links: white label).
     */
    static View row(Context c, int icon, String label, boolean bold, boolean chevron, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 48));
        r.setPadding(dp(c, 16), dp(c, 11), dp(c, 12), dp(c, 11));
        if (tap != null) { r.setBackground(pressable()); r.setOnClickListener(tap); }
        if (icon != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(icon);
            if (chevron) iv.setImageTintList(ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
            ip.rightMargin = dp(c, 14);
            r.addView(iv, ip);
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(tap == null ? SECONDARY : chevron ? Color.WHITE : ACCENT);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (chevron) r.addView(chevron(c));
        return r;
    }

    static TextView chevron(Context c) {
        TextView ch = new TextView(c);
        ch.setText("›");
        ch.setTextColor(0xFF5A5A5F);
        ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        ch.setPadding(dp(c, 6), 0, 0, dp(c, 2));
        return ch;
    }

    /** A section header above a card: small, grey, upper case. */
    static TextView header(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text.toUpperCase(java.util.Locale.getDefault()));
        t.setTextColor(SECONDARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(c, 16), dp(c, 26), dp(c, 16), 0);
        return t;
    }

    /** The grey explanation under a card. */
    static TextView footer(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(SECONDARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLineSpacing(0, 1.1f);
        t.setPadding(dp(c, 16), dp(c, 7), dp(c, 16), dp(c, 6));
        return t;
    }

    /** An iOS "bordered" button: grey capsule fill, accent label (Lighting, Objects, ...). */
    static void pill(android.widget.Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE62C2C2E);
        bg.setCornerRadius(dp(b.getContext(), 18));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setTextColor(ACCENT);
        b.setStateListAnimator(null);
    }

    /** A plain accent text button, like toolbar items (Back, Share). */
    static void textButton(android.widget.Button b) {
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(Color.WHITE)));
        b.setTextColor(ACCENT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinWidth(0); b.setMinimumWidth(0);
        int p = dp(b.getContext(), 10);
        b.setPadding(p, p / 2, p, p / 2);
    }

    /** An iOS "borderedProminent" button: accent fill, dark label, headline weight (Continue scan). */
    static void filled(android.widget.Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(b.getContext(), 12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null));
        b.setTextColor(Color.BLACK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setAllCaps(false);
        b.setStateListAnimator(null);
    }

    /** Highlights a row while pressed, like a list row on iOS. */
    static Drawable pressable() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(Color.WHITE));
    }
}
