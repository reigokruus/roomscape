package com.hackathon.setscan;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * How the phone's hardware is used while scanning and after Done (rk 9 Nov), sampled twice a second into a small
 * JSON-lines file in the scan's folder (perf-scan.jsonl) plus a summary (perf-scan.summary.json). Never synced.
 *
 * Each line: time, stage; the app's threads (CPU time and the core each last ran on, from /proc/self/task/N/stat);
 * the app's CPU time per core cluster (little / mid / big, from the cores' top frequencies); each core's current
 * frequency where readable; GL frame count and time; timed calls (depth AI per delegate); thermal status and
 * headroom; memory (RSS); battery. The Exynos 2400 has 10 cores (4 little, 5 mid, 1 big); per-core load of other
 * apps isn't readable for an app, so loads here are this app's own.
 */
final class PerfLog {
    private final Context ctx;
    private File file;
    private FileWriter out;
    private volatile String stage = "";
    private long started, lastSample;
    private final Map<Integer, long[]> lastThread = new HashMap<>(); // tid -> {cpu ticks}
    private ScheduledExecutorService timer;
    private int frames; private double frameMs;
    private final Map<String, double[]> notes = new HashMap<>(); // name -> {sum ms, count}
    private final Map<String, double[]> sums = new HashMap<>();  // stage -> {seconds, cpuSec, frames, frameMs, maxThermal, peakRssMB, samples, little, mid, big}
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final long TICK_HZ = 100; // USER_HZ
    private final String[] cluster = new String[CORES];

