import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;

/**
 * Experimental probe for the xdja AutoContainer service on DiLink 4.0.
 *
 * Goals:
 *   1. Find whether a shell-uid process can reach the native "AutoContainerNative" binder.
 *   2. Enumerate its transaction codes; the hidden getProjectionDisplayInfo should reply with
 *      a ProjectionDisplayInfoParcel carrying the cluster projection Surface.
 * Reports every result on stdout so it can be read from an adb shell invocation.
 */
public final class AcProbe {

    private static final String DESCRIPTOR = "android.os.IAutoContainer";

    public static void main(String[] args) {
        IBinder service = getService(args.length > 0 ? args[0] : "AutoContainerNative");
        System.out.println("service=" + service);
        if (service == null) {
            dumpServiceManager();
            return;
        }
        System.out.println("pingBinder=" + service.pingBinder());
        System.out.println("interface=" + safeInterface(service));
        for (int code = 1; code <= 10; code++) {
            System.out.println("code=" + code + " -> " + transact(service, code));
        }
    }

    private static IBinder getService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            return (IBinder) get.invoke(null, name);
        } catch (Throwable t) {
            System.out.println("getService failed: " + t);
            return null;
        }
    }

    private static void dumpServiceManager() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method list = sm.getDeclaredMethod("listServices");
            list.setAccessible(true);
            String[] names = (String[]) list.invoke(null);
            System.out.println("services count=" + (names == null ? 0 : names.length));
            if (names != null) {
                for (String n : names) {
                    if (n != null && n.toLowerCase().contains("auto")) {
                        System.out.println("  candidate: " + n);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("listServices failed: " + t);
        }
    }

    private static String safeInterface(IBinder binder) {
        try {
            return binder.getInterfaceDescriptor();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    private static String transact(IBinder binder, int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            try {
                Method token = Parcel.class.getDeclaredMethod("writeInterfaceToken", String.class);
                token.setAccessible(true);
                token.invoke(data, DESCRIPTOR);
            } catch (Throwable ignored) {
                data.writeString(DESCRIPTOR);
            }
            boolean ok = binder.transact(code, data, reply, 0);
            if (!ok) {
                return "transact returned false";
            }
            reply.setDataPosition(0);
            StringBuilder sb = new StringBuilder("replyLen=").append(reply.dataSize()).append(" [");
            for (int i = 0; i < Math.min(reply.dataSize() / 4, 16); i++) {
                sb.append(Integer.toHexString(reply.readInt())).append(' ');
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "exception: " + t;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
