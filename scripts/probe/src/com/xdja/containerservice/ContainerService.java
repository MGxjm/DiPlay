package com.xdja.containerservice;

/**
 * Host stub for the native methods shipped in libxdjacontainerservice_jni.so.
 *
 * The stock library registers its implementations with RegisterNatives during JNI_OnLoad, keyed by
 * this exact class name and method signature. Loading it from a shell-uid app_process therefore binds
 * real implementations onto these declarations, exactly the way the launcher's mirror daemon uses it.
 */
public final class ContainerService {

    static {
        System.load("/system/lib64/libxdjacontainerservice_jni.so");
    }

    /** The single projection display at the given index, or {@code null} when unavailable. */
    public static native QtDisplayInfo getQtProjectionDispInfoNative(int index);

    /** Every projection display currently published by the cluster container. */
    public static native QtDisplayInfo[] getQtProjectionDispInfoArrayNative();

    private ContainerService() {
    }
}
