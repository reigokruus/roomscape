package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shot lighting preview: gear lights and props in the scanned room.
 *
 * The scan's colours are the room as the phone camera saw it, i.e. already lit by the room's own light
 * (assumed ROOM_LUX, a typical lit interior). So each surface's "albedo" is its scanned colour / ROOM_LUX,
 * and with gear lights on it shows   colour * (roomDimmer + sum of light lux / ROOM_LUX).
 * The virtual camera is exposed for the subject's face (like a DP would), +- the exposure offset.
 */
final class Lighting {
    static final float ROOM_LUX = 250f;

    enum Type {
        FRESNEL("Fresnel 300 W LED", 20000f, 30f, 0f),
        PANEL("LED panel 1x1", 3000f, 60f, 0.3f),
        SOFTBOX("Softbox 90 cm", 6000f, 100f, 0.6f),
        TUBE("LED tube", 800f, 140f, 0.5f),
        PRACTICAL("Practical lamp", 300f, 170f, 0.7f);
        final String label; final float luxAt1m, beamDeg, wrap;
        Type(String l, float lux, float beam, float wrap) { label = l; luxAt1m = lux; beamDeg = beam; this.wrap = wrap; }
    }

    static final class Light {
        String name; Type type;
        float x, y, z;          // position
        float dimmer = 0.6f;    // 0..1
        float kelvin = 5600f;
        float beamDeg;
        boolean on = true;
        float lux1m() { return type.luxAt1m * dimmer; }
    }

    enum PropKind {
        PERSON_STANDING("Person (standing)"), PERSON_SEATED("Person (seated)"), TABLE("Table"), CHAIR("Chair");
        final String label; PropKind(String l) { label = l; }
    }

    static final class Prop {
        PropKind kind; float x, z, yawDeg;
        Prop(PropKind k, float x, float z, float yaw) { kind = k; this.x = x; this.z = z; yawDeg = yaw; }
        /** eye / face height above the floor (people), or top height */
        float faceH() { return kind == PropKind.PERSON_STANDING ? 1.62f : kind == PropKind.PERSON_SEATED ? 1.18f : kind == PropKind.TABLE ? 0.75f : 0.45f; }
        boolean isPerson() { return kind == PropKind.PERSON_STANDING || kind == PropKind.PERSON_SEATED; }
    }

    final List<Light> lights = new ArrayList<>();
    final List<Prop> props = new ArrayList<>();
    float floorY;
    float roomDimmer = 1f;   // 0 = room lights off, 1 = as scanned
    float exposureEv = 0f;   // + brighter
    float iso = 800f, shutter = 1f / 50f;
    float wbKelvin = 5600f;
    boolean enabled;
    int selected = -1;       // index into lights, then props (lights.size() + i)
    float camX, camY, camZ;  // the virtual camera (for the face normal); defaults to the viewer
    boolean haveCam;

    /** the subject: the first person, else the first prop, else null */
    Prop subject() {
        for (Prop p : props) if (p.isPerson()) return p;
        return props.isEmpty() ? null : props.get(0);
    }

    float[] subjectFace() {
        Prop s = subject();
        if (s == null) return null;
        return new float[]{s.x, floorY + s.faceH(), s.z};
    }

