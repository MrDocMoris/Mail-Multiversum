package de.mrdocmoris.mail;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.activation.DataHandler;
import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.StoreClosedException;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.search.FromStringTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.RecipientStringTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;
import javax.mail.util.ByteArrayDataSource;

/**
 * Brücke zwischen der Oberfläche (mail.html) und Posteo.
 * Das Passwort kommt nur beim Anmelden einmal herein und wird danach
 * ausschließlich verschlüsselt auf dem Gerät gehalten – die Oberfläche
 * bekommt es nie wieder zu sehen.
 */
@CapacitorPlugin(name = "Mail")
public class MailPlugin extends Plugin {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Session session = MailCore.session();
    private IMAPStore store;
    private IMAPFolder cur;

    private interface Job { void run() throws Exception; }

    @Override
    public void load() {
        Context c = getContext();
        if (SecureStore.hasLogin(c) && SecureStore.prefs(c).getBoolean("notify", true)) {
            try { MailCheckWorker.schedule(c, SecureStore.prefs(c).getInt("notifyMin", 15)); } catch (Exception ignore) {}
        }
    }

    /* ---------- Hilfen ---------- */

    private void run(PluginCall call, Job job) {
        exec.execute(() -> {
            try {
                try {
                    job.run();
                } catch (StoreClosedException | FolderClosedException e) {
                    reset();
                    job.run(); // Verbindung war weg – einmal neu versuchen
                }
            } catch (AuthenticationFailedException e) {
                reset();
                String srv = e.getMessage() == null ? "" : e.getMessage().trim();
                call.reject("Posteo hat die Anmeldung abgelehnt. Für Mail-Apps brauchst du ein Anwendungs-Passwort (Posteo im Browser → Einstellungen → Passwort und Sicherheit)." + (srv.isEmpty() ? "" : " Antwort von Posteo: " + srv), "AUTH", e);
            } catch (Exception e) {
                if (e instanceof MessagingException && !(e instanceof javax.mail.SendFailedException)) {
                    Exception inner = ((MessagingException) e).getNextException();
                    if (inner instanceof java.io.IOException) reset();
                }
                call.reject(friendly(e), "ERR", e);
            }
        });
    }

