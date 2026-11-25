package de.mrdocmoris.mail;

import android.content.Context;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import java.util.Locale;
import java.util.Properties;

import javax.mail.AuthenticationFailedException;
import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Session;

/**
 * Verbindung zu Posteo – ausschließlich verschlüsselt (IMAP 993 / SMTP 465, SSL/TLS)
 * mit Prüfung des Server-Zertifikats und des Hostnamens. Es gibt keinen
 * Server dazwischen: Die App spricht direkt mit posteo.de.
 */
final class MailCore {
    static final String HOST = "posteo.de";
    static final int IMAP_PORT = 993;
    static final int SMTP_PORT = 465;

    private MailCore() {}

    static Session session() {
        Properties p = new Properties();
        // IMAP
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", HOST);
        p.put("mail.imaps.port", String.valueOf(IMAP_PORT));
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", "20000");
        p.put("mail.imaps.timeout", "60000");
        p.put("mail.imaps.writetimeout", "60000");
        p.put("mail.imaps.fetchsize", "1048576");
        p.put("mail.imaps.partialfetch", "true");
        // SMTP
        p.put("mail.smtps.host", HOST);
        p.put("mail.smtps.port", String.valueOf(SMTP_PORT));
        p.put("mail.smtps.auth", "true");
        p.put("mail.smtps.ssl.enable", "true");
        p.put("mail.smtps.ssl.checkserveridentity", "true");
        p.put("mail.smtps.connectiontimeout", "20000");
        p.put("mail.smtps.timeout", "120000");
        p.put("mail.smtps.writetimeout", "120000");
        // Umlaute in Dateinamen und Betreffs
        p.put("mail.mime.encodefilename", "true");
        p.put("mail.mime.decodefilename", "true");
        p.put("mail.mime.decodetext.strict", "false");
        p.put("mail.mime.address.strict", "false");
        return Session.getInstance(p);
    }

    static IMAPStore connect(Session s, String email, String password) throws MessagingException {
        IMAPStore store = (IMAPStore) s.getStore("imaps");
        store.connect(HOST, IMAP_PORT, email, password);
        return store;
    }

    static IMAPStore connect(Session s, Context c) throws MessagingException {
        String email = SecureStore.email(c);
        String pw = SecureStore.password(c);
        if (email == null || pw == null) throw new AuthenticationFailedException("Nicht angemeldet");
        return connect(s, email, pw);
    }

    /** Rolle eines Ordners: inbox, sent, drafts, trash, junk, archive oder "" */
    static String role(Folder f) {
        String full = f.getFullName();
        if ("INBOX".equalsIgnoreCase(full)) return "inbox";
        if (f instanceof IMAPFolder) {
            try {
                for (String a : ((IMAPFolder) f).getAttributes()) {
                    String x = a.toLowerCase(Locale.ROOT);
                    if (x.equals("\\sent")) return "sent";
                    if (x.equals("\\drafts")) return "drafts";
                    if (x.equals("\\trash")) return "trash";
                    if (x.equals("\\junk")) return "junk";
                    if (x.equals("\\archive")) return "archive";
                }
            } catch (Exception ignore) {}
        }
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.equals("sent") || n.equals("gesendet") || n.equals("sent items") || n.equals("sent messages") || n.equals("gesendete objekte")) return "sent";
        if (n.equals("drafts") || n.equals("entwürfe") || n.equals("entwurf")) return "drafts";
        if (n.equals("trash") || n.equals("papierkorb") || n.equals("deleted items") || n.equals("deleted messages") || n.equals("gelöscht")) return "trash";
        if (n.equals("spam") || n.equals("junk") || n.equals("junk e-mail")) return "junk";
        if (n.equals("archive") || n.equals("archiv")) return "archive";
        return "";
    }

    /** Ersten Ordner mit der gesuchten Rolle finden (oder null). */
    static Folder findRole(IMAPStore store, String role) throws MessagingException {
        for (Folder f : store.getDefaultFolder().list("*")) {
            if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
            if (role.equals(role(f))) return f;
        }
        return null;
    }
}