    /** Lux on a surface at p facing n from one light (inverse square, beam falloff, soft wrap). */
    static float luxFrom(Light l, float[] aim, float px, float py, float pz, float nx, float ny, float nz) {
        if (!l.on) return 0;
        float lx = l.x - px, ly = l.y - py, lz = l.z - pz;
        float d2 = Math.max(0.04f, lx * lx + ly * ly + lz * lz), d = (float) Math.sqrt(d2);
        lx /= d; ly /= d; lz /= d;
        float ndl = nx * lx + ny * ly + nz * lz;
        float wrap = l.type.wrap;
        float lam = Math.max(0, (ndl + wrap) / (1 + wrap));
        float cone = 1f;
        if (aim != null) {
            float ax = aim[0] - l.x, ay = aim[1] - l.y, az = aim[2] - l.z, al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (al > 1e-3f) {
                float c = -(lx * ax + ly * ay + lz * az) / al;
                float outer = (float) Math.cos(Math.toRadians(l.beamDeg / 2)), inner = (float) Math.cos(Math.toRadians(l.beamDeg / 2 * 0.6f));
                cone = smooth(outer, inner, c);
            }
        }
        return l.lux1m() * cone * lam / d2;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Light level on the subject's face (facing the camera) and the f-stop that exposes it at ISO / shutter. */
    String readout() {
        float[] f = subjectFace();
        if (f == null) return "Add a person to see the light on their face.";
        float nx = camX - f[0], ny = 0, nz = camZ - f[2];
        float nl = (float) Math.sqrt(nx * nx + nz * nz);
        if (!haveCam || nl < 1e-3f) { nx = 0; nz = 1; } else { nx /= nl; nz /= nl; }
        float lux = ROOM_LUX * roomDimmer * 0.7f;
        StringBuilder per = new StringBuilder();
        for (Light l : lights) {
            float e = luxFrom(l, f, f[0], f[1], f[2], nx, ny, nz);
            lux += e;
            if (l.on) per.append(String.format(Locale.US, "%s %.0f lx · ", l.name, e));
        }
        // incident meter: N^2 = E * t * S / C, C ~ 250
        double n = Math.sqrt(lux * shutter * iso / 250.0) * Math.pow(2, -exposureEv / 2.0);
        return String.format(Locale.US, "Face: %.0f lx (%sroom %.0f lx) → f/%s at ISO %.0f, 1/%.0f s",
                lux, per, ROOM_LUX * roomDimmer * 0.7f, fstop(n), iso, 1 / shutter);
    }

    private static String fstop(double n) {
        double[] stops = {1.0, 1.2, 1.4, 1.8, 2.0, 2.4, 2.8, 3.5, 4.0, 4.8, 5.6, 6.7, 8.0, 9.5, 11, 13, 16, 19, 22};
        double best = stops[0];
        for (double s : stops) if (Math.abs(Math.log(s / n)) < Math.abs(Math.log(best / n))) best = s;
        return (n < 0.9 ? "<1 (too dark: " : "") + (best == Math.floor(best) ? String.valueOf((int) best) : String.valueOf(best)) + (n < 0.9 ? ")" : "");
    }

    /** Exposure multiplier that brings the face to how the room looked in the scan (then +- exposureEv). */
    float exposure() {
        float[] f = subjectFace();
        float lux = ROOM_LUX * roomDimmer;
        if (f != null) {
            float nx = camX - f[0], nz = camZ - f[2], nl = (float) Math.sqrt(nx * nx + nz * nz);
            if (!haveCam || nl < 1e-3f) { nx = 0; nz = 1; } else { nx /= nl; nz /= nl; }
            for (Light l : lights) lux += luxFrom(l, f, f[0], f[1], f[2], nx, 0, nz);
        }
        return ROOM_LUX / Math.max(5f, lux) * (float) Math.pow(2, exposureEv);
    }

    /** Colour of a black body at K, relative to the white balance (approximation by T. Helland). */
    static float[] tint(float k, float wb) {
        float[] a = kelvin(k), b = kelvin(wb);
        return new float[]{a[0] / b[0], a[1] / b[1], a[2] / b[2]};
    }

    static float[] kelvin(float k) {
        double t = k / 100.0, r, g, b;
        if (t <= 66) { r = 255; g = 99.4708025861 * Math.log(t) - 161.1195681661; }
        else { r = 329.698727446 * Math.pow(t - 60, -0.1332047592); g = 288.1221695283 * Math.pow(t - 60, -0.0755148492); }
        if (t >= 66) b = 255; else if (t <= 19) b = 0; else b = 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return new float[]{(float) clamp(r / 255), (float) clamp(g / 255), (float) clamp(b / 255)};
    }

    private static double clamp(double v) { return Math.max(0.02, Math.min(1, v)); }

    /** A starting setup around a subject, from the planner's interview layout if there is one. */
    void defaultSetup(ShotPlanner.Plan plan, float fx, float fz, float floorY) {
        lights.clear(); props.clear();
        this.floorY = floorY;
        Prop person = new Prop(PropKind.PERSON_SEATED, fx, fz, 0);
        props.add(person);
        if (plan != null) {
            person.x = plan.sx; person.z = plan.sz;
            for (ShotPlanner.Element e : plan.elements) {
                if (e.isCamera) { camX = e.x; camY = e.y; camZ = e.z; haveCam = true; continue; }
                Light l = new Light();
                l.name = e.name;
                l.type = e.name.startsWith("Key") ? Type.SOFTBOX : e.name.startsWith("Fill") ? Type.PANEL : Type.FRESNEL;
                l.beamDeg = l.type.beamDeg;
                l.dimmer = e.name.startsWith("Key") ? 0.6f : e.name.startsWith("Fill") ? 0.35f : 0.25f;
                l.x = e.x; l.y = e.y; l.z = e.z;
                lights.add(l);
            }
        }
        if (lights.isEmpty()) {
            Light key = new Light(); key.name = "Key light"; key.type = Type.SOFTBOX; key.beamDeg = key.type.beamDeg;
            key.x = fx + 1.2f; key.y = floorY + 1.9f; key.z = fz + 0.8f; lights.add(key);
            Light fill = new Light(); fill.name = "Fill"; fill.type = Type.PANEL; fill.beamDeg = fill.type.beamDeg; fill.dimmer = 0.3f;
            fill.x = fx - 1.2f; fill.y = floorY + 1.6f; fill.z = fz + 0.9f; lights.add(fill);
        }
    }

    String selectedLabel() {
        if (selected < 0) return "nothing";
        if (selected < lights.size()) { Light l = lights.get(selected); return l.name + " · " + l.type.label; }
        int i = selected - lights.size();
        return i < props.size() ? props.get(i).kind.label : "nothing";
    }

    /** position of the selected thing (x, z) for dragging; null when nothing is selected */
    float[] selectedXZ() {
        if (selected < 0) return null;
        if (selected < lights.size()) { Light l = lights.get(selected); return new float[]{l.x, l.z}; }
        int i = selected - lights.size();
        if (i >= props.size()) return null;
        return new float[]{props.get(i).x, props.get(i).z};
    }

    void moveSelected(float x, float z) {
        if (selected < 0) return;
        if (selected < lights.size()) { lights.get(selected).x = x; lights.get(selected).z = z; return; }
        int i = selected - lights.size();
        if (i < props.size()) { props.get(i).x = x; props.get(i).z = z; }
    }
}
