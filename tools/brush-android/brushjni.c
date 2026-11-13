// JNI bridge from the Android app (com.hackathon.setscan.BrushNative) to Brush's C interface (brush_c.h).
// Brush: https://github.com/ArthurBrussee/brush, Apache-2.0. Built by tools/brush-android/build.sh.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "../brush-ios/include/brush_c.h"

static volatile uint8_t g_cancel = 0;
static JavaVM *g_vm = NULL;

typedef struct { jobject listener; jmethodID on; } Ctx;

static void progress(BrushProgressMessage m, void *user) {
    Ctx *c = (Ctx *) user;
    if (!c->listener || !g_vm) return;
    JNIEnv *env = NULL; int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    (*env)->CallVoidMethod(env, c->listener, c->on, (jint) m.tag, (jint) m.iter);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) { g_vm = vm; return JNI_VERSION_1_6; }

JNIEXPORT jint JNICALL Java_com_hackathon_setscan_BrushNative_train(JNIEnv *env, jclass cls, jstring dataset, jstring out,
        jint steps, jint refineEvery, jint maxRes, jint exportEvery, jint maxSplats, jint sh, jobject listener) {
    const char *d = (*env)->GetStringUTFChars(env, dataset, NULL);
    const char *o = (*env)->GetStringUTFChars(env, out, NULL);
    Ctx c = { NULL, NULL };
    if (listener) {
        c.listener = (*env)->NewGlobalRef(env, listener);
        jclass lc = (*env)->GetObjectClass(env, listener);
        c.on = (*env)->GetMethodID(env, lc, "on", "(II)V");
    }
    g_cancel = 0;
    BrushTrainOptions opt;
    memset(&opt, 0, sizeof opt);
    opt.total_train_steps = (uint32_t) steps; opt.refine_every = (uint32_t) refineEvery;
    opt.max_resolution = (uint32_t) maxRes; opt.export_every = (uint32_t) exportEvery; opt.output_path = o;
    opt.max_splats = (uint32_t) maxSplats; opt.sh_degree = (uint32_t) sh; opt.cancel = (const uint8_t *) &g_cancel;
    BrushTrainExitCode r = train_and_save(d, &opt, progress, &c);
    if (c.listener) (*env)->DeleteGlobalRef(env, c.listener);
    (*env)->ReleaseStringUTFChars(env, dataset, d);
    (*env)->ReleaseStringUTFChars(env, out, o);
    return (jint) r;
}

JNIEXPORT void JNICALL Java_com_hackathon_setscan_BrushNative_cancel(JNIEnv *env, jclass cls) { g_cancel = 1; }

JNIEXPORT void JNICALL Java_com_hackathon_setscan_BrushNative_setenv(JNIEnv *env, jclass cls, jstring name, jstring value) {
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    setenv(n, v, 1);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, value, v);
}
