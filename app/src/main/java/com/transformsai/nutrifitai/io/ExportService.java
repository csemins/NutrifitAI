package com.transformsai.nutrifitai.io;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.FileProvider;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.reminders.Notifications;
import com.transformsai.nutrifitai.util.DateUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-started export of the full nutrition history to CSV and/or versioned XML. Runs as a
 * dataSync foreground service with a progress notification and a Cancel action, so a long export
 * continues if the user leaves the screen. Results are shared through FileProvider.
 */
public class ExportService extends Service {
    public static final String ACTION_START = "com.transformsai.nutrifitai.export.START";
    public static final String ACTION_CANCEL = "com.transformsai.nutrifitai.export.CANCEL";
    public static final String EXTRA_CSV = "csv";
    public static final String EXTRA_XML = "xml";
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Thread worker;

    public static void start(Context c, boolean csv, boolean xml) {
        Intent i = new Intent(c, ExportService.class).setAction(ACTION_START).putExtra(EXTRA_CSV, csv).putExtra(EXTRA_XML, xml);
        androidx.core.content.ContextCompat.startForegroundService(c, i);
    }

    public static void cancel(Context c) {
        c.startService(new Intent(c, ExportService.class).setAction(ACTION_CANCEL));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.set(true);
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        ServiceCompat.startForeground(this, Notifications.ID_EXPORT, progress(0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (worker != null) return START_NOT_STICKY;
        boolean csv = intent == null || intent.getBooleanExtra(EXTRA_CSV, true);
        boolean xml = intent == null || intent.getBooleanExtra(EXTRA_XML, true);
        cancelled.set(false);
        worker = new Thread(() -> run(csv, xml), "nutrifit-export");
        worker.start();
        return START_NOT_STICKY;
    }

    private void run(boolean csv, boolean xml) {
        ArrayList<String> out = new ArrayList<>();
        try {
            ExportStatus.post(ExportStatus.State.RUNNING, 5, null, null);
            String uid = App.graph().session.requireUid();
            ExportData d = App.graph().importExport.snapshot(uid);
            update(35);
            if (cancelled.get()) throw new InterruptedException();
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("no export dir");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            String stamp = LocalDateTime.now(DateUtil.clock()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
            if (csv) {
                File f = new File(dir, "nutrifit-" + stamp + ".csv");
                write(f, CsvWriter.write(d));
                out.add(f.getAbsolutePath());
            }
            update(65);
            if (cancelled.get()) throw new InterruptedException();
            if (xml) {
                File f = new File(dir, "nutrifit-" + stamp + ".xml");
                write(f, XmlCodec.write(d));
                out.add(f.getAbsolutePath());
            }
            update(100);
            if (cancelled.get()) throw new InterruptedException();
            ExportStatus.post(ExportStatus.State.DONE, 100, getString(R.string.export_done_count, d.recordCount()), out);
            notifyDone(out);
        } catch (InterruptedException e) {
            ExportStatus.post(ExportStatus.State.CANCELLED, 0, getString(R.string.export_cancelled), null);
        } catch (Exception e) {
            ExportStatus.post(ExportStatus.State.FAILED, 0, getString(R.string.export_failed), null);
        } finally {
            worker = null;
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private static void write(File f, String content) throws Exception {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private void update(int p) {
        ExportStatus.post(ExportStatus.State.RUNNING, p, null, null);
        if (Notifications.canPost(this)) {
            //noinspection MissingPermission
            NotificationManagerCompat.from(this).notify(Notifications.ID_EXPORT, progress(p));
        }
    }

    private Notification progress(int p) {
        PendingIntent cancel = PendingIntent.getService(this, 31, new Intent(this, ExportService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, Notifications.CH_EXPORT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.export_running))
                .setProgress(100, p, p == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_close, getString(R.string.cancel), cancel)
                .build();
    }

    private void notifyDone(ArrayList<String> files) {
        if (!Notifications.canPost(this) || files.isEmpty()) return;
        Intent share = shareIntent(this, files);
        PendingIntent pi = PendingIntent.getActivity(this, 32, Intent.createChooser(share, getString(R.string.share_export)),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, Notifications.CH_EXPORT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.export_ready))
                .setContentText(getString(R.string.export_ready_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_share, getString(R.string.share), pi)
                .build();
        //noinspection MissingPermission
        NotificationManagerCompat.from(this).notify(Notifications.ID_EXPORT_DONE, n);
    }

    /** Implicit ACTION_SEND(_MULTIPLE) with content:// URIs from FileProvider and read grants. */
    public static Intent shareIntent(Context c, ArrayList<String> paths) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (String p : paths) uris.add(FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", new File(p)));
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
            i.setType(paths.get(0).endsWith(".xml") ? "text/xml" : "text/csv");
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.setType("text/*");
        }
        ClipData clip = ClipData.newRawUri("export", uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.putExtra(Intent.EXTRA_SUBJECT, "NutriFit AI export");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    @Override
    public void onDestroy() {
        cancelled.set(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
