package com.hackathon.setscan.experiment;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.*;
import java.util.*;

/**
 * EXPERIMENT (rk 11 Nov: "what if we switched away from ARCore completely and built our own that uses the ultrawide?").
 * Records the S24's ultrawide camera without ARCore, for checking offline on the Mac how well it could be tracked:
 * video (1280x720, 30 fps, stabilisation and distortion correction off, fixed focus), every frame's sensor timestamp
 * and exposure, gyro / accelerometer / rotation vector at full rate on the same clock, and the lens calibration.
 * Files: <external app files>/recordings/uw-<time>/ (video.mp4, frames.csv, imu.csv, camera.json).
 *
 * Fully separate from the scanner: this folder (experiment/), one debug button, one manifest entry. To remove: delete
 * the folder, the button in MainActivity ("Ultrawide recorder") and the activity line in AndroidManifest.xml.
 */
public class UltrawideRecorderActivity extends Activity implements SensorEventListener {
    static final int W = 1280, H = 720;
    private TextureView preview; private TextView status; private Button button;
    private HandlerThread thread; private Handler handler;
    private CameraDevice camera; private CameraCaptureSession session; private MediaRecorder recorder;
    private SensorManager sensors; private File dir; private PrintWriter frames, imu;
    private boolean recording; private long startNs; private int frameCount;
    private String camId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setBackgroundColor(0xAA000000); bar.setPadding(24, 80, 24, 24);
        status = new TextView(this); status.setTextColor(0xFFFFFFFF); status.setText("Ultrawide recorder (experiment): no ARCore. Walk the room like a scan, past the plain walls, and come back to where you started.");
        button = new Button(this); button.setText("Start recording"); button.setOnClickListener(v -> { if (recording) stop(); else start(); });
        bar.addView(status); bar.addView(button);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        thread = new HandlerThread("uw"); thread.start(); handler = new Handler(thread.getLooper());
        try { camId = pickUltrawide(); } catch (Exception e) { status.setText("No camera: " + e); }
    }

    /** The back camera with the widest view (S24: id 2, 104 deg). */
    private String pickUltrawide() throws CameraAccessException {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String best = null; double bestFov = 0;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF ss = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK || fl == null || fl.length == 0 || ss == null) continue;
            double fov = 2 * Math.toDegrees(Math.atan(ss.getWidth() / (2 * fl[0])));
            if (fov > bestFov) { bestFov = fov; best = id; }
        }
        return best;
    }

    @Override protected void onResume() {
        super.onResume();
        if (preview.isAvailable()) openCamera(); else preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    @Override protected void onPause() { if (recording) stop(); closeCamera(); super.onPause(); }

    private void openCamera() {
        if (camId == null || camera != null) return;
        try {
            ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).openCamera(camId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice c) { camera = c; startSession(null); }
                public void onDisconnected(CameraDevice c) { c.close(); camera = null; }
                public void onError(CameraDevice c, int e) { c.close(); camera = null; runOnUiThread(() -> status.setText("Camera error " + e)); }
            }, handler);
        } catch (SecurityException | CameraAccessException e) { status.setText("Can't open the camera: " + e); }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        if (camera != null) camera.close(); camera = null;
    }

    /** Preview only, or preview + the recorder's surface. */
    private void startSession(Surface rec) {
        try {
            if (session != null) { session.close(); session = null; }
            SurfaceTexture st = preview.getSurfaceTexture(); st.setDefaultBufferSize(W, H);
            Surface pv = new Surface(st);
            List<Surface> outs = new ArrayList<>(); outs.add(pv); if (rec != null) outs.add(rec);
            final CaptureRequest.Builder rb = camera.createCaptureRequest(rec != null ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s : outs) rb.addTarget(s);
            rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));
            rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            rb.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            if (android.os.Build.VERSION.SDK_INT >= 28) rb.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            rb.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.5f);   // ~2 m (fixed-focus lenses ignore it)
            camera.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try { s.setRepeatingRequest(rb.build(), rec != null ? frameLog : null, handler); } catch (Exception e) { runOnUiThread(() -> status.setText("Session: " + e)); }
                }
                public void onConfigureFailed(CameraCaptureSession s) { runOnUiThread(() -> status.setText("The camera session couldn't be set up")); }
            }, handler);
        } catch (Exception e) { status.setText("Session: " + e); }
    }

    private final CameraCaptureSession.CaptureCallback frameLog = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            PrintWriter f = frames; if (f == null) return;
            Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP), exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME), skew = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY); Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            synchronized (f) { f.println(res.getFrameNumber() + "," + ts + "," + exp + "," + iso + "," + skew + "," + fd); }
            frameCount++;
            if (frameCount % 30 == 0) { final int n = frameCount; final long e = exp == null ? 0 : exp / 1000; runOnUiThread(() -> status.setText(String.format(Locale.US, "Recording: %d frames (%.0f s), exposure %d µs", n, n / 30f, e))); }
        }
    };

    private void start() {
        if (camera == null) return;
        try {
            dir = new File(getExternalFilesDir("recordings"), "uw-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
            dir.mkdirs();
            writeCalibration(new File(dir, "camera.json"));
            frames = new PrintWriter(new BufferedWriter(new FileWriter(new File(dir, "frames.csv")))); frames.println("frame,sensor_ts_ns,exposure_ns,iso,rolling_shutter_skew_ns,focus_diopters");
            imu = new PrintWriter(new BufferedWriter(new FileWriter(new File(dir, "imu.csv")))); imu.println("type,ts_ns,x,y,z,w");
            recorder = android.os.Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(W, H); recorder.setVideoFrameRate(30); recorder.setVideoEncodingBitRate(12_000_000);
            recorder.setOutputFile(new File(dir, "video.mp4").getAbsolutePath());
            recorder.prepare();
            for (int t : new int[]{Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE_UNCALIBRATED}) {
                Sensor s = sensors.getDefaultSensor(t); if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, handler);
            }
            frameCount = 0; startNs = System.nanoTime();
            startSession(recorder.getSurface());
            handler.postDelayed(() -> { try { recorder.start(); } catch (Exception e) { runOnUiThread(() -> status.setText("Recorder: " + e)); } }, 400);
            recording = true; button.setText("Stop");
        } catch (Exception e) { status.setText("Couldn't start: " + e); }
    }

    private void stop() {
        recording = false; button.setText("Start recording");
        sensors.unregisterListener(this);
        try { if (session != null) session.stopRepeating(); } catch (Exception ignored) { }
        try { recorder.stop(); } catch (Exception ignored) { }
        try { recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        PrintWriter f = frames, m = imu; frames = null; imu = null;
        if (f != null) synchronized (f) { f.close(); }
        if (m != null) synchronized (m) { m.close(); }
        status.setText("Saved: " + dir.getAbsolutePath() + " (" + frameCount + " frames)");
        if (camera != null) startSession(null);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        PrintWriter m = imu; if (m == null) return;
        String t = e.sensor.getType() == Sensor.TYPE_GYROSCOPE ? "g" : e.sensor.getType() == Sensor.TYPE_ACCELEROMETER ? "a" : e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR ? "r" : "u";
        synchronized (m) { m.println(t + "," + e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2] + "," + (e.values.length > 3 ? e.values[3] : 0)); }
    }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    /** The lens and sensor facts for undistorting and for the IMU-camera time base. */
    private void writeCalibration(File f) throws Exception {
        CameraCharacteristics c = ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(camId);
        org.json.JSONObject j = new org.json.JSONObject();
        j.put("cameraId", camId).put("width", W).put("height", H);
        j.put("focalLengthsMm", arr(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
        android.util.SizeF ss = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE); if (ss != null) j.put("sensorMm", ss.getWidth() + "x" + ss.getHeight());
        android.graphics.Rect act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE); if (act != null) j.put("activeArray", act.flattenToString());
        Size px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE); if (px != null) j.put("pixelArray", px.toString());
        j.put("intrinsics_fx_fy_cx_cy_s", arr(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)));
        if (android.os.Build.VERSION.SDK_INT >= 28) j.put("distortion_k1_k2_k3_p1_p2", arr(c.get(CameraCharacteristics.LENS_DISTORTION)));
        j.put("lensPoseRotation_xyzw", arr(c.get(CameraCharacteristics.LENS_POSE_ROTATION)));
        j.put("lensPoseTranslation_m", arr(c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)));
        if (android.os.Build.VERSION.SDK_INT >= 28) { Integer ref = c.get(CameraCharacteristics.LENS_POSE_REFERENCE); j.put("lensPoseReference", ref); }
        Integer tsrc = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        j.put("timestampSource", tsrc == null ? "?" : tsrc == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ? "REALTIME (same clock as the sensors)" : "UNKNOWN (not the sensors' clock)");
        j.put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION));
        j.put("hardwareLevel", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        j.put("model", android.os.Build.MODEL);
        try (Writer w = new FileWriter(f)) { w.write(j.toString(2)); }
    }

    private static org.json.JSONArray arr(float[] a) throws Exception { org.json.JSONArray r = new org.json.JSONArray(); if (a != null) for (float v : a) r.put((double) v); return r; }
}
