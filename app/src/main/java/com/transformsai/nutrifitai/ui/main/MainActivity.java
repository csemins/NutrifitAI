package com.transformsai.nutrifitai.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityMainBinding;
import com.transformsai.nutrifitai.databinding.ViewNavItemBinding;
import com.transformsai.nutrifitai.reminders.SystemEventsReceiver;
import com.transformsai.nutrifitai.sync.SyncScheduler;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.camera.CameraActivity;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.hydration.HydrationActivity;
import com.transformsai.nutrifitai.ui.social.SocialFragment;

/**
 * Hosts the tab fragments (show/hide keeps their state) and the floating navigation bar with
 * the central camera action. Explicit intents open the camera and detail screens.
 */
public class MainActivity extends BaseActivity implements SystemEventsReceiver.Listener {
    public static final String EXTRA_DEST = "dest";
    public static final String EXTRA_TAB = "tab";
    public static final int TAB_HOME = 0, TAB_MEALS = 1, TAB_TRAIN = 2, TAB_PROGRESS = 3, TAB_PROFILE = 4, TAB_SOCIAL = 5;
    private static final String[] TAGS = {"home", "meals", "train", "progress", "profile", "social"};
    /** Plain tabs in bar order; the camera and Social sit highlighted in the middle. Profile opens from the avatar. */
    private static final int[] BAR_TABS = {TAB_HOME, TAB_MEALS, TAB_TRAIN, TAB_PROGRESS};

