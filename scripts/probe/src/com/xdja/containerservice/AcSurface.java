package com.xdja.containerservice;

/**
 * Tries the headline discovery: load the stock cluster container JNI library and ask it for the
 * projection display Surface. If this returns a Surface, a third-party shell process can draw to the
 * instrument cluster without any system service or root.
 */
public final class AcSurface {

    public static void main(String[] args) {
        try {
            Class.forName("com.xdja.containerservice.ContainerService");
            System.out.println("library loaded OK");
        } catch (Throwable t) {
            System.out.println("load failed: " + t);
            return;
        }
        printArray();
        for (int i = 0; i < 4; i++) {
            printOne(i);
        }
    }

    private static void printArray() {
        try {
            QtDisplayInfo[] all = ContainerService.getQtProjectionDispInfoArrayNative();
            System.out.println("array=" + (all == null ? "null" : ("len=" + all.length)));
            if (all != null) {
                for (QtDisplayInfo info : all) {
                    System.out.println("  " + info);
                }
            }
        } catch (Throwable t) {
            System.out.println("array call failed: " + t);
        }
    }

    private static void printOne(int index) {
        try {
            QtDisplayInfo info = ContainerService.getQtProjectionDispInfoNative(index);
            System.out.println("index=" + index + " -> " + info);
        } catch (Throwable t) {
            System.out.println("index=" + index + " failed: " + t);
        }
    }
}
