#include <windows.h>
#include <jni.h>
JNIEXPORT jlong JNICALL Java_KuboCloseProbe_openProcess(JNIEnv *env, jclass cls, jlong pid) {
    return (jlong)(intptr_t) OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, (DWORD) pid);
}
JNIEXPORT jint JNICALL Java_KuboCloseProbe_processSignal(JNIEnv *env, jclass cls, jlong handle) {
    return (jint) WaitForSingleObject((HANDLE)(intptr_t)handle, 0);
}
JNIEXPORT void JNICALL Java_KuboCloseProbe_closeProcess(JNIEnv *env, jclass cls, jlong handle) {
    CloseHandle((HANDLE)(intptr_t)handle);
}
