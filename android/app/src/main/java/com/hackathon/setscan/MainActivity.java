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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private Button mainButton, topButton, splatButton, planButton, cutButton, viewButton, exportButton;
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

    private void exportScan() {
        exportButton.setEnabled(false);
        Toast.makeText(this, "Packing the scan (" + renderer.recordedFrames() + " depth frames)...", Toast.LENGTH_SHORT).show();
        glView.queueEvent(() -> renderer.exportScan(uri -> runOnUiThread(() -> {
            exportButton.setEnabled(true);
            Toast.makeText(this, "Saved to Downloads/CineX. Pick Claude to send it.", Toast.LENGTH_LONG).show();
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(android.content.Intent.createChooser(send, "Send CineX scan")); }
            catch (Exception e) { Toast.makeText(this, "Saved, but sharing failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }), err -> runOnUiThread(() -> {
            exportButton.setEnabled(true);
            Toast.makeText(this, "Export failed: " + err, Toast.LENGTH_LONG).show();
        })));
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
        root.addView(status, slp);

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
        exportButton = new Button(this);
        exportButton.setText("Export");
        exportButton.setVisibility(View.GONE);
        exportButton.setOnClickListener(v -> exportScan());
        bar.addView(mainButton);
        bar.addView(topButton);
        bar.addView(splatButton);
        bar.addView(exportButton);
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
        viewButton.setText("View: both");
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
        LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.VERTICAL);
        android.widget.HorizontalScrollView row2Scroll = new android.widget.HorizontalScrollView(this);
        row2Scroll.setHorizontalScrollBarEnabled(false);
        row2Scroll.addView(row2);
        bars.addView(row2Scroll);
        bars.addView(bar);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.bottomMargin = dp(56);
        root.addView(bars, blp);

        setContentView(root);

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom(d.getScaleFactor());
                return true;
            }
        });
        glView.setOnTouchListener((v, e) -> {
            if (!renderer.isViewing()) return false;
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
    }

    private void onMainButton() {
        if (renderer.isViewing()) {
            // Rescan
            glView.queueEvent(() -> renderer.reset());
            mainButton.setText("Finish scan");
            topButton.setVisibility(View.GONE);
            topButton.setText("Top view");
            planButton.setVisibility(View.GONE);
            cutButton.setVisibility(View.GONE);
            viewButton.setVisibility(View.GONE);
            exportButton.setVisibility(View.GONE);
            planButton.setText("Plan shot");
            resumeSession();
        } else {
            glView.queueEvent(() -> {
                renderer.finishScan();
                runOnUiThread(() -> {
                    if (session != null) session.pause();
                    mainButton.setText("Rescan");
                    topButton.setVisibility(View.VISIBLE);
                    planButton.setVisibility(View.VISIBLE);
                    cutButton.setVisibility(View.VISIBLE);
                    viewButton.setVisibility(View.VISIBLE);
                    exportButton.setVisibility(View.VISIBLE);
                });
            });
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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
        if (!renderer.isViewing()) resumeSession();
        glView.onResume();
    }

    /** Called from the GL thread when tracking is stuck: pause and resume the AR session. */
    void restartSession() {
        runOnUiThread(() -> {
            if (session == null || renderer.isViewing()) return;
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
