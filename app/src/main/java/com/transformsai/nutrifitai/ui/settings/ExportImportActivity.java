package com.transformsai.nutrifitai.ui.settings;

import android.Manifest;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.repo.ImportExportRepository;
import com.transformsai.nutrifitai.databinding.ActivityExportImportBinding;
import com.transformsai.nutrifitai.io.DownloadsSaver;
import com.transformsai.nutrifitai.io.ExportData;
import com.transformsai.nutrifitai.io.ExportService;
import com.transformsai.nutrifitai.io.ExportStatus;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;

import java.util.ArrayList;

/**
 * Export (foreground service -> FileProvider share via implicit chooser) and XML import
 * (Storage Access Framework + ContentResolver -> validated preview -> explicit confirmation).
 * Also handles "Open with NutriFit AI" VIEW intents for .xml files.
 */
public class ExportImportActivity extends BaseActivity {
    private ActivityExportImportBinding b;
    private ImportExportRepository.Preview preview;
    private ArrayList<String> lastFiles;

    private final ActivityResultLauncher<String[]> openDoc =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
    private final ActivityResultLauncher<String> notif =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), g -> startExport());

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityExportImportBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.export_title);
        b.bar.back.setOnClickListener(v -> finish());
        Ui.onClick(b.export, v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notif.launch(Manifest.permission.POST_NOTIFICATIONS); // progress notification; export runs either way
            } else startExport();
        });
        b.share.setOnClickListener(v -> {
            if (lastFiles != null && !lastFiles.isEmpty()) {
                startActivity(Intent.createChooser(ExportService.shareIntent(this, lastFiles), getString(R.string.share_export)));
            }
        });
        b.saveDownloads.setVisibility(DownloadsSaver.supported() ? View.VISIBLE : View.GONE);
        Ui.onClick(b.saveDownloads, v -> saveToDownloads());
        b.pick.setOnClickListener(v -> openDoc.launch(new String[]{"text/xml", "application/xml", "*/*"}));
        Ui.onClick(b.apply, v -> apply());
        ExportStatus.observe().observe(this, this::renderExport);
        if (s == null && Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
            onPicked(getIntent().getData());
        }
    }

    private void startExport() {
        if (!b.csv.isChecked() && !b.xml.isChecked()) return;
        ExportService.start(this, b.csv.isChecked(), b.xml.isChecked());
    }

    private void renderExport(ExportStatus.Snapshot s) {
        boolean running = s.state == ExportStatus.State.RUNNING;
        b.progress.setVisibility(running ? View.VISIBLE : View.GONE);
        b.progress.setProgressCompat(s.progress, true);
        b.export.setText(running ? R.string.cancel : R.string.export_start);
        b.export.setOnClickListener(running ? v -> ExportService.cancel(this) : v -> startExport());
        b.exportStatus.setVisibility(s.message == null ? View.GONE : View.VISIBLE);
        b.exportStatus.setText(s.message);
        if (s.state == ExportStatus.State.DONE && s.files != null) {
            lastFiles = s.files;
            b.share.setEnabled(true);
            b.saveDownloads.setEnabled(true);
        }
    }

    private void saveToDownloads() {
        if (lastFiles == null || lastFiles.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        ArrayList<String> files = new ArrayList<>(lastFiles);
        graph().runIo(() -> {
            String msg;
            try {
                msg = getString(R.string.saved_downloads, DownloadsSaver.save(this, files));
            } catch (Exception e) {
                msg = getString(R.string.save_downloads_failed);
            }
            String m = msg;
            graph().main.post(() -> {
                if (b != null) Ui.snack(b.getRoot(), m);
            });
        });
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null) return;
        String name = displayName(uri);
        b.importResult.setVisibility(View.GONE);
        b.pick.setEnabled(false);
        graph().importExport.preview(uri, name, r -> {
            b.pick.setEnabled(true);
            if (!r.ok()) {
                b.preview.setVisibility(View.GONE);
                b.importResult.setVisibility(View.VISIBLE);
                b.importResult.setText(r.error);
                return;
            }
            preview = r.value;
            ExportData d = preview.data;
            b.preview.setVisibility(View.VISIBLE);
            b.fileName.setText(name);
            b.counts.setText(getString(R.string.import_preview, d.entries.size(), d.itemCount(), d.water.size(), d.weights.size(), d.goals.size(), d.foods.size()));
            b.dupes.setText(getString(R.string.import_dupes, preview.duplicates, preview.newRecords));
            b.errors.setVisibility(d.errors.isEmpty() ? View.GONE : View.VISIBLE);
            b.errors.setText(getString(R.string.import_errors, d.errors.size()) + (d.errors.isEmpty() ? "" : "\n" + d.errors.get(0)));
            b.apply.setEnabled(d.recordCount() > 0);
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
            // fall through
        }
        return "import.xml";
    }

    private void apply() {
        if (preview == null) return;
        b.apply.setEnabled(false);
        graph().importExport.apply(preview, b.replace.isChecked(), r -> {
            b.preview.setVisibility(View.GONE);
            b.importResult.setVisibility(View.VISIBLE);
            b.importResult.setText(getString(R.string.import_done, r.value.added, r.value.replaced, r.value.skipped));
            preview = null;
        });
    }
}
