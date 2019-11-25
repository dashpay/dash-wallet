/**
 * Created by Hash Engineering on 4/24/14 for the X11 algorithm
 */
#include "hashblock.h"
#include <inttypes.h>

#include <jni.h>

jbyteArray JNICALL x11_native(JNIEnv *env, jclass cls, jbyteArray input, jint offset, jint length)
{
    jbyte *pInput = (env)->GetByteArrayElements(input, NULL);
    jbyteArray byteArray = NULL;

    if (pInput)
    {
        jbyte result[HASH256_SIZE];
        HashX11((uint8_t *)pInput+offset, (uint8_t *)pInput+offset+length, (uint8_t *)result);

        byteArray = (env)->NewByteArray(32);
        if (byteArray)
        {
            (env)->SetByteArrayRegion(byteArray, 0, 32, (jbyte *) result);
        }

        (env)->ReleaseByteArrayElements(input, pInput, JNI_ABORT);
    } else {
        jclass e = env->FindClass("java/lang/NullPointerException");
        env->ThrowNew(e, "input is null");
    }
    return byteArray;
}

static const JNINativeMethod methods[] = {
        { "x11_native", "([BII)[B", (void *) x11_native }
};

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;

    if ((vm)->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass cls = (env)->FindClass("com/hashengineering/crypto/X11");
    int r = (env)->RegisterNatives(cls, methods, 1);

    return (r == JNI_OK) ? JNI_VERSION_1_6 : -1;
}