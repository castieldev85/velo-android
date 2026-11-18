package p32929.easypasscodelock.Utils;

import android.content.Context;

public class EasylockSP {

    private static LockscreenStorage storage;

    public static void init(Context context) {
        if (storage == null) {
            storage = new LockscreenStorage(context);
        }
    }

    public static void put(String title, String value) {
        if ("password".equals(title)) {
            storage.writePin(value);
        } else if (PANIC_PASSWORD.equals(title)) {
            storage.writePanicPin(value);
        }
    }

    public static String getString(String title, String defaultValue) {
        if ("password".equals(title)) {
            final String pin = storage.readPin();
            return pin != null ? pin : defaultValue;
        } else if (PANIC_PASSWORD.equals(title)) {
            final String pin = storage.readPanicPin();
            return pin != null ? pin : defaultValue;
        }
        return defaultValue;
    }

    public static void clearAll() {
        storage.writePin(null);
        storage.writePanicPin(null);
    }

    /** Velo: the panic PIN (VeloPanic). */
    public static final String PANIC_PASSWORD = "panic_password";
}
