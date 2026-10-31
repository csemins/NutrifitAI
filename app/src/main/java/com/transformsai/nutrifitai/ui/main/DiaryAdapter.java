package com.transformsai.nutrifitai.ui.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.databinding.ItemMealBinding;
import com.transformsai.nutrifitai.databinding.ItemMealEmptyBinding;
import com.transformsai.nutrifitai.databinding.ItemMealHeaderBinding;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.Units;
import com.bumptech.glide.Glide;

import java.io.File;
import java.util.Objects;

/** RecyclerView adapter with three ViewHolder types and DiffUtil for smooth updates. */
public class DiaryAdapter extends ListAdapter<DiaryRow, RecyclerView.ViewHolder> {
    public interface Listener {
        void onItem(ItemRow r);

        void onItemMore(ItemRow r);

        void onStep(ItemRow r, int direction);

        void onEat(ItemRow r);

        void onAdd(String mealType);

        void onCoach(String mealType);

        void onMealMore(String mealType);
    }

    private final Listener l;
    private boolean futureDay;

    public DiaryAdapter(Listener l) {
        super(new DiffUtil.ItemCallback<DiaryRow>() {
            @Override
            public boolean areItemsTheSame(@NonNull DiaryRow a, @NonNull DiaryRow b) {
                return a.key().equals(b.key());
            }

            @Override
            public boolean areContentsTheSame(@NonNull DiaryRow a, @NonNull DiaryRow b) {
                if (a.type != b.type) return false;
                if (a.type == DiaryRow.HEADER) return a.kcal == b.kcal && a.plannedKcal == b.plannedKcal;
                if (a.type == DiaryRow.EMPTY) return true;
                MealItemEntity x = a.item.item, y = b.item.item;
                return x.servings == y.servings && x.servingGrams == y.servingGrams && x.updatedAt == y.updatedAt
                        && Objects.equals(a.item.status, b.item.status) && Objects.equals(a.item.imageLocalPath, b.item.imageLocalPath);
            }
        });
        this.l = l;
    }

    public void setFutureDay(boolean f) {
        futureDay = f;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
        LayoutInflater inf = LayoutInflater.from(p.getContext());
        if (type == DiaryRow.HEADER) return new HeaderVH(ItemMealHeaderBinding.inflate(inf, p, false));
        if (type == DiaryRow.EMPTY) return new EmptyVH(ItemMealEmptyBinding.inflate(inf, p, false));
        return new ItemVH(ItemMealBinding.inflate(inf, p, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        DiaryRow r = getItem(pos);
        if (h instanceof HeaderVH) ((HeaderVH) h).bind(r);
        else if (h instanceof EmptyVH) ((EmptyVH) h).bind(r);
        else ((ItemVH) h).bind(r.item);
    }

    final class HeaderVH extends RecyclerView.ViewHolder {
        final ItemMealHeaderBinding b;

        HeaderVH(ItemMealHeaderBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(DiaryRow r) {
            Context c = b.getRoot().getContext();
            b.title.setText(MealLabels.label(c, r.mealType));
            b.dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(c, MealLabels.color(r.mealType))));
            String k = c.getString(R.string.kcal_value, Math.round(r.kcal));
            if (r.plannedKcal > 0) k += " · " + Math.round(r.plannedKcal) + " " + c.getString(R.string.planned).toLowerCase();
            b.kcal.setText(k);
            b.more.setVisibility(r.kcal > 0 || r.plannedKcal > 0 ? View.VISIBLE : View.INVISIBLE);
            b.more.setOnClickListener(v -> l.onMealMore(r.mealType));
        }
    }

    final class EmptyVH extends RecyclerView.ViewHolder {
        final ItemMealEmptyBinding b;

        EmptyVH(ItemMealEmptyBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(DiaryRow r) {
            b.title.setText(futureDay ? R.string.nothing_planned : R.string.nothing_logged);
            b.add.setContentDescription(b.getRoot().getContext().getString(R.string.cd_quick_add,
                    MealLabels.label(b.getRoot().getContext(), r.mealType)));
            Ui.onClick(b.add, v -> l.onAdd(r.mealType));
            b.coach.setOnClickListener(v -> l.onCoach(r.mealType));
        }
    }

    final class ItemVH extends RecyclerView.ViewHolder {
        final ItemMealBinding b;

        ItemVH(ItemMealBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(ItemRow r) {
            Context c = b.getRoot().getContext();
            MealItemEntity i = r.item;
            Nutrients n = NutritionMath.forItem(i);
            boolean planned = MealEntryEntity.STATUS_PLANNED.equals(r.status);
            b.name.setText(i.name);
            b.kcal.setText(Ui.kcal(n.kcal));
            String portion = "g".equals(i.servingLabel) ? Units.grams(i.grams())
                    : Units.num(i.servings) + " × " + i.servingLabel + " · " + Units.grams(i.grams());
            b.sub.setText(portion);
            b.macros.setText(Ui.macroChips(c, n));
            b.qty.setText("g".equals(i.servingLabel) ? Units.num(i.servings) + "g" : Units.num(i.servings));
            b.card.setBackgroundResource(planned ? R.drawable.bg_card_planned : R.drawable.bg_card_pressable);
            if (planned) {
                b.badge.setVisibility(View.VISIBLE);
                b.badge.setText(R.string.planned);
            } else if (i.confidence != null) {
                b.badge.setVisibility(View.VISIBLE);
                b.badge.setText(c.getString(R.string.confidence_chip, Math.round(i.confidence * 100) + "%"));
            } else b.badge.setVisibility(View.GONE);
            b.eat.setVisibility(planned && !futureDay ? View.VISIBLE : View.GONE);
            if (r.imageLocalPath != null && new File(r.imageLocalPath).exists()) {
                Glide.with(b.thumb).load(new File(r.imageLocalPath)).centerCrop().into(b.thumb);
            } else {
                b.thumb.setFood(i.foodId, i.name, i.emoji, null);
            }
            b.getRoot().setContentDescription(i.name + ", " + Math.round(n.kcal) + " kcal, " + portion
                    + (planned ? ", " + c.getString(R.string.planned) : ""));
            b.getRoot().setOnClickListener(v -> l.onItem(r));
            b.getRoot().setOnLongClickListener(v -> {
                l.onItemMore(r);
                return true;
            });
            b.minus.setOnClickListener(v -> l.onStep(r, -1));
            b.plus.setOnClickListener(v -> l.onStep(r, +1));
            Ui.onClick(b.eat, v -> l.onEat(r));
        }
    }
}
