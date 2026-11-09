package com.hackathon.setscan;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.widget.ArrayAdapter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * "Send to nearby phone": RoomScape phones on the same Wi-Fi find each other and send .rmsc scans directly
 * (protocol: docs/rmsc-format.md, "Sending to a nearby phone"). While the app is open it listens on a TCP port and
 * advertises it as _roomscape._tcp (NSD / Bonjour). A scan arrives as HTTP POST /rmsc with the raw .rmsc as the body.
 * The first scan from a phone asks "Accept scans from ...?"; with Always allow, later ones import without asking.
 */
final class Nearby {
    static final String TYPE = "_roomscape._tcp.";

    private final MainActivity act;
    private final NsdManager nsd;
    private final SharedPreferences prefs;
    final String deviceId, deviceName;
    private ServerSocket server;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock lock;
    private String registeredName;

    Nearby(MainActivity act) {
        this.act = act;
        nsd = (NsdManager) act.getSystemService(Context.NSD_SERVICE);
        prefs = act.getSharedPreferences("nearby", Context.MODE_PRIVATE);
        String id = prefs.getString("id", null);
        if (id == null) { id = UUID.randomUUID().toString(); prefs.edit().putString("id", id).apply(); }
        deviceId = id;
        deviceName = Build.MODEL + " " + id.substring(0, 4).toUpperCase(Locale.US);
    }

    // ---------------------------------------------------------------- receiving

