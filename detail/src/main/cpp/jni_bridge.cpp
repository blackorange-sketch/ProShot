#include <jni.h>

#include <vector>

#include "stacker_core.h"

using proshot::NativeStacker;

namespace {

std::vector<uint8_t> copyIn(JNIEnv* env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::vector<uint8_t> v(static_cast<size_t>(n));
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return v;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_proshot_detail_NativeBridge_nativeCreate(
    JNIEnv* env, jobject, jbyteArray y, jbyteArray u, jbyteArray v, jint w, jint h, jfloat sigma) {
    auto cy = copyIn(env, y);
    auto cu = copyIn(env, u);
    auto cv = copyIn(env, v);
    return reinterpret_cast<jlong>(new NativeStacker(cy.data(), cu.data(), cv.data(), w, h, sigma));
}

JNIEXPORT void JNICALL Java_com_proshot_detail_NativeBridge_nativeAdd(
    JNIEnv* env, jobject, jlong handle, jbyteArray y, jbyteArray u, jbyteArray v, jintArray shiftOut) {
    auto* s = reinterpret_cast<NativeStacker*>(handle);
    auto cy = copyIn(env, y);
    auto cu = copyIn(env, u);
    auto cv = copyIn(env, v);
    int shift[2] = {0, 0};
    s->add(cy.data(), cu.data(), cv.data(), shift);
    env->SetIntArrayRegion(shiftOut, 0, 2, reinterpret_cast<const jint*>(shift));
}

JNIEXPORT void JNICALL Java_com_proshot_detail_NativeBridge_nativeResult(
    JNIEnv* env, jobject, jlong handle, jbyteArray y, jbyteArray u, jbyteArray v) {
    auto* s = reinterpret_cast<NativeStacker*>(handle);
    std::vector<uint8_t> oy(static_cast<size_t>(env->GetArrayLength(y)));
    std::vector<uint8_t> ou(static_cast<size_t>(env->GetArrayLength(u)));
    std::vector<uint8_t> ov(static_cast<size_t>(env->GetArrayLength(v)));
    s->result(oy.data(), ou.data(), ov.data());
    env->SetByteArrayRegion(y, 0, static_cast<jsize>(oy.size()), reinterpret_cast<const jbyte*>(oy.data()));
    env->SetByteArrayRegion(u, 0, static_cast<jsize>(ou.size()), reinterpret_cast<const jbyte*>(ou.data()));
    env->SetByteArrayRegion(v, 0, static_cast<jsize>(ov.size()), reinterpret_cast<const jbyte*>(ov.data()));
}

JNIEXPORT void JNICALL Java_com_proshot_detail_NativeBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeStacker*>(handle);
}

}  // extern "C"
