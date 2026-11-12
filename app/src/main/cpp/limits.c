/*
 * 唯一作用：读取并尝试提升本进程的 RLIMIT_NOFILE。
 * 未 root 的 App 只能把 soft limit 提到 hard limit，不能超过 hard。
 * 返回提升后的实际 soft limit；失败返回 -1。
 */
#include <jni.h>
#include <sys/resource.h>
#include <stdint.h>

JNIEXPORT jlongArray JNICALL
Java_com_ai_natlimit_Limits_nativeLimits(JNIEnv *env, jclass clazz) {
    struct rlimit rl;
    jlong out[2] = {-1, -1};
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0) {
        out[0] = (jlong) rl.rlim_cur;
        out[1] = (jlong) rl.rlim_max;
    }
    jlongArray arr = (*env)->NewLongArray(env, 2);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 2, out);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_ai_natlimit_Limits_nativeRaise(JNIEnv *env, jclass clazz, jlong want) {
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) != 0) return -1;
    rlim_t target = (rlim_t) want;
    if (target > rl.rlim_max) target = rl.rlim_max;
    if (target <= rl.rlim_cur) return (jlong) rl.rlim_cur;
    rl.rlim_cur = target;
    if (setrlimit(RLIMIT_NOFILE, &rl) != 0) return -1;
    if (getrlimit(RLIMIT_NOFILE, &rl) != 0) return -1;
    return (jlong) rl.rlim_cur;
}
