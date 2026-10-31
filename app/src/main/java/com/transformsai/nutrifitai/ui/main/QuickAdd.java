package com.transformsai.nutrifitai.ui.main;

import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalTime;
import java.util.Collections;

/**
 * Quick add: log calories (and optional macros) without picking a food, e.g. from a restaurant
 * menu. Saved as one user-entered item (provenance "user") of 1 portion, with unknown fibre.
 */
public final class QuickAdd {
    private QuickAdd() { }

    public static void show(Fragment host) {
        android.content.Context c = host.requireContext();
        int pad = (int) (20 * c.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText name = field(c, R.string.quick_add_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText kcal = field(c, R.string.quick_add_kcal, InputType.TYPE_CLASS_NUMBER);
        EditText p = field(c, R.string.quick_add_protein, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText carb = field(c, R.string.quick_add_carbs, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText f = field(c, R.string.quick_add_fat, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(name);
        box.addView(kcal);
        LinearLayout macros = new LinearLayout(c);
        macros.addView(p, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        macros.addView(carb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        macros.addView(f, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(macros);
        String meal = MealLabels.forHour(LocalTime.now().getHour());
        new MaterialAlertDialogBuilder(c).setTitle(R.string.quick_add_title)
                .setMessage(MealLabels.label(c, meal))
                .setView(box)
                .setPositiveButton(R.string.add, (d, w) -> {
                    double k = num(kcal);
                    if (k <= 0 || k > 5000) {
                        if (host.getView() != null) Ui.snack(host.getView(), c.getString(R.string.quick_add_invalid));
                        return;
                    }
                    MealItemEntity i = new MealItemEntity();
                    i.id = Ids.newId();
                    String n = name.getText().toString().trim();
                    i.name = n.isEmpty() ? c.getString(R.string.quick_add_default_name) : n.length() > 80 ? n.substring(0, 80) : n;
                    i.servings = 1;
                    i.servingLabel = "portion";
                    i.servingGrams = 100; // values below are "per portion" stored as per-100 g of a 100 g portion
                    i.kcal100 = k;
                    i.protein100 = Math.max(0, num(p));
                    i.carbs100 = Math.max(0, num(carb));
                    i.fat100 = Math.max(0, num(f));
                    i.provenance = FoodEntity.PROV_USER;
                    i.emoji = "⚡";
                    App.graph().diary.addItems(DateUtil.today(), meal, false, "quick", Collections.singletonList(i), null, null, r -> {
                        if (host.getView() != null) Ui.snack(host.getView(), c.getString(R.string.added_to, MealLabels.label(c, meal)));
                    });
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private static EditText field(android.content.Context c, int hint, int type) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private static double num(EditText e) {
        try {
            double v = Double.parseDouble(e.getText().toString().trim());
            return Double.isFinite(v) ? v : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
