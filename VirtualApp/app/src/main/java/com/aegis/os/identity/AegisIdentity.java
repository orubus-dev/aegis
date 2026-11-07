package com.aegis.os.identity;

import android.content.Context;

import com.aegis.os.Diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Aegis simulated identity: the fake device profile plus the generated
 * people, conversations and call history that live INSIDE the sandbox.
 *
 * Everything here is generated on first use, stored in the host's private
 * storage, and never leaves the device: the Messages app cannot send
 * anything, the Phone app cannot dial out, contacts never sync anywhere.
 */
public final class AegisIdentity {

    // ---------- model ----------

    public static final class Contact {
        public String id;
        public String name;
        public String phone;
        public String email;
        public String org;
    }

    public static final class Message {
        public long time;
        public boolean fromMe;
        public String text;
    }

    public static final class Thread {
        public String id;
        public String name;
        public String phone;
        public List<Message> messages = new ArrayList<>();
    }

    public static final class CallEntry {
        public long time;
        public boolean outgoing;
        public String name;
        public String phone;
    }

    // ---------- seed pools ----------

    private static final String[] FIRST_NAMES = {
            "Adaeze", "Chinedu", "Funmilayo", "Emeka", "Ngozi", "Tunde", "Aisha",
            "Ibrahim", "Zainab", "Kelechi", "Yemi", "Oluwaseun", "Halima", "Obinna",
            "Amara", "Bode", "Chiamaka", "Femi", "Ify", "Musa", "Nneka", "Segun",
            "Tolu", "Uche", "Bola", "Damilola", "Efe", "Gbenga", "Hauwa", "Ifeanyi",
            "James", "David", "Sarah", "Michael", "Grace", "Daniel", "Esther", "Peter"
    };
    private static final String[] LAST_NAMES = {
            "Okafor", "Adeyemi", "Balogun", "Nwosu", "Ogunlesi", "Chukwu", "Ibrahim",
            "Adeola", "Eze", "Lawal", "Okonkwo", "Abiola", "Danjuma", "Onyeka",
            "Afolabi", "Umeh", "Salami", "Igwe", "Bello", "Obi", "Ade", "Kalu"
    };
    private static final String[] ORGS = {
            "Konga Traders", "Lagos Dispatch", "Sahara Foods", "GreenTech Ltd",
            "Ikeja Designs", "Riverline Logistics", "Nimbus Studio", "Baobab Health"
    };
    private static final String[] PHONE_PREFIXES = {
            "0803", "0806", "0810", "0813", "0814", "0816", "0703", "0706", "0902", "0906"
    };
    private static final String[] DEVICE_MODELS = {
            "Pixel 7", "Galaxy S23", "Redmi Note 12", "Nord 3", "Camon 20",
            "Nokia G21", "Pixel 6a", "Galaxy A54"
    };
    private static final String[] EMAIL_DOMAINS = {
            "gmail.com", "yahoo.com", "outlook.com", "proton.me"
    };

    private static final String[][] SEED_CONVERSATIONS = {
            {"Adaeze Okafor", "Are you coming for lunch today?",
                    "Yes! Give me 20 minutes", "Okay, we are at the usual place 😄"},
            {"Chinedu Eze", "I sent the file to your email",
                    "Got it, thanks", "👍"},
            {"Musa Ibrahim", "The driver is at the gate",
                    "Tell him I'm coming down", "He says he will wait for 5 minutes"},
            {"Adeola Balogun", "Match starts at 8pm tonight",
                    "Perfect, I'll be home by then", "Bring small chops o 😂"},
            {"DataDeals", "You qualify for 2GB bonus data. Reply STOP to opt out.",
                    "STOP", "You have been unsubscribed."},
            {"Ngozi Adeyemi", "Happy birthday!! 🎉",
                    "Thank you!! 😊", "We are celebrating this weekend, no excuses"},
            {"Lagos Dispatch", "Your package LV-88213 is out for delivery today.",
                    "Okay, thank you"},
    };

