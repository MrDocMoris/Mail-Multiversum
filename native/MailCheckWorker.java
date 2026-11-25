package de.mrdocmoris.mail;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.mail.Address;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;

/**
 * Schaut regelmäßig direkt bei Posteo nach neuen Mails im Posteingang
 * (ohne Google-Push, ohne fremden Server) und zeigt eine Benachrichtigung.
 */
public class MailCheckWorker extends Worker {
    static final String WORK = "mailcheck";
    static final String CHANNEL = "mail_neu";
    static final int NOTIFY_ID = 4711;

    public MailCheckWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    static void schedule(Context c, int minutes) {
        PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(MailCheckWorker.class, Math.max(15, minutes), TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, r);
    }

    static void cancel(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK);
    }

    /** Merkt sich die höchste bekannte UID, damit alte Mails nicht gemeldet werden. */
    static void remember(Context c, long uidValidity, long maxUid) {
        SharedPreferences p = SecureStore.prefs(c);
        boolean same = p.getLong("uidv", -1) == uidValidity;
        long old = same ? p.getLong("lastUid", 0) : 0;
        if (!same || maxUid > old) {
            p.edit().putLong("uidv", uidValidity).putLong("lastUid", Math.max(maxUid, old)).apply();
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        if (!SecureStore.hasLogin(c)) return Result.success();
        IMAPStore store = null;
        IMAPFolder inbox = null;
        try {
            store = MailCore.connect(MailCore.session(), c);
            inbox = (IMAPFolder) store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            long uidv = inbox.getUIDValidity();
            SharedPreferences p = SecureStore.prefs(c);
            long last = p.getLong("uidv", -1) == uidv ? p.getLong("lastUid", 0) : 0;
            long next = inbox.getUIDNext();
            if (last == 0) { // erster Lauf: nur merken, nichts melden
                remember(c, uidv, Math.max(0, next - 1));
                return Result.success();
            }
            if (next > 0 && next - 1 <= last) return Result.success();
            Message[] msgs = inbox.getMessagesByUID(last + 1, UIDFolder.LASTUID);
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(UIDFolder.FetchProfileItem.UID);
            inbox.fetch(msgs, fp);
            List<Message> fresh = new ArrayList<>();
            long max = last;
            for (Message m : msgs) {
                if (m == null) continue;
                long uid = inbox.getUID(m);
                if (uid <= last) continue;
                max = Math.max(max, uid);
                if (!m.isSet(Flags.Flag.SEEN)) fresh.add(m);
            }
            remember(c, uidv, max);
            if (!fresh.isEmpty() && p.getBoolean("notify", true)) notify(c, fresh);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignore) {}
            try { if (store != null) store.close(); } catch (Exception ignore) {}
        }
    }

    static String sender(Message m) {
        try {
            Address[] a = m.getFrom();
            if (a != null && a.length > 0) {
                if (a[0] instanceof InternetAddress) {
                    InternetAddress ia = (InternetAddress) a[0];
                    return ia.getPersonal() != null && !ia.getPersonal().isEmpty() ? ia.getPersonal() : ia.getAddress();
                }
                return a[0].toString();
            }
        } catch (Exception ignore) {}
        return "Unbekannt";
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Neue Mails", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Benachrichtigung bei neuen Mails im Posteingang");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void notify(Context c, List<Message> fresh) {
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            if (fresh.size() == 1) {
                Message m = fresh.get(0);
                String subj = m.getSubject() == null || m.getSubject().isEmpty() ? "(kein Betreff)" : m.getSubject();
                b.setContentTitle(sender(m)).setContentText(subj)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(subj));
            } else {
                NotificationCompat.InboxStyle st = new NotificationCompat.InboxStyle();
                int n = 0;
                for (int i = fresh.size() - 1; i >= 0 && n < 6; i--, n++) {
                    Message m = fresh.get(i);
                    st.addLine(sender(m) + ": " + (m.getSubject() == null ? "" : m.getSubject()));
                }
                b.setContentTitle(fresh.size() + " neue Mails").setContentText(sender(fresh.get(fresh.size() - 1)) + " und weitere")
                        .setNumber(fresh.size()).setStyle(st);
            }
            NotificationManagerCompat.from(c).notify(NOTIFY_ID, b.build());
        } catch (SecurityException ignore) {
            // Benachrichtigungen nicht erlaubt
        } catch (Exception ignore) {}
    }
}
