package com.transformsai.nutrifitai.ui.common;

import android.view.View;

import androidx.fragment.app.Fragment;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.databinding.ItemSheetRowBinding;
import com.transformsai.nutrifitai.databinding.SheetItemActionsBinding;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.food.FoodDetailActivity;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/** Shared diary interactions: item action sheet, delete with undo, copy/move with pickers. */
public final class DiaryActions {
    private DiaryActions() {
    }

    public static void pickDate(Fragment f, String current, Consumer<String> onPick) {
        long sel = LocalDate.parse(current).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        MaterialDatePicker<Long> p = MaterialDatePicker.Builder.datePicker().setSelection(sel)
                .setTitleText(R.string.pick_date).build();
        p.addOnPositiveButtonClickListener(ms -> onPick.accept(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()));
        p.show(f.getChildFragmentManager(), "date");
    }

    public static void chooseMeal(Fragment f, String current, Consumer<String> onPick) {
        String[] labels = f.getResources().getStringArray(R.array.meal_types);
        int checked = java.util.Arrays.asList(MealLabels.TYPES).indexOf(current);
        new MaterialAlertDialogBuilder(f.requireContext()).setTitle(R.string.choose_meal)
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    d.dismiss();
                    onPick.accept(MealLabels.TYPES[w]);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    public static void showItemSheet(Fragment f, ItemRow r, View anchor) {
        BottomSheetDialog d = new BottomSheetDialog(f.requireContext());
        SheetItemActionsBinding sb = SheetItemActionsBinding.inflate(f.getLayoutInflater());
        sb.title.setText(r.item.name);
        boolean planned = MealEntryEntity.STATUS_PLANNED.equals(r.status);
        if (planned && !DateUtil.isFuture(r.date)) row(f, sb, R.drawable.ic_check, R.string.mark_eaten, d, () ->
                App.graph().diary.markEaten(r.item.id, null));
        row(f, sb, R.drawable.ic_edit, R.string.edit_portion, d, () ->
                f.startActivity(FoodDetailActivity.forItem(f.requireContext(), r.item.id)));
        row(f, sb, R.drawable.ic_copy, R.string.duplicate, d, () ->
                App.graph().diary.copyItem(r.item.id, r.date, r.mealType, false, x -> Ui.snack(anchor, f.getString(R.string.copied_n, 1))));
        row(f, sb, R.drawable.ic_move, R.string.move_to, d, () -> pickDate(f, r.date, date ->
                chooseMeal(f, r.mealType, meal -> App.graph().diary.copyItem(r.item.id, date, meal, true,
                        x -> Ui.snack(anchor, f.getString(R.string.moved))))));
        row(f, sb, R.drawable.ic_calendar, R.string.copy_to, d, () -> pickDate(f, r.date, date ->
                chooseMeal(f, r.mealType, meal -> App.graph().diary.copyItem(r.item.id, date, meal, false,
                        x -> Ui.snack(anchor, f.getString(R.string.copied_n, 1))))));
        row(f, sb, R.drawable.ic_delete, R.string.delete, d, () -> delete(f, r, anchor));
        d.setContentView(sb.getRoot());
        d.show();
    }

    private static void row(Fragment f, SheetItemActionsBinding sb, int icon, int label, BottomSheetDialog d, Runnable r) {
        ItemSheetRowBinding rb = ItemSheetRowBinding.inflate(f.getLayoutInflater(), sb.rows, false);
        rb.icon.setImageResource(icon);
        rb.label.setText(label);
        rb.getRoot().setOnClickListener(v -> {
            d.dismiss();
            r.run();
        });
        sb.rows.addView(rb.getRoot());
    }

    public static void confirmDelete(Fragment f, ItemRow r, View anchor) {
        delete(f, r, anchor);
    }

    /** Deletes immediately with an Undo snackbar (tombstone is reverted on undo). */
    public static void delete(Fragment f, ItemRow r, View anchor) {
        App.graph().diary.deleteItem(r.item.id, res -> {
            if (!f.isAdded()) return;
            Ui.snack(anchor, f.getString(R.string.item_deleted, r.item.name))
                    .setAction(R.string.undo, v -> App.graph().diary.restoreItem(r.item.id));
        });
    }

    public static void copyMeal(Fragment f, String fromDate, String mealType, View anchor) {
        pickDate(f, DateUtil.shift(fromDate, 1), to -> chooseMeal(f, mealType, meal ->
                App.graph().diary.copyMeal(fromDate, mealType, to, meal,
                        r -> Ui.snack(anchor, f.getString(R.string.copied_n, r.value == null ? 0 : r.value)))));
    }
}