    private ActivityMainBinding b;
    private ViewNavItemBinding[] items;
    private int current = -1;
    private final SystemEventsReceiver systemEvents = new SystemEventsReceiver(this);

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        // signed in now: listen for notifications (the app-start hook ran before sign-in)
        graph().inbox.start();
        com.transformsai.nutrifitai.social.InboxWorker.schedule(this);
        askNotificationsOnce(s);
        // background step counting / Health Connect sync, only when the user turned them on
        if (graph().prefs.stepTracking() || graph().prefs.healthConnect()) com.transformsai.nutrifitai.health.StepWorker.schedule(this);
        boolean rail = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // portrait: docked bottom bar under the gesture area; landscape: a rail on the left, full height
        InsetsUtil.padding(b.nav, rail, true);
        if (rail) {
            // the tabs start where the rail ends (the rail grows by the left system-bar / cutout inset)
            b.nav.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
                android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) b.container.getLayoutParams();
                if (lp.getMarginStart() != v.getWidth()) {
                    lp.setMarginStart(v.getWidth());
                    b.container.setLayoutParams(lp);
                }
            });
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.topScrim, (v, wi) -> {
            int top = wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.getLayoutParams().height = (int) (top * 1.6f);
            v.requestLayout();
            return wi;
        });
        items = new ViewNavItemBinding[]{b.navHome, b.navMeals, b.navTrain, b.navProgress};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_meals, R.drawable.ic_dumbbell, R.drawable.ic_progress};
        int[] labels = {R.string.nav_home, R.string.nav_meals, R.string.nav_train, R.string.nav_progress};
        for (int i = 0; i < items.length; i++) {
            items[i].icon.setImageResource(icons[i]);
            items[i].label.setText(labels[i]);
            items[i].getRoot().setContentDescription(getString(labels[i]));
            final int tab = BAR_TABS[i];
            items[i].getRoot().setOnClickListener(v -> select(tab));
        }
        b.navCamera.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        b.navSocial.setOnClickListener(v -> select(TAB_SOCIAL));
        InsetsUtil.bottomMargin(b.aiChat);
        b.aiChat.setOnClickListener(v -> startActivity(com.transformsai.nutrifitai.ui.assistant.AssistantActivity.forScreen(this, TAGS[current < 0 ? 0 : current])));
        select(s != null ? s.getInt("tab", TAB_HOME) : getIntent().getIntExtra(EXTRA_TAB, TAB_HOME));
        handleDest(getIntent());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (current != TAB_HOME) select(TAB_HOME);
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private final com.transformsai.nutrifitai.ui.common.Permissions.Asker notifAsker =
            new com.transformsai.nutrifitai.ui.common.Permissions.Asker(this, () -> this);

    /** Accounts that signed in without the signup permissions step get one notifications prompt here. */
    private void askNotificationsOnce(@Nullable Bundle s) {
        if (s != null || com.transformsai.nutrifitai.ui.common.Permissions.notificationsOn(this)
                || com.transformsai.nutrifitai.ui.common.Permissions.NOTIFICATIONS.isEmpty()) return;
        android.content.SharedPreferences sp = getSharedPreferences("permissions", MODE_PRIVATE);
        if (sp.getBoolean("main_notif_prompt", false)) return;
        sp.edit().putBoolean("main_notif_prompt", true).apply();
        notifAsker.run(com.transformsai.nutrifitai.ui.common.Permissions.NOTIFICATIONS, R.string.perm_why_notifications, () -> { });
    }

    @Override
    protected void onStart() {
        super.onStart();
        systemEvents.register(this);
        if (b != null && SystemEventsReceiver.airplaneOn(this)) onSystemEvent(SystemEventsReceiver.Event.AIRPLANE_ON);
    }

    @Override
    protected void onStop() {
        systemEvents.unregister(this);
        super.onStop();
    }

    @Override
    public void onSystemEvent(SystemEventsReceiver.Event e) {
        if (b == null) return;
        switch (e) {
            case AIRPLANE_ON:
                Ui.snack(b.getRoot(), getString(R.string.sys_airplane_on));
                com.transformsai.nutrifitai.reminders.SystemEventLog.record(this, e, getString(R.string.sys_log_airplane_on));
                break;
            case AIRPLANE_OFF:
                Ui.snack(b.getRoot(), getString(R.string.sys_airplane_off));
                SyncScheduler.requestSync(this);
                com.transformsai.nutrifitai.reminders.SystemEventLog.record(this, e, getString(R.string.sys_log_airplane_off));
                break;
            case BATTERY_LOW:
            case SAVER_ON:
                Ui.snack(b.getRoot(), getString(R.string.sys_power_saving));
                com.transformsai.nutrifitai.reminders.SystemEventLog.record(this, e, getString(R.string.sys_log_saving));
                break;
            case SAVER_OFF:
            case BATTERY_OKAY:
                com.transformsai.nutrifitai.reminders.SystemEventLog.record(this, e, getString(R.string.sys_log_normal));
                break;
            default:
                break;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_TAB)) select(intent.getIntExtra(EXTRA_TAB, TAB_HOME));
        handleDest(intent);
    }

    private void handleDest(Intent i) {
        String d = i.getStringExtra(EXTRA_DEST);
        if (d == null) return;
        i.removeExtra(EXTRA_DEST);
        if ("hydration".equals(d)) startActivity(new Intent(this, HydrationActivity.class));
        else if ("meals".equals(d)) select(TAB_MEALS);
        else if ("train".equals(d)) select(TAB_TRAIN);
        else if ("social".equals(d)) select(TAB_SOCIAL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", current);
    }

    public void select(int tab) {
        if (tab == current) {
            Fragment f = getSupportFragmentManager().findFragmentByTag(TAGS[tab]);
            if (f instanceof ScrollToTop) ((ScrollToTop) f).scrollToTop();
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);
        for (int i = 0; i < TAGS.length; i++) {
            Fragment f = fm.findFragmentByTag(TAGS[i]);
            if (i == tab) {
                if (f == null) tx.add(R.id.container, create(i), TAGS[i]);
                else tx.show(f);
            } else if (f != null) tx.hide(f);
        }
        tx.commitNow();
        current = tab;
        for (int i = 0; i < items.length; i++) {
            boolean on = BAR_TABS[i] == tab;
            items[i].getRoot().setSelected(on);
            items[i].icon.setSelected(on);
            items[i].label.setSelected(on);
        }
        b.navSocial.setSelected(tab == TAB_SOCIAL);
        androidx.core.view.ViewCompat.setStateDescription(b.navSocial, getString(tab == TAB_SOCIAL ? R.string.selected : R.string.not_selected));
    }

    private static Fragment create(int i) {
        switch (i) {
            case TAB_MEALS: return new MealsFragment();
            case TAB_TRAIN: return new com.transformsai.nutrifitai.ui.train.TrainFragment();
            case TAB_PROGRESS: return new ProgressFragment();
            case TAB_PROFILE: return new ProfileFragment();
            case TAB_SOCIAL: return new SocialFragment();
            default: return new HomeFragment();
        }
    }

    // ---------------------------------------------------------------- swipe between tabs
    private static final int[] ORDER = {TAB_HOME, TAB_MEALS, TAB_SOCIAL, TAB_TRAIN, TAB_PROGRESS, TAB_PROFILE};
    private float downX, downY;
    private long downAt;
    private boolean swipeBlocked;

    /** A horizontal swipe anywhere on a tab moves to the neighbouring tab, unless it starts on something that scrolls sideways. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (b != null) {
            int a = ev.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                downX = ev.getRawX();
                downY = ev.getRawY();
                downAt = ev.getEventTime();
                swipeBlocked = scrollsSideways(b.container, (int) downX, (int) downY)
                        || inside(b.nav, downX, downY) || ev.getPointerCount() > 1;
            } else if (a == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                swipeBlocked = true;
            } else if (a == android.view.MotionEvent.ACTION_UP && !swipeBlocked) {
                float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                float min = 90 * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > min && Math.abs(dx) > 2.2f * Math.abs(dy) && ev.getEventTime() - downAt < 700) {
                    int pos = 0;
                    for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == current) pos = i;
                    int next = pos + (dx < 0 ? 1 : -1);
                    if (next >= 0 && next < ORDER.length) {
                        ev.setAction(android.view.MotionEvent.ACTION_CANCEL); // the finger was swiping, not tapping
                        super.dispatchTouchEvent(ev);
                        select(ORDER[next]);
                        return true;
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private static boolean inside(View v, float x, float y) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return x >= xy[0] && x <= xy[0] + v.getWidth() && y >= xy[1] && y <= xy[1] + v.getHeight();
    }

    /** True when a visible view under (x, y) can scroll horizontally (carousels, charts, chip rows). */
    private static boolean scrollsSideways(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE || !inside(v, x, y)) return false;
        if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) return true;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) if (scrollsSideways(g.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    public interface ScrollToTop {
        void scrollToTop();
    }
}