    /** Listens and advertises while the app is open (onResume). Safe to call again. */
    synchronized void start() {
        if (server != null) return;
        try {
            server = new ServerSocket(0);
            final ServerSocket s = server;
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try { Socket c = s.accept(); new Thread(() -> serve(c), "nearby-serve").start(); }
                    catch (Exception e) { break; }
                }
            }, "nearby-accept");
            t.setDaemon(true);
            t.start();
            WifiManager wifi = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) { lock = wifi.createMulticastLock("roomscape-nearby"); lock.setReferenceCounted(false); lock.acquire(); }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(deviceName);
            info.setServiceType(TYPE);
            info.setPort(s.getLocalPort());
            info.setAttribute("id", deviceId);
            info.setAttribute("name", deviceName);
            info.setAttribute("platform", "android");
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { registeredName = i.getServiceName(); }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int code) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int code) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            stop();
        }
    }

    /** Stops listening (onPause). */
    synchronized void stop() {
        try { if (registration != null) nsd.unregisterService(registration); } catch (Exception ignored) { }
        registration = null;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    private Set<String> trusted() { return new HashSet<>(prefs.getStringSet("trusted", new HashSet<>())); }

    private void serve(Socket c) {
        File file = new File(act.getCacheDir(), "nearby_" + System.currentTimeMillis() + ".rmsc");
        try (Socket sock = c) {
            sock.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 1 << 16);
            OutputStream out = sock.getOutputStream();
            // Request line and headers.
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, last = 0;
            while ((b = in.read()) >= 0 && head.size() < 65536) {
                head.write(b);
                last = (last << 8) | b;
                if (last == 0x0D0A0D0A) break; // "\r\n\r\n": end of the headers
            }
            String[] lines = head.toString("UTF-8").split("\r\n");
            String[] first = lines.length > 0 ? lines[0].split(" ") : new String[0];
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) h.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), URLDecoder.decode(lines[i].substring(colon + 1).trim(), "UTF-8"));
            }
            long length = -1;
            try { length = Long.parseLong(h.getOrDefault("content-length", "-1")); } catch (Exception ignored) { }
            if (first.length < 2 || !"POST".equals(first[0]) || !"/rmsc".equals(first[1]) || length <= 0) { reply(out, 400, "Bad request"); return; }
            // Body to a file.
            try (OutputStream f = new FileOutputStream(file)) {
                byte[] buf = new byte[1 << 16];
                long left = length;
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new Exception("connection closed early");
                    f.write(buf, 0, r);
                    left -= r;
                }
            }
            String id = h.getOrDefault("x-device-id", "unknown"), name = h.getOrDefault("x-device-name", "A phone");
            String fileName = h.getOrDefault("x-file-name", "scan.rmsc");
            if (!trusted().contains(id) && !ask(id, name, fileName)) { reply(out, 403, "Declined"); return; }
            sock.setSoTimeout(0);
            File dir = Rmsc.importFrom(act, Uri.fromFile(file));
            reply(out, 200, "OK");
            act.nearbyReceived(dir, name);
        } catch (Throwable e) {
            try { reply(c.getOutputStream(), 422, String.valueOf(e.getMessage())); } catch (Exception ignored) { }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Asks on screen whether to accept a scan from a phone seen for the first time. Waits up to 2 minutes. */
    private boolean ask(String id, String name, String fileName) throws InterruptedException {
        final boolean[] accept = {false};
        CountDownLatch answered = new CountDownLatch(1);
        act.runOnUiThread(() -> new AlertDialog.Builder(act)
                .setTitle("Accept scans from " + name + "?")
                .setMessage("It's sending a room scan (" + fileName + "). With Always allow, scans from this phone arrive without asking.")
                .setPositiveButton("Always allow", (d, w) -> {
                    Set<String> t = trusted(); t.add(id); prefs.edit().putStringSet("trusted", t).apply();
                    accept[0] = true; answered.countDown();
                })
                .setNeutralButton("Just this once", (d, w) -> { accept[0] = true; answered.countDown(); })
                .setNegativeButton("Decline", (d, w) -> answered.countDown())
                .setOnCancelListener(d -> answered.countDown())
                .show());
        answered.await(2, TimeUnit.MINUTES);
        return accept[0];
    }

    private static void reply(OutputStream out, int status, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------- sending

    /** The share option: lists RoomScape phones found on the Wi-Fi; tapping one sends the scan in `scanDir`. */
    void showSendDialog(File scanDir) {
        final List<NsdServiceInfo> found = new ArrayList<>();
        final ArrayAdapter<String> names = new ArrayAdapter<>(act, android.R.layout.simple_list_item_1, new ArrayList<>());
        final NsdManager.DiscoveryListener[] discovery = new NsdManager.DiscoveryListener[1];
        discovery[0] = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo i) {
                if (i.getServiceName().equals(registeredName) || i.getServiceName().equals(deviceName)) return;
                act.runOnUiThread(() -> {
                    for (NsdServiceInfo f : found) if (f.getServiceName().equals(i.getServiceName())) return;
                    found.add(i); names.add(i.getServiceName());
                });
            }
            @Override public void onServiceLost(NsdServiceInfo i) {
                act.runOnUiThread(() -> {
                    for (int k = 0; k < found.size(); k++) if (found.get(k).getServiceName().equals(i.getServiceName())) {
                        found.remove(k); names.remove(names.getItem(k)); break;
                    }
                });
            }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onStartDiscoveryFailed(String t, int code) { }
            @Override public void onStopDiscoveryFailed(String t, int code) { }
        };
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery[0]);
        // A dialog shows either a message or a list, never both: the help goes above the list in a layout of its own.
        android.widget.LinearLayout box = new android.widget.LinearLayout(act);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView help = new android.widget.TextView(act);
        help.setText("Phones with RoomScape open on the same Wi-Fi appear below. The first time, the other phone asks to accept scans from this one.");
        help.setTextColor(Ios.SECONDARY);
        help.setTextSize(14);
        int pad = Ios.dp(act, 20);
        help.setPadding(pad, Ios.dp(act, 4), pad, Ios.dp(act, 8));
        box.addView(help);
        android.widget.TextView searching = new android.widget.TextView(act);
        searching.setText("Looking for phones...");
        searching.setTextColor(Ios.SECONDARY);
        searching.setPadding(pad, Ios.dp(act, 8), pad, Ios.dp(act, 8));
        box.addView(searching);
        android.widget.ListView list = new android.widget.ListView(act);
        list.setAdapter(names);
        box.addView(list);
        names.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override public void onChanged() { searching.setVisibility(names.getCount() == 0 ? android.view.View.VISIBLE : android.view.View.GONE); }
        });
        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("Send to nearby phone")
                .setView(box)
                .setNegativeButton("Close", null)
                .create();
        list.setOnItemClickListener((parent, view, which, id) -> { dialog.dismiss(); send(scanDir, found.get(which)); });
        dialog.setOnDismissListener(d -> { try { nsd.stopServiceDiscovery(discovery[0]); } catch (Exception ignored) { } });
        dialog.show();
    }

    /** NsdManager resolves one service at a time on older Android: later sends wait for the earlier one. */
    private final Object resolving = new Object();

    private void send(File scanDir, NsdServiceInfo peer) {
        act.toast("Packing the room for " + peer.getServiceName() + "...");
        new Thread(() -> { synchronized (resolving) { resolve(scanDir, peer); } }, "nearby-resolve").start();
    }

    private void resolve(File scanDir, NsdServiceInfo peer) {
        CountDownLatch done = new CountDownLatch(1);
        nsd.resolveService(peer, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int code) { done.countDown(); act.toast("Couldn't reach " + peer.getServiceName() + " (" + code + ")."); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                done.countDown();
                new Thread(() -> {
                    try {
                        Uri uri = Rmsc.export(act, scanDir);
                        byte[] body;
                        try (InputStream in = act.getContentResolver().openInputStream(uri)) {
                            ByteArrayOutputStream all = new ByteArrayOutputStream();
                            byte[] buf = new byte[1 << 16]; int r;
                            while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
                            body = all.toByteArray();
                        }
                        // Prefer an IPv4 address: link-local IPv6 needs the right interface and often fails.
                        InetAddress host = i.getHost();
                        if (Build.VERSION.SDK_INT >= 34) {
                            for (InetAddress a : i.getHostAddresses()) if (a instanceof java.net.Inet4Address) { host = a; break; }
                        }
                        String addr = host.getHostAddress();
                        if (addr != null && addr.contains(":")) addr = "[" + addr.replaceAll("%.*", "") + "]";
                        HttpURLConnection c = (HttpURLConnection) new URL("http://" + addr + ":" + i.getPort() + "/rmsc").openConnection();
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(body.length);
                        c.setConnectTimeout(10_000);
                        c.setReadTimeout(180_000);
                        c.setRequestProperty("Content-Type", "application/vnd.roomscape.rmsc+zip");
                        c.setRequestProperty("X-Device-Id", deviceId);
                        c.setRequestProperty("X-Device-Name", deviceName);
                        c.setRequestProperty("X-File-Name", URLEncoder.encode(uri.getLastPathSegment() == null ? "scan.rmsc" : uri.getLastPathSegment(), "UTF-8"));
                        act.toast("Sending to " + peer.getServiceName() + "... (it may ask to accept)");
                        try (OutputStream o = c.getOutputStream()) { o.write(body); }
                        int code = c.getResponseCode();
                        c.disconnect();
                        act.toast(code == 200 ? "Sent. It's open as a new scan on " + peer.getServiceName() + "."
                                : code == 403 ? peer.getServiceName() + " declined the scan."
                                : "Couldn't send: the other phone answered " + code + ".");
                    } catch (Throwable e) {
                        act.toast("Couldn't send: " + e.getMessage());
                    }
                }, "nearby-send").start();
            }
        });
        try { done.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }
}
