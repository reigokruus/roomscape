package com.hackathon.setscan;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.Locale;

/**
 * Make photo-real (rk 12-13 Nov, as on the iPhone's PhotoRealScreen): its own screen, in its own app process
 * (":photoreal" in the manifest), so none of the scanner's or other scans' memory is held while Brush trains. Started by
 * hand only, after a warning (it takes a while, the phone gets hot, keep the app open). Then the result is shown here in
 * the same splat viewer as the iPhone's (GaussianSplats3D on three.js, served offline from the APK's assets).
 *
 * Files, in the scan folder (the iPhone's layout): splat/dataset/ (transforms.json, images/, points.ply), splat/brush-out/
 * (Brush's export, refine-skips.txt), splat/preview.ply (the result), splat/train-log.txt.
 */
public class PhotoRealActivity extends Activity {
    static final String EXTRA_SCAN = "scan";
    static final int MAX_RES = 720, SH_DEGREE = 1, REFINE_EVERY = 200;

    private File scan, splat;
    private TextView title, line, note;
    private ProgressBar bar;
    private Button action;
    private FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running, cancelled;
    private Thread worker;

    static File result(File scanDir) { return new File(new File(scanDir, "splat"), "preview.ply"); }

    /** Whether a scan can be made photo-real here: Brush is in this APK and the scan kept its rebuild pictures. */
    static boolean possible(android.content.Context ctx, File scanDir) {
        return scanDir != null && new File(scanDir, "rebuild-frames/frames.csv").exists() && BrushNative.present(ctx);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        scan = new File(getIntent().getStringExtra(EXTRA_SCAN));
        splat = new File(scan, "splat");
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF111214);
        setContentView(root);
        if (result(scan).exists()) showViewer(); else showStart();
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private void showStart() {
        root.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(24), dp(24), dp(24), dp(24));
        c.setGravity(Gravity.CENTER_VERTICAL);
        title = text(22, Color.WHITE); title.setText("Make photo-real");
        c.addView(title);
        line = text(15, 0xFFDDDDDD);
        line.setText("This takes a while (about 5 to 15 minutes) and the phone gets hot. Keep the app open on this screen until it's done.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        c.addView(line, lp);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setVisibility(View.GONE);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFEDB14D));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(10)); bp.topMargin = dp(18);
        c.addView(bar, bp);
        note = text(13, 0xFFA3A09A);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.topMargin = dp(8);
        c.addView(note, np);
        action = new Button(this);
        action.setAllCaps(false);
        action.setText("Start");
        action.setOnClickListener(v -> { if (running) cancel(); else start(); });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(52)); ap.topMargin = dp(24);
        c.addView(action, ap);
        root.addView(c, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color); return t;
    }

    private void start() {
        running = true; cancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        action.setText("Stop");
        bar.setVisibility(View.VISIBLE); bar.setProgress(0);
        line.setText("Getting the pictures ready…");
        worker = new Thread(this::work, "photo-real");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    private void cancel() {
        cancelled = true;
        BrushNative.cancel();
        line.setText("Stopping…");
    }

    @Override public void onBackPressed() {
        if (running) { new android.app.AlertDialog.Builder(this).setMessage("Stop making it photo-real?")
                .setPositiveButton("Stop", (d, w) -> { cancel(); finish(); }).setNegativeButton("Keep going", null).show(); return; }
        super.onBackPressed();
    }

    // ---- the work (background thread) ----

    private void work() {
        long t0 = System.currentTimeMillis();
        final double[] rest = {0}, paused = {0};
        try {
            if (!BrushNative.available()) { done(false, "Brush isn't in this build of the app."); return; }
            if (!splat.isDirectory()) splat.mkdirs();
            File dataset = new File(splat, "dataset"), out = new File(splat, "brush-out");
            deleteTree(dataset); deleteTree(out);
            SplatPlan plan = SplatPlan.of(pointCount(new File(scan, "points.bin")), frameCount(new File(scan, "rebuild-frames/frames.csv")));
            int pics = SplatDataset.export(scan, dataset, PhotoRealActivity::sharpness, plan.pictures, plan.startPoints);
            log("plan " + plan + "; " + pics + " pictures picked");
            if (cancelled) { done(false, "Stopped."); return; }
            // the refine memory guard (Brush patch): until two refines ran 1700 MB available, then 800 (as the iPhone)
            BrushNative.setenv("BRUSH_REFINE_MIN_AVAIL_MB", "1700");
            BrushNative.setenv("BRUSH_REFINE_MIN_AVAIL_LATER_MB", "800");
            BrushNative.setenv("CUBECL_AUTOTUNE_LEVEL", "minimal");
            final int steps = plan.steps;
            final long[] last = {System.currentTimeMillis()}; final long trainT0 = System.currentTimeMillis();
            final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            post("Training…", 0, null);
            int r = BrushNative.train(dataset.getAbsolutePath(), out.getAbsolutePath(), steps, REFINE_EVERY, MAX_RES, steps,
                    plan.maxSplats, SH_DEGREE, (tag, iter) -> {
                        if (tag != 1) return;
                        long now = System.currentTimeMillis(); double worked = (now - last[0]) / 1000.0;
                        int th = thermal(pm);
                        double spent = (now - trainT0) / 1000.0 - rest[0] - paused[0];
                        Integer left = iter > 50 ? (int) Math.ceil(((now - trainT0) / 1000.0) / iter * (steps - iter) / 60.0) : null;
                        post(String.format(Locale.US, "Step %,d of %,d", iter, steps) + (left != null ? " · about " + left + " min left" : ""),
                                iter / (float) steps, SplatPlan.note(th));
                        Double f = SplatPlan.restFactor(th);
                        if (f != null) {
                            double s = Math.min(2.0, f * worked);
                            if (s > 0) { sleep(s); rest[0] += s; }
                        } else {   // critical: pause until the phone cools
                            while (!cancelled && SplatPlan.restFactor(thermal(pm)) == null) { sleep(2); paused[0] += 2; }
                        }
                        last[0] = System.currentTimeMillis();
                    });
            log(String.format(Locale.US, "brush exit %d after %.0f s (rest %.0f s, paused %.0f s)", r, (System.currentTimeMillis() - t0) / 1000.0, rest[0], paused[0]));
            File skips = new File(out, "refine-skips.txt");
            if (skips.exists()) log("refines skipped for memory: " + countLines(skips));
            if (r != 0 || cancelled) { done(false, cancelled ? "Stopped." : "Brush stopped with an error. The scan is unchanged."); return; }
            File ply = newestPly(out);
            if (ply == null) { done(false, "Brush finished but wrote no result."); return; }
            File res = result(scan);
            if (res.exists()) res.delete();
            if (!ply.renameTo(res)) { done(false, "Couldn't keep the result."); return; }
            done(true, null);
        } catch (Throwable e) {
            log("error: " + e);
            done(false, "Something went wrong: " + e.getMessage());
        }
    }

    private static int thermal(PowerManager pm) {
        try { return android.os.Build.VERSION.SDK_INT >= 29 ? pm.getCurrentThermalStatus() : 0; } catch (Throwable e) { return 0; }
    }

    private void sleep(double s) { try { Thread.sleep((long) (s * 1000)); } catch (InterruptedException ignored) { } }

    private void post(String l, float frac, String n) {
        ui.post(() -> { if (line == null) return; line.setText(l); if (frac >= 0) bar.setProgress(Math.round(frac * 1000)); note.setText(n == null ? "" : n); });
    }

    private void done(boolean ok, String msg) {
        running = false;
        ui.post(() -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (ok) { showViewer(); return; }
            line.setText(msg); action.setText("Start again"); bar.setVisibility(View.GONE); note.setText("");
        });
    }

    private void log(String s) {
        try (FileWriter w = new FileWriter(new File(splat, "train-log.txt"), true)) {
            w.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()) + " " + s + "\n");
        } catch (Exception ignored) { }
    }

    /** The variance of a 4-neighbour Laplacian on the grey picture, at about 320 px wide (higher = sharper). */
    static float sharpness(File jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpg.getPath(), o);
        if (o.outWidth <= 0) return Float.NaN;
        o.inJustDecodeBounds = false; o.inSampleSize = Math.max(1, o.outWidth / 320);
        Bitmap bm = BitmapFactory.decodeFile(jpg.getPath(), o);
        if (bm == null) return Float.NaN;
        int w = bm.getWidth(), h = bm.getHeight(); int[] px = new int[w * h]; bm.getPixels(px, 0, w, 0, 0, w, h); bm.recycle();
        float[] g = new float[w * h];
        for (int i = 0; i < px.length; i++) { int c = px[i]; g[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255); }
        double sum = 0, sum2 = 0; int k = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            float l = 4 * g[y * w + x] - g[y * w + x - 1] - g[y * w + x + 1] - g[(y - 1) * w + x] - g[(y + 1) * w + x];
            sum += l; sum2 += l * l; k++;
        }
        if (k == 0) return Float.NaN;
        double m = sum / k; return (float) (sum2 / k - m * m);
    }

    static int pointCount(File pointsBin) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(pointsBin))) { return in.readInt(); } catch (Exception e) { return 0; }
    }

    static int frameCount(File csv) {
        try { return countLines(csv); } catch (Exception e) { return 0; }
    }

    static int countLines(File f) throws java.io.IOException {
        int n = 0; try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) { while (r.readLine() != null) n++; } return n;
    }

    static File newestPly(File dir) {
        File best = null; File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) { File b = newestPly(f); if (b != null && (best == null || b.lastModified() > best.lastModified())) best = b; }
            else if (f.getName().endsWith(".ply") && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    // ---- the viewer ----

    static final String HOST = "https://appassets.androidplatform.net/";

    private void showViewer() {
        root.removeAllViews();
        WebView web = new WebView(this);
        web.setBackgroundColor(0xFF111214);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        final File ply = result(scan);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String path = req.getUrl().getPath();
                if (path == null || !req.getUrl().toString().startsWith(HOST)) return null;
                String name = path.substring(path.lastIndexOf('/') + 1);
                try {
                    InputStream in = name.equals("scene.ply") ? new FileInputStream(ply) : getAssets().open("SplatViewer/" + name);
                    String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript" : "application/octet-stream";
                    WebResourceResponse r = new WebResourceResponse(type, "utf-8", in);
                    r.setResponseHeaders(java.util.Collections.singletonMap("Access-Control-Allow-Origin", "*"));
                    return r;
                } catch (Exception e) { return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null); }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        Button back = new Button(this);
        back.setAllCaps(false); back.setText("‹ Back");
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bp.topMargin = dp(36); bp.leftMargin = dp(10);
        root.addView(back, bp);
        web.loadUrl(HOST + "splat/index.html");
    }
}