    // ---------- storage ----------

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "aegis_identity");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private static File file(Context ctx, String name) {
        return new File(dir(ctx), name);
    }

    private static String read(Context ctx, String name) {
        File f = file(ctx, name);
        if (!f.exists()) {
            return null;
        }
        FileInputStream in = null;
        try {
            byte[] buf = new byte[(int) f.length()];
            in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void write(Context ctx, String name, String json) {
        FileOutputStream out = null;
        try {
            File tmp = new File(dir(ctx), name + ".tmp");
            out = new FileOutputStream(tmp);
            out.write(json.getBytes("UTF-8"));
            out.close();
            File f = file(ctx, name);
            if (f.exists() && !f.delete()) {
                Diagnostics.log(ctx, "identity: unable to replace " + name);
            }
            tmp.renameTo(f);
        } catch (Throwable t) {
            Diagnostics.log(ctx, "identity: write failed " + name + ": " + t);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- profile ----------

    public static JSONObject profile(Context ctx) {
        String raw = read(ctx, "profile.json");
        if (raw != null) {
            try {
                return new JSONObject(raw);
            } catch (Throwable ignored) {
            }
        }
        JSONObject p = generateProfile(System.currentTimeMillis());
        write(ctx, "profile.json", p.toString());
        Diagnostics.log(ctx, "identity: generated device profile");
        return p;
    }

    public static void regenerateProfile(Context ctx) {
        write(ctx, "profile.json", generateProfile(System.currentTimeMillis()).toString());
    }

    private static JSONObject generateProfile(long seed) {
        Random r = new Random(seed);
        JSONObject p = new JSONObject();
        try {
            String first = FIRST_NAMES[r.nextInt(FIRST_NAMES.length)];
            String last = LAST_NAMES[r.nextInt(LAST_NAMES.length)];
            p.put("name", first + " " + last);
            p.put("phone", randomPhone(r));
            p.put("email", (first + "." + last).toLowerCase(Locale.US)
                    + "@" + EMAIL_DOMAINS[r.nextInt(EMAIL_DOMAINS.length)]);
            p.put("model", DEVICE_MODELS[r.nextInt(DEVICE_MODELS.length)]);
            p.put("androidVersion", String.valueOf(11 + r.nextInt(5)));
            p.put("androidId", randomHex(r, 16));
            p.put("imei", luhnSeed(r));
            p.put("generatedAt", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return p;
    }

    private static String randomPhone(Random r) {
        StringBuilder sb = new StringBuilder(PHONE_PREFIXES[r.nextInt(PHONE_PREFIXES.length)]);
        for (int i = 0; i < 7; i++) {
            sb.append(r.nextInt(10));
        }
        return sb.toString();
    }

    private static String randomHex(Random r, int len) {
        StringBuilder sb = new StringBuilder();
        String chars = "0123456789abcdef";
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    private static String luhnSeed(Random r) {
        int[] digits = new int[15];
        digits[0] = 3 + r.nextInt(2);
        for (int i = 1; i < 14; i++) {
            digits[i] = r.nextInt(10);
        }
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = digits[i];
            if ((12 - i) % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        int check = (10 - (sum % 10)) % 10;
        StringBuilder sb = new StringBuilder();
        for (int d : digits) {
            sb.append(d);
        }
        sb.append(check);
        return sb.toString();
    }

    // ---------- contacts ----------

    public static List<Contact> contacts(Context ctx) {
        String raw = read(ctx, "contacts.json");
        List<Contact> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Contact c = new Contact();
                    c.id = o.optString("id");
                    c.name = o.optString("name");
                    c.phone = o.optString("phone");
                    c.email = o.optString("email", "");
                    c.org = o.optString("org", "");
                    out.add(c);
                }
                return out;
            } catch (Throwable ignored) {
            }
        }
        out = generateContacts(24, System.currentTimeMillis());
        saveContacts(ctx, out);
        Diagnostics.log(ctx, "identity: generated " + out.size() + " contacts");
        return out;
    }

    public static void saveContacts(Context ctx, List<Contact> contacts) {
        JSONArray arr = new JSONArray();
        for (Contact c : contacts) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", c.id == null ? newId() : c.id);
                o.put("name", c.name == null ? "" : c.name);
                o.put("phone", c.phone == null ? "" : c.phone);
                o.put("email", c.email == null ? "" : c.email);
                o.put("org", c.org == null ? "" : c.org);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        write(ctx, "contacts.json", arr.toString());
    }

    private static List<Contact> generateContacts(int n, long seed) {
        Random r = new Random(seed);
        List<Contact> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Contact c = new Contact();
            c.id = newId();
            c.name = FIRST_NAMES[r.nextInt(FIRST_NAMES.length)] + " "
                    + LAST_NAMES[r.nextInt(LAST_NAMES.length)];
            c.phone = randomPhone(r);
            if (r.nextInt(3) == 0) {
                c.email = c.name.toLowerCase(Locale.US).replace(" ", ".")
                        + "@" + EMAIL_DOMAINS[r.nextInt(EMAIL_DOMAINS.length)];
            }
            if (r.nextInt(4) == 0) {
                c.org = ORGS[r.nextInt(ORGS.length)];
            }
            out.add(c);
        }
        return out;
    }

    // ---------- messages ----------

    public static List<Thread> threads(Context ctx) {
        String raw = read(ctx, "sms.json");
        if (raw != null) {
            try {
                return parseThreads(new JSONArray(raw));
            } catch (Throwable ignored) {
            }
        }
        List<Thread> out = generateThreads(System.currentTimeMillis());
        saveThreads(ctx, out);
        Diagnostics.log(ctx, "identity: generated " + out.size() + " message threads");
        return out;
    }

    public static void saveThreads(Context ctx, List<Thread> threads) {
        JSONArray arr = new JSONArray();
        for (Thread t : threads) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("name", t.name);
                o.put("phone", t.phone);
                JSONArray msgs = new JSONArray();
                for (Message m : t.messages) {
                    JSONObject mo = new JSONObject();
                    mo.put("time", m.time);
                    mo.put("fromMe", m.fromMe);
                    mo.put("text", m.text);
                    msgs.put(mo);
                }
                o.put("messages", msgs);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        write(ctx, "sms.json", arr.toString());
    }

    private static List<Thread> parseThreads(JSONArray arr) {
        List<Thread> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                Thread t = new Thread();
                t.id = o.optString("id");
                t.name = o.optString("name");
                t.phone = o.optString("phone");
                JSONArray msgs = o.optJSONArray("messages");
                if (msgs != null) {
                    for (int j = 0; j < msgs.length(); j++) {
                        JSONObject mo = msgs.getJSONObject(j);
                        Message m = new Message();
                        m.time = mo.optLong("time");
                        m.fromMe = mo.optBoolean("fromMe");
                        m.text = mo.optString("text");
                        t.messages.add(m);
                    }
                }
                out.add(t);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<Thread> generateThreads(long seed) {
        Random r = new Random(seed);
        List<Thread> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < SEED_CONVERSATIONS.length; i++) {
            String[] convo = SEED_CONVERSATIONS[i];
            Thread t = new Thread();
            t.id = newId();
            t.name = convo[0];
            t.phone = randomPhone(r);
            long t0 = now - (i + 1) * 3_600_000L * (2 + r.nextInt(20));
            for (int j = 1; j < convo.length; j++) {
                Message m = new Message();
                m.time = t0 + j * 60_000L * (1 + r.nextInt(9));
                m.fromMe = j % 2 == 0;
                m.text = convo[j];
                t.messages.add(m);
            }
            out.add(t);
        }
        return out;
    }

    // ---------- call log ----------

    public static List<CallEntry> calls(Context ctx) {
        String raw = read(ctx, "calls.json");
        List<CallEntry> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    CallEntry c = new CallEntry();
                    c.time = o.optLong("time");
                    c.outgoing = o.optBoolean("outgoing", true);
                    c.name = o.optString("name", "");
                    c.phone = o.optString("phone", "");
                    out.add(c);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static void logCall(Context ctx, String name, String phone, boolean outgoing) {
        List<CallEntry> calls = calls(ctx);
        CallEntry e = new CallEntry();
        e.time = System.currentTimeMillis();
        e.outgoing = outgoing;
        e.name = name == null ? "" : name;
        e.phone = phone == null ? "" : phone;
        calls.add(0, e);
        while (calls.size() > 100) {
            calls.remove(calls.size() - 1);
        }
        JSONArray arr = new JSONArray();
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
        for (CallEntry c : calls) {
            try {
                JSONObject o = new JSONObject();
                o.put("time", c.time);
                o.put("outgoing", c.outgoing);
                o.put("name", c.name);
                o.put("phone", c.phone);
                o.put("pretty", fmt.format(new Date(c.time)));
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        write(ctx, "calls.json", arr.toString());
    }

    // ---------- misc ----------

    private static String newId() {
        return Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString(new Random().nextInt());
    }
}
