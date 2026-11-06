package com.transformsai.nutrifitai.ui.train;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityWorkoutBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.VoiceCoach;
import com.transformsai.nutrifitai.fitness.WorkoutEngine;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.fitness.pose.ExerciseProfile;
import com.transformsai.nutrifitai.fitness.pose.ExerciseSpec;
import com.transformsai.nutrifitai.fitness.pose.FormCoach;
import com.transformsai.nutrifitai.fitness.pose.FrameConverter;
import com.transformsai.nutrifitai.fitness.pose.MotionTracker;
import com.transformsai.nutrifitai.fitness.pose.Pose;
import com.transformsai.nutrifitai.fitness.pose.PoseEstimator;
import com.transformsai.nutrifitai.fitness.pose.PoseModelStore;
import com.transformsai.nutrifitai.fitness.pose.PoseModels;
import com.transformsai.nutrifitai.social.ChallengeSession;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.social.ChallengeResultActivity;
import com.transformsai.nutrifitai.ui.social.ComposePostActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice-guided workout player. Camera mode: CameraX frames -> MediaPipe pose (on device, nothing is
 * uploaded) -> {@link MotionTracker} -> reps/holds in the {@link WorkoutEngine}. Manual mode: tap +1
 * per rep, holds are timed. The camera and pose model are released in onStop and re-created in onStart.
 */
public class WorkoutActivity extends BaseActivity implements WorkoutEngine.Listener {
    @Override
    protected boolean alwaysDark() {
        return true;
    }

    private static final String EXTRA_PLAN = "plan_id";
    private static final String EXTRA_CAMERA = "camera";
    private static final String EXTRA_CHALLENGE = "challenge_id";

