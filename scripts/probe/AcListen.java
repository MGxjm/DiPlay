import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.lang.reflect.Method;

/**
 * Registers an IContainerCallback with the running AutoContainer service and prints every event it
 * pushes. This is how the cluster projection protocol can be observed without guessing type codes:
 * operate projections on the head unit while this probe runs and read the stream.
 *
 * Usage: CLASSPATH=<dex> app_process /system/bin AcListen [seconds]
 */
public final class AcListen {

    private static final String CONTAINER = "android.os.IAutoContainer";
    private static final String CALLBACK = "android.os.IContainerCallback";

    public static void main(String[] args) {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 120;
        IBinder service = getService("AutoContainer");
        System.out.println("service=" + service);
        IBinder nativeService = getService("AutoContainerNative");
        System.out.println("native=" + nativeService
                + " ping=" + (nativeService != null && nativeService.pingBinder()));

        if (service != null) {
            System.out.println("register -> " + register(service, new Listener()));
        }
        System.out.println("listening for " + seconds + "s ...");
        Looper.prepareMainLooper();
        new android.os.Handler().postDelayed(
                () -> {
                    System.out.println("done");
                    System.exit(0);
                },
                seconds * 1000L);
        Looper.loop();
    }

    private static String register(IBinder service, IBinder callback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            writeToken(data, CONTAINER);
            data.writeStrongBinder(callback);
            boolean ok = service.transact(1, data, reply, 0);
            reply.setDataPosition(0);
            return "ok=" + ok + " replyData=" + reply.dataSize() + " first="
                    + safeInt(reply);
        } catch (Throwable t) {
            return "exception: " + t;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static int safeInt(Parcel p) {
        try {
            return p.readInt();
        } catch (Throwable t) {
            return -999;
        }
    }

    private static void writeToken(Parcel p, String name) {
        try {
            Method m = Parcel.class.getDeclaredMethod("writeInterfaceToken", String.class);
            m.setAccessible(true);
            m.invoke(p, name);
        } catch (Throwable ignored) {
            p.writeString(name);
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

    static final class Listener extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                data.enforceInterface(CALLBACK);
            } catch (Throwable t) {
                System.out.println("  [enforceInterface failed] " + t);
                return false;
            }
            try {
                switch (code) {
                    case 1:
                        System.out.println("  receivedInfo type=" + data.readInt()
                                + " sub=" + data.readInt() + " text=" + data.readString());
                        return true;
                    case 2:
                        System.out.println("  receivedInfo2 type=" + data.readInt()
                                + " bytes=" + describeBytes(data.createByteArray()));
                        return true;
                    case 3:
                        System.out.println("  receivedJson type=" + data.readInt()
                                + " json=" + data.readString());
                        return true;
                    case 4:
                        System.out.println("  serviceDied");
                        return true;
                    default:
                        System.out.println("  unknown code=" + code);
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (Throwable t) {
                System.out.println("  decode error: " + t);
                return true;
            }
        }

        private static String describeBytes(byte[] b) {
            if (b == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder("len=" + b.length + " [");
            for (int i = 0; i < Math.min(b.length, 24); i++) {
                sb.append(String.format("%02x", b[i])).append(' ');
            }
            return sb.append(']').toString();
        }
    }
}
