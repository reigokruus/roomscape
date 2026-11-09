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
    private PilotIntakeUi pilotIntake;
    private Button catalogButton, scannedItemsButton, previewButton;
    private Session session;
    private boolean installRequested;
    private TextView status;
    private Button refineButton;
    private Button undoButton, removePersonButton, interviewButton;
    private Button outletButton;
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
                    updateSpend();
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

    static final int IMPORT_RMSC = 7301;

    /** Sends scans to, and receives them from, RoomScape phones on the same Wi-Fi (see Nearby). */
    Nearby nearby;

    /** A scan arrived from a nearby phone and was imported. Any thread. */
    void nearbyReceived(File dir, String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            refreshHomeIfShown();
        });
    }

    /** Home's scan list again (a scan arrived, synced or became shared). */
    void refreshHomeIfShown() { if (homeShown()) refreshHome(); }

    /** Share: send to a nearby phone, or share the .rmsc file through another app. */
    private void shareMenu() {
        File dir = renderer.currentDir;
        if (dir == null) { toast("The scan saves itself a moment after Done. Try Share again in a few seconds."); return; }
        new android.app.AlertDialog.Builder(this).setTitle("Share room")
                .setItems(new String[]{"Send to nearby phone", "Save to device…", "Share file (.rmsc)"}, (d, which) -> {
                    if (which == 0) nearby.showSendDialog(dir); else if (which == 1) saveRmsc(); else shareRmsc();
                }).show();
    }

    static final int SAVE_RMSC = 7302;
    private File saveRmscDir;

    /** Save to device: the system's save dialog, then the .rmsc is written where the user picked. */
    private void saveRmsc() {
        File dir = renderer.currentDir;
        if (dir == null) { toast("The scan saves itself a moment after Done. Try again in a few seconds."); return; }
        saveRmscDir = dir;
        String name = renderer.currentName != null ? renderer.currentName : dir.getName();
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType(Rmsc.MIME);
        i.putExtra(android.content.Intent.EXTRA_TITLE, name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".rmsc");
        try { startActivityForResult(i, SAVE_RMSC); } catch (Exception e) { toast("No save dialog on this phone: " + e.getMessage()); }
    }

    private void saveRmscTo(android.net.Uri uri) {
        File dir = saveRmscDir;
        if (uri == null || dir == null) return;
        toast("Saving the room...");
        new Thread(() -> {
            File tmp = null;
            try {
                tmp = Rmsc.exportToFile(this, dir);
                try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                     java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new Exception("couldn't open the file");
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                String where = savedPlace(uri);
                runOnUiThread(() -> toast("Saved: " + where));
            } catch (Throwable e) {
                runOnUiThread(() -> toast("Couldn't save: " + e.getMessage()));
            } finally { if (tmp != null) tmp.delete(); }
        }).start();
    }

    /** "Download/workroom.rmsc" where the provider says so, else just the file name. */
    private String savedPlace(android.net.Uri uri) {
        try {
            String id = android.provider.DocumentsContract.getDocumentId(uri);
            int c = id.indexOf(':');
            if (c >= 0 && id.substring(c + 1).contains("/")) return id.substring(c + 1);
        } catch (Exception ignored) { }
        try (android.database.Cursor cur = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return "the folder you picked";
    }

    /** Share: the saved scan as a .rmsc package (opens on the iPhone app, the web viewer, or another Android). */
    private void shareRmsc() {
        File dir = renderer.currentDir;
        if (dir == null) { toast("The scan saves itself a moment after Done. Try Share again in a few seconds."); return; }
        exportButton.setEnabled(false);
        Toast.makeText(this, "Packing the room...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                android.net.Uri uri = Rmsc.export(this, dir);
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    send.setType(Rmsc.MIME);
                    send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                    send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    toast("Saved to Downloads/RoomScape. Pick where to send it.");
                    android.content.Intent chooser = android.content.Intent.createChooser(send, "Share room (.rmsc)");
                    // Not RoomScape itself: it would only import the room again.
                    chooser.putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS,
                            new android.content.ComponentName[]{new android.content.ComponentName(this, MainActivity.class)});
                    try { startActivity(chooser); }
                    catch (Exception e) { toast("Saved, but sharing failed: " + e.getMessage()); }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> { exportButton.setEnabled(true); toast("Share failed: " + e.getMessage()); });
            }
        }).start();
    }

    /** Pick a .rmsc file to import. */
    private void pickRmsc() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, IMPORT_RMSC); } catch (Exception e) { toast("No file picker: " + e.getMessage()); }
    }

    /** Import a .rmsc as a new saved scan and open it. */
    private void importRmsc(android.net.Uri uri) {
        if (uri == null) return;
        toast("Importing the room...");
        new Thread(() -> {
            try {
                File dir = Rmsc.importFrom(this, uri);
                runOnUiThread(() -> {
                    toast("Room imported.");
                    refreshHome();
                    for (ScanStore.Entry e : ScanStore.list(this)) if (e.dir.equals(dir)) { openScan(e); break; }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> new android.app.AlertDialog.Builder(this).setTitle("Couldn't import the room")
                        .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show());
            }
        }).start();
    }

    /** A .rmsc opened from another app (Files, a chat, the browser). */
    private void handleIncoming(android.content.Intent intent) {
        if (intent == null) return;
        android.net.Uri uri = null;
        if (android.content.Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (android.content.Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
        if (uri == null) return;
        intent.setAction(null); // don't import twice on rotation
        final android.net.Uri u = uri;
        importRmsc(u);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
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
                            "https://github.com/reigokruus/roomscape/releases/new?tag=scan-upload&title=Scan%20upload&prerelease=1"))); }
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
        nearby = new Nearby(this);
        SyncInfo.app = getApplicationContext();
        SyncInfo.onSharedChange = () -> nearby.syncSoon(); // a change to a shared scan goes to the other phones
        glView.setRenderer(renderer);
        nativePlanning = new NativePlanningUi(this, glView, renderer);
        pilotIntake = new PilotIntakeUi(this);
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
        improveButton.setText("Continue scan");
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
        refineButton = new Button(this);
        refineButton.setText("Refine");
        refineButton.setVisibility(View.GONE);
        refineButton.setOnClickListener(v -> glView.queueEvent(() -> { String m = renderer.refineAgain(); toast(m); }));
        bar.addView(refineButton);
        undoButton = new Button(this);
        undoButton.setText("Undo");
        undoButton.setVisibility(View.GONE);
        undoButton.setOnClickListener(v -> glView.queueEvent(() -> { String m = renderer.undo(); toast(m); }));
        bar.addView(undoButton);
        removePersonButton = new Button(this);
        removePersonButton.setText("Remove person");
        removePersonButton.setVisibility(View.GONE);
        removePersonButton.setOnClickListener(v -> { glView.queueEvent(() -> renderer.demoTool = 1); toast("Tap the person you want removed"); });
        bar.addView(removePersonButton);
        interviewButton = new Button(this);
        interviewButton.setText("Interview");
        interviewButton.setVisibility(View.GONE);
        interviewButton.setOnClickListener(v -> glView.queueEvent(() -> toast(renderer.interviewToggle())));
        bar.addView(interviewButton);
        exportButton = new Button(this);
        exportButton.setText("Share");
        exportButton.setVisibility(View.GONE);
        exportButton.setOnClickListener(v -> shareMenu());
        exportButton.setOnLongClickListener(v -> { exportScan(); return true; }); // the raw debug zip (depth frames, sensors)
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
        for (Button bt : new Button[]{homeButton, mainButton, lightButton, objButton, catalogButton, scannedItemsButton, previewButton, saveButton, improveButton, depthButton, cleanButton, trainButton, planButton, viewButton, cutButton, topButton, splatButton, sizeButton, refineButton, undoButton, removePersonButton, interviewButton, exportButton, claudeButton, liveClaudeButton, logButton}) {
            if (bt.getParent() != null) ((ViewGroup) bt.getParent()).removeView(bt);
            bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            bt.setAllCaps(false);
            bt.setMinHeight(0); bt.setMinimumHeight(0); bt.setMinWidth(0); bt.setMinimumWidth(0);
            bt.setPadding(dp(12), dp(8), dp(12), dp(8));
            Ios.pill(bt);
            // In the viewer these live in the top bar (Back, Share) and under the 3D view (Continue scan), like iOS.
            if (bt != homeButton && bt != exportButton && bt != improveButton && bt != lightButton && bt != objButton) bars.addView(bt);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.bottomMargin = dp(56);
        chrome.addView(bars, blp);
        toolBars = bars; toolBarsLp = blp;
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
        buildViewerChrome(chrome);
        // Lighting and Objects bars are part of the panel under the 3D view (its first card), not floating over it.
        lightPanel = buildLightPanel();
        lightPanel.setVisibility(View.GONE);
        objPanel = buildObjPanel();
        objPanel.setVisibility(View.GONE);
        for (LinearLayout panel : new LinearLayout[]{objPanel, lightPanel}) {
            android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
            cb.setColor(Ios.CARD); cb.setCornerRadius(dp(Ios.RADIUS_DP));
            panel.setBackground(cb);
            panel.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = dp(12);
            viewerBottom.addView(panel, 0, cp);
        }
        // While Objects or Lighting is open, the panel shows only its bar (and Enter the room), as on the iPhone: the
        // rest (Continue scan, ROOM, CAPTURE) is hidden and comes back as it was when the bar closes.
        viewerBottom.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            boolean on = objPanel.getVisibility() == View.VISIBLE || lightPanel.getVisibility() == View.VISIBLE;
            for (int i = 0; i < viewerBottom.getChildCount(); i++) {
                View c = viewerBottom.getChildAt(i);
                if (c == objPanel || c == lightPanel || c == enterRoomButton) continue;
                if (on) {
                    if (c.getVisibility() != View.GONE) { panelHidden.put(c, c.getVisibility()); c.setVisibility(View.GONE); }
                } else if (panelHidden.containsKey(c)) {
                    c.setVisibility(panelHidden.remove(c));
                }
            }
        });
        // The 3D view ends where the panel starts, also when a bar appears in it or goes.
        viewerPanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t == ob - ot) return;
            v.post(() -> {
                FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glView.getLayoutParams();
                int want = !scanning && walkState == 0 ? viewerPanel.getHeight() : 0;
                if (gp.bottomMargin != want) { gp.bottomMargin = want; glView.setLayoutParams(gp); }
            });
        });
        bars.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = dp(56) + (b - t);
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
        spendView = new TextView(this);
        // Small and quiet, like a caption: grey on a faint pill.
        spendView.setTextColor(Ios.SECONDARY); spendView.setTextSize(11);
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(0x801C1C1E); sbg.setCornerRadius(dp(10));
        spendView.setBackground(sbg); spendView.setPadding(dp(8), dp(3), dp(8), dp(3));
        spendView.setOnClickListener(v -> toast("What Claude has cost on your API key: this session, and in total on this phone (estimate)."));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        sp.topMargin = dp(38); sp.rightMargin = dp(14);
        root.addView(spendView, sp);
        ClaudeHelper.onSpend = () -> runOnUiThread(this::updateSpend);
        updateSpend();
        setContentView(root);

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (renderer.walking) renderer.walkZoom(d.getScaleFactor()); else renderer.zoom(d.getScaleFactor());
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
            if (walkTouch(e)) return true;
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
        handleIncoming(getIntent());
    }

    @Override
    public void onBackPressed() {
        if (walkState != 0) { setWalk(0); return; }
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
        scanStartedMs = System.currentTimeMillis();
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
        updateSpend();
        Button[] viewerMain = {homeButton, exportButton, objButton, saveButton, undoButton, removePersonButton, interviewButton};
        lightButton.setVisibility(!scanning ? View.VISIBLE : View.GONE); // in the pill row, like the iPhone's Lighting
        for (Button b : new Button[]{catalogButton, scannedItemsButton, previewButton}) b.setVisibility(debug && !scanning ? View.VISIBLE : View.GONE); // Marje's planning tools: debug until rk decides
        Button[] tools = {depthButton, cleanButton, trainButton, viewButton, splatButton, sizeBtn, liveClaudeButton, logBtn, planButton, cutButton, topButton, claudeButton};
        for (Button b : viewerMain) b.setVisibility(scanning ? (debug && b == homeButton ? View.VISIBLE : View.GONE) : View.VISIBLE);
        improveButton.setVisibility(!scanning && renderer.currentDir != null ? View.VISIBLE : View.GONE); // demo: Continue scan finds the saved room by itself
        for (Button b : tools) {
            boolean scanTool = b == splatButton || b == sizeBtn || b == liveClaudeButton || b == logBtn;
            b.setVisibility(debug && (!scanning || scanTool) ? View.VISIBLE : View.GONE);
        }
        // While scanning, Cancel and Done are their own row at the bottom (as on the iPhone).
        mainButton.setVisibility(scanning ? View.GONE : View.VISIBLE);
        if (scanRow != null) scanRow.setVisibility(scanning ? View.VISIBLE : View.GONE);
        // the text panel is for debugging; testers see it only for the lighting readout
        status.setVisibility(debug || (!scanning && (renderer.lighting.enabled || renderer.planningPreview)) ? View.VISIBLE : View.GONE);
        if (hud != null) hud.setVisibility(scanning ? View.VISIBLE : View.GONE);
        if (procBox != null && scanning) procBox.setVisibility(View.GONE);
        // Viewer, laid out like the iPhone: Back / title / ✦ / Share on top, Photo | Splats | Points under it,
        // Continue scan under the 3D view; Rename, Undo, Remove person, Interview, Ask Claude and New scan in ✦.
        boolean viewer = !scanning;
        if (viewerTop != null) {
            viewerTop.setVisibility(viewer ? View.VISIBLE : View.GONE);
            styleSwitch.setVisibility(viewer ? View.VISIBLE : View.GONE);
            viewerPanel.setVisibility(viewer && walkState == 0 ? View.VISIBLE : View.GONE);
            pillRow.setVisibility(viewer ? View.VISIBLE : View.GONE);
            viewerTitle.setText(renderer.currentName != null ? renderer.currentName : "Scan");
            nameValue.setText(renderer.currentName != null ? renderer.currentName : "Add a name");
            File cd = renderer.currentDir;
            viewerShared.setVisibility(cd != null && SyncInfo.file(cd).exists() && SyncInfo.load(this, cd).shared ? View.VISIBLE : View.GONE);
            String size = renderer.roomSizeText();
            sizeValue.setText(size != null ? size : "—");
            updateFacts();
            // The 3D view ends where the panel starts, so the model is framed in the part you can see (like the iPhone).
            viewerPanel.post(() -> {
                FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glView.getLayoutParams();
                int want = viewer && walkState == 0 ? viewerPanel.getHeight() : 0;
                if (gp.bottomMargin != want) { gp.bottomMargin = want; glView.setLayoutParams(gp); }
            });
            updateStyleSwitch();
            // The tool buttons sit just above Continue scan in the viewer.
            viewerPanel.post(() -> {
                int want = viewer ? viewerPanel.getHeight() + dp(4) : dp(56);
                if (toolBarsLp.bottomMargin != want) { toolBarsLp.bottomMargin = want; toolBars.setLayoutParams(toolBarsLp); }
            });
        }
        menuActions.clear();
        if (viewer) {
            for (Button b : new Button[]{saveButton, undoButton, removePersonButton, interviewButton, claudeButton, mainButton}) {
                if (b.getVisibility() == View.VISIBLE || b == claudeButton || b == mainButton) menuActions.add(b);
                b.setVisibility(View.GONE);
            }
        }
    }

    // ---------------- viewer chrome, matching the iPhone app ----------------

    private LinearLayout viewerTop, viewerBottom, styleSwitch, pillRow;
    private TextView walkTip;
    private View toolBars;
    /** The scrolling panel under the 3D view (holds viewerBottom). */
    private android.widget.ScrollView viewerPanel;
    private FrameLayout.LayoutParams toolBarsLp;
    private TextView viewerTitle;
    private android.widget.ImageView viewerShared;
    private final java.util.List<Button> menuActions = new java.util.ArrayList<>();
    private final TextView[] styleTabs = new TextView[3];

    private void buildViewerChrome(FrameLayout chrome) {
        // Top bar: ‹ Back, the scan's name, ✦ (more actions), Share.
        viewerTop = new LinearLayout(this);
        viewerTop.setOrientation(LinearLayout.HORIZONTAL);
        viewerTop.setGravity(Gravity.CENTER_VERTICAL);
        viewerTop.setBackgroundColor(Ios.BACKGROUND);
        viewerTop.setPadding(dp(4), dp(34), dp(4), dp(4));
        homeButton.setText("‹ Back");
        Ios.textButton(homeButton);
        viewerTop.addView(homeButton);
        viewerTitle = new TextView(this);
        viewerTitle.setTextColor(Color.WHITE);
        viewerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        viewerTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        viewerTitle.setGravity(Gravity.CENTER);
        viewerTitle.setSingleLine(true);
        viewerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        viewerTop.addView(viewerTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        viewerShared = new android.widget.ImageView(this);
        viewerShared.setImageResource(R.drawable.ic_shared);
        viewerShared.setImageTintList(android.content.res.ColorStateList.valueOf(Ios.ACCENT));
        viewerShared.setContentDescription("Shared: syncs with other phones");
        viewerShared.setVisibility(View.GONE);
        viewerTop.addView(viewerShared, new LinearLayout.LayoutParams(dp(22), dp(22)));
        Button more = new Button(this);
        more.setText("✦");
        Ios.textButton(more);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        more.setOnClickListener(v -> showViewerMenu());
        viewerTop.addView(more);
        exportButton.setText("Share");
        Ios.textButton(exportButton);
        viewerTop.addView(exportButton);
        chrome.addView(viewerTop, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Photo | Splats | Points, an iOS segmented control.
        styleSwitch = new LinearLayout(this);
        styleSwitch.setOrientation(LinearLayout.HORIZONTAL);
        styleSwitch.setPadding(dp(2), dp(2), dp(2), dp(2));
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(0xE61C1C1E); sbg.setCornerRadius(dp(9));
        styleSwitch.setBackground(sbg);
        String[] names = {"Photo", "Splats", "Points"};
        for (int i = 0; i < 3; i++) {
            final int s = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(6), dp(16), dp(6));
            t.setOnClickListener(v -> { renderer.setStyle(s); updateStyleSwitch(); splatButton.setText(renderer.styleLabel()); });
            styleTabs[i] = t;
            styleSwitch.addView(t);
        }
        // Lighting and Objects as pills under the top bar, then the view switch, like the iPhone.
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        lightButton.setText("💡  Lighting");
        objButton.setText("⬡  Objects");
        // ⚡ Outlets n: shows or hides the outlet labels, filled orange while they show (hidden when there are none).
        outletButton = new Button(this);
        outletButton.setAllCaps(false);
        outletButton.setMinHeight(0); outletButton.setMinimumHeight(0); outletButton.setMinWidth(0); outletButton.setMinimumWidth(0);
        outletButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        outletButton.setVisibility(View.GONE);
        renderer.showOutlets = getSharedPreferences("viewer", MODE_PRIVATE).getBoolean("showOutlets", true);
        outletButton.setOnClickListener(v -> {
            renderer.showOutlets = !renderer.showOutlets;
            getSharedPreferences("viewer", MODE_PRIVATE).edit().putBoolean("showOutlets", renderer.showOutlets).apply();
            styleOutletButton();
        });
        for (Button b : new Button[]{outletButton, lightButton, objButton}) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
            pl.rightMargin = dp(8);
            pillRow.addView(b, pl);
        }
        FrameLayout.LayoutParams prp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        prp.topMargin = dp(92); prp.leftMargin = dp(16);
        chrome.addView(pillRow, prp);
        FrameLayout.LayoutParams swp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        swp.topMargin = dp(140); swp.leftMargin = dp(16);
        chrome.addView(styleSwitch, swp);

        // Under the 3D view: Continue scan, prominent, with its explanation.
        viewerBottom = new LinearLayout(this);
        viewerBottom.setOrientation(LinearLayout.VERTICAL);
        viewerBottom.setPadding(dp(16), dp(10), dp(16), dp(18));
        viewerBottom.setBackgroundColor(0xE6000000);
        Button enter = new Button(this);
        enterRoomButton = enter;
        enter.setText("🚶  Enter the room");
        Ios.filled(enter);
        enter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        enter.setPadding(dp(14), 0, dp(14), 0);
        enter.setOnClickListener(v -> setWalk(1));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        ep.bottomMargin = dp(10);
        viewerBottom.addView(enter, ep);
        improveButton.setText("Continue scan");
        Ios.filled(improveButton);
        viewerBottom.addView(improveButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        viewerBottom.addView(Ios.footer(this, "Add parts you missed, or that came out thin or blurry. The phone recognises the room and adds to this scan."));
        // ROOM: the scan's name (tap to rename) and size, like the iPhone's summary.
        viewerBottom.addView(Ios.header(this, "Room"));
        nameRow = (LinearLayout) Ios.row(this, 0, "Name", false, false, v -> saveScan());
        ((TextView) nameRow.getChildAt(0)).setTextColor(Color.WHITE);
        nameValue = new TextView(this);
        nameValue.setTextColor(Ios.ACCENT);
        nameValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nameRow.addView(nameValue);
        sizeRow = (LinearLayout) Ios.row(this, 0, "Approx. size", false, false, null);
        ((TextView) sizeRow.getChildAt(0)).setTextColor(Color.WHITE);
        sizeValue = new TextView(this);
        sizeValue.setTextColor(Ios.SECONDARY);
        sizeValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        sizeRow.addView(sizeValue);
        roomCard = (LinearLayout) Ios.card(this, nameRow, sizeRow);
        viewerBottom.addView(roomCard);
        viewerBottom.addView(Ios.header(this, "Capture"));
        captureCard = new LinearLayout(this);
        captureCard.setOrientation(LinearLayout.VERTICAL);
        viewerBottom.addView(captureCard);
        // Below the 3D view, like the iPhone's list: an opaque panel that scrolls, at most ~45% of the screen.
        viewerBottom.setBackgroundColor(Ios.BACKGROUND);
        viewerPanel = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        viewerPanel.setBackgroundColor(Ios.BACKGROUND);
        viewerPanel.setFillViewport(false);
        viewerPanel.addView(viewerBottom);
        // The app draws under the system navigation bar: pad the end of the list by its height, so the ROOM card can
        // be scrolled fully into view instead of sitting hidden under the bar.
        viewerPanel.setOnApplyWindowInsetsListener((v, insets) -> {
            viewerBottom.setPadding(dp(16), dp(10), dp(16), dp(18) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        viewerPanel.setClipToPadding(false);
        chrome.addView(viewerPanel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        buildWalkChrome(chrome);
        blockTouches(viewerTop, styleSwitch, pillRow, walkControls);
    }

    // ---------------- Enter the room (first person), as on the iPhone ----------------

    private LinearLayout nameRow, sizeRow, roomCard, captureCard;

    /** The ROOM and CAPTURE rows under Name and size (walls, outlets, objects; points, photos), like the iPhone. */
    private void updateFacts() {
        while (roomCard.getChildCount() > 3) roomCard.removeViewAt(3); // Name, separator, size stay
        java.util.List<View> capture = new java.util.ArrayList<>();
        for (String[] f : renderer.roomFacts()) {
            LinearLayout row = (LinearLayout) Ios.row(this, 0, f[1], false, false, null);
            ((TextView) row.getChildAt(0)).setTextColor(Color.WHITE);
            TextView v = new TextView(this);
            v.setText(f[2]); v.setTextColor(Ios.SECONDARY); v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.addView(v);
            if (f[0].equals("Room")) {
                View sep = new View(this);
                sep.setBackgroundColor(Ios.SEPARATOR);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                sl.leftMargin = dp(16);
                roomCard.addView(sep, sl);
                roomCard.addView(row);
            } else capture.add(row);
        }
        captureCard.removeAllViews();
        if (!capture.isEmpty()) captureCard.addView(Ios.card(this, capture.toArray(new View[0])));
    }
    private TextView nameValue, sizeValue, walkHint, heightLabel;
    private View walkControls;
    /** 0 = orbiting, 1 = picking where to stand, 2 = standing in the room. */
    private int walkState;
    private long lastTapTime;

    private void buildWalkChrome(FrameLayout chrome) {
        walkHint = new TextView(this);
        walkHint.setText("Tap the floor where you want to stand");
        walkHint.setTextColor(Color.WHITE);
        walkHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setColor(0xE62C2C2E); hb.setCornerRadius(dp(20));
        walkHint.setBackground(hb);
        walkHint.setPadding(dp(14), dp(9), dp(14), dp(9));
        walkHint.setOnClickListener(v -> setWalk(0));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(40);
        walkHint.setVisibility(View.GONE);
        chrome.addView(walkHint, hp);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.BOTTOM);
        c.setPadding(dp(16), 0, dp(16), dp(28));
        Button exit = new Button(this);
        exit.setText("↩  Exit");
        Ios.filled(exit);
        exit.setOnClickListener(v -> setWalk(0));
        c.addView(exit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)));
        c.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        // Eye height: taller / shorter, 10 cm a tap, remembered.
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE62C2C2E); bg.setCornerRadius(dp(12));
        h.setBackground(bg);
        h.setPadding(dp(6), dp(4), dp(6), dp(4));
        Button up = new Button(this); up.setText("︿"); Ios.textButton(up); up.setOnClickListener(v -> changeEye(0.1f));
        heightLabel = new TextView(this); heightLabel.setTextColor(Color.WHITE); heightLabel.setTextSize(12); heightLabel.setGravity(Gravity.CENTER);
        Button down = new Button(this); down.setText("﹀"); Ios.textButton(down); down.setOnClickListener(v -> changeEye(-0.1f));
        h.addView(up); h.addView(heightLabel); h.addView(down);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = dp(12);
        c.addView(h, hlp);
        LinearLayout moves = new LinearLayout(this);
        moves.setOrientation(LinearLayout.VERTICAL);
        moves.addView(holdButton("▲", 1));
        View gap = new View(this); moves.addView(gap, new LinearLayout.LayoutParams(1, dp(8)));
        moves.addView(holdButton("▼", -1));
        c.addView(moves);
        walkControls = c;
        c.setVisibility(View.GONE);
        walkTip = new TextView(this);
        walkTip.setText("Drag to look · pinch to zoom · double-tap the floor to walk there");
        walkTip.setTextColor(Color.WHITE);
        walkTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
        tb.setColor(0xE62C2C2E); tb.setCornerRadius(dp(14));
        walkTip.setBackground(tb);
        walkTip.setPadding(dp(10), dp(5), dp(10), dp(5));
        walkTip.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tp.bottomMargin = dp(170);
        chrome.addView(walkTip, tp);
        // Above the system navigation bar (the app draws under it).
        c.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16), 0, dp(16), dp(28) + insets.getSystemWindowInsetBottom());
            FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) walkHint.getLayoutParams();
            wl.bottomMargin = dp(40) + insets.getSystemWindowInsetBottom();
            walkHint.setLayoutParams(wl);
            FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) walkTip.getLayoutParams();
            tl.bottomMargin = dp(170) + insets.getSystemWindowInsetBottom();
            walkTip.setLayoutParams(tl);
            return insets;
        });
        chrome.addView(c, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        renderer.setEyeHeight(getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("walkEyeHeight", 1.5f));
    }

    /** A round button that walks while held. */
    private View holdButton(String label, int dir) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL); bg.setColor(0xE62C2C2E);
        b.setBackground(bg);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { renderer.walkMove = dir; v.setAlpha(0.6f); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { renderer.walkMove = 0; v.setAlpha(1f); }
            return true;
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        return b;
    }

    private void changeEye(float d) {
        float h = Math.max(0.3f, Math.min(2.2f, Math.round((renderer.eyeH + d) * 10) / 10f));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("walkEyeHeight", h).apply();
        glView.queueEvent(() -> renderer.setEyeHeight(h));
        heightLabel.setText(String.format(java.util.Locale.US, "%.2f m", h));
    }

    /** 0 = back to orbiting, 1 = "tap the floor", 2 = standing in the room. */
    private void setWalk(int state) {
        walkState = state;
        if (state == 0) glView.queueEvent(renderer::exitWalk);
        if (objPanel.getVisibility() == View.VISIBLE) setObjectMode(false);
        boolean viewerChrome = state == 0;
        viewerPanel.setVisibility(viewerChrome ? View.VISIBLE : View.GONE);
        toolBars.setVisibility(viewerChrome ? View.VISIBLE : View.GONE);
        // The top rows (Lighting, Objects, the view switch) stay while standing in the room, as on the iPhone.
        styleSwitch.setVisibility(View.VISIBLE);
        pillRow.setVisibility(View.VISIBLE);
        walkHint.setVisibility(state == 1 ? View.VISIBLE : View.GONE);
        walkControls.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        walkTip.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        heightLabel.setText(String.format(java.util.Locale.US, "%.2f m", renderer.eyeH));
        // Standing in the room uses the whole screen; otherwise the 3D view ends above the panel.
        viewerPanel.post(() -> {
            FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glView.getLayoutParams();
            int want = state == 0 ? viewerPanel.getHeight() : 0;
            if (gp.bottomMargin != want) { gp.bottomMargin = want; glView.setLayoutParams(gp); }
        });
    }

    /** Touches while picking a spot or standing in the room. True when handled. */
    private boolean walkTouch(MotionEvent e) {
        if (walkState == 0) return false;
        int act = e.getActionMasked();
        if (walkState == 2) scaleDetector.onTouchEvent(e);
        int pc = e.getPointerCount();
        if (act == MotionEvent.ACTION_DOWN) { downTime = e.getEventTime(); downX = e.getX(); downY = e.getY(); multiTouch = false; lastX = e.getX(); lastY = e.getY(); }
        if (pc > 1) multiTouch = true;
        if (walkState == 2 && act == MotionEvent.ACTION_MOVE && pc == 1 && !multiTouch) {
            renderer.look(e.getX() - lastX, e.getY() - lastY);
            lastX = e.getX(); lastY = e.getY();
        }
        if (act == MotionEvent.ACTION_UP && !multiTouch && e.getEventTime() - downTime < 300
                && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
            float tx = e.getX(), ty = e.getY();
            boolean doubleTap = e.getEventTime() - lastTapTime < 350;
            lastTapTime = e.getEventTime();
            if (walkState == 1 || doubleTap) {
                glView.queueEvent(() -> {
                    boolean ok = renderer.enterAt(tx, ty);
                    runOnUiThread(() -> { if (ok && walkState == 1) setWalk(2); else if (!ok) toast("Tap the floor"); });
                });
            }
        }
        return true;
    }

    /** Panels over the 3D view keep their touches: only the 3D view itself turns the model. */
    private static void blockTouches(View... panels) {
        for (View v : panels) { v.setClickable(true); v.setFocusable(false); }
    }

    private void updateStyleSwitch() {
        int s = renderer.style();
        for (int i = 0; i < 3; i++) {
            android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
            on.setColor(0xFF636366); on.setCornerRadius(dp(7));
            styleTabs[i].setBackground(i == s ? on : null);
            styleTabs[i].setTypeface(i == s ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    /** ✦: the scan's other actions, as the iPhone app groups them in its menus. */
    private void showViewerMenu() {
        java.util.List<Button> items = new java.util.ArrayList<>(menuActions);
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = items.get(i).getText().toString();
        new android.app.AlertDialog.Builder(this).setTitle(renderer.currentName != null ? renderer.currentName : "Scan")
                .setItems(labels, (d, which) -> items.get(which).performClick()).show();
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
        // The scan card, the same on both phones: dark translucent, white hint (red only for warnings), orange bars.
        bg.setColor(0xD91C1C1E); bg.setCornerRadius(dp(14));
        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setBackground(bg);
        hud.setPadding(dp(16), dp(12), dp(16), dp(14));
        hudHint = new TextView(this);
        hudHint.setTextColor(Color.WHITE); hudHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        hudHint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        hudHint.setText("Point at the room and move the phone slowly");
        hud.addView(hudHint);
        hudSub = new TextView(this);
        hudSub.setTextColor(0xCCFFFFFF); hudSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hudSub.setVisibility(View.GONE);
        hud.addView(hudSub);
        hudDetail = bar(Ios.ACCENT); hudDetailPct = new TextView(this);
        hudRoom = bar(Ios.ACCENT); hudRoomPct = new TextView(this);
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

        // Cancel and Done at the bottom while scanning, as on the iPhone: same size and place, above the navigation bar.
        scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER);
        Button cancel = new Button(this), done = new Button(this);
        cancel.setText("Cancel"); done.setText("Done");
        Ios.pill(cancel); cancel.setTextColor(Ios.ACCENT);
        Ios.filled(done);
        for (Button b : new Button[]{cancel, done}) {
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            b.setMinWidth(dp(110)); b.setMinimumWidth(dp(110)); b.setMinHeight(0); b.setMinimumHeight(0);
            b.setPadding(dp(22), 0, dp(22), 0);
            android.graphics.drawable.Drawable d = b.getBackground();
            if (d instanceof android.graphics.drawable.RippleDrawable) {
                android.graphics.drawable.Drawable in = ((android.graphics.drawable.RippleDrawable) d).getDrawable(0);
                if (in instanceof android.graphics.drawable.GradientDrawable) ((android.graphics.drawable.GradientDrawable) in).setCornerRadius(dp(25));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(50));
            lp.leftMargin = dp(8); lp.rightMargin = dp(8);
            scanRow.addView(b, lp);
        }
        cancel.setOnClickListener(v -> cancelScan());
        done.setOnClickListener(v -> onMainButton());
        done.setOnLongClickListener(v -> mainButton.performLongClick());
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        chrome.addView(scanRow, sp);
        scanRow.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, 0, 0, dp(32) + insets.getSystemWindowInsetBottom());
            return insets;
        });

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

    private LinearLayout scanRow;
    /** Hint colours by level: white, white, red for warnings (the same on the iPhone). */
    private static final int[] HINT_COLORS = {Color.WHITE, Color.WHITE, 0xFFFF6B5B};

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
            hudHint.setTextColor(HINT_COLORS[Math.max(0, Math.min(2, level))]);
            hudSub.setText(sub == null ? "" : sub);
            hudSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
            hudDetail.setProgress(Math.round(detail * 1000));
            hudDetail.setProgressTintList(android.content.res.ColorStateList.valueOf(detail >= ScanRenderer.SPOT_DONE ? 0xFF33D17A : Ios.ACCENT));
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
    private TextView spendView;
    private double spendSaved; // session spend already added to the stored total

    /** The money counter at the top: shown whenever a Claude key is saved. */
    void updateSpend() {
        if (spendView == null) return;
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        double now = ClaudeHelper.sessionUsd, total = p.getFloat("claude_usd_total", 0f);
        if (now > spendSaved) { total += now - spendSaved; spendSaved = now; p.edit().putFloat("claude_usd_total", (float) total).apply(); }
        boolean on = debug && claudeKey() != null; // the cost counter is a debug tool now (rk: no counter on top)
        spendView.setVisibility(on ? View.VISIBLE : View.GONE);
        spendView.setText(String.format(java.util.Locale.US, "Claude $%.2f · total $%.2f", now, total));
        if (hud != null && hud.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) hud.getLayoutParams();
            int want = on ? dp(68) : dp(40); // the scan card sits under the counter
            if (hp.topMargin != want) { hp.topMargin = want; hud.setLayoutParams(hp); }
        }
    }

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

    void outletsFound(int n, int objs, boolean ok) {
        runOnUiThread(() -> {
            if (!ok) { toast(n > 0 ? "Search stopped early (" + n + " outlets found). Check the internet." : "Couldn't search for outlets and objects. Check the internet."); return; }
            String o = n == 0 ? "No power outlets" : "⚡ " + n + " power outlet" + (n == 1 ? "" : "s") + " (orange)";
            String f = objs == 0 ? "" : ", " + objs + " object" + (objs == 1 ? "" : "s") + " in Objects";
            Toast.makeText(this, o + f + ".", Toast.LENGTH_LONG).show();
        });
    }

    void autoSaved(String name, String err) {
        runOnUiThread(() -> {
            if (name == null) { toast("Couldn't save the scan: " + err); return; }
            if (!scanning) improveButton.setVisibility(View.VISIBLE);
            if (!scanning && !name.equals(lastSavedName)) { lastSavedName = name; toast("Saved as \"" + name + "\". Tap Rename to change it."); }
        });
    }

    void trainingLabel(String l) { runOnUiThread(() -> trainButton.setText(l)); }

    // ---------------- home screen and saved scans ----------------

    private FrameLayout home;
    private LinearLayout homeList;
    private View scanChrome; // status + buttons, hidden on the home screen

    private View buildHome() {
        // Laid out like the iPhone app's Home: logo, inset-grouped cards on black, grey footers, saved scans as rows.
        home = new FrameLayout(this);
        home.setBackgroundColor(Ios.BACKGROUND);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(48), dp(16), dp(28));
        // Below the status bar (whatever its height), plus the iPhone's spacing.
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16), insets.getSystemWindowInsetTop() + dp(48), dp(16), dp(28) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setContentDescription("RoomScape");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(120));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(8);
        col.addView(logo, lp);

        col.addView(Ios.card(this, Ios.row(this, R.drawable.ic_scan, "New room scan", true, false, v -> startNewScan())));
        col.addView(Ios.footer(this, "Walk slowly around the room and point the phone at every wall, window, door and piece of furniture."));
        col.addView(Ios.card(this, Ios.row(this, R.drawable.ic_import, "Import a scan (.rmsc)", false, false, v -> pickRmsc())));
        col.addView(Ios.footer(this, "Open a room scanned with RoomScape on another phone. Or send one from a nearby phone: open a scan there and tap Share → Send to nearby phone."));
        col.addView(Ios.card(this, Ios.row(this, R.drawable.ic_list, "Item catalog", false, true, v -> nativePlanning.showCatalog())));
        col.addView(Ios.footer(this, "Lights, stands and grip you can place in a room, with sizes and power draw. Works offline."));

        col.addView(Ios.header(this, "Saved scans"));
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeList);
        col.addView(Ios.footer(this, "Touch and hold a scan to rename or delete it."));

        TextView pilot = new TextView(this);
        pilot.setText("Interested in a paid pilot?");
        pilot.setTextColor(Ios.SECONDARY);
        pilot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pilot.setGravity(Gravity.CENTER);
        pilot.setPadding(0, dp(24), 0, dp(8));
        pilot.setOnClickListener(v -> pilotIntake.open());
        col.addView(pilot);
        sv.addView(col);
        home.addView(sv);
        return home;
    }

    private void showHome() {
        if (walkState != 0) setWalk(0);
        if (session != null) session.pause();
        glView.queueEvent(() -> renderer.goHome());
        scanChrome.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        refreshHome();
    }

    private long scanStartedMs;

    /** Cancel while scanning: throw the scan away, as on the iPhone (asks first after a minute of scanning). */
    private void cancelScan() {
        Runnable discard = () -> {
            if (session != null) session.pause();
            glView.queueEvent(() -> renderer.discardScan(() -> {
                scanChrome.setVisibility(View.GONE);
                home.setVisibility(View.VISIBLE);
                refreshHome();
            }));
        };
        if (System.currentTimeMillis() - scanStartedMs < 60_000) { discard.run(); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Discard this scan?")
                .setMessage(renderer.isImproving() ? "The new pass is thrown away. The saved room stays as it was." : "Everything scanned so far is thrown away.")
                .setPositiveButton("Discard", (d, w) -> discard.run())
                .setNegativeButton("Keep scanning", null)
                .show();
    }

    private boolean homeShown() { return home != null && home.getVisibility() == View.VISIBLE; }

    private void hideHome() {
        home.setVisibility(View.GONE);
        scanChrome.setVisibility(View.VISIBLE);
    }

    private void refreshHome() {
        new Thread(() -> {
            java.util.List<ScanStore.Entry> es = ScanStore.list(this);
            java.util.List<android.graphics.Bitmap> th = new java.util.ArrayList<>();
            for (ScanStore.Entry e : es) th.add(ScanStore.thumb(e));
            runOnUiThread(() -> {
                homeList.removeAllViews();
                if (es.isEmpty()) {
                    homeList.addView(Ios.card(this, Ios.row(this, 0, "No scans yet. They're saved here automatically.", false, false, null)));
                    return;
                }
                View[] rows = new View[es.size()];
                for (int i = 0; i < es.size(); i++) rows[i] = scanRow(es.get(i), th.get(i));
                homeList.addView(Ios.card(this, rows));
            });
        }).start();
    }

    /** The "shared" mark: this room syncs with other phones (amber circle, dark people icon). */
    private View sharedBadge() {
        android.widget.ImageView b = new android.widget.ImageView(this);
        b.setImageResource(R.drawable.ic_shared);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL); bg.setColor(Ios.ACCENT);
        b.setBackground(bg);
        b.setPadding(dp(3), dp(3), dp(3), dp(3));
        b.setContentDescription("Shared");
        return b;
    }

    /** A saved scan as an iPhone-style list row: thumbnail, name (and date when it has a name), chevron. */
    private View scanRow(ScanStore.Entry e, android.graphics.Bitmap thumb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(8), dp(12), dp(8));
        r.setBackground(Ios.pressable());
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
        tb.setColor(Color.BLACK); tb.setCornerRadius(dp(8));
        iv.setBackground(tb);
        iv.setClipToOutline(true);
        if (thumb != null) iv.setImageBitmap(thumb);
        FrameLayout tf = new FrameLayout(this);
        tf.addView(iv, new FrameLayout.LayoutParams(dp(88), dp(66)));
        if (SyncInfo.file(e.dir).exists() && SyncInfo.load(this, e.dir).shared) tf.addView(sharedBadge(), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.END));
        r.addView(tf, new LinearLayout.LayoutParams(dp(88), dp(66)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        String when = android.text.format.DateFormat.format("d MMM yyyy, HH:mm", e.modified).toString();
        // Unnamed scans are called "Room <date>" (made here, or by an iPhone export): show just their own date.
        boolean named = e.name != null && !e.name.isEmpty() && !e.name.startsWith("Room ");
        if (!named && e.name != null && e.name.startsWith("Room ")) when = e.name.substring(5);
        TextView name = new TextView(this);
        name.setText(named ? e.name : when);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setSingleLine(true);
        text.addView(name);
        if (false) {
            TextView sub = new TextView(this);
            sub.setText(when);
            sub.setTextColor(Ios.SECONDARY);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            text.addView(sub);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ios.chevron(this));
        r.setOnClickListener(v -> openScan(e));
        r.setOnLongClickListener(v -> { scanMenu(e); return true; });
        return r;
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
        // Straight to the scan view (never the scanning card, bars or buttons): same reset as scanUi, but viewing.
        scanUi();
        scanning = false;
        applyUi();
        viewerTitle.setText(e.name);
        nameValue.setText(e.name);
        mainButton.setText("New scan");
        renderer.setStyle(0); // Photo first, like the iPhone app
        glView.queueEvent(() -> renderer.loadScan(e.dir, () -> runOnUiThread(this::viewerUi)));
    }

    private void saveScan() {
        String def = renderer.currentName != null ? renderer.currentName
                : "Room " + android.text.format.DateFormat.format("d MMM yyyy, HH:mm", System.currentTimeMillis());
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
        if (on) viewerPanel.post(() -> viewerPanel.smoothScrollTo(0, 0)); // the bar is the panel's first card
        glView.queueEvent(() -> { String t = renderer.objectsOn(on); runOnUiThread(() -> objPanelText(t)); });
    }

    private void refreshObjPanel() { glView.queueEvent(() -> { String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }); }

    private void objPanelText(String t) {
        objLabel.setText(t);
        objRemoveBtn.setText("Remove");
        if (objUndoBtn != null) { boolean can = renderer.canUndoObjects(); objUndoBtn.setEnabled(can); objUndoBtn.setAlpha(can ? 1f : 0.4f); }
    }

    /** Object mode touches: a finger on the selected object drags it; a tap selects; everything else orbits as usual. */
    /** Two fingers on a selected object: 0 deciding, 1 turning it, 2 the camera's (pan / pinch). */
    private int twistMode;
    private Button enterRoomButton;
    private final java.util.HashMap<View, Integer> panelHidden = new java.util.HashMap<>();
    private Button objUndoBtn;
    private float twistX0, twistY0, twistSpread0, twistLastX;

    private static float spread(MotionEvent e) { return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); }

    private boolean objectTouch(MotionEvent e) {
        int act = e.getActionMasked();
        // Two fingers left / right turn the selected object about the floor's vertical (rk 9 Nov), after a dead zone,
        // so a pan or pinch of the view doesn't turn it by accident. Mostly-sideways = turn; anything else = camera.
        if (e.getPointerCount() == 2 && renderer.objectSelectedMovable()) {
            float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
            if (act == MotionEvent.ACTION_POINTER_DOWN) {
                objDragging = false; twistMode = 0; twistX0 = mx; twistY0 = my; twistSpread0 = spread(e);
                return true;
            }
            if (act == MotionEvent.ACTION_MOVE) {
                if (twistMode == 0) {
                    float dx = mx - twistX0, dy = my - twistY0, ds = Math.abs(spread(e) - twistSpread0);
                    // A pinch still zooms the view; sideways turns the object; the view doesn't pan meanwhile.
                    if (ds > dp(30)) { twistMode = 2; lastPointerCount = 0; return false; }
                    if (Math.abs(dx) > dp(28) && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                        twistMode = 1; twistLastX = mx;
                        glView.queueEvent(() -> renderer.objectSnapshot());
                    }
                    return true;
                }
                if (twistMode == 1) {
                    float d = mx - twistLastX; twistLastX = mx;
                    glView.queueEvent(() -> renderer.objectRotate(-d * (float) Math.toRadians(1 / 3.0))); // 1° per 3 px
                    return true;
                }
                return false;
            }
            if (act == MotionEvent.ACTION_POINTER_UP) {
                boolean mine = twistMode != 2;
                if (twistMode == 1) { glView.queueEvent(renderer::objectSnapTurn); refreshObjPanel(); }
                twistMode = 0; lastPointerCount = 0; return mine;
            }
            return twistMode != 2;
        }
        if (e.getPointerCount() > 1) { objDragging = false; return false; }
        if (act == MotionEvent.ACTION_DOWN) {
            objDownX = objLastX = e.getX(); objDownY = objLastY = e.getY(); objDownT = e.getEventTime(); objMoved = false;
            int hit = renderer.objectHit(e.getX(), e.getY());
            objDragging = hit >= 0 && hit == renderer.objects.selected;
            if (!objDragging) return false;
            glView.queueEvent(() -> renderer.objectSnapshot());
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
        objRemoveBtn.setOnClickListener(v -> glView.queueEvent(() -> { renderer.objectRemove(); String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }));
        row.addView(objRemoveBtn);
        Button putBack = smallButton("Put back");
        putBack.setOnClickListener(v -> glView.queueEvent(() -> { renderer.objectPutBackSelected(); String t = renderer.objectInfo(); runOnUiThread(() -> objPanelText(t)); }));
        row.addView(putBack);
        Button undo = smallButton("Undo");
        objUndoBtn = undo;
        undo.setOnClickListener(v -> glView.queueEvent(() -> {
            boolean did = renderer.objectUndo(); String t = renderer.objectInfo();
            runOnUiThread(() -> { objPanelText(t); if (!did) toast("Nothing to undo"); });
        }));
        row.addView(undo);
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

    private int outletTotal;

    /** From the renderer: the open scan has `n` outlets. */
    void outletCount(int n) {
        runOnUiThread(() -> {
            outletTotal = n;
            outletButton.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            styleOutletButton();
        });
    }

    private void styleOutletButton() {
        outletButton.setText("⚡ Outlets " + outletTotal);
        if (renderer.showOutlets) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Ios.ACCENT);
            bg.setCornerRadius(dp(18));
            outletButton.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), bg, null));
            outletButton.setTextColor(android.graphics.Color.BLACK);
            outletButton.setStateListAnimator(null);
        } else Ios.pill(outletButton);
        outletButton.setContentDescription(renderer.showOutlets ? "Hide the power outlets" : "Show the power outlets");
    }

    void updateStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    protected void onResume() {
        super.onResume();
        nearby.start();
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
        nearby.stop();
        renderer.paused = true;
        glView.onPause();
        if (session != null) session.pause();
        renderer.sensors.stop(this);
    }

    @Override
    protected void onDestroy() {
        if (nativePlanning != null) nativePlanning.close();
        if (pilotIntake != null) pilotIntake.close();
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == IMPORT_RMSC && resultCode == RESULT_OK && data != null) importRmsc(data.getData());
        if(requestCode == SAVE_RMSC && resultCode == RESULT_OK && data != null) saveRmscTo(data.getData());
        if(requestCode == NativePlanningUi.IMPORT_CATALOG && resultCode == RESULT_OK && data != null && data.getData() != null) nativePlanning.importUri(data.getData());
        if(requestCode == PilotIntakeUi.EXPORT_PILOT) pilotIntake.exportResult(resultCode == RESULT_OK, data == null ? null : data.getData());
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