    PerfLog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        long[] maxF = new long[CORES];
        long lo = Long.MAX_VALUE, hi = 0;
        for (int c = 0; c < CORES; c++) { maxF[c] = readLong("/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_max_freq"); if (maxF[c] > 0) { lo = Math.min(lo, maxF[c]); hi = Math.max(hi, maxF[c]); } }
        for (int c = 0; c < CORES; c++) cluster[c] = maxF[c] <= 0 ? "?" : maxF[c] == lo ? "little" : maxF[c] == hi ? "big" : "mid";
    }

    synchronized void start(File f, String stage) {
        stop();
        try {
            f.getParentFile().mkdirs();
            file = f; out = new FileWriter(f, false);
            this.stage = stage; started = lastSample = System.nanoTime();
            lastThread.clear(); notes.clear(); sums.clear(); frames = 0; frameMs = 0;
            JSONObject o = new JSONObject();
            o.put("event", "start"); o.put("stage", stage); o.put("cores", CORES); o.put("clusters", new JSONArray(cluster));
            o.put("device", Build.MODEL + (Build.VERSION.SDK_INT >= 31 ? " " + Build.SOC_MODEL : ""));
            line(o);
            sampleThreads(true);
        } catch (Exception e) { android.util.Log.e("RoomScape", "perf log: " + e); return; }
        timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "perf-log"); t.setPriority(Thread.MIN_PRIORITY); return t; });
        timer.scheduleAtFixedRate(this::sample, 500, 500, TimeUnit.MILLISECONDS);
    }

    void stage(String s) { stage = s; }
    synchronized void frame(double ms) { frames++; frameMs += ms; }
    synchronized void note(String name, double ms) { double[] n = notes.computeIfAbsent(name, k -> new double[2]); n[0] += ms; n[1]++; }
    File file() { return file; }

    /** Stops and writes the summary (<name>.summary.json). */
    synchronized void stop() {
        if (timer != null) { timer.shutdownNow(); timer = null; }
        if (out == null) return;
        sample();
        try { out.close(); } catch (Exception ignored) { }
        out = null;
        try {
            JSONObject s = new JSONObject();
            s.put("cores", CORES); s.put("clusters", new JSONArray(cluster));
            for (Map.Entry<String, double[]> e : sums.entrySet()) {
                double[] v = e.getValue(); double secs = Math.max(0.001, v[0]), n = Math.max(1, v[6]);
                JSONObject st = new JSONObject();
                st.put("seconds", v[0]); st.put("appCpuCoresBusy", v[1] / secs);
                st.put("appCpuOnLittle", v[7] / secs); st.put("appCpuOnMid", v[8] / secs); st.put("appCpuOnBig", v[9] / secs);
                st.put("glFramesPerSecond", v[2] / secs); st.put("glMeanFrameMs", v[2] > 0 ? v[3] / v[2] : 0);
                st.put("maxThermalStatus", v[4]); st.put("peakRssMB", v[5]);
                s.put(e.getKey(), st);
            }
            Files.write(new File(file.getParent(), file.getName().replace(".jsonl", ".summary.json")).toPath(), s.toString(2).getBytes());
        } catch (Exception e) { android.util.Log.e("RoomScape", "perf summary: " + e); }
    }

    /** Moves the log and its summary into a folder as name.jsonl / name.summary.json. */
    void moveInto(File dir, String name) {
        if (file == null || dir == null) return;
        File s = new File(file.getParent(), file.getName().replace(".jsonl", ".summary.json"));
        moveFile(file, new File(dir, name + ".jsonl"));
        moveFile(s, new File(dir, name + ".summary.json"));
    }

    private static void moveFile(File a, File b) {
        if (!a.exists()) return;
        if (!a.renameTo(b)) try { Files.copy(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); a.delete(); } catch (Exception ignored) { }
    }

    private synchronized void sample() {
        if (out == null) return;
        try {
            long now = System.nanoTime();
            double dt = (now - lastSample) / 1e9;
            lastSample = now;
            JSONObject o = new JSONObject();
            o.put("t", Math.round((now - started) / 1e8) / 10.0);
            o.put("stage", stage);
            // Threads: CPU time since the last sample and the core each last ran on.
            JSONArray top = new JSONArray();
            double[] perCluster = new double[3]; double cpu = 0;
            for (Object[] t : sampleThreads(false)) {
                double sec = (Long) t[1] / (double) TICK_HZ;
                cpu += sec;
                int core = (Integer) t[2];
                String cl = core >= 0 && core < CORES ? cluster[core] : "?";
                if ("little".equals(cl)) perCluster[0] += sec; else if ("mid".equals(cl)) perCluster[1] += sec; else if ("big".equals(cl)) perCluster[2] += sec;
                if (sec / dt >= 0.05) top.put(new JSONObject().put("name", t[0]).put("cpu", Math.round(100 * sec / dt)).put("core", core));
            }
            o.put("appCpu", Math.round(100 * cpu / dt) / 100.0);
            o.put("onLittle", Math.round(100 * perCluster[0] / dt) / 100.0);
            o.put("onMid", Math.round(100 * perCluster[1] / dt) / 100.0);
            o.put("onBig", Math.round(100 * perCluster[2] / dt) / 100.0);
            o.put("threads", top);
            JSONArray freq = new JSONArray();
            for (int c = 0; c < CORES; c++) freq.put(readLong("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq") / 1000);
            o.put("freqMHz", freq);
            o.put("glFrames", frames); o.put("glMeanMs", frames > 0 ? Math.round(frameMs / frames * 10) / 10.0 : 0);
            if (!notes.isEmpty()) {
                JSONObject n = new JSONObject();
                for (Map.Entry<String, double[]> e : notes.entrySet()) n.put(e.getKey(), Math.round(e.getValue()[0] / Math.max(1, e.getValue()[1])));
                o.put("timedMs", n);
            }
            int thermal = -1; double headroom = -1;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 29) thermal = pm.getCurrentThermalStatus();
            if (pm != null && Build.VERSION.SDK_INT >= 30) headroom = pm.getThermalHeadroom(10);
            o.put("thermal", thermal); o.put("headroom", Math.round(headroom * 100) / 100.0);
            double rss = readStatusMB("VmRSS");
            o.put("rssMB", Math.round(rss));
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) { o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)); o.put("charging", bm.isCharging()); }
            line(o);
            double[] s = sums.computeIfAbsent(stage, k -> new double[10]);
            s[0] += dt; s[1] += cpu; s[2] += frames; s[3] += frameMs; s[4] = Math.max(s[4], thermal); s[5] = Math.max(s[5], rss); s[6]++;
            s[7] += perCluster[0]; s[8] += perCluster[1]; s[9] += perCluster[2];
            frames = 0; frameMs = 0; notes.clear();
        } catch (Exception e) { android.util.Log.e("RoomScape", "perf sample: " + e); }
    }

    /** {name, cpu ticks since last time, last core} per thread of this app. */
    private java.util.List<Object[]> sampleThreads(boolean prime) {
        java.util.List<Object[]> res = new java.util.ArrayList<>();
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) return res;
        for (File t : tasks) {
            try {
                String st = new String(Files.readAllBytes(new File(t, "stat").toPath()));
                int open = st.indexOf('('), close = st.lastIndexOf(')');
                String name = st.substring(open + 1, close);
                String[] f = st.substring(close + 2).split(" ");
                long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]); // utime, stime (fields 14, 15)
                int core = Integer.parseInt(f[36]);                           // processor (field 39)
                int tid = Integer.parseInt(t.getName());
                long[] last = lastThread.get(tid);
                long d = last == null ? 0 : ticks - last[0];
                lastThread.put(tid, new long[]{ticks});
                if (!prime && d > 0) res.add(new Object[]{name, d, core});
            } catch (Exception ignored) { }
        }
        return res;
    }

    private void line(JSONObject o) throws Exception { out.write(o.toString()); out.write('\n'); out.flush(); }

    private static long readLong(String path) {
        try { return Long.parseLong(new String(Files.readAllBytes(new File(path).toPath())).trim()); } catch (Exception e) { return -1; }
    }

    private static double readStatusMB(String key) {
        try {
            for (String l : Files.readAllLines(new File("/proc/self/status").toPath()))
                if (l.startsWith(key + ":")) return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024.0;
        } catch (Exception ignored) { }
        return 0;
    }
}
