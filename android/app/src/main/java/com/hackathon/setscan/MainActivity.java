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
    private Button undoButton, removePersonButton, interviewButton, photoRealButton;
    private Button outletButton;
    private Button recordButton;

    /** Every scan records itself (rk 9 Nov): ARCore's recorder, from the scan's start to Done; kept with the scan. */
    static final boolean AUTO_RECORD = true;
    /** Debug A/B: the ARCore session recording (video + depth) on; read at the next scan. */
    static boolean recordWanted = true;
    /** A build for someone else's phone (gradle -PguestBuild): recording and screen video start off. */
    private boolean guestBuild() { try { return getResources().getBoolean(R.bool.guest_build); } catch (Exception e) { return false; } }
    /** The recording of the scan in progress, and a finished one waiting to move into its scan's folder. */
    private File recordingFile, finishedRecording;

    /** A replay (intent extra replayName): its name; started once. */
    private String replayName; private boolean replayStarted, replayEnded;

    /** ARCore lost track mid-scan and STOP_ON_LOSS is on: Done, as if tapped (the scan is kept). */
    void stopOnLoss() {
        runOnUiThread(() -> {
            if (renderer.isViewing()) return;
            act("tracking lost: scan stopped automatically");
            haptic(true);
            toast("Scan stopped: the phone lost track. Everything scanned is kept.");
            onMainButton();
        });
    }

    /** The replayed recording ended: Done, as if tapped. */
    void replayFinished() {
        runOnUiThread(() -> {
            if (replayEnded || renderer.isViewing()) return;
            replayEnded = true;
            android.util.Log.i("RoomScape", "replay: recording ended, Done");
            onMainButton();
        });
    }

    private void startRecordingIfNeeded() {
        if (!recordWanted || replayName != null) return;   // a replay isn't recorded again
        try {
            if (session == null || session.getRecordingStatus() == com.google.ar.core.RecordingStatus.OK) return;
            File dir = new File(getExternalFilesDir(null), "recordings");
            dir.mkdirs();
            recordingFile = new File(dir, "scan-" + android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis()) + ".mp4");
            session.startRecording(new com.google.ar.core.RecordingConfig(session)
                    .setMp4DatasetUri(android.net.Uri.fromFile(recordingFile)).setAutoStopOnPause(false));
            android.util.Log.i("RoomScape", "auto recording to " + recordingFile);
        } catch (Exception e) { android.util.Log.e("RoomScape", "auto recording failed: " + e); }
    }

    /**
     * At launch: an auto recording left in recordings/ (the app closed or failed before moving it) goes into the scan
     * it belongs to: the first scan without a recording saved within 15 minutes after the recording started. Copied,
     * checked, then removed from recordings/ (never lost).
     */
    private void adoptStrayRecordings() {
        new Thread(() -> {
            try {
                File dir = new File(getExternalFilesDir(null), "recordings");
                File[] recs = dir.listFiles((d, n) -> n.startsWith("scan-") && n.endsWith(".mp4"));
                File[] scans = ScanStore.root(this).listFiles(File::isDirectory);
                if (recs == null || scans == null) return;
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
                for (File rec : recs) {
                    long start;
                    try { start = f.parse(rec.getName().substring(5, 20)).getTime(); } catch (Exception e) { continue; }
                    if (System.currentTimeMillis() - rec.lastModified() < 60_000) continue; // still being written
                    File best = null; long bestCreated = Long.MAX_VALUE;
                    for (File sd : scans) {
                        if (new File(sd, "recording.mp4").exists()) continue;
                        long created;
                        try { created = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(sd, "meta.json").toPath()))).optLong("created", 0); }
                        catch (Exception e) { continue; }
                        if (created >= start && created - start < 15 * 60_000 && created < bestCreated) { best = sd; bestCreated = created; }
                    }
                    if (best == null) continue;
                    File to = new File(best, "recording.mp4"), part = new File(best, "recording.mp4.part");
                    java.nio.file.Files.copy(rec.toPath(), part.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (part.length() == rec.length() && part.renameTo(to)) {
                        rec.delete();
                        android.util.Log.i("RoomScape", "adopted stray recording " + rec.getName() + " into " + best.getName());
                    } else part.delete();
                }
            } catch (Exception e) { android.util.Log.e("RoomScape", "adopting stray recordings failed: " + e); }
        }).start();
    }

    /** After a save: the finished recording moves into the scan's folder as recording.mp4. */
    private void attachRecording() {
        File rec = finishedRecording, dir = renderer.currentDir;
        if (rec == null || dir == null || !rec.exists()) return;
        finishedRecording = null;
        new Thread(() -> {
            File to = new File(dir, "recording.mp4");
            try {
                if (!rec.renameTo(to)) { java.nio.file.Files.copy(rec.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); rec.delete(); }
                android.util.Log.i("RoomScape", "recording kept with the scan: " + to + " (" + to.length() / 1_000_000 + " MB)");
            } catch (Exception e) { android.util.Log.e("RoomScape", "keeping the recording failed: " + e); }
        }).start();
    }

    /** Ends a recording if one is running (Done, Cancel, leaving the scan or the app). */
    private void stopRecordingIfAny() {
        try {
            if (session != null && session.getRecordingStatus() == com.google.ar.core.RecordingStatus.OK) {
                session.stopRecording();
                android.util.Log.i("RoomScape", "recording stopped");
                if (recordingFile != null) { finishedRecording = recordingFile; recordingFile = null; }
            }
        } catch (Exception e) { android.util.Log.e("RoomScape", "stop recording: " + e); }
        if (recordButton != null) recordButton.setText("Record session");
    }

    private void toggleRecording() {
        if (session == null) return;
        try {
            if (session.getRecordingStatus() == com.google.ar.core.RecordingStatus.OK) {
                session.stopRecording();
                recordButton.setText("Record session");
                toast("Recording saved");
                return;
            }
            File dir = new File(getExternalFilesDir(null), "recordings");
            dir.mkdirs();
            File out = new File(dir, "session-" + android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis()) + ".mp4");
            session.startRecording(new com.google.ar.core.RecordingConfig(session)
                    .setMp4DatasetUri(android.net.Uri.fromFile(out)).setAutoStopOnPause(false)); // survives tracking restarts
            recordButton.setText("Stop recording");
            android.util.Log.i("RoomScape", "recording to " + out);
            toast("Recording… tap again to stop");
        } catch (Exception e) {
            toast("Recording failed: " + e.getMessage());
            android.util.Log.e("RoomScape", "recording failed: " + e);
        }
    }
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
    private boolean wallDragging;

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
        act("Share");
        // the log up to now goes with whatever is shared (a .rmsc carries it in android/diagnostics/)
        if (renderer != null) try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(getFilesDir(), "app-log.txt"))) { o.write(renderer.logText().getBytes("UTF-8")); } catch (Exception ignored) { }
        File dir = renderer.currentDir;
        if (dir == null) { toast("The scan saves itself a moment after Done. Try Share again in a few seconds."); return; }
        File video = new File(dir, "recording.mp4"), screen = new File(dir, "screen.mp4");
        java.util.List<String> items = new java.util.ArrayList<>(java.util.Arrays.asList("Send to nearby phone", "Save to device…", "Share file (.rmsc)"));
        // the videos are never in the .rmsc: the camera video (large) and what the screen showed (small)
        if (video.exists()) items.add(String.format(java.util.Locale.US, "Share scan video (large, %d MB)", video.length() / 1048576));
        if (screen.exists()) items.add(String.format(java.util.Locale.US, "Share screen video (%d MB)", Math.max(1, screen.length() / 1048576)));
        String name = renderer.currentName != null ? renderer.currentName : dir.getName();
        new android.app.AlertDialog.Builder(this).setTitle("Share room")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String it = items.get(which);
                    act("Share: " + it);
                    if (which == 0) nearby.showSendDialog(dir); else if (which == 1) saveRmsc(); else if (which == 2) shareRmsc();
                    else if (it.startsWith("Share screen")) shareUri("Packing the video...", "video/mp4", "Share screen video",
                            () -> Rmsc.exportVideo(this, dir, name, "screen.mp4", "-screen.mp4"));
                    else shareUri("Packing the video...", "video/mp4", "Share scan video",
                            () -> Rmsc.exportVideo(this, dir, name));
                }).show();
    }

    interface UriMaker { android.net.Uri make() throws Exception; }

    /** Makes a file in Downloads/RoomScape on a background thread, then opens the share sheet for it. */
    private void shareUri(String packing, String mime, String title, UriMaker maker) {
        toast(packing);
        new Thread(() -> {
            try {
                android.net.Uri uri = maker.make();
                runOnUiThread(() -> {
                    android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                    send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    toast("Saved to Downloads/RoomScape. Pick where to send it.");
                    try { startActivity(android.content.Intent.createChooser(send, title)); }
                    catch (Exception e) { toast("Saved, but sharing failed: " + e.getMessage()); }
                });
            } catch (Throwable e) { runOnUiThread(() -> toast("Couldn't share: " + e.getMessage())); }
        }).start();
    }

    /** Diagnostics without a finished scan (long-press the logo on the home screen): logs, device, GPU, last crash. */
    private void shareDiagnostics() {
        if (renderer != null) try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(getFilesDir(), "app-log.txt"))) { o.write(renderer.logText().getBytes("UTF-8")); } catch (Exception ignored) { }
        File latest = null;
        File[] scans = new File(getFilesDir(), "scans").listFiles(File::isDirectory);
        if (scans != null) for (File f : scans) if (latest == null || f.lastModified() > latest.lastModified()) latest = f;
        final File l = latest;
        shareUri("Packing the diagnostics...", "application/zip", "Share RoomScape diagnostics", () -> Rmsc.exportDiagnostics(this, l));
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
        {   // recording switches before anything scans (a guest build starts with both off; a saved choice wins)
            android.content.SharedPreferences sp0 = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (sp0.getBoolean("abLowMemory", false)) System.setProperty("roomscape.lowmem", "1");   // before ScanRenderer loads (it reads this once)
            recordWanted = sp0.getBoolean("abRecord", !guestBuild());
            ScanRenderer.SCREEN_VIDEO = sp0.getBoolean("abScreen", !guestBuild());
        }
        Rmsc.appFiles = getFilesDir();
        keepLogHistory();
        installCrashLog();
        writeDeviceInfo();
        adoptStrayRecordings();

        FrameLayout root = new FrameLayout(this);
        glView = new GLSurfaceView(this);
        glView.setPreserveEGLContextOnPause(true);
        // ES 3 context: the GPU surface (GpuTsdf) needs 3.1 compute; drivers don't have to hand out 3.x for a request of 2
        // (the S24 does, an Adreno may not). The scan's own drawing is ES 2 code, which runs in it unchanged.
        glView.setEGLContextClientVersion(3);
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
        statusLp = slp;

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
            act("Lighting");
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
        objButton.setOnClickListener(v -> { act("Objects"); setObjectMode(objPanel.getVisibility() != View.VISIBLE); });
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
        // Make photo-real (rk 12-13 Nov, as the iPhone): by hand only, after a warning; its own screen and process.
        photoRealButton = new Button(this);
        photoRealButton.setVisibility(View.GONE);
        photoRealButton.setOnClickListener(v -> openPhotoReal());
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
        // Debug: record the session (camera, motion, depth) with ARCore's recorder, for replaying on the Mac /
        // on the phone later (docs/recordings.md). Saved in the app's files: Android/data/…/files/recordings.
        recordButton = new Button(this);
        recordButton.setText("Record session");
        recordButton.setOnClickListener(v -> toggleRecording());
        bar.addView(recordButton);
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
        for (Button bt : new Button[]{homeButton, mainButton, lightButton, objButton, catalogButton, scannedItemsButton, previewButton, saveButton, improveButton, depthButton, cleanButton, trainButton, planButton, viewButton, cutButton, topButton, splatButton, sizeButton, refineButton, undoButton, removePersonButton, interviewButton, exportButton, claudeButton, liveClaudeButton, recordButton, logButton}) {
            if (bt.getParent() != null) ((ViewGroup) bt.getParent()).removeView(bt);
            bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            bt.setAllCaps(false);
            bt.setMinHeight(0); bt.setMinimumHeight(0); bt.setMinWidth(0); bt.setMinimumWidth(0);
            bt.setPadding(dp(12), dp(8), dp(12), dp(8));
            Ios.pill(bt);
            // In the viewer these live in the top bar (Back, Share) and under the 3D view (Continue scan), like iOS.
            if (bt != homeButton && bt != exportButton && bt != improveButton && bt != lightButton && bt != objButton) bars.addView(bt);
        }
        // GPU surface (rk 10 Nov): on by default (self-test 10:13: 50x faster, 99% the same surface); the switch turns it off.
        {
            Button gpuB = new Button(this), testB = new Button(this);
            boolean on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("gpuSurface2", true);
            renderer.gpuSurfaceWanted = on;
            gpuB.setText(on ? "GPU surface: on" : "GPU surface: off");
            gpuB.setOnClickListener(v -> {
                boolean now = !renderer.gpuSurfaceWanted;
                renderer.gpuSurfaceWanted = now;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("gpuSurface2", now).apply();
                gpuB.setText(now ? "GPU surface: on" : "GPU surface: off");
                toast(now ? "GPU surface on from the next scan" : "GPU surface off");
            });
            testB.setText("GPU self-test");
            testB.setOnClickListener(v -> { updateStatus("GPU self-test running…"); glView.queueEvent(renderer::runGpuSelfTest); });
            // Tracking A/B switches (rk 10 Nov: losses on detailed scenes): video recording, camera focus, 30 fps cap.
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            Button recB = new Button(this), focB = new Button(this), capB = new Button(this), scrB = new Button(this);
            recordWanted = sp.getBoolean("abRecord", !guestBuild());
            ScanRenderer.FIXED_FOCUS = sp.getBoolean("abFixedFocus", true);
            renderer.fpsCap30 = sp.getBoolean("abCap30v2", true);
            recB.setText(recordWanted ? "Video: on" : "Video: off");
            focB.setText(ScanRenderer.FIXED_FOCUS ? "Focus: fixed" : "Focus: auto");
            capB.setText(renderer.fpsCap30 ? "Draw: 15 fps" : "Draw: 60 fps");
            recB.setOnClickListener(v -> { recordWanted = !recordWanted; sp.edit().putBoolean("abRecord", recordWanted).apply(); recB.setText(recordWanted ? "Video: on" : "Video: off"); toast("From the next scan"); });
            focB.setOnClickListener(v -> { ScanRenderer.FIXED_FOCUS = !ScanRenderer.FIXED_FOCUS; sp.edit().putBoolean("abFixedFocus", ScanRenderer.FIXED_FOCUS).apply(); focB.setText(ScanRenderer.FIXED_FOCUS ? "Focus: fixed" : "Focus: auto"); toast("From the next scan"); });
            Button wallsB = new Button(this);
            ScanRenderer.ADJUST_WALLS = sp.getBoolean("abAdjustWalls2", true);
            wallsB.setText(ScanRenderer.ADJUST_WALLS ? "Adjust room walls: on" : "Adjust room walls: off");
            wallsB.setOnClickListener(v -> { ScanRenderer.ADJUST_WALLS = !ScanRenderer.ADJUST_WALLS; sp.edit().putBoolean("abAdjustWalls2", ScanRenderer.ADJUST_WALLS).apply(); wallsB.setText(ScanRenderer.ADJUST_WALLS ? "Adjust room walls: on" : "Adjust room walls: off"); });
            wallsB.setOnLongClickListener(v -> { glView.queueEvent(renderer::resetWallEdits); toast("Room walls reset to the scan's own"); return true; });
            Button tilesB = new Button(this);
            ScanRenderer.FLAT_TILES = sp.getBoolean("abFlatTiles2", false);   // off for the confidence-greys experiment (rk 11 Nov 21:55; a new key so it starts off)
            tilesB.setText(ScanRenderer.FLAT_TILES ? "Flat tiles: on" : "Flat tiles: off");
            tilesB.setOnClickListener(v -> { ScanRenderer.FLAT_TILES = !ScanRenderer.FLAT_TILES; sp.edit().putBoolean("abFlatTiles2", ScanRenderer.FLAT_TILES).apply(); tilesB.setText(ScanRenderer.FLAT_TILES ? "Flat tiles: on" : "Flat tiles: off"); });
            Button dropB = new Button(this);
            ScanRenderer.DROP_UNDER_TILES = sp.getBoolean("abDropUnderTiles", false);
            dropB.setText(ScanRenderer.DROP_UNDER_TILES ? "Points under tiles: hidden" : "Points under tiles: kept");
            dropB.setOnClickListener(v -> { ScanRenderer.DROP_UNDER_TILES = !ScanRenderer.DROP_UNDER_TILES; sp.edit().putBoolean("abDropUnderTiles", ScanRenderer.DROP_UNDER_TILES).apply(); dropB.setText(ScanRenderer.DROP_UNDER_TILES ? "Points under tiles: hidden" : "Points under tiles: kept"); });
            Button spotB = new Button(this);
            ScanRenderer.GOOD_SPOTS = sp.getBoolean("abGoodSpots2", false);
            spotB.setText(ScanRenderer.GOOD_SPOTS ? "Good spot after a loss: on" : "Good spot after a loss: off");
            spotB.setOnClickListener(v -> { ScanRenderer.GOOD_SPOTS = !ScanRenderer.GOOD_SPOTS; sp.edit().putBoolean("abGoodSpots2", ScanRenderer.GOOD_SPOTS).apply(); spotB.setText(ScanRenderer.GOOD_SPOTS ? "Good spot after a loss: on" : "Good spot after a loss: off"); });
            Button warnB = new Button(this);
            ScanRenderer.PLAIN_WARNING = sp.getBoolean("abPlainWarning2", false);
            warnB.setText(ScanRenderer.PLAIN_WARNING ? "Plain-wall warning: on" : "Plain-wall warning: off");
            warnB.setOnClickListener(v -> { ScanRenderer.PLAIN_WARNING = !ScanRenderer.PLAIN_WARNING; sp.edit().putBoolean("abPlainWarning2", ScanRenderer.PLAIN_WARNING).apply(); warnB.setText(ScanRenderer.PLAIN_WARNING ? "Plain-wall warning: on" : "Plain-wall warning: off"); });
            Button uwB = new Button(this);   // EXPERIMENT (remove with experiment/): records the ultrawide without ARCore
            uwB.setText("Ultrawide recorder (experiment)");
            uwB.setOnClickListener(v -> startActivity(new android.content.Intent(this, com.hackathon.setscan.experiment.UltrawideRecorderActivity.class)));
            Button fineB = new Button(this);
            ScanRenderer.FINE_NEAR = sp.getBoolean("abFineNear", true);
            fineB.setText(ScanRenderer.FINE_NEAR ? "Fine dots up close: on" : "Fine dots up close: off");
            fineB.setOnClickListener(v -> { ScanRenderer.FINE_NEAR = !ScanRenderer.FINE_NEAR; sp.edit().putBoolean("abFineNear", ScanRenderer.FINE_NEAR).apply(); fineB.setText(ScanRenderer.FINE_NEAR ? "Fine dots up close: on" : "Fine dots up close: off"); toast("From the next scan"); });
            Button camB = new Button(this);
            ScanRenderer.CAMERA_OPACITY = sp.getFloat("abCamOpacity", 0.3f);
            camB.setText("Camera opacity: " + Math.round(ScanRenderer.CAMERA_OPACITY * 100) + "%");
            camB.setOnClickListener(v -> {   // 30% -> 60% -> 100% -> 0% -> 30%
                float[] steps = {0.3f, 0.6f, 1f, 0f}; int k = 0;
                for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - ScanRenderer.CAMERA_OPACITY) < 0.01f) k = (i + 1) % steps.length;
                ScanRenderer.CAMERA_OPACITY = steps[k]; sp.edit().putFloat("abCamOpacity", steps[k]).apply();
                camB.setText("Camera opacity: " + Math.round(ScanRenderer.CAMERA_OPACITY * 100) + "%");
            });
            Button dotB = new Button(this);
            ScanRenderer.DOT_SCALE = sp.getFloat("abDotScale2", 1f);
            dotB.setText("Dot size: " + Math.round(ScanRenderer.DOT_SCALE * 100) + "%");
            dotB.setOnClickListener(v -> {   // 100% (the old size) -> 70% -> 50% -> 35% -> 100%
                float[] steps = {1f, 0.7f, 0.5f, 0.35f}; int k = 0;
                for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - ScanRenderer.DOT_SCALE) < 0.01f) k = (i + 1) % steps.length;
                ScanRenderer.DOT_SCALE = steps[k]; sp.edit().putFloat("abDotScale2", steps[k]).apply();
                dotB.setText("Dot size: " + Math.round(ScanRenderer.DOT_SCALE * 100) + "%");
            });
            Button closeB = new Button(this);
            ScanRenderer.CLOSEST_WINS = sp.getBoolean("abClosestWins", true);
            closeB.setText(ScanRenderer.CLOSEST_WINS ? "Closest sure look wins: on" : "Closest sure look wins: off");
            closeB.setOnClickListener(v -> { ScanRenderer.CLOSEST_WINS = !ScanRenderer.CLOSEST_WINS; sp.edit().putBoolean("abClosestWins", ScanRenderer.CLOSEST_WINS).apply(); closeB.setText(ScanRenderer.CLOSEST_WINS ? "Closest sure look wins: on" : "Closest sure look wins: off"); });
            Button paintB = new Button(this);
            ScanRenderer.PAINT_MIN = sp.getInt("abPaintMin3", 204);
            paintB.setText("Paint from confidence: " + Math.round(ScanRenderer.PAINT_MIN * 100 / 255f) + "%");
            paintB.setOnClickListener(v -> {   // 80% -> 90% -> 95% -> 100% (255 only) -> 50% -> all (the old 55) -> 80%
                int[] steps = {204, 230, 242, 255, 128, 55}; int k = 0; for (int i = 0; i < steps.length; i++) if (steps[i] == ScanRenderer.PAINT_MIN) k = (i + 1) % steps.length;
                ScanRenderer.PAINT_MIN = steps[k]; sp.edit().putInt("abPaintMin3", steps[k]).apply();
                paintB.setText("Paint from confidence: " + Math.round(ScanRenderer.PAINT_MIN * 100 / 255f) + "%");
            });
            Button stopB = new Button(this);
            ScanRenderer.STOP_ON_LOSS = sp.getBoolean("abStopOnLoss", true);
            stopB.setText(ScanRenderer.STOP_ON_LOSS ? "Stop the scan on a loss: on" : "Stop the scan on a loss: off");
            stopB.setOnClickListener(v -> { ScanRenderer.STOP_ON_LOSS = !ScanRenderer.STOP_ON_LOSS; sp.edit().putBoolean("abStopOnLoss", ScanRenderer.STOP_ON_LOSS).apply(); stopB.setText(ScanRenderer.STOP_ON_LOSS ? "Stop the scan on a loss: on" : "Stop the scan on a loss: off"); });
            Button greyB = new Button(this);
            ScanRenderer.CONF_GREY = sp.getBoolean("abConfGrey2", false);
            greyB.setText(ScanRenderer.CONF_GREY ? "Confidence greys: on" : "Confidence greys: off");
            greyB.setOnClickListener(v -> { ScanRenderer.CONF_GREY = !ScanRenderer.CONF_GREY; sp.edit().putBoolean("abConfGrey2", ScanRenderer.CONF_GREY).apply(); greyB.setText(ScanRenderer.CONF_GREY ? "Confidence greys: on" : "Confidence greys: off"); });
            Button fillB = new Button(this);
            ScanRenderer.FILL_WALL = sp.getBoolean("abFillWall", true);
            fillB.setText(ScanRenderer.FILL_WALL ? "Guess the plain wall: on" : "Guess the plain wall: off");
            fillB.setOnClickListener(v -> { ScanRenderer.FILL_WALL = !ScanRenderer.FILL_WALL; sp.edit().putBoolean("abFillWall", ScanRenderer.FILL_WALL).apply(); fillB.setText(ScanRenderer.FILL_WALL ? "Guess the plain wall: on" : "Guess the plain wall: off"); });
            Button noteB = new Button(this);
            ScanRenderer.STANDING_NOTE = sp.getBoolean("abStandingNote", true);
            noteB.setText(ScanRenderer.STANDING_NOTE ? "Standing notice: on" : "Standing notice: off");
            noteB.setOnClickListener(v -> { ScanRenderer.STANDING_NOTE = !ScanRenderer.STANDING_NOTE; sp.edit().putBoolean("abStandingNote", ScanRenderer.STANDING_NOTE).apply(); noteB.setText(ScanRenderer.STANDING_NOTE ? "Standing notice: on" : "Standing notice: off"); });
            Button pullB = new Button(this);
            ScanRenderer.PULL_BACK = sp.getBoolean("abPullBack", false);
            pullB.setText(ScanRenderer.PULL_BACK ? "Pulled-back view: on" : "Pulled-back view: off");
            pullB.setOnClickListener(v -> { ScanRenderer.PULL_BACK = !ScanRenderer.PULL_BACK; sp.edit().putBoolean("abPullBack", ScanRenderer.PULL_BACK).apply(); pullB.setText(ScanRenderer.PULL_BACK ? "Pulled-back view: on" : "Pulled-back view: off"); });
            Button lowB = new Button(this);
            final boolean lowWanted = sp.getBoolean("abLowMemory", false);
            lowB.setText((lowWanted ? "Low memory mode (like a 512 MB phone): on" : "Low memory mode (like a 512 MB phone): off")
                    + (lowWanted != ScanRenderer.FORCED_LOW_MEMORY ? ", after an app restart" : ""));
            lowB.setOnClickListener(v -> {
                boolean lowOn = !sp.getBoolean("abLowMemory", false);
                sp.edit().putBoolean("abLowMemory", lowOn).apply();
                lowB.setText((lowOn ? "Low memory mode (like a 512 MB phone): on" : "Low memory mode (like a 512 MB phone): off")
                        + (lowOn != ScanRenderer.FORCED_LOW_MEMORY ? ", after an app restart" : ""));
                toast("Close the app (swipe it away) and open it again");
            });
            Button photoB = new Button(this);
            ScanRenderer.PHOTO_SURFACE = sp.getBoolean("abPhotoSurface", false);
            photoB.setText(ScanRenderer.PHOTO_SURFACE ? "Photo surface: on" : "Photo surface: off");
            photoB.setOnClickListener(v -> { ScanRenderer.PHOTO_SURFACE = !ScanRenderer.PHOTO_SURFACE; sp.edit().putBoolean("abPhotoSurface", ScanRenderer.PHOTO_SURFACE).apply(); photoB.setText(ScanRenderer.PHOTO_SURFACE ? "Photo surface: on" : "Photo surface: off"); toast("From the next scan"); applyPhotoTab(); });
            Button liveB = new Button(this);
            ScanRenderer.LIVE_POINTS = sp.getBoolean("abLivePoints", true);
            liveB.setText(ScanRenderer.LIVE_POINTS ? "Live view: Points" : "Live view: Surface");
            liveB.setOnClickListener(v -> { ScanRenderer.LIVE_POINTS = !ScanRenderer.LIVE_POINTS; sp.edit().putBoolean("abLivePoints", ScanRenderer.LIVE_POINTS).apply(); liveB.setText(ScanRenderer.LIVE_POINTS ? "Live view: Points" : "Live view: Surface"); });
            ScanRenderer.SCREEN_VIDEO = sp.getBoolean("abScreen", !guestBuild());
            scrB.setText(ScanRenderer.SCREEN_VIDEO ? "Screen video: on" : "Screen video: off");
            scrB.setOnClickListener(v -> { ScanRenderer.SCREEN_VIDEO = !ScanRenderer.SCREEN_VIDEO; sp.edit().putBoolean("abScreen", ScanRenderer.SCREEN_VIDEO).apply(); scrB.setText(ScanRenderer.SCREEN_VIDEO ? "Screen video: on" : "Screen video: off"); toast("From the next scan"); });
            capB.setOnClickListener(v -> { renderer.fpsCap30 = !renderer.fpsCap30; sp.edit().putBoolean("abCap30v2", renderer.fpsCap30).apply(); capB.setText(renderer.fpsCap30 ? "Draw: 15 fps" : "Draw: 60 fps"); });
            for (Button bt : new Button[]{gpuB, testB, recB, focB, capB, scrB, liveB, photoB, tilesB, dropB, wallsB, lowB, spotB, warnB, uwB, pullB, fillB, noteB, greyB, stopB, paintB, closeB, dotB, camB, fineB}) {
                bt.setAllCaps(false); bt.setMinHeight(0); bt.setMinimumHeight(0); bt.setMinWidth(0); bt.setMinimumWidth(0);
                bt.setPadding(dp(12), dp(8), dp(12), dp(8)); Ios.pill(bt); bars.addView(bt);
            }
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.bottomMargin = dp(56);
        // Debug tools: their own scrolling panel (at most 30% of the screen) with the debug text above the buttons, so
        // they never cover the normal controls (rk 9 Nov: they spilled over the pills and the style switch).
        android.widget.ScrollView dbg = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.3f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout dbgBox = new LinearLayout(this);
        dbgBox.setOrientation(LinearLayout.VERTICAL);
        dbgBox.addView(bars, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dbg.addView(dbgBox);
        dbg.setBackgroundColor(0xCC000000);
        dbg.setVisibility(View.GONE);
        chrome.addView(dbg, blp);
        toolBars = dbg; toolBarsLp = blp; debugBox = dbgBox;
        sizeBtn = sizeButton; logBtn = logButton;
        // Debug tools are off at every start and never stored (rk 9 Nov: they stayed on after a long-press); this also
        // clears the setting older versions kept.
        debug = false;
        try { getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("debug").apply(); } catch (Exception ignored) { }
        mainButton.setOnLongClickListener(v -> {
            debug = !debug;
            toast(debug ? "Debug tools on (until the app closes)" : "Debug tools off");
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
                int want = !scanning && walkState == 0 ? viewerPanel.getHeight() + ((FrameLayout.LayoutParams) viewerPanel.getLayoutParams()).bottomMargin : 0;
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
                // Press and hold (0.9 s, a ring fills under the finger) clears the scan there so it can be redone
                // (rk 9 Nov; hold instead of tap since 10 Nov: a first-time user taps by instinct).
                int act = e.getActionMasked();
                if (!CLEAR_WHILE_SCANNING) return true;   // off for now (rk 10 Nov 11:20); "Scan area again" after the scan stays
                if (act == MotionEvent.ACTION_DOWN) {
                    downTime = e.getEventTime(); downX = e.getX(); downY = e.getY(); multiTouch = false;
                    startHoldRing(e.getX(), e.getY());
                }
                if (e.getPointerCount() > 1 && !multiTouch) { multiTouch = true; cancelHoldRing(); }
                if (act == MotionEvent.ACTION_MOVE && Math.hypot(e.getX() - downX, e.getY() - downY) >= dp(12)) cancelHoldRing();
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) cancelHoldRing();
                return true;
            }
            if (walkState == 0 && measureOverlay != null && e.getActionMasked() == MotionEvent.ACTION_DOWN) { downTime = e.getEventTime(); downX = e.getX(); downY = e.getY(); }
            if (walkState == 0 && measureOverlay != null && e.getActionMasked() == MotionEvent.ACTION_UP && e.getEventTime() - downTime < 300
                    && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12) && measureOverlay.tapAt(e.getX(), e.getY())) return true;
            if (walkTouch(e)) return true;
            if (objPanel.getVisibility() == View.VISIBLE && objectTouch(e)) return true;
            if (ScanRenderer.ADJUST_WALLS) {   // a finger on a room's wall line drags that wall (debug switch "Adjust room walls")
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) wallDragging = renderer.wallDragStart(e.getX(), e.getY());
                if (wallDragging) {
                    if (e.getPointerCount() > 1) { wallDragging = false; glView.queueEvent(renderer::wallDragEnd); }
                    else {
                        if (act == MotionEvent.ACTION_MOVE) renderer.wallDragTo(e.getX(), e.getY());
                        else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) { wallDragging = false; glView.queueEvent(renderer::wallDragEnd); }
                        return true;
                    }
                }
            }
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

    /** The app log survives restarts (11 Nov): the last session's app-log.txt is appended to app-log-history.txt
     *  (the newest 400 KB kept), which goes into the diagnostics and every .rmsc. */
    private void keepLogHistory() {
        try {
            File last = new File(getFilesDir(), "app-log.txt"), hist = new File(getFilesDir(), "app-log-history.txt");
            if (!last.exists() || last.length() == 0) return;
            byte[] old = hist.exists() ? java.nio.file.Files.readAllBytes(hist.toPath()) : new byte[0];
            byte[] add = java.nio.file.Files.readAllBytes(last.toPath());
            String head = "\n===== session ending " + new java.util.Date(last.lastModified()) + " =====\n";
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            b.write(old); b.write(head.getBytes("UTF-8")); b.write(add);
            byte[] all = b.toByteArray();
            int from = Math.max(0, all.length - 400_000);
            java.nio.file.Files.write(hist.toPath(), java.util.Arrays.copyOfRange(all, from, all.length));
            last.delete();
        } catch (Exception ignored) { }
    }

    /** Every user action into the app log (11 Nov: a teammate's empty scan couldn't be explained from the log): what was
     *  tapped, with the screen and the heap at that moment. Nothing personal: labels and counts only. */
    void act(String what) {
        if (renderer == null) return;
        Runtime rt = Runtime.getRuntime();
        String screen = homeShown() ? "home" : renderer.isViewing() ? (walkState == 1 ? "result: picking a spot" : walkState == 2 ? "result: in the room" : "result") : "scanning";
        renderer.log(String.format(java.util.Locale.US, "user: %s [%s, %d points, heap %d MB]", what, screen, renderer.pointCount(), (rt.totalMemory() - rt.freeMemory()) >> 20));
    }

    private void onMainButton() {
        act("tapped \"" + mainButton.getText() + "\"");
        if (!renderer.isViewing()) stopRecordingIfAny(); // Done ends the recording
        if (renderer.isViewing()) {
            // Rescan: a new scan from scratch. Asked first (11 Nov: a tap here emptied the view and the next "name"
            // saved the empty state as a new scan); the scan on screen stays saved either way.
            new android.app.AlertDialog.Builder(this).setTitle("Start a new scan?")
                    .setMessage("The scan you're looking at stays saved in your list.")
                    .setPositiveButton("New scan", (d, w) -> {
                        act("new scan: confirmed");
                        glView.queueEvent(() -> renderer.startNewScan());
                        setRoomSize(null);
                        nameValue.setText("Add a name");
                        scanUi();
                        resumeSession();
                    })
                    .setNegativeButton("Cancel", (d, w) -> act("new scan: cancelled")).show();
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

    /** The Photo tab only with the photo surface on (otherwise the result is Points / Mesh). */
    private void applyPhotoTab() {
        if (styleTabs[0] != null) styleTabs[0].setVisibility(ScanRenderer.PHOTO_SURFACE ? View.VISIBLE : View.GONE);
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
    /** The debug panel's content (the debug text goes in here while the tools are on). */
    private LinearLayout debugBox;
    private FrameLayout.LayoutParams statusLp;
    private Button sizeBtn, logBtn;
    private LinearLayout hud, procBox;
    private FrameLayout.LayoutParams procLp;
    private TextView hudHint, hudSub, hudDetailPct, hudRoomPct, hudTimer, procLabel;
    private android.widget.ProgressBar hudDetail, hudRoom, procBar;

    /** Which buttons show: while scanning only Finish; in the viewer the everyday tools; everything else in debug mode (long-press the main button). */
    private void applyUi() {
        if (measureOverlay != null) measureOverlay.setVisibility(renderer.isViewing() || scanning ? View.VISIBLE : View.GONE);   // live doorway outlines while scanning
        renderer.overlays = debug;
        updateSpend();
        Button[] viewerMain = {homeButton, exportButton, objButton, saveButton, undoButton, removePersonButton, interviewButton};
        lightButton.setVisibility(!scanning ? View.VISIBLE : View.GONE); // in the pill row, like the iPhone's Lighting
        for (Button b : new Button[]{catalogButton, scannedItemsButton, previewButton}) b.setVisibility(debug && !scanning ? View.VISIBLE : View.GONE); // Marje's planning tools: debug until rk decides
        // Every scan records itself now, so the Record session button is gone (toggleRecording stays for tests).
        recordButton.setVisibility(View.GONE);
        // Splat training and the splat style cycle are gone with the Splats view (rk 9 Nov).
        trainButton.setVisibility(View.GONE); splatButton.setVisibility(View.GONE);
        Button[] tools = {depthButton, cleanButton, viewButton, sizeBtn, liveClaudeButton, logBtn, planButton, cutButton, topButton, claudeButton};
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
        // With the debug tools on, the debug text sits in their panel; otherwise (lighting readout) at the top as before.
        ViewGroup statusHome = debug ? debugBox : (ViewGroup) scanChrome;
        if (statusHome != null && status.getParent() != statusHome) {
            if (status.getParent() != null) ((ViewGroup) status.getParent()).removeView(status);
            if (debug) statusHome.addView(status, 0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            else statusHome.addView(status, 0, statusLp);
        }
        if (toolBars != null) toolBars.setVisibility(debug && walkState == 0 ? View.VISIBLE : View.GONE);
        if (hud != null) hud.setVisibility(scanning ? View.VISIBLE : View.GONE);
        showScanTip(scanning);
        if (procBox != null && scanning) procBox.setVisibility(View.GONE);
        // Viewer, laid out like the iPhone: Back / title / ✦ / Share on top, Photo | Splats | Points under it,
        // Continue scan under the 3D view; Rename, Undo, Remove person, Interview, Ask Claude and New scan in ✦.
        boolean viewer = !scanning;
        if (viewerTop != null) {
            viewerTop.setVisibility(viewer ? View.VISIBLE : View.GONE);
            styleSwitch.setVisibility(viewer ? View.VISIBLE : View.GONE);
            updateSplatTab(viewer);
            viewerPanel.setVisibility(viewer && walkState == 0 ? View.VISIBLE : View.GONE);
            pillRow.setVisibility(viewer ? View.VISIBLE : View.GONE);
            setViewerTitle();
            nameValue.setText(renderer.currentName != null ? renderer.currentName : "Add a name");
            File cd = renderer.currentDir;
            viewerShared.setVisibility(cd != null && SyncInfo.file(cd).exists() && SyncInfo.load(this, cd).shared ? View.VISIBLE : View.GONE);
            String size = renderer.roomSizeText();
            // "scanned area" is in the title; the card keeps just the numbers
            if (size != null && size.startsWith("scanned area ")) size = size.substring(13) + " (scanned area)";
            sizeValue.setText(size != null ? size : "—");
            updateFacts();
            // The 3D view ends where the panel starts, so the model is framed in the part you can see (like the iPhone).
            viewerPanel.post(() -> {
                FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glView.getLayoutParams();
                int want = viewer && walkState == 0 ? viewerPanel.getHeight() + ((FrameLayout.LayoutParams) viewerPanel.getLayoutParams()).bottomMargin : 0;
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
            java.io.File dir = renderer.currentDir;
            if (dir != null && PhotoRealActivity.result(dir).exists()) { photoRealButton.setText("Show photo-real"); menuActions.add(0, photoRealButton); }
            else if (PhotoRealActivity.possible(this, dir)) { photoRealButton.setText("Make photo-real"); menuActions.add(0, photoRealButton); }
        }
    }

    // ---------------- viewer chrome, matching the iPhone app ----------------

    private LinearLayout viewerTop, viewerBottom, styleSwitch, pillRow;
    private TextView walkTip;
    private View toolBars;
    /** The scrolling panel under the 3D view (holds viewerBottom). */
    private android.widget.ScrollView viewerPanel;
    private FrameLayout.LayoutParams toolBarsLp;
    private TextView viewerTitle, resetWalls;
    /** The room's size under the title in the 3D view ("5.12 × 6.70 m · 2.48 m high"), or null. */
    private volatile String roomSizeText;

    void setRoomSize(String s) { roomSizeText = s; runOnUiThread(this::setViewerTitle); }

    private void setViewerTitle() {
        if (viewerTitle == null) return;
        String name = renderer.currentName != null ? renderer.currentName : "Scan", size = roomSizeText != null ? renderer.roomTitleText() : null;
        if (resetWalls != null) resetWalls.setVisibility(renderer.wallsEdited() ? View.VISIBLE : View.GONE);
        // the ROOM card's size row follows every change too (it was only set when the scan opened: stale after a drag)
        if (sizeValue != null) { String full = renderer.roomSizeText(); if (full != null && full.startsWith("scanned area ")) full = full.substring(13) + " (scanned area)"; sizeValue.setText(full != null ? full : "—"); }
        if (size == null) { viewerTitle.setText(name); return; }
        // "scanned area" on a line of its own, so the size itself never gets cut off (rk 10 Nov 12:07)
        if (size.startsWith("scanned area ")) size = "scanned area\n" + size.substring(13);
        android.text.SpannableString t = new android.text.SpannableString(name + "\n" + size);
        t.setSpan(new android.text.style.RelativeSizeSpan(0.8f), name.length() + 1, t.length(), 0);
        t.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFD60A), name.length() + 1, t.length(), 0);
        viewerTitle.setText(t);
    }
    private android.widget.ImageView viewerShared;
    private final java.util.List<Button> menuActions = new java.util.ArrayList<>();
    private final TextView[] styleTabs = new TextView[3];
    /** The renderer style behind each tab: Photo, Points, Mesh (rk 9 Nov: the Splats view is gone; style 1 is no
     *  longer offered, its data only feeds the Lighting preview). */
    private static final int[] TAB_STYLE = {0, 2, 3};

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
        viewerTitle.setMaxLines(3);
        viewerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        viewerTop.addView(viewerTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // "Reset walls": only after a room's walls were dragged by hand (the scan's own outline back)
        resetWalls = new TextView(this);
        resetWalls.setText("Reset walls");
        resetWalls.setTextColor(0xFFFFD60A);
        resetWalls.setTextSize(13);
        resetWalls.setPadding(dp(10), dp(6), dp(10), dp(6));
        resetWalls.setVisibility(View.GONE);
        resetWalls.setOnClickListener(v -> glView.queueEvent(renderer::resetWallEdits));
        viewerTop.addView(resetWalls, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
        String[] names = {"Photo", "Points", "Mesh"};
        for (int i = 0; i < names.length; i++) {
            final int s = TAB_STYLE[i];
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(6), dp(16), dp(6));
            final String tabName = names[i];
            t.setOnClickListener(v -> { act("view tab \"" + tabName + "\""); hideSplatView(); renderer.setStyle(s); updateStyleSwitch(); splatButton.setText(renderer.styleLabel()); });
            styleTabs[i] = t;
            styleSwitch.addView(t);
        }
        // Photo-real: the scan's splat in this same view, a fourth tab once it has been made (rk 13 Nov 17:12)
        splatTab = new TextView(this);
        splatTab.setText("Photo-real");
        splatTab.setTextColor(Color.WHITE);
        splatTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        splatTab.setGravity(Gravity.CENTER);
        splatTab.setPadding(dp(16), dp(6), dp(16), dp(6));
        splatTab.setVisibility(View.GONE);
        splatTab.setOnClickListener(v -> { act("view tab \"Photo-real\""); showSplatView(); });
        styleSwitch.addView(splatTab);
        applyPhotoTab();
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
        nameRow = (LinearLayout) Ios.row(this, 0, "Name", false, false, v -> nameScan());
        ((TextView) nameRow.getChildAt(0)).setTextColor(Color.WHITE);
        nameValue = new TextView(this);
        nameValue.setTextColor(Ios.ACCENT);
        nameValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nameRow.addView(nameValue);
        sizeRow = (LinearLayout) Ios.row(this, 0, "Approx. size", false, false, null);
        ((TextView) sizeRow.getChildAt(0)).setTextColor(Color.WHITE);
        ((TextView) sizeRow.getChildAt(0)).setSingleLine(true);   // the label never wraps; the value does
        sizeValue = new TextView(this);
        sizeValue.setTextColor(Ios.SECONDARY);
        sizeValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        // The label keeps its width; the value takes the rest and wraps onto a second line (rk 10 Nov 13:30: a long
        // value squeezed "Approx. size" down to "A").
        ((TextView) sizeRow.getChildAt(0)).setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sizeValue.setGravity(Gravity.END); sizeValue.setMaxLines(2);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        svp.leftMargin = dp(12);
        sizeRow.addView(sizeValue, svp);
        roomCard = (LinearLayout) Ios.card(this, nameRow, sizeRow);
        viewerBottom.addView(roomCard);
        viewerBottom.addView(Ios.footer(this, "≈ estimate. Use Measure in Enter the room for exact sizes. A dashed doorway: scan around its frame for its size."));
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
        // The panel ends above the system navigation bar (rk 10 Nov 12:23: the last row sat under it).
        viewerPanel.setOnApplyWindowInsetsListener((v, insets) -> {
            viewerBottom.setPadding(dp(16), dp(10), dp(16), dp(18));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp != null && lp.bottomMargin != insets.getSystemWindowInsetBottom()) { lp.bottomMargin = insets.getSystemWindowInsetBottom(); v.setLayoutParams(lp); }
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

        // Standing in the room (rk 10 Nov 12:07: the move buttons went off the screen): the moves (a D-pad: forward,
        // back, left, right, hold to keep going) and the eye height in their own cluster on the right, above a bottom
        // bar that wraps (Exit, Gyro, Measure, Clear) whatever the screen width or text size.
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), 0, dp(16), dp(28));
        FlowRow bar = new FlowRow(this, dp(8));
        LinearLayout cluster = new LinearLayout(this);
        cluster.setOrientation(LinearLayout.HORIZONTAL);
        cluster.setGravity(Gravity.BOTTOM | Gravity.END);
        Button exit = new Button(this);
        exit.setText("↩  Exit");
        Ios.filled(exit);
        exit.setOnClickListener(v -> setWalk(0));
        bar.addView(exit, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        // Gyro look (rk 10 Nov): turn / tilt the phone to look around; on by default, remembered.
        Button gyro = new Button(this);
        boolean gyroOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("walkGyro", true);
        renderer.gyroLook = gyroOn;
        gyro.setText(gyroOn ? "Gyro: on" : "Gyro: off");
        Ios.textButton(gyro);
        gyro.setOnClickListener(v -> {
            boolean on = !renderer.gyroLook;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("walkGyro", on).apply();
            glView.queueEvent(() -> renderer.setGyroLook(on));
            gyro.setText(on ? "Gyro: on" : "Gyro: off");
            setGyroSensor(walkState == 2 && on);
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        glp.leftMargin = dp(8);
        bar.addView(gyro, glp);
        // Measure (rk 10 Nov): tap two spots on the scan for the distance between them.
        measureButton = new Button(this);
        measureButton.setText("Measure");
        Ios.textButton(measureButton);
        measureButton.setOnClickListener(v -> { act("Measure"); setMeasuring(!measuring); });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        mlp.leftMargin = dp(8);
        bar.addView(measureButton, mlp);
        measureClear = new Button(this);
        measureClear.setText("Clear");
        Ios.textButton(measureClear);
        measureClear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Remove all measurements?")
                .setMessage("They're saved with this scan.").setPositiveButton("Remove all", (d, w) -> { measureOverlay.clearAll(); updateMeasureButtons(); })
                .setNegativeButton("Cancel", null).show());
        measureClear.setVisibility(View.GONE);
        bar.addView(measureClear, mlp);
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
        cluster.addView(h, hlp);
        android.widget.GridLayout pad = new android.widget.GridLayout(this);
        pad.setColumnCount(3);
        View[] cells = {new View(this), holdButton("▲", 1, 0), new View(this), holdButton("◀", 0, -1), holdButton("▼", -1, 0), holdButton("▶", 0, 1)};
        for (View v : cells) {
            android.widget.GridLayout.LayoutParams gl = new android.widget.GridLayout.LayoutParams();
            gl.width = dp(52); gl.height = dp(52); gl.setMargins(dp(3), dp(3), dp(3), dp(3));
            pad.addView(v, gl);
        }
        cluster.addView(pad);
        c.addView(walkTipHolder = new FrameLayout(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        c.addView(cluster, clp);
        c.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        walkControls = c;
        c.setVisibility(View.GONE);
        walkTip = new TextView(this);
        walkTip.setText("Turn the phone or drag to look · pinch to zoom · double-tap the floor to walk there");
        walkTip.setTextColor(Color.WHITE);
        walkTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
        tb.setColor(0xE62C2C2E); tb.setCornerRadius(dp(14));
        walkTip.setBackground(tb);
        walkTip.setPadding(dp(10), dp(5), dp(10), dp(5));
        walkTip.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        tp.bottomMargin = dp(8);
        walkTipHolder.addView(walkTip, tp);   // above the controls, never over them
        // Above the system navigation bar (the app draws under it).
        c.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16), 0, dp(16), dp(28) + insets.getSystemWindowInsetBottom());
            FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) walkHint.getLayoutParams();
            wl.bottomMargin = dp(40) + insets.getSystemWindowInsetBottom();
            walkHint.setLayoutParams(wl);
            return insets;
        });
        chrome.addView(c, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        renderer.setEyeHeight(getSharedPreferences(PREFS, MODE_PRIVATE).getFloat("walkEyeHeight", 1.5f));
    }

    /** A round button that walks while held. */
    private View holdButton(String label, int dir) { return holdButton(label, dir, 0); }

    /** A round button that moves while held: dir forward (+1) / back (-1), side right (+1) / left (-1). */
    private View holdButton(String label, int dir, int side) {
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
            if (a == MotionEvent.ACTION_DOWN) { renderer.walkMove = dir; renderer.walkStrafe = side; v.setAlpha(0.6f); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { renderer.walkMove = 0; renderer.walkStrafe = 0; v.setAlpha(1f); }
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

    private boolean gyroRegistered;

    /** The rotation sensor only while standing in the room with gyro look on. */
    private void setGyroSensor(boolean on) {
        android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm == null) return;
        if (on && !gyroRegistered) {
            android.hardware.Sensor rv = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rv != null) gyroRegistered = sm.registerListener(renderer.gyroListener, rv, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        } else if (!on && gyroRegistered) {
            sm.unregisterListener(renderer.gyroListener);
            gyroRegistered = false;
        }
    }

    /** 0 = back to orbiting, 1 = "tap the floor", 2 = standing in the room. */
    private void setWalk(int state) {
        act(state == 1 ? "Enter the room (pick a spot)" : state == 2 ? "standing in the room" : "left Enter the room");
        walkState = state;
        setGyroSensor(state == 2 && renderer.gyroLook);
        if (state == 0) glView.queueEvent(renderer::exitWalk);
        if (objPanel.getVisibility() == View.VISIBLE) setObjectMode(false);
        boolean viewerChrome = state == 0;
        viewerPanel.setVisibility(viewerChrome ? View.VISIBLE : View.GONE);
        toolBars.setVisibility(viewerChrome && debug ? View.VISIBLE : View.GONE);
        // The top rows (Lighting, Objects, the view switch) stay while standing in the room, as on the iPhone.
        styleSwitch.setVisibility(View.VISIBLE);
        pillRow.setVisibility(View.VISIBLE);
        walkHint.setVisibility(state == 1 ? View.VISIBLE : View.GONE);
        walkControls.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        if (state != 2 && measuring) setMeasuring(false);
        if (measureOverlay != null) measureOverlay.setVisibility(renderer.isViewing() || scanning ? View.VISIBLE : View.GONE);   // live doorway outlines while scanning
        walkTip.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        heightLabel.setText(String.format(java.util.Locale.US, "%.2f m", renderer.eyeH));
        // Standing in the room uses the whole screen; otherwise the 3D view ends above the panel.
        viewerPanel.post(() -> {
            FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glView.getLayoutParams();
            int want = state == 0 ? viewerPanel.getHeight() + ((FrameLayout.LayoutParams) viewerPanel.getLayoutParams()).bottomMargin : 0;
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
        // A tap on a measurement selects it (Delete next to it), in or out of Measure mode; a tap on the scan in
        // Measure mode adds a point.
        if (walkState == 2 && measureOverlay != null && act == MotionEvent.ACTION_UP && !multiTouch && e.getEventTime() - downTime < 300
                && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12) && measureOverlay.tapAt(e.getX(), e.getY())) { updateMeasureButtons(); return true; }
        if (walkState == 2 && measuring && act == MotionEvent.ACTION_UP && !multiTouch && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
            float tx = e.getX(), ty = e.getY();
            long held = e.getEventTime() - downTime;
            if (held < 300) {
                glView.queueEvent(() -> {
                    float[] p = renderer.measurePick(tx, ty);
                    runOnUiThread(() -> {
                        if (p == null) { toast("Nothing scanned there"); return; }
                        measureOverlay.add(p, renderer.walkFloorY());
                        updateMeasureButtons();
                    });
                });
            }
            return true;
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
        int s = splatWeb != null ? -1 : renderer.style();
        for (int i = 0; i < styleTabs.length; i++) {
            android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
            on.setColor(0xFF636366); on.setCornerRadius(dp(7));
            styleTabs[i].setBackground(TAB_STYLE[i] == s ? on : null);
            styleTabs[i].setTypeface(TAB_STYLE[i] == s ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
        if (splatTab != null) {
            android.graphics.drawable.GradientDrawable on = new android.graphics.drawable.GradientDrawable();
            on.setColor(0xFF636366); on.setCornerRadius(dp(7));
            splatTab.setBackground(splatWeb != null ? on : null);
            splatTab.setTypeface(splatWeb != null ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    // ---------------- the Photo-real tab: the splat in the room view itself (rk 13 Nov 17:12) ----------------

    private TextView splatTab;
    private File photoRealReturn;   // the scan being made photo-real on its own screen
    private android.webkit.WebView splatWeb;
    private File splatDir;

    /** Shows or hides the Photo-real tab for the open scan, and closes the splat when the view no longer fits it. */
    private void updateSplatTab(boolean viewer) {
        if (splatTab == null) return;
        File d = renderer.currentDir;
        boolean has = viewer && walkState == 0 && d != null && PhotoRealActivity.result(d).exists();
        splatTab.setVisibility(has ? View.VISIBLE : View.GONE);
        if (splatWeb != null && (!has || !d.equals(splatDir))) hideSplatView();
    }

    /** The splat over the 3D view, from the same camera; the GL view rests underneath until the tab is left. */
    private void showSplatView() {
        File d = renderer.currentDir;
        if (d == null || !PhotoRealActivity.result(d).exists()) return;
        if (splatWeb != null) return;
        float[] c = renderer.orbitCamera();
        android.webkit.WebView web = new android.webkit.WebView(this);
        web.setBackgroundColor(0xFF111214);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.setWebViewClient(PhotoRealActivity.viewerClient(this, PhotoRealActivity.result(d)));
        ViewGroup root = (ViewGroup) glView.getParent();
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) glView.getLayoutParams();
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        wp.bottomMargin = gp.bottomMargin;
        root.addView(web, root.indexOfChild(glView) + 1, wp);
        splatWeb = web; splatDir = d;
        web.loadUrl(String.format(java.util.Locale.US, "%ssplat/index.html?cam=%.3f,%.3f,%.3f&look=%.3f,%.3f,%.3f&fov=60",
                PhotoRealActivity.HOST, c[0], c[1], c[2], c[3], c[4], c[5]));
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);   // no point drawing the room under it
        updateStyleSwitch();
    }

    /** Back to the room view, at the splat view's camera. */
    private void hideSplatView() {
        final android.webkit.WebView web = splatWeb;
        if (web == null) return;
        splatWeb = null; splatDir = null;
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        updateStyleSwitch();
        web.evaluateJavascript("window.roomCam ? window.roomCam() : null", v -> {
            try {
                if (v != null && !v.equals("null")) {
                    String json = v.startsWith("\"") ? new org.json.JSONTokener(v).nextValue().toString() : v;
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    org.json.JSONArray e = o.getJSONArray("cam"), l = o.getJSONArray("look");
                    renderer.setOrbitCamera((float) e.getDouble(0), (float) e.getDouble(1), (float) e.getDouble(2),
                            (float) l.getDouble(0), (float) l.getDouble(1), (float) l.getDouble(2));
                }
            } catch (Exception ignored) { }
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        });
    }

    /** Opens the photo-real screen for the open scan; a new one only after the warning (rk 12 Nov 22:41). */
    private void openPhotoReal() {
        final java.io.File dir = renderer.currentDir;
        if (dir == null) return;
        if (!PhotoRealActivity.result(dir).exists() && !PhotoRealActivity.possible(this, dir)) {
            if (renderer.framesNotKeptYet()) {   // right after Done: finish saving the scan's pictures first
                android.widget.Toast.makeText(this, "Finishing saving the scan…", android.widget.Toast.LENGTH_SHORT).show();
                renderer.keepFramesNow(() -> {
                    if (dir.equals(renderer.currentDir) && PhotoRealActivity.possible(this, dir)) openPhotoReal();
                    else android.widget.Toast.makeText(this, "This scan has no pictures to make it photo-real.", android.widget.Toast.LENGTH_LONG).show();
                });
            } else android.widget.Toast.makeText(this, "This scan has no pictures to make it photo-real.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (PhotoRealActivity.result(dir).exists()) { showSplatView(); return; }   // made already: the Photo-real tab
        Runnable go = () -> { act("Make photo-real"); photoRealReturn = dir; startActivity(new android.content.Intent(this, PhotoRealActivity.class).putExtra(PhotoRealActivity.EXTRA_SCAN, dir.getAbsolutePath())); };
        new android.app.AlertDialog.Builder(this).setTitle("Make photo-real?")
                .setMessage("This takes a while (about 5 to 15 minutes) and the phone gets hot. Keep the app open on the next screen until it's done.")
                .setPositiveButton("Continue", (d, w) -> go.run()).setNegativeButton("Not now", null).show();
    }

    /** ✦: the scan's other actions, as the iPhone app groups them in its menus. */
    private void showViewerMenu() {
        java.util.List<Button> items = new java.util.ArrayList<>(menuActions);
        // Decided when the menu opens, not when the result view opened: right after Done the scan is still being saved
        // and its rebuild-frames/ only move in at the end, so the entry was missing (rk 13 Nov 16:55 "cant find photoreal").
        items.remove(photoRealButton);
        java.io.File dir = renderer.currentDir;
        if (dir != null && PhotoRealActivity.result(dir).exists()) { photoRealButton.setText("Show photo-real"); items.add(0, photoRealButton); }
        else if (PhotoRealActivity.possible(this, dir)) { photoRealButton.setText("Make photo-real"); items.add(0, photoRealButton); }
        else if (dir != null && renderer.framesNotKeptYet() && BrushNative.present(this)) {
            photoRealButton.setText("Make photo-real"); items.add(0, photoRealButton); }
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
        hudSpot = new android.widget.ImageView(this);   // the good spot to point at after a loss (ScanRenderer.pickGoal)
        hudSpot.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        hudSpot.setAdjustViewBounds(true);
        hudSpot.setVisibility(View.GONE);
        hudSpot.setContentDescription("The spot to point the phone at");
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(260));
        spl.topMargin = dp(6);
        hud.addView(hudSpot, spl);
        hudDetail = bar(Ios.ACCENT); hudDetailPct = new TextView(this);
        hudRoom = bar(Ios.ACCENT); hudRoomPct = new TextView(this);
        LinearLayout r1 = barRow("This spot", hudDetail, hudDetailPct), r2 = barRow("Room", hudRoom, hudRoomPct);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(10);
        hud.addView(r1, rp);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp2.topMargin = dp(6);
        hud.addView(r2, rp2);
        r1.setVisibility(View.GONE); r2.setVisibility(View.GONE);   // hidden (rk 13 Nov), see `bars` below
        // Scan length (rk 10 Nov): counts only while depth is being fused (not while the position is lost).
        hudTimer = new TextView(this);
        hudTimer.setTextColor(0x99FFFFFF); hudTimer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hudTimer.setTypeface(android.graphics.Typeface.MONOSPACE);
        hudTimer.setGravity(Gravity.END);
        hudTimer.setText("0:00");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        hud.addView(hudTimer, tp);
        hud.setOnLongClickListener(v -> { mainButton.performLongClick(); return true; });
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        hp.topMargin = dp(40); hp.leftMargin = dp(12); hp.rightMargin = dp(12);
        chrome.addView(hud, hp);

        // The capture zone's outline and the slightly darker outside, over the GL view (as the iPhone's CaptureZone).
        zoneFrame = new ZoneFrame(this);
        zoneFrame.setVisibility(View.GONE);
        chrome.addView(zoneFrame, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // One small tip capsule just inside the capture zone's bottom edge, rotating for the whole scan (rk 13 Nov, as
        // on the iPhone): 7 s each with a soft fade, looping. Android has no LiDAR, so "Look around" comes first and
        // every second tip (rk 13 Nov 11:26).
        scanTip = new TextView(this);
        scanTip.setTextColor(0xE6FFFFFF); scanTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        scanTip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
        tb.setColor(0x73000000); tb.setCornerRadius(dp(14));
        scanTip.setBackground(tb);
        scanTip.setPadding(dp(10), dp(5), dp(10), dp(5));
        scanTip.setVisibility(View.GONE);
        chrome.addView(scanTip, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        chrome.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeScanTip());

        // A standing note just inside the zone's top edge (rk 13 Nov 14:18): circle around what you scan, as if on an
        // invisible ball around it, rather than panning side to side or up and down; that gives the S24 the most depth.
        orbitNote = new LinearLayout(this);
        orbitNote.setOrientation(LinearLayout.HORIZONTAL);
        orbitNote.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable ob2 = new android.graphics.drawable.GradientDrawable();
        ob2.setColor(0x73000000); ob2.setCornerRadius(dp(16));
        orbitNote.setBackground(ob2);
        orbitNote.setPadding(dp(6), dp(3), dp(11), dp(3));
        orbitNote.addView(new OrbitIcon(this), new LinearLayout.LayoutParams(dp(44), dp(30)));
        TextView ot2 = new TextView(this);
        ot2.setText("Circle around");   // minimal: the animation explains the move (rk 13 Nov 14:19)
        ot2.setTextColor(0xE6FFFFFF); ot2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ot2.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(5);
        orbitNote.addView(ot2, olp);
        orbitNote.setVisibility(View.GONE);
        chrome.addView(orbitNote, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        orbitNote.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeScanTip());
        scanTip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeScanTip());

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
            b.setMinWidth(dp(96)); b.setMinimumWidth(dp(96)); b.setMinHeight(0); b.setMinimumHeight(0);
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
    private TextView scanTip;
    private LinearLayout orbitNote;
    private ZoneFrame zoneFrame;
    /** The capture zone on screen: the outside 18% darker (even-odd fill) and a thin light outline, radius 22 dp. A View
     *  rather than a GL pass (on the S24 the late GL pass showed in only some frames: the outline flickered). */
    static final class ZoneFrame extends View {
        private final android.graphics.Paint dim = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF rect = new android.graphics.RectF();
        ZoneFrame(android.content.Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            dim.setStyle(android.graphics.Paint.Style.FILL); dim.setColor(0x2E000000);
            edge.setStyle(android.graphics.Paint.Style.STROKE); edge.setStrokeWidth(Math.max(1f, d)); edge.setColor(0x73FFFFFF);
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float rad = ScanRenderer.ZONE_RADIUS_DP * getResources().getDisplayMetrics().density;
            rect.set(w * (1 - ScanRenderer.ZONE_W) / 2, h * (1 - ScanRenderer.ZONE_H) / 2, w * (1 + ScanRenderer.ZONE_W) / 2, h * (1 + ScanRenderer.ZONE_H) / 2);
            path.reset(); path.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
            path.addRoundRect(rect, rad, rad, android.graphics.Path.Direction.CW);
            invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float rad = ScanRenderer.ZONE_RADIUS_DP * getResources().getDisplayMetrics().density;
            cv.drawPath(path, dim);
            cv.drawRoundRect(rect, rad, rad, edge);
        }
    }
    /** The standing note's small looping animation (rk 13 Nov 14:19: "maybe that move isn't clearly apparent from a
     *  still image"): an object in the middle, a faint tilted orbit around it (the invisible ball seen from the side),
     *  and the phone gliding along the orbit, always turned to face the object; behind the object it goes dimmer and
     *  is hidden by it. One lap every 3.2 s, drawn only while on screen. */
    static final class OrbitIcon extends View {
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint phone = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF(), pr = new android.graphics.RectF();
        static final long LAP_MS = 3200;
        OrbitIcon(android.content.Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            line.setStyle(android.graphics.Paint.Style.STROKE); line.setStrokeWidth(1.2f * d); line.setColor(0x66FFFFFF);
            line.setPathEffect(new android.graphics.DashPathEffect(new float[]{2.5f * d, 2f * d}, 0));
            fill.setStyle(android.graphics.Paint.Style.FILL); fill.setColor(0xE6FFFFFF);
            phone.setStyle(android.graphics.Paint.Style.FILL); phone.setColor(0xFFEDB14D);   // the app's amber
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, d = getResources().getDisplayMetrics().density;
            float rx = w * 0.40f, ry = h * 0.30f;
            r.set(cx - rx, cy - ry, cx + rx, cy + ry);
            cv.drawArc(r, 0, 180, false, line);   // the near half of the orbit: a half orbit, left and back right (rk 13 Nov 14:25)
            double t = 2 * Math.PI * ((android.os.SystemClock.uptimeMillis() % LAP_MS) / (double) LAP_MS);
            double a = Math.PI / 2 + Math.PI / 2 * Math.sin(t);
            float px = cx + rx * (float) Math.cos(a), py = cy + ry * (float) Math.sin(a);
            float o = 4f * d;
            phone.setAlpha(255);
            cv.drawRoundRect(new android.graphics.RectF(cx - o, cy - o * 1.15f, cx + o, cy + o * 1.15f), 1.2f * d, 1.2f * d, fill);   // the object
            drawPhone(cv, px, py, cx, cy, d);
            if (isShown()) postInvalidateOnAnimation();
        }
        /** The phone at (px, py), its back turned to the orbit's outside: its long side across the line to the object. */
        private void drawPhone(android.graphics.Canvas cv, float px, float py, float cx, float cy, float d) {
            float deg = (float) Math.toDegrees(Math.atan2(cy - py, cx - px));
            cv.save();
            cv.rotate(deg, px, py);
            pr.set(px - 1.4f * d, py - 3.4f * d, px + 1.4f * d, py + 3.4f * d);
            cv.drawRoundRect(pr, 0.9f * d, 0.9f * d, phone);
            cv.restore();
        }
    }
    private int scanTipIndex = -1;
    static final long SCAN_TIP_MS = 7000;
    static final String[] SCAN_TIPS = {"🔄  Look around", "🐢  Move slowly", "🔄  Look around", "🔍  Go closer"};
    private final Runnable scanTipTick = new Runnable() {
        @Override public void run() {
            if (scanTip == null || scanTip.getVisibility() != View.VISIBLE) return;
            scanTipIndex = (scanTipIndex + 1) % SCAN_TIPS.length;
            final String next = SCAN_TIPS[scanTipIndex];
            if (scanTipIndex == 0 && scanTip.getAlpha() < 0.01f) { scanTip.setText(next); scanTip.animate().alpha(1f).setDuration(500).start(); }
            else scanTip.animate().alpha(0f).setDuration(250).withEndAction(() -> { scanTip.setText(next); scanTip.animate().alpha(1f).setDuration(250).start(); }).start();
            scanTip.postDelayed(this, SCAN_TIP_MS);
        }
    };
    /** The tip's centre sits 22 dp above the capture zone's bottom edge (ScanRenderer.ZONE_H of the screen's height). */
    private void placeScanTip() {
        if (scanTip == null || !(scanTip.getParent() instanceof View)) return;
        int h = ((View) scanTip.getParent()).getHeight();
        if (h <= 0) return;
        float y = h * (1 + ScanRenderer.ZONE_H) / 2f - dp(22) - scanTip.getHeight() / 2f;
        if (Math.abs(scanTip.getTranslationY() - y) > 0.5f) scanTip.setTranslationY(y);
        if (orbitNote != null) {   // just inside the zone's top edge
            float oy = h * (1 - ScanRenderer.ZONE_H) / 2f + dp(10);
            if (Math.abs(orbitNote.getTranslationY() - oy) > 0.5f) orbitNote.setTranslationY(oy);
        }
    }
    /** Shown while scanning: starts from the first tip each scan. */
    private void showScanTip(boolean on) {
        if (scanTip == null) return;
        if (orbitNote != null) orbitNote.setVisibility(on ? View.VISIBLE : View.GONE);
        if (zoneFrame != null) zoneFrame.setVisibility(on ? View.VISIBLE : View.GONE);
        boolean was = scanTip.getVisibility() == View.VISIBLE;
        if (on == was) return;
        scanTip.removeCallbacks(scanTipTick);
        scanTip.animate().cancel();
        if (on) { scanTipIndex = -1; scanTip.setAlpha(0f); scanTip.setVisibility(View.VISIBLE); scanTipTick.run(); }
        else scanTip.setVisibility(View.GONE);
    }
    /** Hint colours by level: white, white, red for warnings (the same on the iPhone). */
    private static final int[] HINT_COLORS = {Color.WHITE, Color.WHITE, 0xFFFF6B5B, 0xFFFFB020};   // 3 = amber: lost track (rk 10 Nov 16:45)

    /** One short haptic pulse (lost track) or a lighter tick (scanning again). */
    void haptic(boolean strong) { runOnUiThread(() -> glView.performHapticFeedback(strong ? android.view.HapticFeedbackConstants.LONG_PRESS : android.view.HapticFeedbackConstants.CLOCK_TICK)); }

    void planningModeChanged() {
        runOnUiThread(() -> {
            previewButton.setText(renderer.planningPreview ? "Show original scan" : "Show planning room");
            applyUi();
            if(renderer.planningPreview) updateStatus("Planning shapes only — approximate detected furniture and catalog size proxies. Original fused scan/photos unchanged; hidden surfaces are NOT reconstructed.");
        });
    }

    /** A crash leaves crash-last.txt (stack, time, device), sent with the next export's diagnostics. */
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final File out = new File(getFilesDir(), "crash-last.txt");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(out))) {
                w.println("crash " + new java.util.Date() + " on thread " + t.getName());
                w.println(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
                e.printStackTrace(w);
                if (renderer != null) { w.println("--- app log ---"); w.println(renderer.logText()); }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** device.json for the diagnostics: model, Android, ARCore and app versions, memory. */
    private void writeDeviceInfo() {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL).put("device", android.os.Build.DEVICE)
             .put("android", android.os.Build.VERSION.RELEASE).put("sdk", android.os.Build.VERSION.SDK_INT).put("soc", android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "");
            try { j.put("arcore", getPackageManager().getPackageInfo("com.google.ar.core", 0).versionName); } catch (Exception e) { j.put("arcore", "not installed"); }
            try { android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0); j.put("app", pi.versionName).put("appBuild", pi.versionCode); } catch (Exception ignored) { }
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
            j.put("ramMB", mi.totalMem / 1048576).put("heapLimitMB", am.getLargeMemoryClass());
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(getFilesDir(), "device.json"))) { o.write(j.toString(1).getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    /** From the renderer, ~2x a second while scanning. level 0 ok, 1 hold on, 2 stop and stay here. */
    private android.widget.ImageView hudSpot;

    /** Shows the good spot's picture under the hint (null hides it), big enough to recognise the place. The camera
     *  image is landscape: turned to the phone's upright view. */
    void showSpot(byte[] jpg) {
        android.graphics.Bitmap bm = null;
        if (jpg != null) {
            android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (raw != null) {
                android.graphics.Matrix rot = new android.graphics.Matrix(); rot.postRotate(90);
                bm = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), rot, true);
            }
        }
        final android.graphics.Bitmap show = bm;
        runOnUiThread(() -> {
            if (hudSpot == null) return;
            hudSpot.setImageBitmap(show);
            hudSpot.setVisibility(show == null ? View.GONE : View.VISIBLE);
        });
    }

    void updateHud(String hint, String sub, int level, float detail, float room, int points, int scanSeconds) {
        runOnUiThread(() -> {
            if (hud == null) return;
            // The record button shows whether ARCore is really recording (it can stop on its own).
            if (recordButton != null && session != null) {
                boolean on = session.getRecordingStatus() == com.google.ar.core.RecordingStatus.OK;
                String want = on ? "Stop recording" : "Record session";
                if (!want.contentEquals(recordButton.getText())) recordButton.setText(want);
            }
            hudHint.setText(hint);
            hudHint.setTextColor(HINT_COLORS[Math.max(0, Math.min(3, level))]);
            hudSub.setText(sub == null ? "" : sub);
            hudSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
            // The "This spot" / "Room" bars are hidden for good (rk 13 Nov: "safe to remove all that "this area" and
            // "whole room" counters on top"); they're still filled below. Before: shown unless the position was lost.
            int bars = View.GONE;
            ((View) hudDetail.getParent()).setVisibility(bars); ((View) hudRoom.getParent()).setVisibility(bars);
                        hudDetail.setProgress(Math.round(detail * 1000));
            hudDetail.setProgressTintList(android.content.res.ColorStateList.valueOf(detail >= ScanRenderer.SPOT_DONE ? 0xFF33D17A : Ios.ACCENT));
            hudDetailPct.setText(Math.round(detail * 100) + "%");
            hudRoom.setProgress(Math.round(room * 1000));
            hudRoomPct.setText(Math.round(room * 100) + "%");
            if (hudTimer != null) {   // time · dots against the cap · phone load (rk 23:34), small and quiet
                if (android.os.Build.VERSION.SDK_INT >= 29) try { renderer.thermal = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus(); } catch (Exception ignored) { }
                int lvl = renderer.loadLevel(), cap = renderer.maxPoints();
                int inUse = renderer.liveDotCount(); String dots = String.format(java.util.Locale.US, "Dots %dk / %.1fM", inUse / 1000, cap / 1e6f);
                String t = String.format(java.util.Locale.US, "%d:%02d  ·  %s  ·  ", scanSeconds / 60, scanSeconds % 60, dots);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(t);
                if (inUse >= cap * 0.9f) sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFB020), t.indexOf("Dots"), t.indexOf("Dots") + dots.length(), 0);
                int st = sb.length(); sb.append("● Phone load");
                sb.setSpan(new android.text.style.ForegroundColorSpan(lvl == 2 ? 0xFFFF5040 : lvl == 1 ? 0xFFFFB020 : 0xFF40D070), st, st + 1, 0);
                if (debug) sb.append("  (").append(renderer.loadNumbers()).append(")");
                hudTimer.setText(sb);
            }
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

    private Button measureButton, measureClear;
    private FrameLayout walkTipHolder;
    private boolean measuring;
    private MeasureOverlay measureOverlay;

    /** Doorways found after the scan: outlined with their size in the 3D view (MeasureOverlay). */
    /** The scan's saved measurements (measurements.json), shown again when it's opened. */
    void loadMeasurements(File dir) {
        runOnUiThread(() -> { ensureOverlay(); measureOverlay.load(dir); updateMeasureButtons(); });
    }

    void setDoorways(java.util.List<DoorwayFinder.Doorway> ds) {
        runOnUiThread(() -> { ensureOverlay(); measureOverlay.setDoorways(ds); });
    }

    private void ensureOverlay() {
        if (measureOverlay == null) {
            measureOverlay = new MeasureOverlay(this, renderer);
            ((android.view.ViewGroup) glView.getParent()).addView(measureOverlay, ((android.view.ViewGroup) glView.getParent()).indexOfChild(glView) + 1,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        measureOverlay.setVisibility(renderer.isViewing() || scanning ? View.VISIBLE : View.GONE);   // live doorway outlines while scanning
    }

    private void setMeasuring(boolean on) {
        measuring = on;
        if (measureOverlay == null) {
            measureOverlay = new MeasureOverlay(this, renderer);
            ((android.view.ViewGroup) glView.getParent()).addView(measureOverlay, ((android.view.ViewGroup) glView.getParent()).indexOfChild(glView) + 1,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        measureOverlay.setVisibility(walkState == 2 ? View.VISIBLE : View.GONE);
        if (!on) measureOverlay.cancelPending();
        if (on) toast("Tap two spots on the scan. Tap a measurement to select or delete it.");
        updateMeasureButtons();
    }

    private void updateMeasureButtons() {
        if (measureButton == null) return;
        measureButton.setText(measuring ? "Measure: on" : "Measure");
        measureClear.setVisibility(measureOverlay != null && measureOverlay.count() > 0 ? View.VISIBLE : View.GONE);
    }

    /** Clearing a spot while scanning (press and hold): off for now, rk 10 Nov 11:20. */
    static final boolean CLEAR_WHILE_SCANNING = false;
    private HoldRing holdRing;
    private final Runnable holdFire = () -> {
        if (holdRing == null || holdRing.getVisibility() != View.VISIBLE) return;
        float x = holdRing.cx, y = holdRing.cy;
        holdRing.setVisibility(View.GONE);
        glView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        android.util.Log.i("RoomScape", "scan hold-to-clear at " + Math.round(x) + "," + Math.round(y));
        renderer.requestClear(x, y);
    };

    private void startHoldRing(float x, float y) {
        if (holdRing == null) {
            holdRing = new HoldRing(this);
            ((android.view.ViewGroup) glView.getParent()).addView(holdRing, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        holdRing.start(x, y, dp(30));
        glView.removeCallbacks(holdFire);
        glView.postDelayed(holdFire, HoldRing.MS);
    }

    private void cancelHoldRing() {
        glView.removeCallbacks(holdFire);
        if (holdRing != null) holdRing.setVisibility(View.GONE);
    }

    /** The ring that fills while a finger is held down to clear a spot. */
    static final class HoldRing extends View {
        static final long MS = 900;
        float cx, cy, r; long t0;
        final android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), fg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        HoldRing(android.content.Context c) {
            super(c);
            bg.setStyle(android.graphics.Paint.Style.STROKE); bg.setStrokeWidth(10); bg.setColor(0x66000000);
            fg.setStyle(android.graphics.Paint.Style.STROKE); fg.setStrokeWidth(10); fg.setColor(0xFFFFFFFF); fg.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            setVisibility(GONE); setClickable(false); setFocusable(false);
        }
        void start(float x, float y, float radius) { cx = x; cy = y; r = radius; t0 = android.os.SystemClock.uptimeMillis(); setVisibility(VISIBLE); invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float f = Math.min(1f, (android.os.SystemClock.uptimeMillis() - t0) / (float) MS);
            android.graphics.RectF o = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawCircle(cx, cy, r, bg);
            c.drawArc(o, -90, 360 * f, false, fg);
            if (f < 1) postInvalidateOnAnimation();
        }
    }

    /** The scan lost its place 30 s ago and hasn't found it again: keep looking, or carry on as a new part. */
    void offerNewPart() {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            new android.app.AlertDialog.Builder(this).setTitle("Still can't find where you are")
                    .setMessage("Point at something you already scanned to continue where you were. Or carry on from here as a new part of the scan (it may not line up with the rest).")
                    .setPositiveButton("Keep looking", null)
                    .setNegativeButton("Carry on from here", (d, w) -> renderer.forceResume = true)
                    .show();
        });
    }

    private boolean askedOutletKey;
    /** Outlets are found by Claude: ask for the key once per app run. */
    void outletsNeedKey() {
        runOnUiThread(() -> {
            if (askedOutletKey || isFinishing() || !debug) return;   // no unasked key prompt for first-time users
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
            attachRecording();
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
        logo.setContentDescription("RoomScape. Long-press to share diagnostics");
        logo.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(this).setTitle("Share diagnostics?")
                    .setMessage("Logs, phone model, Android/ARCore/app versions, graphics chip info and the last crash, for fixing problems. No location, no account, no photos.")
                    .setPositiveButton("Share", (d, w) -> shareDiagnostics()).setNegativeButton("Cancel", null).show();
            return true;
        });
        {   // the build number above the logo, big (rk 11 Nov 21:56: "show me the version ... so I don't use the wrong one")
            TextView bl = new TextView(this);
            bl.setText("Build " + buildStamp() + (ScanRenderer.FORCED_LOW_MEMORY ? "  ·  low memory mode" : ""));
            bl.setTextColor(ScanRenderer.FORCED_LOW_MEMORY ? 0xFFE0A030 : 0xFFFFFFFF);
            bl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            bl.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            bl.setGravity(Gravity.CENTER);
            bl.setPadding(0, 0, 0, dp(6));
            col.addView(bl);
        }
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
        // (the ultrawide recorder experiment is off the home screen: rk 11 Nov 21:40, "they won't help us"; still in debug tools)

        col.addView(Ios.header(this, "Saved scans"));
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeList);
        col.addView(Ios.footer(this, "Touch and hold a scan to rename or delete it."));

        TextView pilot = new TextView(this);
        pilot.setVisibility(View.GONE);   // removed from the home screen (rk 10 Nov 11:20); the form stays in the code
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

    /** The readable build stamp ("13 Nov 14:34:25", Tallinn time), what builds are called (rk 13 Nov 14:33). */
    String buildStamp() { return getString(R.string.build_stamp); }
    int buildCode() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; } }

    private void showHome() {
        act("home screen");
        stopRecordingIfAny();
        attachRecording(); // leaving keeps the scan (autosaved), so its recording too
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
        act("Cancel (while scanning)");
        Runnable discard = () -> {
            act("Cancel: scan discarded");
            // Cancel discards the scan, and its recording with it.
            stopRecordingIfAny();
            if (finishedRecording != null) { finishedRecording.delete(); finishedRecording = null; }
            if (session != null) session.pause();
            glView.queueEvent(() -> renderer.discardScan(() -> {
                scanChrome.setVisibility(View.GONE);
                home.setVisibility(View.VISIBLE);
                refreshHome();
            }));
        };
        if (System.currentTimeMillis() - scanStartedMs < 60_000) { discard.run(); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Stop this scan?")
                .setMessage(renderer.isImproving() ? "The new pass is thrown away. The saved room stays as it was." : "It stops here. What's scanned so far stays in your list, marked (cancelled).")
                .setPositiveButton("Stop", (d, w) -> discard.run())
                .setNegativeButton("Keep scanning", null)
                .show();
    }

    private boolean homeShown() { return home != null && home.getVisibility() == View.VISIBLE; }

    private void hideHome() {
        home.setVisibility(View.GONE);
        scanChrome.setVisibility(View.VISIBLE);
        if (scanning) { showScanTip(false); showScanTip(true); }   // each scan starts from the first tip
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
        if (session == null) {   // no AR on this run: say why, don't open a black camera screen
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) { arProblemShown = true; requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ); return; }   // onResume follows the dialog
            if (arProblem == null) arProblem = "AR is still starting. Try again in a moment.";
            showArProblem();
            return;
        }
        hideHome();
        // A fresh ARCore session for every scan after the first: an old session keeps its map, and after a tracking
        // loss that map can hold two versions of the room ~0.8 m apart that ARCore then flips between (rk 10 Nov
        // 12:58: +0.9 m at the start of a scan and -0.8 m back 17 s later).
        if (scansInSession > 0) {
            try {
                final Session old = session;
                try { old.pause(); } catch (Exception ignored) { }
                final Session fresh = new Session(this);
                session = fresh;
                glView.queueEvent(() -> { renderer.setSession(fresh); try { old.close(); } catch (Exception ignored) { } });
            } catch (Exception e) { android.util.Log.w("RoomScape", "fresh AR session failed, keeping the old one: " + e); }
        }
        scansInSession++;
        glView.queueEvent(() -> renderer.startNewScan());
        scanUi();
        updateStatus("Scanning. Walk around the room slowly. Tap things to mark corners, walls, floor or objects.");
        resumeSession();
    }

    private void openScan(ScanStore.Entry e) {
        act("opened scan " + e.dir.getName());
        hideHome();
        if (session != null) session.pause();
        // Straight to the scan view (never the scanning card, bars or buttons): same reset as scanUi, but viewing.
        scanUi();
        scanning = false;
        applyUi();
        viewerTitle.setText(e.name);
        nameValue.setText(e.name);
        mainButton.setText("New scan");
        renderer.setStyle(2); // Points first: the photo surface isn't reliable on big scans yet (rk 10 Nov 15:22)
        glView.queueEvent(() -> renderer.loadScan(e.dir, () -> runOnUiThread(this::viewerUi)));
    }

    /** The result's Name row: renames the scan that is open (11 Nov: on a teammate's phone it saved the app's current,
     *  empty state as a new scan "Corrigo office" with 0 points; the real scan stayed under its date). */
    private void nameScan() {
        act("Name row");
        File dir = renderer.currentDir;
        if (!renderer.isViewing() || dir == null) { saveScan(); return; }
        String cur = renderer.currentName != null ? renderer.currentName : "";
        askName("Name this scan", cur.startsWith("Room ") ? "" : cur, name -> new Thread(() -> {
            try {
                ScanStore.rename(dir, name);
                glView.queueEvent(() -> { renderer.currentName = name; renderer.log("renamed to '" + name + "'"); });
                runOnUiThread(() -> { nameValue.setText(name); viewerTitle.setText(name); setViewerTitle(); toast("Renamed"); });
            } catch (Exception ex) { runOnUiThread(() -> toast("Rename failed: " + ex.getMessage())); }
        }).start());
    }

    private void saveScan() {
        act("Save scan as");
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

    /** A translucent circle where a tap cleared the scan, fading out. */
    void flashCleared(float x, float y, float r) {
        runOnUiThread(() -> {
            View c = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(0x40FFFFFF); g.setStroke(dp(2), 0xCCFFFFFF);
            c.setBackground(g);
            int d = Math.round(2 * r);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d, d);
            lp.leftMargin = Math.round(x - r); lp.topMargin = Math.round(y - r);
            ((FrameLayout) scanChrome).addView(c, lp);
            c.animate().alpha(0f).setDuration(700).withEndAction(() -> ((FrameLayout) scanChrome).removeView(c)).start();
        });
    }

    void updateStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        act("system asked to free memory (level " + level + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        act("app resumed (onResume)");
        // back from making it photo-real: the new Photo-real tab, opened on it
        if (viewerTop != null && !scanning) {
            updateSplatTab(true);
            File pr = photoRealReturn; photoRealReturn = null;
            if (pr != null && pr.equals(renderer.currentDir) && PhotoRealActivity.result(pr).exists()) glView.post(this::showSplatView);
        }
        // the phone's rotation and shaking while scanning: the held view and the "stand still" hint when tracking is lost
        try {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
            if (sm != null && renderer != null) {
                android.hardware.Sensor rv = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR), la = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION);
                if (rv != null) sm.registerListener(renderer.scanRotListener, rv, android.hardware.SensorManager.SENSOR_DELAY_GAME);
                if (la != null) sm.registerListener(renderer.scanRotListener, la, android.hardware.SensorManager.SENSOR_DELAY_UI);
            }
        } catch (Exception ignored) { }
        nearby.start();
        renderer.paused = false;
        setGyroSensor(walkState == 2 && renderer.gyroLook);
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
                    // Asked once per run; denied = no scanning, but saved and shared rooms still open.
                    if (!cameraAsked) { cameraAsked = true; requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ); return; }
                    throw new SecurityException("camera");
                }
                session = new Session(this);
                // Debug: replay a recorded session instead of the camera (tools/replay-s24.sh):
                // adb shell am start -n com.hackathon.setscan/.MainActivity --es playback /sdcard/Android/data/…/x.mp4
                String playback = getIntent().getStringExtra("playback");
                if (playback != null) {
                    try {
                        session.setPlaybackDatasetUri(android.net.Uri.fromFile(new File(playback)));
                        android.util.Log.i("RoomScape", "playback from " + playback);
                        // Replays for tests (tools/replay-s24.sh): which fusion variant, and a name; the scan then starts
                        // by itself (always a new scan) and ends with Done when the recording ends.
                        ScanRenderer.FUSE_VARIANT = getIntent().getIntExtra("fuseVariant", 0);
                        replayName = getIntent().getStringExtra("replayName");
                        android.util.Log.i("RoomScape", "replay: fusion variant " + ScanRenderer.FUSE_VARIANT + ", name " + replayName);
                    } catch (Exception e) { android.util.Log.e("RoomScape", "playback failed: " + e); }
                }
                // The renderer picks the depth mode and falls back to another one if tracking never starts.
                renderer.setSession(session);
            } catch (Exception e) {
                // No scanning on this run, but the app stays usable for saved and shared rooms (it used to stop here,
                // leaving even the 3D view paused).
                arProblem = arProblemText(e);
                updateStatus(arProblem);
                if (!arProblemShown) { arProblemShown = true; showArProblem(); }
            }
        }
        renderer.sensors.start(this);
        if (session != null && !renderer.isViewing() && !homeShown()) resumeSession();
        glView.onResume();
        if (replayName != null && !replayStarted && session != null) {
            replayStarted = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!homeShown()) showHome();
                startNewScan();
                final String nm = replayName;
                glView.queueEvent(() -> { renderer.currentName = nm; renderer.replayDone = this::replayFinished; renderer.log("replay \"" + nm + "\": fusion variant " + ScanRenderer.FUSE_VARIANT); });
            }, 1500);
        }
    }

    private boolean cameraAsked, arProblemShown;
    /** Scans started on the current ARCore session (a new scan after the first gets a fresh session). */
    private int scansInSession;
    /** Why this run can't scan (null = it can). */
    private String arProblem;

    private String arProblemText(Exception e) {
        if (e instanceof SecurityException) return "RoomScape needs the camera to scan. Allow it in Settings > Apps > RoomScape > Permissions. Saved rooms still open without it.";
        if (e instanceof com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException) return "Scanning needs Google Play Services for AR. Reopen the app to install it.";
        if (e instanceof com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException) return "This phone doesn't support ARCore, so it can't scan rooms. Saved and shared rooms still open.";
        if (e instanceof com.google.ar.core.exceptions.UnavailableApkTooOldException) return "Google Play Services for AR is out of date. Update it in the Play Store, then reopen RoomScape.";
        if (e instanceof com.google.ar.core.exceptions.UnavailableSdkTooOldException) return "This version of RoomScape is too old for the phone's ARCore. Install a newer RoomScape.";
        if (e instanceof com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException) return "Scanning needs Google Play Services for AR. Install it from the Play Store, then reopen RoomScape.";
        return "AR couldn't start on this phone (" + e.getClass().getSimpleName() + "). Saved rooms still open. Long-press the logo to share diagnostics.";
    }

    private void showArProblem() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this).setTitle("Can't scan right now").setMessage(arProblem)
                .setNegativeButton("OK", null);
        if (arProblem != null && arProblem.startsWith("RoomScape needs the camera")) b.setPositiveButton("Open settings", (d, w) -> {
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", getPackageName(), null))); } catch (Exception ignored) { }
        });
        b.show();
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
            if (scanning && AUTO_RECORD && recordWanted) startRecordingIfNeeded();
        } catch (CameraNotAvailableException e) {
            updateStatus("Camera not available, try reopening the app.");
        }
    }

    @Override
    protected void onPause() {
        act("app paused (onPause)");
        try { android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE); if (sm != null && renderer != null) sm.unregisterListener(renderer.scanRotListener); } catch (Exception ignored) { }
        // The app's event log, for "Share diagnostics" even when no scan was finished.
        if (renderer != null) try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(getFilesDir(), "app-log.txt"))) { o.write(renderer.logText().getBytes("UTF-8")); } catch (Exception ignored) { }
        setGyroSensor(false);
        stopRecordingIfAny();
        super.onPause();
        nearby.stop();
        renderer.paused = true;
        glView.onPause();
        if (session != null) session.pause();
        renderer.sensors.stop(this);
    }

    @Override
    protected void onDestroy() {
        act("app closed (onDestroy, finishing " + isFinishing() + ")");
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
            // Stay open: saved and shared rooms work without the camera (onResume shows why scanning can't start).
        }
    }
}
