package com.aegis.os.permission;

import java.util.HashMap;
import java.util.Map;

/**
 * Human-readable labels and families for the Android permissions Aegis
 * simulates.  Covers the standard dangerous permissions plus the common
 * normal/signature ones guests actually declare; anything unknown falls
 * back to the raw name with an "Other" family.
 */
public final class AegisPermissionCatalog {

    public static final class Entry {
        public final String label;
        public final String group;

        Entry(String label, String group) {
            this.label = label;
            this.group = group;
        }
    }

    private static final Map<String, Entry> CATALOG = new HashMap<>();

    private static void put(String perm, String label, String group) {
        CATALOG.put(perm, new Entry(label, group));
    }

    static {
        // Location
        put("android.permission.ACCESS_FINE_LOCATION", "Precise location", "Location");
        put("android.permission.ACCESS_COARSE_LOCATION", "Approximate location", "Location");
        put("android.permission.ACCESS_BACKGROUND_LOCATION", "Location in background", "Location");

        // Camera & microphone
        put("android.permission.CAMERA", "Take pictures and record video", "Camera");
        put("android.permission.RECORD_AUDIO", "Record audio", "Microphone");

        // Contacts & accounts
        put("android.permission.READ_CONTACTS", "Read contacts", "Contacts");
        put("android.permission.WRITE_CONTACTS", "Modify contacts", "Contacts");
        put("android.permission.GET_ACCOUNTS", "Get accounts on device", "Contacts");

        // SMS
        put("android.permission.SEND_SMS", "Send SMS", "SMS");
        put("android.permission.RECEIVE_SMS", "Receive SMS", "SMS");
        put("android.permission.READ_SMS", "Read SMS", "SMS");
        put("android.permission.RECEIVE_MMS", "Receive MMS", "SMS");
        put("android.permission.RECEIVE_WAP_PUSH", "Receive WAP push", "SMS");

        // Phone
        put("android.permission.CALL_PHONE", "Directly call phone numbers", "Phone");
        put("android.permission.READ_PHONE_STATE", "Read phone state", "Phone");
        put("android.permission.READ_PHONE_NUMBERS", "Read phone numbers", "Phone");
        put("android.permission.READ_CALL_LOG", "Read call log", "Phone");
        put("android.permission.WRITE_CALL_LOG", "Modify call log", "Phone");
        put("android.permission.ADD_VOICEMAIL", "Add voicemail", "Phone");
        put("android.permission.USE_SIP", "Make SIP calls", "Phone");
        put("android.permission.PROCESS_OUTGOING_CALLS", "Process outgoing calls", "Phone");
        put("android.permission.ANSWER_PHONE_CALLS", "Answer phone calls", "Phone");

        // Storage & media
        put("android.permission.READ_EXTERNAL_STORAGE", "Read shared storage", "Storage");
        put("android.permission.WRITE_EXTERNAL_STORAGE", "Modify shared storage", "Storage");
        put("android.permission.MANAGE_EXTERNAL_STORAGE", "All files access", "Storage");
        put("android.permission.ACCESS_MEDIA_LOCATION", "Read media location tags", "Storage");

        // Calendar
        put("android.permission.READ_CALENDAR", "Read calendar", "Calendar");
        put("android.permission.WRITE_CALENDAR", "Modify calendar", "Calendar");

        // Sensors & body
        put("android.permission.BODY_SENSORS", "Body sensors", "Sensors");
        put("android.permission.ACTIVITY_RECOGNITION", "Activity recognition", "Sensors");
        put("android.permission.HIGH_SAMPLING_RATE_SENSORS", "High-rate sensor access", "Sensors");

        // Connectivity
        put("android.permission.INTERNET", "Full network access", "Network");
        put("android.permission.ACCESS_NETWORK_STATE", "View network state", "Network");
        put("android.permission.ACCESS_WIFI_STATE", "View Wi-Fi state", "Network");
        put("android.permission.CHANGE_WIFI_STATE", "Toggle Wi-Fi", "Network");
        put("android.permission.BLUETOOTH", "Bluetooth basics", "Network");
        put("android.permission.BLUETOOTH_ADMIN", "Bluetooth admin", "Network");
        put("android.permission.BLUETOOTH_CONNECT", "Bluetooth devices", "Network");
        put("android.permission.BLUETOOTH_SCAN", "Discover Bluetooth devices", "Network");

        // System-ish
        put("android.permission.VIBRATE", "Vibrate", "Device");
        put("android.permission.WAKE_LOCK", "Keep device awake", "Device");
        put("android.permission.RECEIVE_BOOT_COMPLETED", "Run at startup", "Device");
        put("android.permission.FOREGROUND_SERVICE", "Run foreground services", "Device");
        put("android.permission.SYSTEM_ALERT_WINDOW", "Draw over other apps", "Device");
        put("android.permission.REQUEST_INSTALL_PACKAGES", "Install packages", "Device");
        put("android.permission.POST_NOTIFICATIONS", "Post notifications", "Device");
        put("android.permission.NFC", "NFC", "Device");
        put("android.permission.USE_BIOMETRIC", "Use biometrics", "Device");
        put("android.permission.USE_FINGERPRINT", "Use fingerprint", "Device");
        put("android.permission.MODIFY_AUDIO_SETTINGS", "Change audio settings", "Device");
        put("android.permission.INSTALL_SHORTCUT", "Install shortcuts", "Device");
    }

    private AegisPermissionCatalog() {
    }

    public static Entry describe(String permission) {
        Entry e = CATALOG.get(permission);
        if (e != null) {
            return e;
        }
        String shortName = permission == null ? "?" : permission;
        int dot = shortName.lastIndexOf('.');
        if (dot >= 0 && dot < shortName.length() - 1) {
            shortName = shortName.substring(dot + 1);
        }
        return new Entry(shortName, "Other");
    }

    public static boolean isDangerousGroup(String group) {
        switch (group) {
            case "Location":
            case "Camera":
            case "Microphone":
            case "Contacts":
            case "SMS":
            case "Phone":
            case "Storage":
            case "Calendar":
            case "Sensors":
                return true;
            default:
                return false;
        }
    }
}
