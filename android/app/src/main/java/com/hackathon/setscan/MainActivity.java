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
    private NativePlanningUi nativePlanning;
    private Button catalogButton, scannedItemsButton, previewButton;
    private Session session;
    private boolean installRequested;
    private TextView status;
    private Button lightButton, saveButton, homeButton, improveButton, cleanButton, depthButton, trainButton, mainButton, topButton, splatButton, planButton, cutButton, viewButton, exportButton, claudeButton, liveClaudeButton;
    private long downTime;
    private float downX, downY;
    private boolean multiTouch;
    private ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private int lastPointerCount;
    private boolean lightDragging;
    private LinearLayout lightPanel;
    private android.widget.SeekBar powerBar, kelvinBar, heightBar, roomBar, expoBar;
    private TextView selLabel;
    private boolean syncingBars;

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
    void askTapKind(boolean cornersOnly) {
        runOnUiThread(() -> {
            String[] items = cornersOnly ? new String[]{"Room corner (where floor meets walls)", "Undo my last mark"}
                    : new String[]{"Room corner (where floor meets walls)", "Wall", "Floor", "Ceiling", "Object: cut it out and place it", "Undo my last mark"};
            int[] kinds = cornersOnly ? new int[]{1, 5} : new int[]{1, 3, 2, 4, 0, 5};
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
        Toast.makeText(this, "Packing the scan...", Toast.LENGTH_SHORT).show();
        glView.queueEvent(() -> renderer.exportScan(uri -> runOnUiThread(() -> {
            exportButton.setEnabled(true);
            long size = -1;
            try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r")) { if (fd != null) size = fd.getLength(); }
            catch (Exception ignored) { }
            if (size > 25L * 1024 * 1024) { bigExport(uri, size); return; }
            Toast.makeText(this, "Saved to Downloads/CineX. Pick an app to send it.", Toast.LENGTH_LONG).show();
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
        nativePlanning = new NativePlanningUi(this, glView, renderer);
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
        mainButton.setText("Done");
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
        lightButton = new Button(this);
        lightButton.setText("Lighting");
        lightButton.setVisibility(View.GONE);
        lightButton.setOnClickListener(v -> {
            // panel closed while lighting is still on: the button just brings the panel back
            if (renderer.lighting.enabled && lightPanel.getVisibility() != View.VISIBLE) { lightPanel.setVisibility(View.VISIBLE); lightingSelected(); return; }
            glView.queueEvent(() -> {
            String label = renderer.toggleLighting();
            runOnUiThread(() -> { lightButton.setText(label); lightPanel.setVisibility(renderer.lighting.enabled ? View.VISIBLE : View.GONE); lightingSelected(); applyUi(); });
            });
        });
        bar.addView(lightButton);
        objButton = new Button(this);
        objButton.setText("Objects");
        objButton.setVisibility(View.GONE);
        objButton.setOnClickListener(v -> setObjectMode(objPanel.getVisibility() != View.VISIBLE));
        bar.addView(objButton);
        catalogButton = new Button(this); catalogButton.setText("Catalog"); catalogButton.setOnClickListener(v -> nativePlanning.showCatalog()); bar.addView(catalogButton);
        scannedItemsButton = new Button(this); scannedItemsButton.setText("Scanned items"); scannedItemsButton.setOnClickListener(v -> nativePlanning.showScanned()); bar.addView(scannedItemsButton);
        previewButton = new Button(this); previewButton.setText("Plan / original scan"); previewButton.setOnClickListener(v -> glView.queueEvent(() -> {
            try { renderer.requirePlanningScan(); renderer.roomEdits.capture(renderer.room.boxes); renderer.planningPreview = !renderer.planningPreview; planningModeChanged(); toast(renderer.planningPreview ? "Planning shapes only; original scan unchanged" : "Original scan — hidden planning items reappear here"); }
            catch(Exception e) { toast(e.getMessage()); }
        })); bar.addView(previewButton);

        saveButton = new Button(this);
        saveButton.setText("Rename");
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
        exportButton.setText("Share");
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
        for (Button bt : new Button[]{homeButton, mainButton, lightButton, catalogButton, scannedItemsButton, previewButton, saveButton, improveButton, depthButton, cleanButton, trainButton, planButton, viewButton, cutButton, topButton, splatButton, sizeButton, exportButton, claudeButton, liveClaudeButton, logButton}) {
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
        sizeBtn = sizeButton; logBtn = logButton;
        try { debug = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("debug", false); } catch (Exception ignored) { }
        mainButton.setOnLongClickListener(v -> {
            debug = !debug;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("debug", debug).apply();
            toast(debug ? "Debug tools on" : "Debug tools off");
            applyUi();
            return true;
        });
        buildHud(chrome);
        exportButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1D6B86));
        exportButton.setTextColor(Color.WHITE);
        lightPanel = buildLightPanel();
        lightPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        llp.bottomMargin = dp(150);
        chrome.addView(lightPanel, llp);
        objPanel = buildObjPanel();
        objPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        olp.bottomMargin = dp(150);
        chrome.addView(objPanel, olp);
        // keep the panel just above the button rows, however many rows they wrap into
        bars.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = dp(56) + (b - t);
            if (llp.bottomMargin != want) { llp.bottomMargin = want; lightPanel.post(() -> lightPanel.setLayoutParams(llp)); }
            if (olp.bottomMargin != want) { olp.bottomMargin = want; objPanel.post(() -> objPanel.setLayoutParams(olp)); }
            if (procLp != null && procLp.bottomMargin != want + dp(8)) { procLp.bottomMargin = want + dp(8); procBox.post(() -> procBox.setLayoutParams(procLp)); }
        });
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
            if (objPanel.getVisibility() == View.VISIBLE && objectTouch(e)) return true;
            if (renderer.lighting.enabled) {
                // a finger that lands on a light or prop selects and drags it; anywhere else it orbits as usual
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) lightDragging = renderer.lightingDragStart(e.getX(), e.getY());
                if (lightDragging) {
                    if (e.getPointerCount() > 1) lightDragging = false;
                    else {
                        if (act == MotionEvent.ACTION_MOVE) renderer.lightingDragTo(e.getX(), e.getY());
                        else if (act == MotionEvent.ACTION_UP) { lightDragging = false; glView.queueEvent(renderer::lightingDragEnd); }
                        return true;
                    }
                }
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
        scanning = true;
        mainButton.setText("Done");
        applyUi();
        renderer.lighting.enabled = false;
        lightButton.setText("Lighting");
        if (lightPanel != null) lightPanel.setVisibility(View.GONE);
        if (objPanel != null) objPanel.setVisibility(View.GONE);
        topButton.setText("Top view");
        trainButton.setText("Train splats");
        depthButton.setText("Depth AI");
        planButton.setText("Plan shot");
    }

    /** Buttons for looking at a finished or opened scan. */
    private boolean gestureTipShown;
    private void viewerUi() {
        if (!gestureTipShown) { gestureTipShown = true; toast("Drag to look around · pinch to zoom · two fingers to pan"); }
        scanning = false;
        mainButton.setText("New scan");
        applyUi();
    }

    // ---------------- minimal UI: the scan shows only guidance and progress; tools are behind debug ----------------
    private boolean scanning = true, debug;
    private Button sizeBtn, logBtn;
    private LinearLayout hud, procBox;
    private FrameLayout.LayoutParams procLp;
    private TextView hudHint, hudSub, hudDetailPct, hudRoomPct, procLabel;
    private android.widget.ProgressBar hudDetail, hudRoom, procBar;

    /** Which buttons show: while scanning only Finish; in the viewer the everyday tools; everything else in debug mode (long-press the main button). */
    private void applyUi() {
        renderer.overlays = debug;
        Button[] viewerMain = {homeButton, exportButton, objButton, saveButton};
        lightButton.setVisibility(debug && !scanning ? View.VISIBLE : View.GONE); // lighting is parked for now (rk: just the model)
        for (Button b : new Button[]{catalogButton, scannedItemsButton, previewButton}) b.setVisibility(debug && !scanning ? View.VISIBLE : View.GONE); // Marje's planning tools: debug until rk decides
        Button[] tools = {depthButton, cleanButton, trainButton, viewButton, splatButton, sizeBtn, liveClaudeButton, logBtn, planButton, cutButton, topButton, claudeButton};
        for (Button b : viewerMain) b.setVisibility(scanning ? (debug && b == homeButton ? View.VISIBLE : View.GONE) : View.VISIBLE);
        improveButton.setVisibility(debug && !scanning && renderer.currentDir != null ? View.VISIBLE : View.GONE); // needs corner taps, which are off
        for (Button b : tools) {
            boolean scanTool = b == splatButton || b == sizeBtn || b == liveClaudeButton || b == logBtn;
            b.setVisibility(debug && (!scanning || scanTool) ? View.VISIBLE : View.GONE);
        }
        mainButton.setVisibility(View.VISIBLE);
        // the text panel is for debugging; testers see it only for the lighting readout
        status.setVisibility(debug || (!scanning && (renderer.lighting.enabled || renderer.planningPreview)) ? View.VISIBLE : View.GONE);
        if (hud != null) hud.setVisibility(scanning ? View.VISIBLE : View.GONE);
        if (procBox != null && scanning) procBox.setVisibility(View.GONE);
    }

    private android.widget.ProgressBar bar(int color) {
        android.widget.ProgressBar p = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        p.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        p.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        return p;
    }

    private LinearLayout barRow(String label, android.widget.ProgressBar p, TextView pct) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label); l.setTextColor(0xCCFFFFFF); l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        r.addView(l, new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(p, new LinearLayout.LayoutParams(0, dp(10), 1f));
        pct.setTextColor(Color.WHITE); pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); pct.setGravity(Gravity.END);
        r.addView(pct, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void buildHud(FrameLayout chrome) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xB0101418); bg.setCornerRadius(dp(14));
        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setBackground(bg);
        hud.setPadding(dp(16), dp(12), dp(16), dp(14));
        hudHint = new TextView(this);
        hudHint.setTextColor(Color.WHITE); hudHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        hudHint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hudHint.setText("Point at the room and move the phone slowly");
        hud.addView(hudHint);
        hudSub = new TextView(this);
        hudSub.setTextColor(0xCCFFFFFF); hudSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hudSub.setVisibility(View.GONE);
        hud.addView(hudSub);
        hudDetail = bar(0xFF33D17A); hudDetailPct = new TextView(this);
        hudRoom = bar(0xFF5BB3CF); hudRoomPct = new TextView(this);
        LinearLayout r1 = barRow("This spot", hudDetail, hudDetailPct), r2 = barRow("Room", hudRoom, hudRoomPct);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(10);
        hud.addView(r1, rp);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp2.topMargin = dp(6);
        hud.addView(r2, rp2);
        hud.setOnLongClickListener(v -> { mainButton.performLongClick(); return true; });
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        hp.topMargin = dp(40); hp.leftMargin = dp(12); hp.rightMargin = dp(12);
        chrome.addView(hud, hp);

        android.graphics.drawable.GradientDrawable bg2 = new android.graphics.drawable.GradientDrawable();
        bg2.setColor(0xD0101418); bg2.setCornerRadius(dp(12));
        procBox = new LinearLayout(this);
        procBox.setOrientation(LinearLayout.VERTICAL);
        procBox.setBackground(bg2);
        procBox.setPadding(dp(14), dp(10), dp(14), dp(12));
        procLabel = new TextView(this);
        procLabel.setTextColor(Color.WHITE); procLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        procBox.addView(procLabel);
        procBar = bar(0xFF5BB3CF);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        pp.topMargin = dp(8);
        procBox.addView(procBar, pp);
        procBox.setVisibility(View.GONE);
        procLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        procLp.bottomMargin = dp(150); procLp.leftMargin = dp(12); procLp.rightMargin = dp(12);
        chrome.addView(procBox, procLp);
    }

    private static final int[] HINT_COLORS = {0xFF33D17A, 0xFFF5C542, 0xFFFF6B5B};

    void planningModeChanged() {
        runOnUiThread(() -> {
            previewButton.setText(renderer.planningPreview ? "Show original scan" : "Show planning room");
            applyUi();
            if(renderer.planningPreview) updateStatus("Planning shapes only — approximate detected furniture and catalog size proxies. Original fused scan/photos unchanged; hidden surfaces are NOT reconstructed.");
        });
    }

    /** From the renderer, ~2x a second while scanning. level 0 ok, 1 hold on, 2 stop and stay here. */
    void updateHud(String hint, String sub, int level, float detail, float room, int points) {
        runOnUiThread(() -> {
            if (hud == null) return;
            hudHint.setText(hint);
            hudHint.setTextColor(level == 0 ? Color.WHITE : HINT_COLORS[Math.min(2, level)]);
            hudSub.setText(sub == null ? "" : sub);
            hudSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
            hudDetail.setProgress(Math.round(detail * 1000));
            hudDetail.setProgressTintList(android.content.res.ColorStateList.valueOf(detail >= ScanRenderer.SPOT_DONE ? HINT_COLORS[0] : detail >= 0.35f ? HINT_COLORS[1] : HINT_COLORS[2]));
            hudDetailPct.setText(Math.round(detail * 100) + "%");
            hudRoom.setProgress(Math.round(room * 1000));
            hudRoomPct.setText(Math.round(room * 100) + "%");
        });
    }

    /** The automatic processing after a scan: label + progress 0..1; null/negative hides it, 1 shows Done briefly. */
    void updateProcess(String label, float frac) {
        runOnUiThread(() -> {
            if (procBox == null) return;
            if (label == null || frac < 0 || frac >= 1) ProcessingService.stop(this); else ProcessingService.start(this);
            if (label == null || frac < 0) { procBox.setVisibility(View.GONE); return; }
            procBox.setVisibility(scanning ? View.GONE : View.VISIBLE);
            procLabel.setText(frac >= 1 ? "✓ Ready. Drag to look around, pinch to zoom" : label + "\nSaved already. You can look around while this runs.");
            procBar.setProgress(Math.round(Math.min(1f, frac) * 1000));
            if (frac >= 1) procBox.postDelayed(() -> { if (procBar.getProgress() >= 1000) procBox.setVisibility(View.GONE); }, 6000);
        });
    }

    /** The renderer saved the scan on its own (after Done, and again after clean-up). */
    private String lastSavedName;
    /** The Claude key saved on this phone, or null. */
    String claudeKey() {
        String k = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, null);
        return k == null || k.trim().isEmpty() ? null : k.trim();
    }

    private boolean askedOutletKey;
    /** Outlets are found by Claude: ask for the key once per app run. */
    void outletsNeedKey() {
        runOnUiThread(() -> {
            if (askedOutletKey || isFinishing()) return;
            askedOutletKey = true;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Find power outlets?")
                    .setMessage("Claude can look through this scan's photos and mark every power outlet in the room. It needs your Claude API key.")
                    .setPositiveButton("Add key", (d, w) -> askKey(() -> glView.queueEvent(() -> renderer.findOutlets(false))))
                    .setNegativeButton("Not now", null)
                    .show();
        });
    }

    void outletsFound(int n, boolean ok) {
        runOnUiThread(() -> {
            if (!ok) toast(n > 0 ? "Outlet search stopped early (" + n + " found). Check the internet." : "Couldn't search for power outlets. Check the internet.");
            else toast(n == 0 ? "No power outlets found in the photos." : "⚡ Found " + n + " power outlet" + (n == 1 ? "" : "s") + ". They're marked in orange.");
        });
    }

    void autoSaved(String name, String err) {
        runOnUiThread(() -> {
            if (name == null) { toast("Couldn't save the scan: " + err); return; }
            if (!scanning && debug) improveButton.setVisibility(View.VISIBLE);
            if (!scanning && !name.equals(lastSavedName)) { lastSavedName = name; toast("Saved as \"" + name + "\". Tap Rename to change it."); }
        });
    }

    void trainingLabel(String l) { runOnUiThread(() -> trainButton.setText(l)); }

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
        Button browseCatalog = new Button(this); browseCatalog.setText("Browse item catalog (offline)"); browseCatalog.setOnClickListener(v -> nativePlanning.showCatalog()); col.addView(browseCatalog);
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
                    t.setText("No scans yet. Scans are saved here automatically.");
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
                    if (debug) improveButton.setVisibility(View.VISIBLE);
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
            runOnUiThread(() -> { scanUi(); updateStatus(hint); Toast.makeText(this, hint, Toast.LENGTH_LONG).show(); resumeSession(); });
        });
    }

    // ---------------- objects: remove / move / put back / catalogue (core feature) ----------------
    private Button objButton, objRemoveBtn;
    private LinearLayout objPanel;
    private TextView objLabel;
    private boolean objDragging, objMoved;
    private float objLastX, objLastY, objDownX, objDownY;
    private long objDownT;

    /** A catalogue item was just placed: open the Objects panel on it. */
    void showPlacedObject() { if (objPanel == null) return; objPanel.setVisibility(View.VISIBLE); if (lightPanel != null) lightPanel.setVisibility(View.GONE); refreshObjPanel(); }

    private void setObjectMode(boolean on) {
        objPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && lightPanel != null) lightPanel.setVisibility(View.GONE);
        glView.queueEvent(() -> { String t = renderer.objectsOn(on); runOnUiThread(() -> objPanelText(t)); });
    }

    private void refreshObjPanel() { glView.queueEvent(() -> { String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }); }

    private void objPanelText(String t) {
        objLabel.setText(t);
        SceneObjects.Obj o = renderer.objects.sel();
        objRemoveBtn.setText(o == null ? "Remove" : o.removed || o.moved() ? "Put back" : "Remove");
    }

    /** Object mode touches: a finger on the selected object drags it; a tap selects; everything else orbits as usual. */
    private boolean objectTouch(MotionEvent e) {
        int act = e.getActionMasked();
        if (e.getPointerCount() > 1) { objDragging = false; return false; }
        if (act == MotionEvent.ACTION_DOWN) {
            objDownX = objLastX = e.getX(); objDownY = objLastY = e.getY(); objDownT = e.getEventTime(); objMoved = false;
            int hit = renderer.objectHit(e.getX(), e.getY());
            objDragging = hit >= 0 && hit == renderer.objects.selected;
            if (!objDragging) return false;
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE && objDragging) {
            float dx = e.getX() - objLastX, dy = e.getY() - objLastY;
            objLastX = e.getX(); objLastY = e.getY();
            if (Math.hypot(e.getX() - objDownX, e.getY() - objDownY) > dp(6)) objMoved = true;
            glView.queueEvent(() -> renderer.objectDrag(dx, dy));
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            boolean tap = e.getEventTime() - objDownT < 300 && Math.hypot(e.getX() - objDownX, e.getY() - objDownY) < dp(12);
            boolean wasDrag = objDragging;
            objDragging = false;
            if (tap) {
                int hit = renderer.objectHit(e.getX(), e.getY());
                renderer.objects.selected = hit;
                refreshObjPanel();
                lastPointerCount = 0;
                return true;
            }
            if (wasDrag) { refreshObjPanel(); lastPointerCount = 0; return true; }
        }
        return false;
    }

    private LinearLayout buildObjPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xCC101216);
        p.setPadding(dp(10), dp(6), dp(10), dp(6));
        objLabel = new TextView(this);
        objLabel.setTextColor(Color.WHITE);
        objLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(objLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button close = smallButton("✕ Done");
        close.setOnClickListener(v -> setObjectMode(false));
        head.addView(close);
        p.addView(head);
        FlowRow row = new FlowRow(this, dp(4));
        objRemoveBtn = smallButton("Remove");
        objRemoveBtn.setOnClickListener(v -> glView.queueEvent(() -> { renderer.objectRemoveToggle(); String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }));
        row.addView(objRemoveBtn);
        Button turn = smallButton("Turn 15°");
        turn.setOnClickListener(v -> glView.queueEvent(() -> { renderer.objectTurn(); String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }));
        row.addView(turn);
        Button removed = smallButton("Removed…");
        removed.setOnClickListener(v -> showRemoved());
        row.addView(removed);
        Button save = smallButton("Save to catalogue");
        save.setOnClickListener(v -> saveObjectToCatalog());
        row.addView(save);
        Button cat = smallButton("Catalogue…");
        cat.setOnClickListener(v -> showCatalog());
        row.addView(cat);
        p.addView(row);
        return p;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        return b;
    }

    private void showRemoved() {
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < renderer.objects.list.size(); i++) {
            SceneObjects.Obj o = renderer.objects.list.get(i);
            if (o.removed) { idx.add(i); names.add(o.name + " · " + o.size()); }
        }
        if (idx.isEmpty()) { toast("Nothing removed yet"); return; }
        new android.app.AlertDialog.Builder(this).setTitle("Put back")
                .setItems(names.toArray(new String[0]), (d, w) -> { int k = idx.get(w); glView.queueEvent(() -> { renderer.objectPutBack(k); String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }); })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveObjectToCatalog() {
        SceneObjects.Obj o = renderer.objects.sel();
        if (o == null) { toast("Tap an object first"); return; }
        android.widget.EditText in = new android.widget.EditText(this);
        in.setText(o.name); in.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this).setTitle("Save to catalogue").setMessage(o.size()).setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String name = in.getText().toString().trim().isEmpty() ? o.name : in.getText().toString().trim();
                    o.name = name;
                    String from = renderer.currentName;
                    new Thread(() -> {
                        try { SceneObjects.saveToCatalog(this, o, name, from); toast("Saved \"" + name + "\" to the catalogue"); }
                        catch (Exception e) { toast("Couldn't save: " + e.getMessage()); }
                    }).start();
                    refreshObjPanel();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showCatalog() {
        java.util.List<SceneObjects.Item> items = SceneObjects.listCatalog(this);
        if (items.isEmpty()) { toast("The catalogue is empty. Select an object and tap Save to catalogue."); return; }
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            SceneObjects.Item it = items.get(i);
            names[i] = String.format(java.util.Locale.US, "%s · %.0f × %.0f × %.0f cm%s", it.name, it.w * 100, it.d * 100, it.h * 100, it.from.isEmpty() ? "" : "\nfrom " + it.from);
        }
        new android.app.AlertDialog.Builder(this).setTitle("Catalogue: tap to place")
                .setItems(names, (d, w) -> {
                    SceneObjects.Item it = items.get(w);
                    glView.queueEvent(() -> { renderer.objectPlace(it); String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); });
                })
                .setNegativeButton("Close", null).show();
    }

    private LinearLayout buildLightPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xCC101216);
        p.setPadding(dp(10), dp(6), dp(10), dp(6));
        selLabel = new TextView(this);
        selLabel.setTextColor(Color.WHITE);
        selLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(selLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(this);
        close.setText("✕ Close"); close.setAllCaps(false); close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        close.setMinHeight(0); close.setMinimumHeight(0); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setPadding(dp(10), dp(4), dp(10), dp(4));
        close.setOnClickListener(v -> { p.setVisibility(View.GONE); toast("Lights stay on. Tap the Lighting button to open the panel again."); });
        head.addView(close);
        p.addView(head);
        FlowRow row = new FlowRow(this, dp(4));
        String[] names = {"+ Light", "+ Person", "+ Seated", "+ Table", "+ Chair", "Type", "On/Off", "Delete", "Placed details"};
        for (int i = 0; i < names.length; i++) {
            Button b = new Button(this);
            b.setText(names[i]); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
            b.setPadding(dp(10), dp(4), dp(10), dp(4));
            final int which = i;
            b.setOnClickListener(v -> lightAction(which));
            row.addView(b);
        }
        p.addView(row);
        powerBar = slider(p, "Power", 100);
        kelvinBar = slider(p, "Colour temp", 100);
        heightBar = slider(p, "Height", 100);
        roomBar = slider(p, "Room light", 100);
        expoBar = slider(p, "Exposure", 60);
        return p;
    }

    private android.widget.SeekBar slider(LinearLayout parent, String label, int max) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(0xFFCCCCCC); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        r.addView(t, new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(max);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) { if (user && !syncingBars) sliderChanged(); }
            public void onStartTrackingTouch(android.widget.SeekBar b) { }
            public void onStopTrackingTouch(android.widget.SeekBar b) { glView.queueEvent(() -> renderer.lightingStatus()); }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, dp(32), 1f));
        parent.addView(r);
        return sb;
    }

    private void sliderChanged() {
        Lighting L = renderer.lighting;
        synchronized (L) {
            if (L.selected >= 0 && L.selected < L.lights.size()) {
                Lighting.Light l = L.lights.get(L.selected);
                l.dimmer = powerBar.getProgress() / 100f;
                l.kelvin = 2700 + kelvinBar.getProgress() * 38f; // 2700 .. 6500 K
                l.y = L.floorY + 0.3f + heightBar.getProgress() * 0.03f; // 0.3 .. 3.3 m
            }
            L.roomDimmer = roomBar.getProgress() / 100f;
            L.exposureEv = (expoBar.getProgress() - 30) / 10f; // -3 .. +3 EV
        }
        selLabel.setText(selectedText());
    }

    private String selectedText() {
        Lighting L = renderer.lighting;
        synchronized (L) {
            if (L.selected >= 0 && L.selected < L.lights.size()) {
                Lighting.Light l = L.lights.get(L.selected);
                return String.format(java.util.Locale.US, "%s (%s): %d%%, %.0f K, %.1f m high, %s", l.name, l.type.label, Math.round(l.dimmer * 100), l.kelvin, l.y - L.floorY, l.on ? "on" : "off");
            }
            return "Selected: " + L.selectedLabel() + ". Drag it on the floor to move it.";
        }
    }

    /** Called when a light or prop gets selected: put its values on the sliders. */
    void lightingSelected() {
        runOnUiThread(() -> {
            if (powerBar == null) return;
            Lighting L = renderer.lighting;
            syncingBars = true;
            synchronized (L) {
                boolean isLight = L.selected >= 0 && L.selected < L.lights.size();
                if (isLight) {
                    Lighting.Light l = L.lights.get(L.selected);
                    powerBar.setProgress(Math.round(l.dimmer * 100));
                    kelvinBar.setProgress(Math.round((l.kelvin - 2700) / 38f));
                    heightBar.setProgress(Math.round((l.y - L.floorY - 0.3f) / 0.03f));
                }
                powerBar.setEnabled(isLight); kelvinBar.setEnabled(isLight); heightBar.setEnabled(isLight);
                roomBar.setProgress(Math.round(L.roomDimmer * 100));
                expoBar.setProgress(Math.round(L.exposureEv * 10 + 30));
            }
            syncingBars = false;
            selLabel.setText(selectedText());
        });
    }

    private void lightAction(int which) {
        if(which == 8) { nativePlanning.editSelectedPlacement(); return; }
        Lighting L = renderer.lighting;
        synchronized (L) {
            float[] face = L.subjectFace();
            float cx = face != null ? face[0] : 0, cz = face != null ? face[2] : 0;
            switch (which) {
                case 0: {
                    if (L.lights.size() >= 4) { toast("Up to 4 lights for now."); break; }
                    Lighting.Light l = new Lighting.Light();
                    l.type = Lighting.Type.PANEL; l.beamDeg = l.type.beamDeg;
                    l.name = new String[]{"Key light", "Fill", "Back light", "Light 4"}[Math.min(3, L.lights.size())];
                    double a = Math.toRadians(60 + 90 * L.lights.size());
                    l.x = cx + 1.5f * (float) Math.cos(a); l.z = cz + 1.5f * (float) Math.sin(a); l.y = L.floorY + 1.9f;
                    L.lights.add(l);
                    L.selected = L.lights.size() - 1;
                    break;
                }
                case 1: case 2: case 3: case 4: {
                    Lighting.PropKind k = which == 1 ? Lighting.PropKind.PERSON_STANDING : which == 2 ? Lighting.PropKind.PERSON_SEATED : which == 3 ? Lighting.PropKind.TABLE : Lighting.PropKind.CHAIR;
                    float ox = L.props.isEmpty() ? 0 : 0.9f;
                    L.props.add(new Lighting.Prop(k, cx + ox, cz + (which == 3 ? 0.6f : 0), 0));
                    L.selected = L.lights.size() + L.props.size() - 1;
                    break;
                }
                case 5:
                    if (L.selected >= 0 && L.selected < L.lights.size()) {
                        Lighting.Light l = L.lights.get(L.selected);
                        l.type = Lighting.Type.values()[(l.type.ordinal() + 1) % Lighting.Type.values().length];
                        l.beamDeg = l.type.beamDeg;
                        if (l.type == Lighting.Type.PRACTICAL) l.kelvin = 2900;
                    } else if (L.selected >= L.lights.size()) {
                        Lighting.Prop p = L.props.get(L.selected - L.lights.size());
                        p.yawDeg += 45; // turn tables and chairs
                    }
                    break;
                case 6:
                    if (L.selected >= 0 && L.selected < L.lights.size()) L.lights.get(L.selected).on = !L.lights.get(L.selected).on;
                    break;
                case 7:
                    if (L.selected >= 0 && L.selected < L.lights.size()) L.lights.remove(L.selected);
                    else if (L.selected >= L.lights.size() && L.selected - L.lights.size() < L.props.size()) L.props.remove(L.selected - L.lights.size());
                    L.selected = L.lights.isEmpty() && L.props.isEmpty() ? -1 : 0;
                    break;
            }
        }
        lightingSelected();
        glView.queueEvent(() -> { renderer.lightingStatus(); renderer.planningChanged(); });
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
        renderer.paused = false;
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
        renderer.paused = true;
        glView.onPause();
        if (session != null) session.pause();
        renderer.sensors.stop(this);
    }

    @Override
    protected void onDestroy() {
        if (nativePlanning != null) nativePlanning.close();
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == NativePlanningUi.IMPORT_CATALOG && resultCode == RESULT_OK && data != null && data.getData() != null) nativePlanning.importUri(data.getData());
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
