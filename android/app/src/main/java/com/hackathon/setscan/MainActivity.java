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
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;

public class MainActivity extends Activity {
    private static final int CAMERA_REQ = 1;

    private GLSurfaceView glView;
    private ScanRenderer renderer;
    private Session session;
    private boolean installRequested;
    private TextView status;
    private Button mainButton, topButton, splatButton;
    private ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private int lastPointerCount;

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
        splatButton.setText("Dots");
        splatButton.setOnClickListener(v -> {
            boolean on = renderer.toggleSplats();
            splatButton.setText(on ? "Dots" : "Splats");
        });
        bar.addView(mainButton);
        bar.addView(topButton);
        bar.addView(splatButton);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.bottomMargin = dp(56);
        root.addView(bar, blp);

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
            if (e.getActionMasked() == MotionEvent.ACTION_UP) lastPointerCount = 0;
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
            resumeSession();
        } else {
            glView.queueEvent(() -> {
                renderer.finishScan();
                runOnUiThread(() -> {
                    if (session != null) session.pause();
                    mainButton.setText("Rescan");
                    topButton.setVisibility(View.VISIBLE);
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
                Config config = new Config(session);
                boolean depth = false;
                // Smoothed (AUTOMATIC) depth gives a value for every pixel, which is much denser than raw depth.
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                    depth = true;
                }
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                session.configure(config);
                renderer.setSession(session, depth,
                        false);
            } catch (Exception e) {
                Toast.makeText(this, "ARCore error: " + e, Toast.LENGTH_LONG).show();
                updateStatus("ARCore could not start: " + e.getMessage());
                return;
            }
        }
        if (!renderer.isViewing()) resumeSession();
        glView.onResume();
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