    private static String friendly(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.net.UnknownHostException) return "Keine Verbindung zu Posteo – bist du online?";
            if (t instanceof java.net.SocketTimeoutException) return "Posteo antwortet nicht (Zeitüberschreitung).";
            if (t instanceof javax.net.ssl.SSLException) return "Sichere Verbindung fehlgeschlagen – Zertifikat ungültig oder Verbindung manipuliert. Aus Sicherheitsgründen abgebrochen.";
            if (t instanceof java.net.ConnectException) return "Keine Verbindung zu Posteo – bist du online?";
            t = t.getCause() != null && t.getCause() != t ? t.getCause() : (t instanceof MessagingException ? ((MessagingException) t).getNextException() : null);
        }
        if (e instanceof javax.mail.internet.AddressException) return "Ungültige E-Mail-Adresse: " + e.getMessage();
        if (e instanceof javax.mail.SendFailedException) return "Senden fehlgeschlagen: " + e.getMessage();
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Unbekannter Fehler (" + e.getClass().getSimpleName() + ")" : m;
    }

    private void reset() {
        try { if (cur != null && cur.isOpen()) cur.close(false); } catch (Exception ignore) {}
        try { if (store != null) store.close(); } catch (Exception ignore) {}
        cur = null;
        store = null;
    }

    private IMAPStore store() throws MessagingException {
        if (store == null || !store.isConnected()) {
            reset();
            store = MailCore.connect(session, getContext());
        }
        return store;
    }

    private IMAPFolder open(String name, boolean fresh) throws MessagingException {
        IMAPStore s = store();
        if (!fresh && cur != null && cur.isOpen() && cur.getFullName().equals(name)) return cur;
        try { if (cur != null && cur.isOpen()) cur.close(false); } catch (Exception ignore) {}
        cur = null;
        IMAPFolder f = (IMAPFolder) s.getFolder(name);
        f.open(Folder.READ_WRITE);
        cur = f;
        return f;
    }

    private static JSObject addr(Address a) {
        JSObject o = new JSObject();
        if (a instanceof InternetAddress) {
            InternetAddress ia = (InternetAddress) a;
            o.put("name", ia.getPersonal() == null ? "" : ia.getPersonal());
            o.put("addr", ia.getAddress() == null ? "" : ia.getAddress());
        } else if (a != null) {
            o.put("name", "");
            o.put("addr", a.toString());
        }
        return o;
    }

    private static JSArray addrs(Address[] list) {
        JSArray arr = new JSArray();
        if (list != null) for (Address a : list) arr.put(addr(a));
        return arr;
    }

    private static long[] uids(PluginCall call) throws Exception {
        JSArray a = call.getArray("uids");
        if (a == null) throw new IllegalArgumentException("Keine Mails ausgewählt");
        long[] r = new long[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.getLong(i);
        return r;
    }

    private static Message[] byUids(IMAPFolder f, long[] uids) throws MessagingException {
        Message[] all = f.getMessagesByUID(uids);
        List<Message> ok = new ArrayList<>();
        for (Message m : all) if (m != null) ok.add(m);
        return ok.toArray(new Message[0]);
    }

    private static JSObject summary(IMAPFolder f, Message m) throws MessagingException {
        JSObject o = new JSObject();
        o.put("uid", f.getUID(m));
        Address[] from = m.getFrom();
        o.put("from", from != null && from.length > 0 ? addr(from[0]) : new JSObject());
        o.put("to", addrs(m.getRecipients(Message.RecipientType.TO)));
        o.put("subject", m.getSubject() == null ? "" : m.getSubject());
        Date d = m.getSentDate() != null ? m.getSentDate() : m.getReceivedDate();
        o.put("date", d == null ? 0 : d.getTime());
        o.put("seen", m.isSet(Flags.Flag.SEEN));
        o.put("flagged", m.isSet(Flags.Flag.FLAGGED));
        o.put("answered", m.isSet(Flags.Flag.ANSWERED));
        boolean att = false;
        try { att = m.isMimeType("multipart/mixed"); } catch (Exception ignore) {}
        o.put("att", att);
        return o;
    }

    private static FetchProfile listProfile() {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.CONTENT_INFO);
        fp.add(UIDFolder.FetchProfileItem.UID);
        return fp;
    }

    /* ---------- Anmeldung ---------- */

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("loggedIn", SecureStore.hasLogin(getContext()));
        r.put("email", SecureStore.email(getContext()));
        r.put("notify", SecureStore.prefs(getContext()).getBoolean("notify", true));
        r.put("notifyMin", SecureStore.prefs(getContext()).getInt("notifyMin", 15));
        call.resolve(r);
    }

    @PluginMethod
    public void login(PluginCall call) {
        String email = call.getString("email", "").trim();
        String pw = call.getString("password", "");
        run(call, () -> {
            if (email.isEmpty() || pw.isEmpty()) throw new IllegalArgumentException("Bitte E-Mail-Adresse und Passwort eingeben.");
            reset();
            IMAPStore s = MailCore.connect(session, email, pw); // wirft bei falschem Passwort
            SecureStore.saveLogin(getContext(), email, pw);
            store = s;
            // Startpunkt für Benachrichtigungen: alte Mails nicht melden
            try {
                IMAPFolder inbox = (IMAPFolder) s.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                MailCheckWorker.remember(getContext(), inbox.getUIDValidity(), Math.max(0, inbox.getUIDNext() - 1));
                inbox.close(false);
            } catch (Exception ignore) {}
            SecureStore.prefs(getContext()).edit().putBoolean("notify", true).putInt("notifyMin", 15).apply();
            MailCheckWorker.schedule(getContext(), 15);
            JSObject r = new JSObject();
            r.put("email", email);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void logout(PluginCall call) {
        run(call, () -> {
            reset();
            MailCheckWorker.cancel(getContext());
            SecureStore.clear(getContext());
            deleteDir(new File(getContext().getCacheDir(), "anhaenge"));
            call.resolve();
        });
    }

    @PluginMethod
    public void setNotify(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("enabled", true));
        int min = call.getInt("minutes", 15);
        SecureStore.prefs(getContext()).edit().putBoolean("notify", on).putInt("notifyMin", Math.max(15, min)).apply();
        if (on && SecureStore.hasLogin(getContext())) MailCheckWorker.schedule(getContext(), min);
        else MailCheckWorker.cancel(getContext());
        call.resolve();
    }

    /* ---------- Ordner & Liste ---------- */

    @PluginMethod
    public void folders(PluginCall call) {
        run(call, () -> {
            IMAPStore s = store();
            JSArray arr = new JSArray();
            for (Folder f : s.getDefaultFolder().list("*")) {
                if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                JSObject o = new JSObject();
                o.put("id", f.getFullName());
                o.put("name", f.getName());
                o.put("role", MailCore.role(f));
                o.put("sep", String.valueOf(f.getSeparator()));
                try { o.put("unread", f.getUnreadMessageCount()); } catch (Exception e) { o.put("unread", 0); }
                try { o.put("total", f.getMessageCount()); } catch (Exception e) { o.put("total", 0); }
                arr.put(o);
            }
            JSObject r = new JSObject();
            r.put("folders", arr);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void list(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        int offset = Math.max(0, call.getInt("offset", 0));
        int count = Math.min(200, Math.max(1, call.getInt("count", 40)));
        run(call, () -> {
            IMAPFolder f = open(name, offset == 0);
            int total = f.getMessageCount();
            int end = total - offset;
            JSArray arr = new JSArray();
            if (end >= 1) {
                int start = Math.max(1, end - count + 1);
                Message[] msgs = f.getMessages(start, end);
                f.fetch(msgs, listProfile());
                long max = 0;
                for (int i = msgs.length - 1; i >= 0; i--) {
                    arr.put(summary(f, msgs[i]));
                    max = Math.max(max, f.getUID(msgs[i]));
                }
                if ("INBOX".equalsIgnoreCase(name) && offset == 0) {
                    MailCheckWorker.remember(getContext(), f.getUIDValidity(), max);
                    try { NotificationManagerCompat.from(getContext()).cancel(MailCheckWorker.NOTIFY_ID); } catch (Exception ignore) {}
                }
            }
            JSObject r = new JSObject();
            r.put("total", total);
            r.put("unread", f.getUnreadMessageCount());
            r.put("messages", arr);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void search(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        String q = call.getString("query", "").trim();
        run(call, () -> {
            IMAPFolder f = open(name, false);
            SearchTerm t = new OrTerm(new SearchTerm[]{
                    new SubjectTerm(q),
                    new FromStringTerm(q),
                    new RecipientStringTerm(Message.RecipientType.TO, q)
            });
            Message[] found = f.search(t);
            int from = Math.max(0, found.length - 100);
            Message[] last = new Message[found.length - from];
            System.arraycopy(found, from, last, 0, last.length);
            f.fetch(last, listProfile());
            JSArray arr = new JSArray();
            for (int i = last.length - 1; i >= 0; i--) arr.put(summary(f, last[i]));
            JSObject r = new JSObject();
            r.put("messages", arr);
            r.put("more", found.length > last.length);
            call.resolve(r);
        });
    }

    /* ---------- Mail lesen ---------- */

    private static final class Parsed {
        String html, text;
        JSObject cids = new JSObject();
        JSArray atts = new JSArray();
        int cidBytes = 0;
    }

    private static String readText(Part p) throws Exception {
        try {
            Object c = p.getContent();
            if (c instanceof String) return (String) c;
            if (c instanceof InputStream) return new String(readAll((InputStream) c), StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            // unbekannter Zeichensatz – als UTF-8 lesen
        }
        return new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static String fileName(Part p) {
        try {
            String n = p.getFileName();
            if (n != null) return MimeUtility.decodeText(n);
        } catch (Exception ignore) {}
        return null;
    }

    private static String cid(Part p) {
        try {
            String[] h = p.getHeader("Content-ID");
            if (h != null && h.length > 0) return h[0].trim().replaceAll("^<|>$", "");
        } catch (Exception ignore) {}
        return null;
    }

    private static String baseType(Part p) {
        try {
            String t = p.getContentType();
            if (t == null) return "application/octet-stream";
            int i = t.indexOf(';');
            return (i > 0 ? t.substring(0, i) : t).trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private void walk(Part p, String path, Parsed out) throws Exception {
        if (p.isMimeType("multipart/*")) {
            Object c = p.getContent();
            if (c instanceof Multipart) {
                Multipart mp = (Multipart) c;
                for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), path.isEmpty() ? String.valueOf(i) : path + "." + i, out);
                return;
            }
        }
        String disp = null;
        try { disp = p.getDisposition(); } catch (Exception ignore) {}
        boolean isAttDisp = Part.ATTACHMENT.equalsIgnoreCase(disp);
        String fname = fileName(p);
        String cid = cid(p);

        if (!isAttDisp && fname == null && p.isMimeType("text/html")) {
            if (out.html == null) out.html = readText(p);
            else out.html += "<hr>" + readText(p);
            return;
        }
        if (!isAttDisp && fname == null && p.isMimeType("text/plain")) {
            if (out.text == null) out.text = readText(p);
            else out.text += "\n\n" + readText(p);
            return;
        }
        if (cid != null && !isAttDisp && p.isMimeType("image/*") && out.cidBytes < 8 * 1024 * 1024) {
            byte[] b = readAll(p.getInputStream());
            out.cidBytes += b.length;
            out.cids.put(cid, "data:" + baseType(p) + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP));
            return;
        }
        JSObject a = new JSObject();
        String type = baseType(p);
        if (fname == null) fname = p.isMimeType("message/rfc822") ? "Weitergeleitete Nachricht.eml" : "Anhang " + (out.atts.length() + 1);
        a.put("part", path.isEmpty() ? "0" : path);
        a.put("name", fname);
        a.put("mime", type);
        a.put("size", Math.max(0, p.getSize()));
        out.atts.put(a);
    }

    private static Part partAt(Message m, String path) throws Exception {
        if (!m.isMimeType("multipart/*")) return m; // Einzelteil: Pfad "0"
        Part p = m;
        for (String s : path.split("\\.")) {
            Object c = p.getContent();
            if (!(c instanceof Multipart)) throw new IllegalStateException("Anhang nicht gefunden");
            p = ((Multipart) c).getBodyPart(Integer.parseInt(s));
        }
        return p;
    }

    @PluginMethod
    public void read(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        long uid = call.getData().optLong("uid", 0);
        run(call, () -> {
            IMAPFolder f = open(name, false);
            Message m = f.getMessageByUID(uid);
            if (m == null) throw new IllegalStateException("Mail nicht mehr vorhanden (vielleicht woanders gelöscht).");
            Parsed p = new Parsed();
            walk(m, "", p);
            if (!m.isSet(Flags.Flag.SEEN)) m.setFlag(Flags.Flag.SEEN, true);
            JSObject r = summary(f, m);
            r.put("cc", addrs(m.getRecipients(Message.RecipientType.CC)));
            r.put("replyTo", addrs(m.getReplyTo()));
            r.put("html", p.html);
            r.put("text", p.text);
            r.put("cids", p.cids);
            r.put("attachments", p.atts);
            String[] mid = m.getHeader("Message-ID");
            r.put("messageId", mid != null && mid.length > 0 ? mid[0] : "");
            String[] refs = m.getHeader("References");
            r.put("references", refs != null && refs.length > 0 ? refs[0] : "");
            r.put("seen", true);
            call.resolve(r);
        });
    }

    /* ---------- Anhänge ---------- */

    private static String safeName(String n) {
        String s = n == null ? "Anhang" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "Anhang";
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    private static void deleteDir(File d) {
        File[] l = d.listFiles();
        if (l != null) for (File f : l) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }

    @PluginMethod
    public void attachment(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        long uid = call.getData().optLong("uid", 0);
        String part = call.getString("part", "0");
        String action = call.getString("action", "open");
        run(call, () -> {
            IMAPFolder f = open(name, false);
            Message m = f.getMessageByUID(uid);
            if (m == null) throw new IllegalStateException("Mail nicht mehr vorhanden.");
            Part p = partAt(m, part);
            String fname = safeName(fileName(p) != null ? fileName(p) : call.getString("name", "Anhang"));
            String mime = baseType(p);
            Context c = getContext();
            if ("save".equals(action) && Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Mail-Multiversum");
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IllegalStateException("Speichern nicht möglich");
                try (InputStream in = p.getInputStream(); OutputStream out = cr.openOutputStream(u)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                JSObject r = new JSObject();
                r.put("saved", "Download/Mail-Multiversum/" + fname);
                call.resolve(r);
                return;
            }
            // In den App-eigenen Zwischenspeicher legen und mit einer anderen App öffnen/teilen
            File dir = new File(c.getCacheDir(), "anhaenge/" + System.currentTimeMillis());
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("Zwischenspeicher nicht verfügbar");
            File file = new File(dir, fname);
            try (InputStream in = p.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", file);
            Intent i;
            if ("open".equals(action)) {
                i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
            } else {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "open".equals(action) ? "Öffnen mit" : "Teilen mit");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(chooser);
            call.resolve();
        });
    }

    /* ---------- Markieren, Verschieben, Löschen ---------- */

    @PluginMethod
    public void setFlags(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        Boolean seen = call.getBoolean("seen");
        Boolean flagged = call.getBoolean("flagged");
        run(call, () -> {
            IMAPFolder f = open(name, false);
            Message[] msgs = byUids(f, uids(call));
            if (msgs.length > 0) {
                if (seen != null) f.setFlags(msgs, new Flags(Flags.Flag.SEEN), seen);
                if (flagged != null) f.setFlags(msgs, new Flags(Flags.Flag.FLAGGED), flagged);
            }
            call.resolve();
        });
    }

    private void moveTo(IMAPFolder f, Message[] msgs, Folder target) throws MessagingException {
        if (msgs.length == 0) return;
        try {
            f.moveMessages(msgs, target);
        } catch (MessagingException e) {
            // Server ohne MOVE: kopieren und löschen
            f.copyMessages(msgs, target);
            f.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
            f.expunge(msgs);
        }
    }

    @PluginMethod
    public void move(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        String target = call.getString("target", "");
        run(call, () -> {
            IMAPFolder f = open(name, false);
            Folder t = store().getFolder(target);
            if (!t.exists()) throw new IllegalStateException("Zielordner gibt es nicht.");
            moveTo(f, byUids(f, uids(call)), t);
            call.resolve();
        });
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String name = call.getString("folder", "INBOX");
        boolean forever = Boolean.TRUE.equals(call.getBoolean("forever", false));
        run(call, () -> {
            IMAPFolder f = open(name, false);
            Message[] msgs = byUids(f, uids(call));
            Folder trash = MailCore.findRole(store(), "trash");
            boolean inTrash = trash != null && trash.getFullName().equals(f.getFullName());
            if (forever || inTrash || trash == null) {
                if (msgs.length > 0) {
                    f.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
                    f.expunge(msgs);
                }
            } else {
                moveTo(f, msgs, trash);
            }
            JSObject r = new JSObject();
            r.put("forever", forever || inTrash || trash == null);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void emptyFolder(PluginCall call) {
        String name = call.getString("folder", "");
        run(call, () -> {
            IMAPFolder f = open(name, true);
            String role = MailCore.role(f);
            if (!"trash".equals(role) && !"junk".equals(role)) throw new IllegalStateException("Nur Papierkorb und Spam können geleert werden.");
            Message[] all = f.getMessages();
            if (all.length > 0) {
                f.setFlags(all, new Flags(Flags.Flag.DELETED), true);
                f.expunge();
            }
            call.resolve();
        });
    }

    /* ---------- Senden ---------- */

    private static InternetAddress[] parseList(String s) throws Exception {
        if (s == null || s.trim().isEmpty()) return new InternetAddress[0];
        InternetAddress[] a = InternetAddress.parse(s.replace(';', ','), false);
        for (InternetAddress x : a) x.validate();
        return a;
    }

    @PluginMethod
    public void send(PluginCall call) {
        run(call, () -> {
            Context c = getContext();
            String email = SecureStore.email(c), pw = SecureStore.password(c);
            if (email == null || pw == null) throw new AuthenticationFailedException("Nicht angemeldet");
            InternetAddress[] to = parseList(call.getString("to", ""));
            InternetAddress[] cc = parseList(call.getString("cc", ""));
            InternetAddress[] bcc = parseList(call.getString("bcc", ""));
            if (to.length + cc.length + bcc.length == 0) throw new IllegalArgumentException("Bitte mindestens einen Empfänger eingeben.");

            MimeMessage msg = new MimeMessage(session);
            String fromName = call.getString("fromName", "");
            msg.setFrom(fromName == null || fromName.trim().isEmpty() ? new InternetAddress(email) : new InternetAddress(email, fromName.trim(), "UTF-8"));
            if (to.length > 0) msg.setRecipients(Message.RecipientType.TO, to);
            if (cc.length > 0) msg.setRecipients(Message.RecipientType.CC, cc);
            if (bcc.length > 0) msg.setRecipients(Message.RecipientType.BCC, bcc);
            msg.setSubject(call.getString("subject", ""), "UTF-8");
            msg.setSentDate(new Date());
            String inReplyTo = call.getString("inReplyTo", "");
            if (inReplyTo != null && !inReplyTo.isEmpty()) {
                msg.setHeader("In-Reply-To", inReplyTo);
                String refs = call.getString("references", "");
                msg.setHeader("References", ((refs == null ? "" : refs) + " " + inReplyTo).trim());
            }
            String text = call.getString("text", "");

            JSArray atts = call.getArray("attachments");
            JSObject fwd = call.getObject("forward");
            boolean hasFwd = fwd != null && fwd.has("parts") && fwd.getJSONArray("parts").length() > 0;
            if ((atts == null || atts.length() == 0) && !hasFwd) {
                msg.setText(text, "UTF-8");
            } else {
                MimeMultipart mp = new MimeMultipart("mixed");
                MimeBodyPart body = new MimeBodyPart();
                body.setText(text, "UTF-8");
                mp.addBodyPart(body);
                if (atts != null) {
                    for (int i = 0; i < atts.length(); i++) {
                        JSONObject a = atts.getJSONObject(i);
                        byte[] data = Base64.decode(a.getString("data"), Base64.DEFAULT);
                        MimeBodyPart bp = new MimeBodyPart();
                        bp.setDataHandler(new DataHandler(new ByteArrayDataSource(data, a.optString("mime", "application/octet-stream"))));
                        bp.setFileName(MimeUtility.encodeText(a.optString("name", "Anhang"), "UTF-8", null));
                        bp.setDisposition(Part.ATTACHMENT);
                        mp.addBodyPart(bp);
                    }
                }
                if (hasFwd) {
                    IMAPFolder of = open(fwd.getString("folder"), false);
                    Message orig = of.getMessageByUID(fwd.getLong("uid"));
                    if (orig == null) throw new IllegalStateException("Originalmail für die Anhänge nicht gefunden.");
                    JSONArray parts = fwd.getJSONArray("parts");
                    for (int i = 0; i < parts.length(); i++) {
                        Part op = partAt(orig, parts.getString(i));
                        byte[] data = readAll(op.getInputStream());
                        MimeBodyPart bp = new MimeBodyPart();
                        bp.setDataHandler(new DataHandler(new ByteArrayDataSource(data, baseType(op))));
                        String fn = fileName(op);
                        bp.setFileName(MimeUtility.encodeText(fn == null ? "Anhang" : fn, "UTF-8", null));
                        bp.setDisposition(Part.ATTACHMENT);
                        mp.addBodyPart(bp);
                    }
                }
                msg.setContent(mp);
            }
            msg.saveChanges();

            Transport t = session.getTransport("smtps");
            try {
                t.connect(MailCore.HOST, MailCore.SMTP_PORT, email, pw);
                t.sendMessage(msg, msg.getAllRecipients());
            } finally {
                try { t.close(); } catch (Exception ignore) {}
            }

            // Kopie in "Gesendet" ablegen
            boolean savedCopy = false;
            try {
                Folder sent = MailCore.findRole(store(), "sent");
                if (sent != null) {
                    msg.setFlag(Flags.Flag.SEEN, true);
                    sent.appendMessages(new Message[]{msg});
                    savedCopy = true;
                }
            } catch (Exception ignore) {}

            // Original als "beantwortet" markieren
            JSObject rep = call.getObject("replyOf");
            if (rep != null && rep.has("uid")) {
                try {
                    IMAPFolder rf = open(rep.getString("folder"), false);
                    Message om = rf.getMessageByUID(rep.getLong("uid"));
                    if (om != null) om.setFlag(Flags.Flag.ANSWERED, true);
                } catch (Exception ignore) {}
            }
            JSObject r = new JSObject();
            r.put("savedCopy", savedCopy);
            call.resolve(r);
        });
    }
}
