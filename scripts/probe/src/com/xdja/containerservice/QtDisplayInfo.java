package com.xdja.containerservice;

import android.view.Surface;

/** Mirror of the head unit's own projection info class; the field names must match the stock one. */
public final class QtDisplayInfo {
    public String name;
    public int width;
    public int height;
    public Surface surface;

    @Override
    public String toString() {
        return "QtDisplayInfo{name=" + name + ", width=" + width
                + ", height=" + height + ", surface=" + surface + "}";
    }
}
