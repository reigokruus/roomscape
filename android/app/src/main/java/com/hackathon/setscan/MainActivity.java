package com.hackathon.setscan;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;

public class MainActivity extends Activity {
    private static final int CAMERA_REQ = 1;

    private GLSurfaceView glView;
    private ScanRenderer renderer;
    private Session session;
    private boolean installRequested;
    private TextView status;
    private Button saveButton, homeButton, improveButton, cleanButton, depthButton, trainButton, mainButton, topButton, splatButton, planButton, cutButton, viewButton, exportButton, claudeButton, liveClaudeButton;
    private long downTime;
    private float downX, downY;
    private boolean multiTouch;
    private ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private int lastPointerCount;

    /** Lets the user type the room size they already know; empty fields stay measured by the scan. */
    private void askRoomSize() {
        float[] k = renderer.knownSize();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        String[] labels = {"Height (m)", "Width (m)", "Depth (m)"};
        android.widget.EditText[] fields = new android.widget.EditText[3];
        for (int i = 0; i < 3; i++) {
            android.widget.EditText e = new android.widget.EditText(this);
            e.setHint(labels[i] + ", leave empty to measure");
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            if (k[i] > 0) e.setText(String.format(java.util.Locale.US, "%.2f", k[i]));
            fields[i] = e;
            box.addView(e);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Known room size")
                .setMessage("If you already know the room's measurements, enter them and the scan will use them.")
                .setView(box)
                .setPositiveButton("Use", (d, w) -> {
                    float[] v = new float[3];
                    for (int i = 0; i < 3; i++) {
                        try { v[i] = Float.parseFloat(fields[i].getText().toString().trim().replace(',', '.')); }
                        catch (Exception ex) { v[i] = 0; }
                        if (v[i] < 0.5f || v[i] > 50f) v[i] = 0;
                    }
                    glView.queueEvent(() -> renderer.setKnownSize(v[0], v[1], v[2]));
                })
                .setNeutralButton("Clear", (d, w) -> glView.queueEvent(() -> renderer.setKnownSize(0, 0, 0)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** After a tap while scanning: what did you tap? */
    void askTapKind() {
        runOnUiThread(() -> {
            String[] items = {"Room corner (where floor meets walls)", "Wall", "Floor", "Ceiling", "Object: cut it out and place it", "Undo my last mark"};
            int[] kinds = {1, 3, 2, 4, 0, 5};
            new android.app.AlertDialog.Builder(this)
                    .setTitle("What did you tap?")
                    .setItems(items, (d, which) -> glView.queueEvent(() -> renderer.applyTap(kinds[which])))
                    .setOnCancelListener(d -> glView.queueEvent(() -> renderer.applyTap(-1)))
                    .show();
        });
    }

    void toast(String msg) { runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()); }

    // ---- Ask Claude (optional, needs internet and the user's own API key) ----
    private static final String PREFS = "cinex", KEY = "claude_api_key";

    /** Live Claude: while scanning, every few seconds the newest photo goes to Claude to name what's in view. */
    private void toggleLiveClaude() {
        if (renderer.claudeLiveKey != null) {
            renderer.claudeLiveKey = null;
            renderer.log("claude live off");
            liveClaudeButton.setText("Claude live: off");
            return;
        }
        String key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
        if (key == null || key.isEmpty()) { askKey(this::toggleLiveClaude); return; }
        renderer.claudeLiveKey = key;
        renderer.log("claude live on");
        liveClaudeButton.setText("Claude live: on");
        Toast.makeText(this, "Claude now looks at the scan every few seconds and names what it sees (needs internet).", Toast.LENGTH_LONG).show();
    }

    private void askKey(Runnable then) {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("sk-ant-...");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Claude API key")
                .setMessage("Paste your key from console.anthropic.com. It's saved only on this phone. "
                        + "Claude gets scan photos to name objects. Long-press Ask Claude to change the key.")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String k = in.getText().toString().trim();
                    if (k.isEmpty()) return;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, k).apply();
                    if (renderer.claudeLiveKey != null) renderer.claudeLiveKey = k;
                    then.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askClaude(boolean changeKey) {
        String key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
        if (changeKey) { askKey(() -> { }); return; }
        if (key == null || key.isEmpty()) { askKey(() -> askClaude(false)); return; }
        claudeButton.setEnabled(false);
        Toast.makeText(this, "Asking Claude about the scan...", Toast.LENGTH_SHORT).show();
        glView.queueEvent(() -> renderer.askClaude(key, r -> runOnUiThread(() -> {
            claudeButton.setEnabled(true);
            Toast.makeText(this, "Claude named the objects. Its tips are at the top of the status.", Toast.LENGTH_LONG).show();
        }), err -> runOnUiThread(() -> {
            claudeButton.setEnabled(true);
            new android.app.AlertDialog.Builder(this).setTitle("Ask Claude failed").setMessage(err)
                    .setPositiveButton("OK", null).show();
        })));
    }

    private void exportScan() {
        exportButton.setEnabled(false);
        Toast.makeText(this, "Packing the scan (" + renderer.recordedFrames() + " depth frames)...", Toast.LENGTH_SHORT).show();
        glView.queueEvent(() -> renderer.exportScan(uri -> runOnUiThread(() -> {
            exportButton.setEnabled(true);
            long size = -1;
            try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r")) { if (fd != null) size = fd.getLength(); }
            catch (Exception ignored) { }
            if (size > 25L * 1024 * 1024) { bigExport(uri, size); return; }
            Toast.makeText(this, "Saved to Downloads/CineX. Pick Claude to send it.", Toast.LENGTH_LONG).show();
            shareScan(uri);
        }), err -> runOnUiThread(() -> {
            exportButton.setEnabled(true);
            Toast.makeText(this, "Export failed: " + err, Toast.LENGTH_LONG).show();
        })));
    }

    /** Over the chat upload limit: offer GitHub's upload page (a private draft release) instead. */
    private void bigExport(android.net.Uri uri, long size) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(String.format(java.util.Locale.US, "Scan is %.0f MB", size / 1048576.0))
                .setMessage("That's too big for chat. It's saved in Downloads/CineX. Open GitHub's upload page, "
                        + "drop the zip into \"Attach binaries\" (not the description box) and press Save draft.")
                .setPositiveButton("Open GitHub upload", (d, w) -> {
                    try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(
                            "https://github.com/reigokruus/shootlocal/releases/new?tag=scan-upload&title=Scan%20upload&prerelease=1"))); }
                    catch (Exception e) { Toast.makeText(this, "No browser: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("Share anyway", (d, w) -> shareScan(uri))
                .show();
    }

    private void shareScan(android.net.Uri uri) {
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
        send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(android.content.Intent.createChooser(send, "Send CineX scan")); }
        catch (Exception e) { Toast.makeText(this, "Saved, but sharing failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        glView = new GLSurfaceView(this);
        glView.setPreserveEGLContextOnPause(true);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        renderer = new ScanRenderer(this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x88000000);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int pad = dp(12);
        status.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        slp.topMargin = dp(36);
        FrameLayout chrome = new FrameLayout(this);
        scanChrome = chrome;
        chrome.addView(status, slp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        mainButton = new Button(this);
        mainButton.setText("Finish scan");
        mainButton.setOnClickListener(v -> onMainButton());
        topButton = new Button(this);
        topButton.setText("Top view");
        topButton.setVisibility(View.GONE);
        topButton.setOnClickListener(v -> {
            boolean top = renderer.toggleTopView();
            topButton.setText(top ? "3D view" : "Top view");
        });
        splatButton = new Button(this);
        splatButton.setText(renderer.styleLabel());
        splatButton.setOnClickListener(v -> splatButton.setText(renderer.cycleStyle()));
        depthButton = new Button(this);
        depthButton.setText("Depth AI");
        depthButton.setVisibility(View.GONE);
        depthButton.setOnClickListener(v -> glView.queueEvent(() -> {
            String label = renderer.toggleDepthAI();
            runOnUiThread(() -> depthButton.setText(label));
        }));
        bar.addView(depthButton);
        saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setVisibility(View.GONE);
        saveButton.setOnClickListener(v -> saveScan());
        bar.addView(saveButton);
        improveButton = new Button(this);
        improveButton.setText("Improve scan");
        improveButton.setVisibility(View.GONE);
        improveButton.setOnClickListener(v -> improveScan());
        bar.addView(improveButton);
        homeButton = new Button(this);
        homeButton.setText("Home");
        homeButton.setOnClickListener(v -> showHome());
        bar.addView(homeButton);
        cleanButton = new Button(this);
        cleanButton.setText("Clean up");
        cleanButton.setVisibility(View.GONE);
        cleanButton.setOnClickListener(v -> glView.queueEvent(() -> renderer.startCleanUp()));
        bar.addView(cleanButton);
        trainButton = new Button(this);
        trainButton.setText("Train splats");
        trainButton.setVisibility(View.GONE);
        trainButton.setOnClickListener(v -> glView.queueEvent(() -> {
            String label = renderer.toggleTraining();
            runOnUiThread(() -> trainButton.setText(label));
        }));
        bar.addView(trainButton);
        exportButton = new Button(this);
        exportButton.setText("Export");
        exportButton.setVisibility(View.GONE);
        exportButton.setOnClickListener(v -> exportScan());
        bar.addView(mainButton);

        bar.addView(topButton);
        bar.addView(splatButton);
        bar.addView(exportButton);
        claudeButton = new Button(this);
        claudeButton.setText("Ask Claude");
        claudeButton.setVisibility(View.GONE);
        claudeButton.setOnClickListener(v -> askClaude(false));
        claudeButton.setOnLongClickListener(v -> { askClaude(true); return true; }); // long press: change the key
        bar.addView(claudeButton);
        liveClaudeButton = new Button(this);
        liveClaudeButton.setText("Claude live: off");
        liveClaudeButton.setOnClickListener(v -> toggleLiveClaude());
        bar.addView(liveClaudeButton);
        Button logButton = new Button(this);
        logButton.setText("Copy log");
        logButton.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("CineX log", renderer.report()));
            Toast.makeText(this, "Diagnostics copied. Paste them into the chat.", Toast.LENGTH_SHORT).show();
        });
        planButton = new Button(this);
        planButton.setText("Plan shot");
        planButton.setVisibility(View.GONE);
        planButton.setOnClickListener(v -> glView.queueEvent(() -> {
            String label = renderer.cyclePlan();
            runOnUiThread(() -> planButton.setText(label));
        }));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        bar.removeView(logButton);
        Button cutButton = new Button(this);
        cutButton.setText("Show walls");
        cutButton.setVisibility(View.GONE);
        cutButton.setOnClickListener(v -> {
            boolean on = renderer.toggleCutaway();
            cutButton.setText(on ? "Show walls" : "Cut walls");
        });
        this.cutButton = cutButton;
        Button viewButton = new Button(this);
        viewButton.setText(renderer.viewModeLabel());
        viewButton.setVisibility(View.GONE);
        viewButton.setOnClickListener(v -> glView.queueEvent(() -> {
            String label = renderer.cycleViewMode();
            runOnUiThread(() -> viewButton.setText(label));
        }));
        this.viewButton = viewButton;
        row2.addView(planButton);
        row2.addView(viewButton);
        row2.addView(cutButton);
        Button sizeButton = new Button(this);
        sizeButton.setText("Room size");
        sizeButton.setOnClickListener(v -> askRoomSize());
        row2.addView(sizeButton);
        row2.addView(logButton);
        FlowRow bars = new FlowRow(this, dp(4));
        bars.setPadding(dp(6), dp(4), dp(6), dp(4));
        for (Button bt : new Button[]{homeButton, mainButton, saveButton, improveButton, depthButton, cleanButton, trainButton, planButton, viewButton, cutButton, topButton, splatButton, sizeButton, exportButton, claudeButton, liveClaudeButton, logButton}) {
            ((ViewGroup) bt.getParent()).removeView(bt);
            bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            bt.setAllCaps(false);
            bt.setMinHeight(0); bt.setMinimumHeight(0); bt.setMinWidth(0); bt.setMinimumWidth(0);
            bt.setPadding(dp(12), dp(8), dp(12), dp(8));
            bars.addView(bt);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.bottomMargin = dp(56);
        chrome.addView(bars, blp);
        root.addView(chrome, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(buildHome(), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scanChrome.setVisibility(View.GONE);

        // the cut-out model comes from Google Play services: ask for it now so the first tap works
        try {
            com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(this).installModules(
                    com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
                            .addApi(com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(
                                    new com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()))
                            .build());
        } catch (Throwable ignored) { }
        setContentView(root);

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom(d.getScaleFactor());
                return true;
            }
        });
        glView.setOnTouchListener((v, e) -> {
            if (!renderer.isViewing()) {
                // While scanning, a quick tap on a thing cuts it out of the photo and stands it in the room.
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downTime = e.getEventTime(); downX = e.getX(); downY = e.getY(); multiTouch = false; }
                if (e.getPointerCount() > 1) multiTouch = true;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !multiTouch && e.getEventTime() - downTime < 300
                        && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
                    float tx = e.getX(), ty = e.getY();
                    glView.queueEvent(() -> renderer.requestRescan(tx, ty));
                }
                return true;
            }
            scaleDetector.onTouchEvent(e);
            int pc = e.getPointerCount();
            float x = 0, y = 0;
            for (int i = 0; i < pc; i++) { x += e.getX(i); y += e.getY(i); }
            x /= pc; y /= pc;
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && pc == lastPointerCount) {
                float dx = x - lastX, dy = y - lastY;
                if (pc == 1) renderer.orbit(dx, dy);
                else renderer.pan(dx, dy);
            }
            lastX = x; lastY = y; lastPointerCount = pc;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downTime = e.getEventTime(); downX = e.getX(); downY = e.getY(); multiTouch = false;
            }
            if (pc > 1) multiTouch = true;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                lastPointerCount = 0;
                // A quick tap (not a drag) places the subject for the shot plan.
                if (!multiTouch && e.getEventTime() - downTime < 300
                        && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
                    float tx = e.getX(), ty = e.getY();
                    glView.queueEvent(() -> renderer.pickSubject(tx, ty));
                }
            }
            return true;
        });
        updateStatus("Starting...");
        refreshHome();
    }

    @Override
    public void onBackPressed() {
        if (!homeShown()) { showHome(); return; }
        super.onBackPressed();
    }

    private void onMainButton() {
        if (renderer.isViewing()) {
            // Rescan: a new scan from scratch
            glView.queueEvent(() -> renderer.startNewScan());
            scanUi();
            resumeSession();
        } else {
            glView.queueEvent(() -> {
                renderer.finishScan();
                runOnUiThread(() -> {
                    if (session != null) session.pause();
                    viewerUi();
                });
            });
        }
    }

    /** Buttons for scanning. */
    private void scanUi() {
        mainButton.setText("Finish scan");
        for (Button b : new Button[]{topButton, planButton, cutButton, viewButton, exportButton, claudeButton, trainButton, depthButton, cleanButton, saveButton, improveButton})
            b.setVisibility(View.GONE);
        topButton.setText("Top view");
        trainButton.setText("Train splats");
        depthButton.setText("Depth AI");
        planButton.setText("Plan shot");
    }

    /** Buttons for looking at a finished or opened scan. */
    private void viewerUi() {
        mainButton.setText("New scan");
        for (Button b : new Button[]{topButton, planButton, cutButton, viewButton, exportButton, claudeButton, trainButton, depthButton, cleanButton, saveButton})
            b.setVisibility(View.VISIBLE);
        improveButton.setVisibility(renderer.currentDir != null ? View.VISIBLE : View.GONE);
    }

    // ---------------- home screen and saved scans ----------------

    private FrameLayout home;
    private LinearLayout homeList;
    private View scanChrome; // status + buttons, hidden on the home screen

    private View buildHome() {
        home = new FrameLayout(this);
        home.setBackgroundColor(0xFF101216);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(40), dp(16), dp(24));
        TextView title = new TextView(this);
        title.setText("CineX");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Scan a room, plan the shot.");
        sub.setTextColor(0xFFAAB0BA);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setPadding(0, 0, 0, dp(16));
        col.addView(sub);
        Button newScan = new Button(this);
        newScan.setText("+  New scan");
        newScan.setAllCaps(false);
        newScan.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        newScan.setOnClickListener(v -> startNewScan());
        col.addView(newScan, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        TextView saved = new TextView(this);
        saved.setText("Saved scans");
        saved.setTextColor(Color.WHITE);
        saved.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        saved.setPadding(0, dp(24), 0, dp(8));
        col.addView(saved);
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeList);
        sv.addView(col);
        home.addView(sv);
        return home;
    }

    private void showHome() {
        if (session != null) session.pause();
        glView.queueEvent(() -> renderer.goHome());
        scanChrome.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        refreshHome();
    }

    private boolean homeShown() { return home != null && home.getVisibility() == View.VISIBLE; }

    private void hideHome() {
        home.setVisibility(View.GONE);
        scanChrome.setVisibility(View.VISIBLE);
    }

    private void refreshHome() {
        homeList.removeAllViews();
        new Thread(() -> {
            java.util.List<ScanStore.Entry> es = ScanStore.list(this);
            java.util.List<android.graphics.Bitmap> th = new java.util.ArrayList<>();
            for (ScanStore.Entry e : es) th.add(ScanStore.thumb(e));
            runOnUiThread(() -> {
                homeList.removeAllViews();
                if (es.isEmpty()) {
                    TextView t = new TextView(this);
                    t.setText("No saved scans yet. After a scan, tap Save to keep it here.");
                    t.setTextColor(0xFFAAB0BA);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    homeList.addView(t);
                    return;
                }
                LinearLayout row = null;
                for (int i = 0; i < es.size(); i++) {
                    if (i % 2 == 0) {
                        row = new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        homeList.addView(row);
                    }
                    View card = scanCard(es.get(i), th.get(i));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(4), dp(4), dp(4), dp(8));
                    row.addView(card, lp);
                }
                if (es.size() % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            });
        }).start();
    }

    private View scanCard(ScanStore.Entry e, android.graphics.Bitmap thumb) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFF1C2028);
        c.setPadding(dp(6), dp(6), dp(6), dp(8));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFF2A2F38);
        if (thumb != null) iv.setImageBitmap(thumb);
        c.addView(iv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        name.setSingleLine(true);
        c.addView(name);
        TextView info = new TextView(this);
        info.setText(android.text.format.DateFormat.format("d MMM, HH:mm", e.modified) + " · " + String.format(java.util.Locale.US, "%,d", e.points) + " pts"
                + (e.corners > 0 ? " · " + e.corners + " corners" : ""));
        info.setTextColor(0xFF9AA0AA);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(dp(2), 0, dp(2), 0);
        c.addView(info);
        c.setOnClickListener(v -> openScan(e));
        c.setOnLongClickListener(v -> { scanMenu(e); return true; });
        return c;
    }

    private void scanMenu(ScanStore.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(new String[]{"Open", "Rename", "Delete"}, (d, which) -> {
                    if (which == 0) openScan(e);
                    else if (which == 1) askName("Rename scan", e.name, n -> new Thread(() -> {
                        try { ScanStore.rename(e.dir, n); } catch (Exception ex) { toast("Rename failed: " + ex.getMessage()); }
                        runOnUiThread(this::refreshHome);
                    }).start());
                    else new android.app.AlertDialog.Builder(this)
                            .setMessage("Delete \"" + e.name + "\"? This can't be undone.")
                            .setPositiveButton("Delete", (d2, w2) -> { ScanStore.delete(e.dir); refreshHome(); })
                            .setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void askName(String title, String current, java.util.function.Consumer<String> then) {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setText(current);
        in.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    String n = in.getText().toString().trim();
                    then.accept(n.isEmpty() ? current : n);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startNewScan() {
        hideHome();
        glView.queueEvent(() -> renderer.startNewScan());
        scanUi();
        updateStatus("Scanning. Walk around the room slowly. Tap things to mark corners, walls, floor or objects.");
        resumeSession();
    }

    private void openScan(ScanStore.Entry e) {
        hideHome();
        if (session != null) session.pause();
        scanUi();
        mainButton.setText("New scan");
        glView.queueEvent(() -> renderer.loadScan(e.dir, () -> runOnUiThread(this::viewerUi)));
    }

    private void saveScan() {
        String def = renderer.currentName != null ? renderer.currentName
                : "Room " + android.text.format.DateFormat.format("d MMM HH:mm", System.currentTimeMillis());
        askName("Save scan as", def, name -> {
            updateStatus("Saving \"" + name + "\"...");
            File dir = renderer.currentDir;
            glView.queueEvent(() -> renderer.requestSave(name, dir, (out, err) -> runOnUiThread(() -> {
                if (out != null) {
                    toast("Saved \"" + name + "\"");
                    updateStatus("Saved \"" + name + "\". Improve scan adds a second pass that lines up with this one.");
                    improveButton.setVisibility(View.VISIBLE);
                } else updateStatus("Save failed: " + err);
            })));
        });
    }

    private void improveScan() {
        File dir = renderer.currentDir;
        if (dir == null) { toast("Save the scan first."); return; }
        glView.queueEvent(() -> {
            renderer.startImprove(dir);
            String hint = renderer.improveHint();
            runOnUiThread(() -> { scanUi(); updateStatus(hint); resumeSession(); });
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Depth AI finished: reset its button and the training button (the splats are rebuilt). */
    void depthDone() {
        runOnUiThread(() -> { depthButton.setText("Depth AI"); trainButton.setText("Train splats"); });
    }

    void updateStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session == null) {
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
                    return;
                }
                session = new Session(this);
                // The renderer picks the depth mode and falls back to another one if tracking never starts.
                renderer.setSession(session);
            } catch (Exception e) {
                Toast.makeText(this, "ARCore error: " + e, Toast.LENGTH_LONG).show();
                updateStatus("ARCore could not start: " + e.getMessage());
                return;
            }
        }
        renderer.sensors.start(this);
        if (!renderer.isViewing() && !homeShown()) resumeSession();
        glView.onResume();
    }

    /** Called from the GL thread when tracking is stuck: pause and resume the AR session. */
    void restartSession() {
        runOnUiThread(() -> {
            if (session == null || renderer.isViewing() || homeShown()) return;
            session.pause();
            resumeSession();
        });
    }

    private void resumeSession() {
        if (session == null) return;
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            updateStatus("Camera not available, try reopening the app.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
        if (session != null) session.pause();
        renderer.sensors.stop(this);
    }

    @Override
    protected void onDestroy() {
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
