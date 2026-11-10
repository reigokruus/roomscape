package com.hackathon.setscan;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The room's surface fusion (TSDF) on the S24's GPU (rk 10 Nov: "S24 GPU surface building"), an OpenGL ES 3.1
 * compute shader in the renderer's own GL context: a dense block around where the scan started, every voxel updated
 * from each depth frame at once, with the same rules as the CPU Tsdf (band, carving free space with a margin, solid
 * surface only nudged, colours, and no clearing beside thin things). Changed 8×8×8 bricks are packed on the GPU and
 * read back into the CPU Tsdf (sync), which the live mesh, Done and everything else keep using.
 * GL thread only. If anything fails (no ES 3.1, the shader doesn't compile, a GL error), `failed` is set and the
 * renderer keeps the CPU fusion.
 */
final class GpuTsdf {
    private static final String TAG = "RoomScape";
    static final int MAX_SYNC_BRICKS = 4096;

    final float vs, trunc, carve, carveWeight;
    final int ox, oy, oz;          // block origin, voxel coordinates
    final int nx, ny, nz;          // size in voxels (multiples of 8)
    final int bx, by, bz;          // size in bricks
    boolean failed;
    String error = "";

    private int fuseProg, packProg;
    private int sdfBuf, wBuf, colBuf, dirtyBuf, depthBuf, imgBuf, listBuf, stageBuf, countBuf;
    private int depthCap;
    private int frame;

    /** A block from (minX, minY, minZ) to (maxX, maxY, maxZ) metres. Check `failed` afterwards. */
    GpuTsdf(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float voxel, float truncation, float carveDist, float carveWeight) {
        vs = voxel; trunc = truncation; carve = carveDist; this.carveWeight = carveWeight;
        ox = (int) Math.floor(minX / voxel); oy = (int) Math.floor(minY / voxel); oz = (int) Math.floor(minZ / voxel);
        bx = (int) Math.ceil((maxX - minX) / voxel / 8); by = (int) Math.ceil((maxY - minY) / voxel / 8); bz = (int) Math.ceil((maxZ - minZ) / voxel / 8);
        nx = bx * 8; ny = by * 8; nz = bz * 8;
        try {
            fuseProg = compute(FUSE);
            packProg = compute(PACK);
            int voxels = nx * ny * nz, bricks = bx * by * bz;
            int[] b = new int[9];
            GLES20.glGenBuffers(9, b, 0);
            sdfBuf = b[0]; wBuf = b[1]; colBuf = b[2]; dirtyBuf = b[3]; depthBuf = b[4]; imgBuf = b[5]; listBuf = b[6]; stageBuf = b[7]; countBuf = b[8];
            alloc(sdfBuf, voxels * 4); alloc(wBuf, voxels * 4); alloc(colBuf, voxels * 4); alloc(dirtyBuf, bricks * 4);
            alloc(listBuf, MAX_SYNC_BRICKS * 4); alloc(stageBuf, MAX_SYNC_BRICKS * 512 * 12); alloc(countBuf, 4);
            check("buffers");
            Log.i(TAG, "GPU surface: block " + nx + "x" + ny + "x" + nz + " voxels (" + voxels * 12 / 1048576 + " MB)");
        } catch (Throwable e) {
            failed = true; error = String.valueOf(e.getMessage());
            Log.e(TAG, "GPU surface unavailable: " + error);
        }
    }

    int voxelCount() { return nx * ny * nz; }

    private static void alloc(int buf, int bytes) {
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf);
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES20.GL_DYNAMIC_DRAW);
        // Zero it (null data is undefined).
        ByteBuffer z = ByteBuffer.allocateDirect(Math.min(bytes, 1 << 22)).order(ByteOrder.nativeOrder());
        for (int at = 0; at < bytes; at += z.capacity()) {
            int n = Math.min(z.capacity(), bytes - at);
            z.position(0); z.limit(n);
            GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, at, n, z);
        }
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static int compute(String src) {
        int s = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compute shader: " + GLES20.glGetShaderInfoLog(s));
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, s);
        GLES20.glLinkProgram(p);
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compute link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private void check(String where) {
        int e = GLES20.glGetError();
        if (e != GLES20.GL_NO_ERROR) throw new RuntimeException("GL error 0x" + Integer.toHexString(e) + " at " + where);
    }

    /**
     * One depth frame: `depth` metres per pixel (0 = none), w × h, row 0 at the top; `rgb` packed 0xBBGGRR per pixel;
     * `cam` camera → world (column-major, ARCore: x right, y up, looking down -z); intrinsics at the depth map's size.
     */
    void integrate(float[] depth, int[] rgb, int w, int h, float[] cam, float fx, float fy, float cx, float cy) {
        if (failed) return;
        try {
            int n = w * h;
            if (depthCap < n) {
                alloc(depthBuf, n * 4); alloc(imgBuf, n * 4); depthCap = n;
            }
            FloatBuffer db = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            db.put(depth, 0, n).position(0);
            IntBuffer ib = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(rgb, 0, n).position(0);
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, depthBuf);
            GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, n * 4, db);
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, imgBuf);
            GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, n * 4, ib);
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            float[] inv = new float[16];
            android.opengl.Matrix.invertM(inv, 0, cam, 0);
            frame++;
            GLES20.glUseProgram(fuseProg);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, sdfBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, wBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, colBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, dirtyBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, depthBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, imgBuf);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(fuseProg, "uToCam"), 1, false, inv, 0);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(fuseProg, "uK"), fx, fy, cx, cy);
            GLES20.glUniform2i(GLES20.glGetUniformLocation(fuseProg, "uSize"), w, h);
            GLES20.glUniform3i(GLES20.glGetUniformLocation(fuseProg, "uOrigin"), ox, oy, oz);
            GLES20.glUniform3i(GLES20.glGetUniformLocation(fuseProg, "uBricks"), bx, by, bz);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(fuseProg, "uP"), vs, trunc, carve, carveWeight);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(fuseProg, "uCam"), cam[12], cam[13], cam[14]);
            GLES31.glDispatchCompute(nx / 8, ny / 8, nz / 4);
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            check("fuse");
        } catch (Throwable e) {
            failed = true; error = String.valueOf(e.getMessage());
            Log.e(TAG, "GPU surface failed: " + error);
        }
    }

    /** Changed bricks, read back: brick ids (block brick index) and per voxel sdf, weight, packed colour. */
    static final class Sync { int count; int[] bricks; float[] sdf; int[] w; int[] col; }

    /** Packs the bricks changed since the last call (at most MAX_SYNC_BRICKS; the rest next time) and reads them back. */
    Sync sync() {
        if (failed) return null;
        try {
            ByteBuffer zero = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, countBuf);
            GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, zero);
            GLES20.glUseProgram(packProg);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, sdfBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, wBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, colBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, dirtyBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 6, listBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, stageBuf);
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 8, countBuf);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(packProg, "uBrickCount"), bx * by * bz);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(packProg, "uMax"), MAX_SYNC_BRICKS);
            GLES31.glDispatchCompute((bx * by * bz + 63) / 64, 1, 1);
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            Sync s = new Sync();
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, countBuf);
            ByteBuffer cb = (ByteBuffer) GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, GLES30.GL_MAP_READ_BIT);
            int count = cb == null ? 0 : Math.min(MAX_SYNC_BRICKS, cb.order(ByteOrder.nativeOrder()).getInt(0));
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            s.count = count;
            s.bricks = new int[count]; s.sdf = new float[count * 512]; s.w = new int[count * 512]; s.col = new int[count * 512];
            if (count > 0) {
                GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, listBuf);
                ByteBuffer lb = (ByteBuffer) GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 4, GLES30.GL_MAP_READ_BIT);
                lb.order(ByteOrder.nativeOrder()).asIntBuffer().get(s.bricks);
                GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
                GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, stageBuf);
                ByteBuffer sb = (ByteBuffer) GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 512 * 12, GLES30.GL_MAP_READ_BIT);
                sb.order(ByteOrder.nativeOrder());
                // Stage layout per slot: 512 sdf floats, 512 weights, 512 colours.
                for (int k = 0; k < count; k++) {
                    int base = k * 512 * 12;
                    for (int i = 0; i < 512; i++) {
                        s.sdf[k * 512 + i] = sb.getFloat(base + i * 4);
                        s.w[k * 512 + i] = sb.getInt(base + 2048 + i * 4);
                        s.col[k * 512 + i] = sb.getInt(base + 4096 + i * 4);
                    }
                }
                GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            }
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            check("sync");
            return s;
        } catch (Throwable e) {
            failed = true; error = String.valueOf(e.getMessage());
            Log.e(TAG, "GPU surface sync failed: " + error);
            return null;
        }
    }

    /** Puts synced bricks into the CPU Tsdf (any thread holding the Tsdf). */
    void apply(Sync s, Tsdf t) {
        if (s == null) return;
        for (int k = 0; k < s.count; k++) {
            int b = s.bricks[k];
            int bxi = b % bx, byi = (b / bx) % by, bzi = b / (bx * by);
            for (int i = 0; i < 512; i++) {
                int lx = i & 7, ly = (i >> 3) & 7, lz = i >> 6;
                int vx = ox + bxi * 8 + lx, vy = oy + byi * 8 + ly, vz = oz + bzi * 8 + lz;
                int wi = s.w[k * 512 + i];
                int c = s.col[k * 512 + i];
                t.setVoxel(vx, vy, vz, s.sdf[k * 512 + i], wi, c & 255, (c >> 8) & 255, (c >> 16) & 255);
            }
        }
    }

    void release() {
        int[] b = {sdfBuf, wBuf, colBuf, dirtyBuf, depthBuf, imgBuf, listBuf, stageBuf, countBuf};
        GLES20.glDeleteBuffers(b.length, b, 0);
        if (fuseProg != 0) GLES20.glDeleteProgram(fuseProg);
        if (packProg != 0) GLES20.glDeleteProgram(packProg);
    }

    // Voxel (gx, gy, gz) in the block lives at brick (gx/8, gy/8, gz/8), 512 voxels per brick, x fastest inside.
    private static final String COMMON =
            "#version 310 es\n" +
            "precision highp float; precision highp int;\n" +
            "layout(std430, binding = 0) buffer Sdf { float sdf[]; };\n" +
            "layout(std430, binding = 1) buffer Wgt { uint wgt[]; };\n" +
            "layout(std430, binding = 2) buffer Col { uint col[]; };\n" +
            "layout(std430, binding = 3) buffer Dirty { uint dirty[]; };\n";

    private static final String FUSE = COMMON +
            "layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;\n" +
            "layout(std430, binding = 4) readonly buffer Depth { float depth[]; };\n" +
            "layout(std430, binding = 5) readonly buffer Img { uint img[]; };\n" +
            "uniform mat4 uToCam; uniform vec4 uK; uniform ivec2 uSize; uniform ivec3 uOrigin; uniform ivec3 uBricks;\n" +
            "uniform vec4 uP; uniform vec3 uCam;\n" +
            "const uint MAX_W = 40u; const uint SOLID_W = 20u;\n" +
            "void main() {\n" +
            "  ivec3 g = ivec3(gl_GlobalInvocationID);\n" +
            "  if (any(greaterThanEqual(g, uBricks * 8))) return;\n" +
            "  float vs = uP.x, trunc = uP.y, carve = uP.z, cw = uP.w;\n" +
            "  vec3 c = (vec3(uOrigin + g) + 0.5) * vs;\n" +
            "  vec4 q = uToCam * vec4(c, 1.0);\n" +
            "  float z = -q.z;\n" +
            "  if (z < 0.1) return;\n" +
            "  float u = uK.x * q.x / z + uK.z, v = uK.w - uK.y * q.y / z;\n" +
            "  if (u < 0.0 || v < 0.0 || u >= float(uSize.x) || v >= float(uSize.y)) return;\n" +
            "  float d = depth[int(v) * uSize.x + int(u)];\n" +
            "  if (d <= 0.0) return;\n" +
            "  float r = length(c - uCam);\n" +
            "  float L = d * r / z;\n" +
            "  float s = L - r;\n" +
            "  ivec3 b = g / 8; ivec3 l = g - b * 8;\n" +
            "  int bi = (b.z * uBricks.y + b.y) * uBricks.x + b.x;\n" +
            "  int i = bi * 512 + (l.z * 64 + l.y * 8 + l.x);\n" +
            "  uint w = wgt[i];\n" +
            "  // How many CPU rays (one per depth pixel, steps of half a voxel) would cross this voxel: its size over a pixel.\n" +
            "  float fp = z / uK.x;\n" +
            "  uint add = uint(clamp((vs / fp) * (vs / fp), 1.0, 8.0));\n" +
            "  if (s > trunc) {\n" +
            "    float nearS = trunc + 0.05 * L, farS = min(carve, L - 0.25);\n" +
            "    if (w == 0u || s <= nearS || s > farS) return;\n" +
            "    // Only when the voxel's whole patch is clear: no clearing beside thin things.\n" +
            "    float rpx = min(6.0, 0.6 * vs * uK.x / z);\n" +
            "    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {\n" +
            "      if (dx == 0 && dy == 0) continue;\n" +
            "      float uu = u + float(dx) * rpx, vv = v + float(dy) * rpx;\n" +
            "      if (uu < 0.0 || vv < 0.0 || uu >= float(uSize.x) || vv >= float(uSize.y)) continue;\n" +
            "      float dd = depth[int(vv) * uSize.x + int(uu)];\n" +
            "      if (dd > 0.0 && dd * r / z < r + trunc) return;\n" +
            "    }\n" +
            "    if (w >= SOLID_W) { sdf[i] += (1.0 - sdf[i]) * 0.04 * cw; dirty[bi] = 1u; return; }\n" +
            "    sdf[i] = (sdf[i] * float(w) + cw) / (float(w) + cw);\n" +
            "    wgt[i] = min(w + 1u, MAX_W);\n" +
            "    dirty[bi] = 1u;\n" +
            "    return;\n" +
            "  }\n" +
            "  if (s < -trunc) return;\n" +
            "  float dn = clamp(s / trunc, -1.0, 1.0);\n" +
            "  if (w == 0u && dn > 0.99) return;   // free space only changes voxels that exist\n" +
            "  sdf[i] = (sdf[i] * float(w) + dn * float(add)) / float(w + add);\n" +
            "  wgt[i] = min(w + add, MAX_W);\n" +
            "  if (abs(dn) < 0.4) {\n" +
            "    uint p = img[int(v) * uSize.x + int(u)];\n" +
            "    vec3 nc = vec3(float(p & 255u), float((p >> 8) & 255u), float((p >> 16) & 255u));\n" +
            "    uint o = col[i];\n" +
            "    vec3 oc = vec3(float(o & 255u), float((o >> 8) & 255u), float((o >> 16) & 255u));\n" +
            "    float a = 1.0 / float(min(w + 1u, 12u));\n" +
            "    vec3 m = w == 0u ? nc : oc + (nc - oc) * a;\n" +
            "    col[i] = uint(m.r + 0.5) | (uint(m.g + 0.5) << 8) | (uint(m.b + 0.5) << 16);\n" +
            "  }\n" +
            "  dirty[bi] = 1u;\n" +
            "}\n";

    private static final String PACK = COMMON +
            "layout(local_size_x = 64) in;\n" +
            "layout(std430, binding = 6) buffer List { uint list[]; };\n" +
            "layout(std430, binding = 7) buffer Stage { uint stage[]; };\n" +
            "layout(std430, binding = 8) buffer Count { uint count; };\n" +
            "uniform int uBrickCount; uniform int uMax;\n" +
            "void main() {\n" +
            "  int b = int(gl_GlobalInvocationID.x);\n" +
            "  if (b >= uBrickCount || dirty[b] == 0u) return;\n" +
            "  uint slot = atomicAdd(count, 1u);\n" +
            "  if (int(slot) >= uMax) return;            // stays dirty: next sync\n" +
            "  dirty[b] = 0u;\n" +
            "  list[slot] = uint(b);\n" +
            "  int base = int(slot) * 1536, src = b * 512;\n" +
            "  for (int i = 0; i < 512; i++) {\n" +
            "    stage[base + i] = floatBitsToUint(sdf[src + i]);\n" +
            "    stage[base + 512 + i] = wgt[src + i];\n" +
            "    stage[base + 1024 + i] = col[src + i];\n" +
            "  }\n" +
            "}\n";
}
