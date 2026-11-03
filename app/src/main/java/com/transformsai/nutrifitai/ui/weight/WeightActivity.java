package com.transformsai.nutrifitai.ui.weight;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.databinding.ActivityWeightBinding;
import com.transformsai.nutrifitai.databinding.DialogWeightBinding;
import com.transformsai.nutrifitai.databinding.ItemWeightBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Units;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Weight history with add / edit / delete (undo). */
public class WeightActivity extends BaseActivity {
    private static final String EXTRA_ADD = "add";

    public static Intent add(Context c) {
        return new Intent(c, WeightActivity.class).putExtra(EXTRA_ADD, true);
    }

    private ActivityWeightBinding b;
    private final List<WeightEntity> items = new ArrayList<>();
    private String units = "metric";
    private Adapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityWeightBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.weight_title);
        b.bar.back.setOnClickListener(v -> finish());
        adapter = new Adapter();
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        Ui.onClick(b.add, v -> dialog(null));
        graph().profile.observe(uid).observe(this, p -> {
            if (p != null) units = p.units;
            adapter.notifyDataSetChanged();
        });
        graph().weights.observeAll(uid).observe(this, list -> {
            items.clear();
            if (list != null) items.addAll(list);
            b.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
        });
        if (s == null && getIntent().getBooleanExtra(EXTRA_ADD, false)) dialog(null);
    }

    private void dialog(@Nullable WeightEntity w) {
        DialogWeightBinding d = DialogWeightBinding.inflate(getLayoutInflater());
        d.kgLayout.setHint(getString(R.string.weight_hint, Units.weightUnit(units)));
        final String[] date = {w == null ? DateUtil.today() : w.date};
        if (w != null) {
            d.kg.setText(Units.weightNumber(w.kg, units));
            d.note.setText(w.note);
        }
        d.date.setText(DateUtil.monthDay(date[0]));
        d.date.setOnClickListener(v -> {
            MaterialDatePicker<Long> p = MaterialDatePicker.Builder.datePicker()
                    .setSelection(LocalDate.parse(date[0]).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()).build();
            p.addOnPositiveButtonClickListener(ms -> {
                String picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString();
                if (DateUtil.isFuture(picked)) return; // weights can't be logged in the future
                date[0] = picked;
                d.date.setText(DateUtil.monthDay(picked));
            });
            p.show(getSupportFragmentManager(), "date");
        });
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(w == null ? R.string.add_weight : R.string.edit).setView(d.getRoot())
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).show();
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double val = Ui.parse(d.kg.getText());
            double kg = Units.imperial(units) ? Units.lbToKg(val) : val;
            if (Double.isNaN(kg) || kg < 20 || kg > 400) {
                d.kgLayout.setError(getString(R.string.err_weight_value));
                return;
            }
            String note = d.note.getText() == null ? null : d.note.getText().toString().trim();
            graph().weights.save(w == null ? null : w.id, date[0], Math.round(kg * 10) / 10.0, note == null || note.isEmpty() ? null : note);
            dlg.dismiss();
        });
    }

    final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ItemWeightBinding b;

            VH(ItemWeightBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(ItemWeightBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            WeightEntity w = items.get(pos);
            h.b.value.setText(Units.weight(w.kg, units));
            h.b.date.setText(DateUtil.dayShort(w.date) + ", " + DateUtil.monthDay(w.date) + (w.note == null ? "" : " · " + w.note));
            if (pos + 1 < items.size()) {
                double diff = w.kg - items.get(pos + 1).kg;
                double shown = Units.imperial(units) ? Units.kgToLb(diff) : diff;
                h.b.delta.setText(String.format(Locale.getDefault(), "%+.1f", shown));
            } else h.b.delta.setText("");
            h.b.getRoot().setOnClickListener(v -> dialog(w));
            h.b.delete.setOnClickListener(v -> {
                graph().weights.setDeleted(w.id, true);
                Ui.snack(b.getRoot(), getString(R.string.weight_deleted)).setAction(R.string.undo, x -> graph().weights.setDeleted(w.id, false));
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
