package com.transformsai.nutrifitai.ui.common;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.remote.AiAccess;
import com.transformsai.nutrifitai.databinding.SheetAiAccessBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * Shown on AI screens when the account is not enrolled for AI features. "Request access" sends one request
 * per account; pressing it again only thanks the user (nothing is sent).
 */
public final class AiAccessSheet {
    private AiAccessSheet() { }

    /** Checks access when an AI screen opens and shows the sheet once if AI is not enabled. */
    public static void attach(BaseActivity a) {
        AiAccess access = App.graph().aiAccess;
        boolean[] shown = {false};
        access.state().observe(a, s -> {
            if (s == AiAccess.State.NOT_ENROLLED && !shown[0] && !a.isFinishing()) {
                shown[0] = true;
                show(a);
            }
        });
        access.refresh();
    }

    public static void show(BaseActivity a) {
        AiAccess access = App.graph().aiAccess;
        BottomSheetDialog d = new BottomSheetDialog(a);
        SheetAiAccessBinding v = SheetAiAccessBinding.inflate(a.getLayoutInflater());
        d.setContentView(v.getRoot());
        if (access.requested()) thanks(v);
        v.request.setOnClickListener(x -> {
            if (access.request()) thanks(v); // repeat taps send nothing and just thank again
            else v.body.setText(R.string.ai_access_failed);
        });
        v.close.setOnClickListener(x -> d.dismiss());
        d.show();
    }

    private static void thanks(SheetAiAccessBinding v) {
        v.title.setText(R.string.ai_access_thanks_title);
        v.body.setText(R.string.ai_access_thanks_body);
        v.request.setText(R.string.ai_access_received);
        v.request.setIcon(null);
        v.close.setText(R.string.done);
    }
}
