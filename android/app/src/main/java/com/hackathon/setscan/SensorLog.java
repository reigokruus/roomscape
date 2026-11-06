package com.hackathon.setscan;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Logs the phone's raw sensors while scanning, for offline analysis of a scan:
 * gyro + accelerometer (drift checks), rotation vector (compass: where north is, i.e. which way windows face),
 * magnetometer, ambient light (lux, for lighting advice) and barometer.
 * Timestamps are elapsedRealtime nanos, the same clock as ARCore frame timestamps on Android.
 */
final class SensorLog implements SensorEventListener {
    // type ids in the export
    static final byte GYRO = 1, ACCEL = 2, ROT = 3, MAG = 4, LUX = 5, BARO = 6;
    private static final int MAX = 400_000;
    private final long[] ts = new long[MAX];
    private final byte[] type = new byte[MAX];
    private final float[] v = new float[MAX * 4];
    private int n;
    volatile boolean active;
    volatile float lastLux = -1, lastHeadingDeg = Float.NaN;
    private final float[] rotM = new float[9], ori = new float[3];
    private String sensorNames = "";

    void start(Context ctx) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        StringBuilder names = new StringBuilder();
        int[][] want = {{Sensor.TYPE_GYROSCOPE, 5000}, {Sensor.TYPE_ACCELEROMETER, 5000}, {Sensor.TYPE_ROTATION_VECTOR, 20000},
                {Sensor.TYPE_MAGNETIC_FIELD, 20000}, {Sensor.TYPE_LIGHT, 200000}, {Sensor.TYPE_PRESSURE, 200000}};
        for (int[] w : want) {
            Sensor s = sm.getDefaultSensor(w[0]);
            if (s == null) continue;
            sm.registerListener(this, s, w[1]);
            names.append(s.getStringType()).append(" = ").append(s.getName()).append(" (").append(s.getVendor()).append(")\n");
        }
        sensorNames = names.toString();
    }

    void stop(Context ctx) {
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) sm.unregisterListener(this);
    }

    synchronized void clear() { n = 0; }

    @Override public void onSensorChanged(SensorEvent e) {
        byte t;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GYROSCOPE: t = GYRO; break;
            case Sensor.TYPE_ACCELEROMETER: t = ACCEL; break;
            case Sensor.TYPE_ROTATION_VECTOR:
                t = ROT;
                SensorManager.getRotationMatrixFromVector(rotM, e.values);
                SensorManager.getOrientation(rotM, ori);
                lastHeadingDeg = (float) ((Math.toDegrees(ori[0]) + 360) % 360);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD: t = MAG; break;
            case Sensor.TYPE_LIGHT: t = LUX; lastLux = e.values[0]; break;
            case Sensor.TYPE_PRESSURE: t = BARO; break;
            default: return;
        }
        if (!active) return;
        synchronized (this) {
            if (n >= MAX) return;
            ts[n] = e.timestamp; type[n] = t;
            for (int k = 0; k < 4; k++) v[n * 4 + k] = k < e.values.length ? e.values[k] : 0;
            n++;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    /** Binary: per sample int64 timestamp, uint8 type, float32 x4 (little endian, 25 bytes). */
    synchronized byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(n * 25).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            b.putLong(ts[i]).put(type[i]);
            for (int k = 0; k < 4; k++) b.putFloat(v[i * 4 + k]);
        }
        return b.array();
    }

    String describe() {
        return "sensors.bin: int64 ts_ns, uint8 type (1 gyro rad/s, 2 accel m/s2, 3 rotation vector quat xyzw (north-referenced),"
                + " 4 magnetometer uT, 5 light lux, 6 pressure hPa), float32 x4; " + n + " samples\n" + sensorNames;
    }
}
