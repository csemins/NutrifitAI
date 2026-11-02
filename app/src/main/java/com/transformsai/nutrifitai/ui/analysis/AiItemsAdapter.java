package com.transformsai.nutrifitai.ui.analysis;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.ai.AiItem;
import com.transformsai.nutrifitai.ai.AiResult;
import com.transformsai.nutrifitai.databinding.ItemAiFoodBinding;
import com.transformsai.nutrifitai.databinding.ItemAiFooterBinding;
import com.transformsai.nutrifitai.databinding.ItemAiHeaderBinding;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.Units;
import com.bumptech.glide.Glide;

import java.io.File;
import java.util.List;

/** Header (photo, warnings, questions) + one editable card per detected item + footer. */
public class AiItemsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onEdit(AiItem i);

        void onRemove(AiItem i);

        void onAddManual();
    }

    private static final int HEADER = 0, ITEM = 1, FOOTER = 2;
    private final Listener l;
    private AiResult result;
    private String imagePath;
    private List<AiItem> items;

    public AiItemsAdapter(Listener l) {
        this.l = l;
    }

    public void set(AiResult r, String image) {
        result = r;
        imagePath = image;
        items = r.activeItems();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return result == null ? 0 : items.size() + 2;
    }

    @Override
    public int getItemViewType(int p) {
        return p == 0 ? HEADER : p == getItemCount() - 1 ? FOOTER : ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int t) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (t == HEADER) return new VH(ItemAiHeaderBinding.inflate(inf, parent, false).getRoot());
        if (t == FOOTER) return new VH(ItemAiFooterBinding.inflate(inf, parent, false).getRoot());
        return new VH(ItemAiFoodBinding.inflate(inf, parent, false).getRoot());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
        View v = h.itemView;
        int t = getItemViewType(p);
        if (t == HEADER) {
            ItemAiHeaderBinding b = ItemAiHeaderBinding.bind(v);
            if (imagePath != null && new File(imagePath).exists()) {
                b.image.setVisibility(View.VISIBLE);
                Glide.with(b.image).load(new File(imagePath)).centerCrop().into(b.image);
            } else b.image.setVisibility(View.GONE);
            b.lowConf.setVisibility(AiResult.STATUS_UNCERTAIN.equals(result.status) || result.anyLowConfidence() ? View.VISIBLE : View.GONE);
            b.warnings.setVisibility(View.GONE);
            b.questions.setVisibility(result.clarifyingQuestions.isEmpty() ? View.GONE : View.VISIBLE);
            b.questions.setText(v.getContext().getString(R.string.ai_questions) + ":\n• " + String.join("\n• ", result.clarifyingQuestions));
        } else if (t == FOOTER) {
            ItemAiFooterBinding.bind(v).addItem.setOnClickListener(x -> l.onAddManual());
        } else {
            AiItem i = items.get(p - 1);
            ItemAiFoodBinding b = ItemAiFoodBinding.bind(v);
            b.name.setText(i.name);
            b.bn.setText(i.nameBn == null ? "" : i.nameBn);
            b.bn.setVisibility(i.nameBn == null ? View.GONE : View.VISIBLE);
            b.portion.setText(Units.num(i.quantity) + " " + i.unit + " · ~" + Units.grams(i.weightG));
            b.kcal.setText(Ui.kcal(i.kcal));
            b.confidence.setText(i.lowConfidence() ? R.string.ai_chip_check : R.string.ai_chip_estimate);
            b.confidence.setBackgroundResource(i.lowConfidence() ? R.drawable.bg_banner_warning : R.drawable.bg_badge_lime);
            b.confidence.setTextColor(v.getContext().getColor(i.lowConfidence() ? R.color.nl_warning : R.color.nl_lime));
            b.macros.setText(Ui.macroChips(v.getContext(), new Nutrients(i.kcal, i.protein, i.carbs, i.fat, i.fibre)));
            b.range.setVisibility(View.GONE);
            b.assumptions.setVisibility(View.GONE);
            b.edit.setOnClickListener(x -> l.onEdit(i));
            b.remove.setOnClickListener(x -> l.onRemove(i));
        }
    }

    static final class VH extends RecyclerView.ViewHolder {
        VH(View v) {
            super(v);
        }
    }
}
