package com.hackathon.setscan;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A scan's identity for sharing and syncing (docs/rmsc-format.md, "Shared scans"): a permanent id, a revision that
 * goes up with every saved change, and whether (and with whom) it's shared. Kept in the scan folder as sync.json,
 * the same fields as the iPhone app's.
 */
final class SyncInfo {
    String id;
    int revision = 1;
    String revisionBy = "";
    long modified = System.currentTimeMillis();
    boolean shared;
    /** Phones it's shared with: device id -> name. */
    final Map<String, String> peers = new HashMap<>();
    /** The revision both sides last agreed on: a change on both sides since then is a conflict. */
    int syncedRevision;

    /** Called after a change to a shared scan (MainActivity syncs it to the phones around). */
    static volatile Runnable onSharedChange;
    /** The app, for code without a Context at hand (set in MainActivity.onCreate). */
    static volatile Context app;

    static void touch(File dir) { if (app != null) touch(app, dir); }

    static File file(File dir) { return new File(dir, "sync.json"); }

    /** The scan's sync info, created (unshared, revision 1) the first time it's asked for. */
    static SyncInfo load(Context c, File dir) {
        SyncInfo s = new SyncInfo();
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(file(dir).toPath()), StandardCharsets.UTF_8));
            s.id = j.getString("id");
            s.revision = j.optInt("revision", 1);
            s.revisionBy = j.optString("revisionBy", "");
            s.modified = j.optLong("modifiedMs", s.modified);
            s.shared = j.optBoolean("shared", false);
            s.syncedRevision = j.optInt("syncedRevision", 0);
            JSONObject p = j.optJSONObject("peers");
            if (p != null) for (Iterator<String> it = p.keys(); it.hasNext(); ) { String k = it.next(); s.peers.put(k, p.optString(k)); }
            return s;
        } catch (Exception e) {
            s.id = UUID.randomUUID().toString();
            s.revisionBy = deviceId(c);
            save(dir, s);
            return s;
        }
    }

    static void save(File dir, SyncInfo s) {
        try {
            JSONObject j = new JSONObject();
            j.put("id", s.id); j.put("revision", s.revision); j.put("revisionBy", s.revisionBy);
            j.put("modifiedMs", s.modified); j.put("shared", s.shared); j.put("syncedRevision", s.syncedRevision);
            JSONObject p = new JSONObject();
            for (Map.Entry<String, String> e : s.peers.entrySet()) p.put(e.getKey(), e.getValue());
            j.put("peers", p);
            try (FileOutputStream o = new FileOutputStream(file(dir))) { o.write(j.toString(1).getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
    }

    /** A saved change (rename, Continue scan): a new revision, synced to the phones it's shared with. */
    static void touch(Context c, File dir) {
        SyncInfo s = load(c, dir);
        s.revision++;
        s.revisionBy = deviceId(c);
        s.modified = System.currentTimeMillis();
        save(dir, s);
        Runnable r = onSharedChange;
        if (s.shared && r != null) r.run();
    }

    static void markShared(Context c, File dir, String peerId, String peerName, int synced) {
        SyncInfo s = load(c, dir);
        s.shared = true;
        s.peers.put(peerId, peerName);
        s.syncedRevision = Math.max(s.syncedRevision, synced);
        save(dir, s);
    }

    /** The scan folder with this sync id, or null. */
    static File find(Context c, String id) {
        if (id == null) return null;
        for (ScanStore.Entry e : ScanStore.list(c)) {
            File f = file(e.dir);
            if (f.exists() && id.equals(load(c, e.dir).id)) return e.dir;
        }
        return null;
    }

    /** Shared scans: folder and info. */
    static List<Object[]> shared(Context c) {
        List<Object[]> out = new ArrayList<>();
        for (ScanStore.Entry e : ScanStore.list(c)) {
            if (!file(e.dir).exists()) continue;
            SyncInfo s = load(c, e.dir);
            if (s.shared) out.add(new Object[]{e.dir, s});
        }
        return out;
    }

    static String deviceId(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("nearby", Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("id", id).apply(); }
        return id;
    }
}
