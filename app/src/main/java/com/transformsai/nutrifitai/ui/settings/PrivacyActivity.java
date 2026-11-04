package com.transformsai.nutrifitai.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityPrivacyBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.launch.LauncherActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Privacy & data: how data is processed, export/import, account deletion and credits. */
public class PrivacyActivity extends BaseActivity {
    private static final String NL = System.lineSeparator();
    private ActivityPrivacyBinding b;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        b = ActivityPrivacyBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.privacy);
        b.bar.back.setOnClickListener(v -> finish());

        b.rowExport.icon.setImageResource(R.drawable.ic_download);
        b.rowExport.icon.setImageTintList(ContextCompat.getColorStateList(this, R.color.nl_lime));
        b.rowExport.title.setText(R.string.data_export);
        b.rowExport.subtitle.setText(R.string.export_row_sub);
        b.rowExport.getRoot().setOnClickListener(v -> startActivity(new Intent(this, ExportImportActivity.class)));
        Ui.onClick(b.deleteAccount, v -> confirmDelete());

        graph().runIo(() -> {
            String text = credits();
            graph().main.post(() -> b.credits.setText(text));
        });
    }

    /** Deleting needs a fresh sign-in: the password for email accounts, a new Google token otherwise. */
    private void confirmDelete() {
        boolean password = graph().auth.isPasswordUser();
        TextInputLayout til = new TextInputLayout(this);
        TextInputEditText pw = new TextInputEditText(til.getContext());
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        til.setHint(getString(R.string.delete_confirm_pw));
        til.addView(pw);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        if (password) box.addView(til);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_q).setMessage(R.string.delete_body)
                .setView(box)
                .setPositiveButton(password ? R.string.delete_account : R.string.delete_google, (d, w) -> {
                    if (password) runDelete(pw.getText() == null ? "" : pw.getText().toString(), null);
                    else GoogleReauth.request(this, b.getRoot(), token -> runDelete(null, token));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void runDelete(@Nullable String password, @Nullable String googleToken) {
        if (password != null && password.isEmpty()) return;
        b.deleteAccount.setEnabled(false);
        Ui.snack(b.getRoot(), getString(R.string.deleting));
        graph().auth.deleteAccount(password, googleToken, r -> {
            if (b == null) return;
            b.deleteAccount.setEnabled(true);
            if (!r.ok()) {
                Ui.snack(b.getRoot(), r.error);
                return;
            }
            startActivity(new Intent(this, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
    }

    /** Attribution for bundled photos (CC BY / CC BY-SA require it) and data sources. */
    private String credits() {
        StringBuilder sb = new StringBuilder();
        sb.append("Exercise animations: body poses from free-exercise-db (public domain), drawn as animated figures.").append(NL);
        sb.append(NL).append("Food photos (Wikimedia Commons unless marked as illustrations):").append(NL);
        appendPhotoCredits(sb);
        sb.append(NL).append("Food data: USDA FoodData Central SR Legacy (public domain). Pose model: Google MediaPipe (Apache-2.0).")
                .append(NL).append("Barcode products: Open Food Facts (ODbL). App illustrations and icons: generated for NutriFit AI.");
        return sb.toString();
    }

    private void appendPhotoCredits(StringBuilder sb) {
        try (InputStream in = getAssets().open("food_images/credits.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : o.keySet()) {
                String name = k.replace("cat_", "").replace("category_", "group: ").replace('_', ' ');
                JsonObject c = o.getAsJsonObject(k);
                String src = c.has("author") ? c.get("author").getAsString() + ", " + c.get("license").getAsString()
                        : c.get("source").getAsString();
                sb.append("• ").append(name).append(": ").append(src).append(NL);
            }
        } catch (Exception ignored) {
            // credits asset missing in this build
        }
    }

    @Override
    protected void onDestroy() {
        b = null;
        super.onDestroy();
    }
}
