package com.transformsai.nutrifitai.ui.food;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.databinding.ItemFoodBinding;
import com.transformsai.nutrifitai.databinding.ItemSearchFooterBinding;
import com.transformsai.nutrifitai.databinding.ItemSectionLabelBinding;
import com.transformsai.nutrifitai.util.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Section label + food rows + footer (empty message, frequent foods, create custom). */
public class FoodAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onOpen(FoodEntity f);

        void onQuickAdd(FoodEntity f, View v);

        void onFooter(ItemSearchFooterBinding footer);
    }

    private static final int LABEL = 0, FOOD = 1, FOOTER = 2;
    private final Listener l;
    private final List<FoodEntity> foods = new ArrayList<>();
    private String label = "";
    private Set<String> favorites;
    private boolean showAdd = true;

    public FoodAdapter(Listener l) {
        this.l = l;
    }

    public void setShowAdd(boolean s) {
        showAdd = s;
    }

    public void submit(String label, List<FoodEntity> list, Set<String> favs) {
        this.label = label;
        foods.clear();
        if (list != null) foods.addAll(list);
        favorites = favs;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return foods.size() + 2;
    }

    @Override
    public int getItemViewType(int p) {
        if (p == 0) return LABEL;
        if (p == getItemCount() - 1) return FOOTER;
        return FOOD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == LABEL) return new Simple(ItemSectionLabelBinding.inflate(inf, parent, false).getRoot());
        if (type == FOOTER) {
            ItemSearchFooterBinding fb = ItemSearchFooterBinding.inflate(inf, parent, false);
            Simple s = new Simple(fb.getRoot());
            s.footer = fb;
            return s;
        }
        return new FoodVH(ItemFoodBinding.inflate(inf, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
        if (h instanceof FoodVH) ((FoodVH) h).bind(foods.get(p - 1));
        else if (getItemViewType(p) == LABEL) ((android.widget.TextView) h.itemView).setText(label);
        else l.onFooter(((Simple) h).footer);
    }

    static final class Simple extends RecyclerView.ViewHolder {
        ItemSearchFooterBinding footer;

        Simple(View v) {
            super(v);
        }
    }

    final class FoodVH extends RecyclerView.ViewHolder {
        final ItemFoodBinding b;

        FoodVH(ItemFoodBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(FoodEntity f) {
            b.name.setText(f.name);
            b.bn.setText(f.nameBn == null ? "" : f.nameBn);
            b.bn.setVisibility(f.nameBn == null ? View.GONE : View.VISIBLE);
            String serving = f.servingLabel == null ? "100 g" : f.servingLabel + " · " + Units.grams(f.servingGrams);
            int kcal = (int) Math.round(f.kcal100 * f.servingGrams / 100.0);
            b.sub.setText(b.getRoot().getContext().getString(R.string.food_row_sub, kcal, serving));
            b.thumb.setFood(f.id, null, f.emoji, f.category);
            b.fav.setVisibility(favorites != null && favorites.contains(f.id) ? View.VISIBLE : View.GONE);
            b.add.setVisibility(showAdd ? View.VISIBLE : View.GONE);
            b.add.setContentDescription(b.getRoot().getContext().getString(R.string.cd_quick_add, f.name));
            b.getRoot().setOnClickListener(v -> l.onOpen(f));
            b.add.setOnClickListener(v -> l.onQuickAdd(f, v));
        }
    }
}