    private ActivityWorkoutBinding b;
    private ExerciseLibrary lib;
    private Map<String, ExerciseProfile> profiles;
    private WorkoutPlan plan;
    private WorkoutEngine engine;
    private VoiceCoach voice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (engine != null) engine.tick(now);
            if (challenge != null && now - lastBoardTick >= 1000) {
                lastBoardTick = now;
                challenge.tick();
            }
            main.postDelayed(this, 100);
        }
    };

    private boolean cameraMode, frontCamera = true, cameraBound, finished;
    private long lastBoardTick;
    private ProcessCameraProvider provider;
    private ExecutorService cameraExec, submitExec;
    private final FrameConverter converter = new FrameConverter();
    private volatile PoseEstimator estimator;
    private volatile MotionTracker tracker;
    private String trackerFor;
    private long startedWall;
    private int lastIndex = -1;
    private final Runnable hideFault = () -> {
        if (b != null) b.fault.setVisibility(View.GONE);
    };
    private WorkoutEngine.Phase lastPhase;
    /** Set in challenge mode: live scoring and friends' tiles. */
    @Nullable private ChallengeOverlay challenge;
    @Nullable private String challengeId;
    private ClipRecorder clips;

    private final com.transformsai.nutrifitai.ui.common.Permissions.Asker micAsker = new com.transformsai.nutrifitai.ui.common.Permissions.Asker(this, () -> this);

    /** Challenge call: asks for the microphone when the player unmutes for the first time. */
    void requestMic() {
        micAsker.run(com.transformsai.nutrifitai.ui.common.Permissions.MIC, R.string.perm_why_mic, () -> {
            if (challenge != null) challenge.micGranted();
        });
    }

    private final ActivityResultLauncher<String> camPerm = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else if (challengeId != null) {
                    Ui.snack(b.getRoot(), getString(R.string.challenge_needs_camera));
                    main.postDelayed(this::finish, 2500);
                } else {
                    setCameraMode(false);
                    Snackbar sb = Ui.snack(b.getRoot(), getString(R.string.cam_perm_workout));
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        sb.setAction(R.string.open_settings, v -> Ui.openAppSettings(this));
                    }
                }
            });

    public static Intent intent(Context c, String planId, boolean camera) {
        return new Intent(c, WorkoutActivity.class).putExtra(EXTRA_PLAN, planId).putExtra(EXTRA_CAMERA, camera);
    }

    /** A live challenge: always camera-tracked, scored, with the other players shown as tiles. */
    public static Intent forChallenge(Context c, String challengeId) {
        return new Intent(c, WorkoutActivity.class).putExtra(EXTRA_CHALLENGE, challengeId).putExtra(EXTRA_CAMERA, true);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityWorkoutBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.topBar, true, false);
        InsetsUtil.topMargin(b.statusBox); // keeps the tracking hint below the top bar on every device
        InsetsUtil.topMargin(b.muscleCard);
        InsetsUtil.topMargin(b.livePanel);
        InsetsUtil.bottomMargin(b.panel);
        cameraMode = getIntent().getBooleanExtra(EXTRA_CAMERA, true);
        challengeId = getIntent().getStringExtra(EXTRA_CHALLENGE);
        clips = new ClipRecorder(this, clipListener);
        voice = new VoiceCoach(this);
        voice.setMuted(!graph().prefs.voiceCoach());
        cameraExec = Executors.newSingleThreadExecutor();
        submitExec = Executors.newSingleThreadExecutor();
        setControlsEnabled(false);
        b.ring.setColors(ContextCompat.getColor(this, R.color.nl_lime), ContextCompat.getColor(this, R.color.nl_lime_bright));
        wireControls();
        String planId = getIntent().getStringExtra(EXTRA_PLAN);
        graph().runIo(() -> {
            lib = ExerciseLibrary.get(this);
            profiles = PoseModels.get(this);
            ClipRecorder.prune(this);
            if (challengeId != null) {
                String me = graph().cards.myName();
                main.post(() -> graph().challenges.load(challengeId, r -> {
                    if (isDestroyed()) return;
                    if (!r.ok()) {
                        Ui.snack(b.getRoot(), r.error);
                        main.postDelayed(this::finish, 2000);
                        return;
                    }
                    plan = new WorkoutPlan();
                    WorkoutPlan.parseSteps(r.value.stepsJson, plan, lib);
                    plan.id = r.value.id;
                    plan.title = r.value.title;
                    if (plan.steps.isEmpty()) {
                        finish();
                        return;
                    }
                    String uid = graph().session.requireUid();
                    challenge = new ChallengeOverlay(new ChallengeSession(challengeId, uid, me,
                            plan.estimatedSeconds(lib) * 1000L), b, this, challengeId, uid);
                    begin();
                }));
                return;
            }
            main.post(() -> graph().workouts.loadPlan(planId, r -> {
                if (isDestroyed()) return;
                if (!r.ok() || r.value.steps.isEmpty()) {
                    finish();
                    return;
                }
                plan = r.value;
                plan.applyRest(graph().prefs.restSeconds());
                begin();
            }));
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmEnd();
            }
        });
    }

    private void begin() {
        if (isFinishing() || isDestroyed()) return; // a late callback for a screen that is already gone
        engine = new WorkoutEngine(plan, lib, this);
        startedWall = System.currentTimeMillis();
        boolean anyCamera = plan.cameraSteps(lib) > 0;
        if (!anyCamera) cameraMode = false;
        setCameraMode(cameraMode);
        setControlsEnabled(true);
        engine.start(SystemClock.uptimeMillis());
        main.post(ticker);
    }

    private void wireControls() {
        Ui.onClick(b.close, v -> confirmEnd());
        b.voice.setOnClickListener(v -> {
            boolean mute = !voice.muted();
            voice.setMuted(mute);
            graph().prefs.setVoiceCoach(!mute);
            renderVoice();
        });
        b.mode.setOnClickListener(v -> setCameraMode(!cameraMode));
        b.record.setOnClickListener(v -> toggleRecording());
        b.flip.setOnClickListener(v -> {
            frontCamera = !frontCamera;
            if (cameraMode) bindCamera();
        });
        b.plusOne.setOnClickListener(v -> {
            if (engine == null) return;
            if (graph().prefs.haptics()) Ui.haptic(v);
            engine.addRep(SystemClock.uptimeMillis());
        });
        b.addRest.setOnClickListener(v -> {
            if (engine != null) engine.extendRest(15, SystemClock.uptimeMillis());
        });
        b.resume.setOnClickListener(v -> {
            if (engine != null) engine.resume(SystemClock.uptimeMillis());
        });
        b.restart.setOnClickListener(v -> {
            if (engine == null) return;
            engine.restartStep(SystemClock.uptimeMillis());
            trackerFor = null;
            ensureTracker();
        });
        b.endNow.setOnClickListener(v -> {
            if (engine != null) engine.finishEarly(SystemClock.uptimeMillis());
        });
        b.skip.setOnClickListener(v -> {
            if (engine != null) engine.skip(SystemClock.uptimeMillis());
        });
        b.pause.setOnClickListener(v -> {
            if (engine == null) return;
            long now = SystemClock.uptimeMillis();
            if (engine.paused()) engine.resume(now);
            else engine.pause(now);
        });
        renderVoice();
    }

    private void setControlsEnabled(boolean on) {
        b.plusOne.setEnabled(on);
        b.skip.setEnabled(on);
        b.pause.setEnabled(on);
        b.addRest.setEnabled(on);
    }

    private void renderVoice() {
        boolean muted = voice != null && voice.muted();
        b.voice.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        b.voice.setContentDescription(getString(muted ? R.string.cd_voice_off : R.string.cd_voice_on));
    }

    // ------------------------------------------------------------------ camera mode
    private void setCameraMode(boolean on) {
        cameraMode = on;
        b.mode.setText(on ? R.string.mode_camera : R.string.mode_manual);
        b.mode.setContentDescription(getString(on ? R.string.mode_camera : R.string.mode_manual));
        b.preview.setVisibility(on ? View.VISIBLE : View.GONE);
        b.overlay.setVisibility(on && graph().prefs.showSkeleton() ? View.VISIBLE : View.GONE);
        b.flip.setVisibility(on ? View.VISIBLE : View.GONE);
        b.record.setVisibility(on ? View.VISIBLE : View.GONE);
        b.demo.setVisibility(on ? View.GONE : View.VISIBLE);
        if (engine != null) engine.setCameraMode(on && currentIsCamera());
        if (on) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
            else camPerm.launch(Manifest.permission.CAMERA);
        } else {
            stopCamera();
            b.overlay.setPose(null, true, true);
        }
        render();
    }

    private boolean currentIsCamera() {
        if (engine == null) return false;
        Exercise x = engine.exercise();
        return x != null && x.camera && ExerciseSpec.forId(x.trackId()) != null;
    }

    private void startCamera() {
        if (!cameraMode || isFinishing()) return;
        if (estimator == null) {
            cameraExec.execute(() -> {
                try {
                    estimator = createEstimator();
                } catch (Throwable t) {
                    main.post(() -> {
                        Ui.snack(b.getRoot(), getString(R.string.pose_model_error));
                        setCameraMode(false);
                    });
                }
            });
        }
        bindCamera();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                if (!cameraMode || isFinishing() || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
                int rotation = b.preview.getDisplay() != null ? b.preview.getDisplay().getRotation() : Surface.ROTATION_0;
                CameraSelector sel = frontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                if (!provider.hasCamera(sel)) {
                    frontCamera = !frontCamera;
                    sel = frontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                }
                ResolutionSelector res = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
                ImageAnalysis analysis = new ImageAnalysis.Builder().setResolutionSelector(res).setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();
                analysis.setAnalyzer(cameraExec, this::analyze);
                provider.unbindAll();
                if (clips.wanted()) {
                    try {
                        provider.bindToLifecycle(this, sel, preview, analysis, clips.useCase());
                    } catch (IllegalArgumentException tooMany) {
                        // this camera cannot record while the pose tracker runs: keep tracking, skip the clip
                        clips.release();
                        Ui.snack(b.getRoot(), getString(R.string.clip_unsupported));
                        provider.unbindAll();
                        provider.bindToLifecycle(this, sel, preview, analysis);
                    }
                } else {
                    provider.bindToLifecycle(this, sel, preview, analysis);
                }
                preview.setSurfaceProvider(b.preview.getSurfaceProvider());
                cameraBound = true;
                if (clips.wanted() && !clips.recording()) clips.start();
            } catch (Exception e) {
                Ui.snack(b.getRoot(), getString(R.string.camera_unavailable));
                setCameraMode(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (provider != null) provider.unbindAll();
        cameraBound = false;
        PoseEstimator e = estimator;
        estimator = null;
        if (e != null) cameraExec.execute(e::close);
    }

    /** Camera thread: convert every frame; the submit thread feeds the newest one to the model. */
    private void analyze(ImageProxy proxy) {
        long arrival = SystemClock.uptimeMillis();
        try {
            if (estimator == null) return;
            converter.convert(proxy, arrival);
        } finally {
            proxy.close();
        }
        try {
            submitExec.execute(() -> {
                PoseEstimator e = estimator;
                if (e == null || e.isBusy()) return;
                Bitmap bmp = converter.takePending();
                ChallengeOverlay ch = challenge;
                if (bmp != null && ch != null) ch.frame(bmp); // copied for the call before the model reads it
                if (bmp != null) e.submit(bmp, converter.takenArrivalMs());
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private final PoseEstimator.Listener poseListener = new PoseEstimator.Listener() {
        @Override
        public void onPose(@Nullable Pose pose, long ts) {
            converter.release();
            MotionTracker t = tracker;
            MotionTracker.Frame f = t == null ? null : t.update(pose, SystemClock.uptimeMillis());
            boolean good = f == null || f.status == MotionTracker.Status.READY || f.status == MotionTracker.Status.MOVING
                    || f.status == MotionTracker.Status.HOLDING;
            b.overlay.setPose(pose, frontCamera, good);
            ChallengeOverlay ch = challenge;
            if (ch != null && pose != null) main.post(() -> ch.pose(pose, frontCamera));
            if (f != null) main.post(() -> onFrame(f));
        }

        @Override
        public void onError(String message) {
            converter.release();
        }
    };

    private void onFrame(MotionTracker.Frame f) {
        if (engine == null || !cameraMode || !currentIsCamera()) return;
        long now = SystemClock.uptimeMillis();
        if (engine.phase() == WorkoutEngine.Phase.WORK) {
            if (f.repCompleted) {
                engine.addRep(now);
                if (challenge != null) challenge.rep();
                if (graph().prefs.haptics()) Ui.haptic(b.reps);
            }
            if (f.repRejected && f.fault != null) {
                engine.rejectRep(f.fault.cue, now);
                if (challenge != null) challenge.fault();
                showFault(getString(R.string.no_rep_fault, f.fault.cue));
            }
            if (f.partialRep) {
                engine.partialRep(tracker.spec().depthCue, now);
                if (challenge != null) challenge.fault();
                showFault(getString(R.string.no_rep_fault, tracker.spec().depthCue));
            }
            if (engine.isHold()) {
                engine.setInPosition(f.inPosition, now);
                if (f.fault != null) {
                    engine.formCue(f.fault.cue, now);
                    if (challenge != null) challenge.holdFault();
                    showFault(f.fault.cue);
                }
            }
        }
        renderStatus(f);
    }

    private void renderStatus(@Nullable MotionTracker.Frame f) {
        if (!cameraMode || !currentIsCamera()) {
            b.status.setVisibility(View.GONE);
            return;
        }
        b.status.setVisibility(View.VISIBLE);
        ExerciseSpec spec = tracker == null ? null : tracker.spec();
        String viewHint = spec == null ? "" : getString("side".equals(spec.view) ? R.string.view_side : R.string.view_front);
        if (f == null || f.status == MotionTracker.Status.NO_PERSON) b.status.setText(R.string.status_no_person);
        else if (f.status == MotionTracker.Status.ADJUST) b.status.setText(engine != null && engine.isHold()
                ? getString(R.string.status_out) : getString(R.string.status_adjust, viewHint));
        else if (f.status == MotionTracker.Status.HOLDING) b.status.setText(R.string.status_holding);
        else b.status.setText(R.string.status_ready);
    }

    /** One tracker per exercise step, trained profile from the bundled samples. */
    private void ensureTracker() {
        if (engine == null) return;
        Exercise x = engine.exercise();
        String key = engine.index() + ":" + (x == null ? "" : x.id);
        if (key.equals(trackerFor)) return;
        trackerFor = key;
        ExerciseProfile p = x == null || profiles == null ? null : profiles.get(x.trackId());
        tracker = p == null ? null : new MotionTracker(p,
                new FormCoach(x.trackId(), FormCoach.level(graph().prefs.coachStrictness())));
        engine.setCameraMode(cameraMode && p != null && x.camera);
        if (cameraMode && p != null && x.camera) engine.setInPosition(false, SystemClock.uptimeMillis());
    }

    // ------------------------------------------------------------------ engine callbacks
    @Override
    public void speak(String text, boolean interrupt) {
        if (voice != null) voice.say(text, interrupt);
        if (!interrupt && b != null) b.cue.announceForAccessibility(text);
    }

    @Override
    public void changed() {
        if (engine == null || b == null) return;
        if (engine.phase() == WorkoutEngine.Phase.DONE) {
            finishWorkout();
            return;
        }
        if (engine.index() != lastIndex || engine.phase() != lastPhase) {
            lastIndex = engine.index();
            lastPhase = engine.phase();
            ensureTracker();
            renderStep();
        }
        if (challenge != null) challenge.progress(engine);
        render();
    }

    private void renderStep() {
        Exercise x = engine.exercise();
        boolean rest = engine.phase() == WorkoutEngine.Phase.REST;
        Exercise shown = x;
        b.exercise.setText(x == null ? "" : x.name);
        b.cue.setText(engine.step().cue != null && !engine.step().cue.isEmpty() ? engine.step().cue
                : x != null && !x.cues.isEmpty() ? x.cues.get(0) : "");
        if (rest) {
            b.exercise.setText(getString(R.string.next_up, x == null ? "" : x.name));
        }
        b.thumb.setExercise(shown, true);
        b.demo.setExercise(shown, true);
        b.muscles.setIntensities(shown == null ? null : shown.targets);
        // camera mode, but this move is not camera-tracked: show its animation instead of the raw preview
        boolean tracked = cameraMode && currentIsCamera();
        b.demo.setVisibility(tracked ? View.GONE : View.VISIBLE);
        b.preview.setVisibility(tracked ? View.VISIBLE : View.INVISIBLE);
        b.fault.setVisibility(View.GONE);
        b.stepOf.setText(getString(R.string.step_of, Math.min(engine.index() + 1, engine.stepCount()), engine.stepCount()));
        b.progress.setProgressCompat((int) (100f * engine.index() / engine.stepCount()), true);
        boolean showMode = plan.cameraSteps(lib) > 0 && challenge == null; // challenges are always camera-scored
        b.mode.setVisibility(showMode ? View.VISIBLE : View.GONE);
    }

    private void render() {
        if (engine == null) return;
        long now = SystemClock.uptimeMillis();
        WorkoutEngine.Phase ph = engine.phase();
        boolean paused = engine.paused();
        b.pause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        b.pause.setContentDescription(getString(paused ? R.string.resume : R.string.pause));
        if (paused != (b.pauseOverlay.getVisibility() == View.VISIBLE)) {
            b.pauseOverlay.setVisibility(paused ? View.VISIBLE : View.GONE);
            if (paused) b.resume.requestFocus();
        }
        if (paused) {
            Exercise cx = engine.exercise();
            String what = cx == null ? "" : cx.name;
            b.pauseSub.setText(ph == WorkoutEngine.Phase.REST ? getString(R.string.next_up, what)
                    : engine.isHold() ? getString(R.string.pause_sub_hold, what, (int) (engine.heldMs() / 1000), engine.step().target)
                    : getString(R.string.pause_sub_reps, what, engine.reps(), engine.step().target));
            b.restart.setVisibility(ph == WorkoutEngine.Phase.WORK ? View.VISIBLE : View.GONE);
        }
        b.countdown.setVisibility(ph == WorkoutEngine.Phase.COUNTDOWN && !paused ? View.VISIBLE : View.GONE);
        if (ph == WorkoutEngine.Phase.COUNTDOWN) b.countdown.setText(String.valueOf(Math.max(1, (engine.remainingMs(now) + 999) / 1000)));
        String phaseLabel = paused ? getString(R.string.paused)
                : ph == WorkoutEngine.Phase.COUNTDOWN ? getString(R.string.get_ready)
                : ph == WorkoutEngine.Phase.REST ? getString(R.string.rest) : "GO";
        b.phase.setText(phaseLabel.toUpperCase(java.util.Locale.getDefault()));
        boolean hold = engine.isHold();
        if (ph == WorkoutEngine.Phase.REST) {
            WorkoutPlan.Step prev = plan.steps.get(Math.max(0, engine.index() - 1));
            long left = engine.remainingMs(now);
            showRing(true);
            b.seconds.setText(String.valueOf((left + 999) / 1000));
            int total = Math.max(engine.restTotalSeconds(), prev.restSeconds);
            b.ring.setProgress(total <= 0 ? 0 : Math.min(1f, left / (total * 1000f)), false);
            centre(CENTRE_REST);
        } else if (hold) {
            long target = engine.step().target * 1000L;
            long left = Math.max(0, target - engine.heldMs());
            showRing(true);
            b.seconds.setText(String.valueOf((left + 999) / 1000));
            b.ring.setProgress(target == 0 ? 0 : engine.heldMs() / (float) target, false);
            b.hint.setText(cameraMode && currentIsCamera() ? getString(R.string.camera_hint_hold) : getString(R.string.manual_hint_hold));
            centre(CENTRE_HINT);
        } else {
            showRing(false);
            b.reps.setText(String.valueOf(engine.reps()));
            b.target.setText(getString(R.string.reps_slash, engine.step().target));
            boolean auto = cameraMode && currentIsCamera();
            b.hint.setText(auto ? getString(R.string.camera_hint_reps, engine.rejected()) : getString(R.string.manual_hint_reps));
            centre(ph == WorkoutEngine.Phase.WORK && !auto && challenge == null ? CENTRE_PLUS : CENTRE_HINT);
        }
        if (!cameraMode || !currentIsCamera()) b.status.setVisibility(View.GONE);
        else if (tracker == null) renderStatus(null);
    }

    private static final int CENTRE_PLUS = 0, CENTRE_REST = 1, CENTRE_HINT = 2;

    /** The panel's centre slot: +1 button (manual reps), +15 s (rest) or a short hint (camera / holds). */
    private void centre(int what) {
        b.plusOne.setVisibility(what == CENTRE_PLUS ? View.VISIBLE : View.GONE);
        b.addRest.setVisibility(what == CENTRE_REST ? View.VISIBLE : View.GONE);
        b.hint.setVisibility(what == CENTRE_HINT ? View.VISIBLE : View.GONE);
    }

    private void showFault(String text) {
        b.fault.setText(text);
        b.fault.setVisibility(View.VISIBLE);
        b.overlay.flashFault();
        main.removeCallbacks(hideFault);
        main.postDelayed(hideFault, 2600);
    }

    /** Pose model from Settings > Advanced (auto / lite / full / heavy) and processor (auto / GPU / CPU). */
    private PoseEstimator createEstimator() {
        String pref = graph().prefs.poseModel();
        boolean tryGpu = !"cpu".equals(graph().prefs.poseDelegate());
        if (PoseModelStore.AUTO.equals(pref)) {
            PoseEstimator e = PoseEstimator.create(this, PoseEstimator.MODEL_FULL, tryGpu, poseListener);
            if (!e.gpu) { // CPU only: the lite model keeps the frame rate usable
                e.close();
                e = PoseEstimator.create(this, PoseEstimator.MODEL_LITE, false, poseListener);
            }
            return e;
        }
        String model = PoseModelStore.resolve(this, pref, true);
        String path = PoseModelStore.HEAVY.equals(model) ? PoseModelStore.heavyFile(this).getAbsolutePath()
                : PoseModelStore.assetPath(model);
        return PoseEstimator.create(this, path, tryGpu, poseListener);
    }

    private void showRing(boolean ring) {
        b.ringBox.setVisibility(ring ? View.VISIBLE : View.GONE);
        b.repsBox.setVisibility(ring ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ clips
    private void toggleRecording() {
        if (clips.recording()) {
            clips.stop();
            return;
        }
        clips.want();
        bindCamera(); // adds the video use case, then starts recording
        b.record.setImageResource(R.drawable.ic_stop);
        b.record.setContentDescription(getString(R.string.cd_stop_clip));
    }

    private final ClipRecorder.Listener clipListener = new ClipRecorder.Listener() {
        @Override
        public void onTick(long elapsedMs) {
            if (b == null) return;
            b.recChip.setVisibility(View.VISIBLE);
            b.recChip.setText(getString(R.string.clip_recording, elapsedMs / 1000, ClipRecorder.MAX_MS / 1000));
        }

        @Override
        public void onFinished(@Nullable File clip) {
            if (b == null) return;
            b.recChip.setVisibility(View.GONE);
            b.record.setImageResource(R.drawable.ic_record);
            b.record.setContentDescription(getString(R.string.cd_record_clip));
            if (clip == null) return;
            Ui.snack(b.getRoot(), getString(R.string.clip_saved)).setAction(R.string.share, v ->
                    startActivity(ComposePostActivity.intent(WorkoutActivity.this, Post.CLIP, clip.getAbsolutePath())));
            if (cameraMode && cameraBound && !finished) bindCamera(); // back to preview + tracking only
        }
    };

    // ------------------------------------------------------------------ finish
    private void confirmEnd() {
        if (engine == null) {
            finish();
            return;
        }
        engine.pause(SystemClock.uptimeMillis());
        new MaterialAlertDialogBuilder(this).setTitle(R.string.end_workout).setMessage(R.string.end_workout_body)
                .setPositiveButton(R.string.end, (d, w) -> engine.finishEarly(SystemClock.uptimeMillis()))
                .setNegativeButton(R.string.keep_going, (d, w) -> engine.resume(SystemClock.uptimeMillis()))
                .setCancelable(false).show();
    }

    private void finishWorkout() {
        if (finished) return;
        finished = true;
        main.removeCallbacks(ticker);
        clips.release();
        stopCamera();
        ChallengeOverlay ch = challenge;
        if (ch != null) {
            int score = ch.finish(engine);
            boolean done = engine.results().size() >= engine.stepCount();
            graph().challenges.reportResult(challengeId, score, ch.session().reps(), ch.session().faults(), ch.session().elapsedMs(), done);
        }
        graph().workouts.saveSession(plan, engine.results(), startedWall, System.currentTimeMillis(), r -> {
            if (ch != null) startActivity(ChallengeResultActivity.intent(this, challengeId));
            else if (r.ok()) startActivity(WorkoutSummaryActivity.forSession(this, r.value.id));
            else Ui.snack(b.getRoot(), getString(R.string.nothing_saved));
            main.postDelayed(this::finish, r.ok() || ch != null ? 0 : 1500);
        });
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onStart() {
        super.onStart();
        if (engine != null && cameraMode && !cameraBound && !finished
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onStop() {
        // Release the camera and the model whenever the screen is not visible; pause the session.
        if (engine != null && !finished && !isChangingConfigurations()) engine.pause(SystemClock.uptimeMillis());
        clips.release();
        stopCamera();
        if (challenge != null) challenge.cameraStopped();
        if (voice != null) voice.stop();
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (cameraMode && cameraBound) bindCamera(); // new display rotation for the analysis frames
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopCamera();
        if (challenge != null) challenge.leave();
        if (voice != null) voice.release();
        if (cameraExec != null) cameraExec.shutdown();
        if (submitExec != null) submitExec.shutdownNow();
        super.onDestroy();
    }
}
