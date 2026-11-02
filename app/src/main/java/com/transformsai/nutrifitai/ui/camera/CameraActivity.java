package com.transformsai.nutrifitai.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityCameraBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Programs;
import com.transformsai.nutrifitai.ui.food.CustomFoodActivity;
import com.transformsai.nutrifitai.ui.food.FoodDetailActivity;
import com.transformsai.nutrifitai.ui.train.WorkoutActivity;
import com.transformsai.nutrifitai.ui.train.WorkoutLauncher;
import com.transformsai.nutrifitai.ui.widget.ExerciseAnimView;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.analysis.AnalysisActivity;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.ImageUtils;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.time.LocalTime;

/**
 * The camera hub, with three modes (tap the pill or swipe):
 * <ul>
 * <li>Food (default): CameraX capture or photo-picker import for AI analysis. Only the confirmed,
 *     processed still image is analysed; no frames are streamed anywhere.</li>
 * <li>Workout (swipe right): pick a camera-tracked exercise or today's program and open the live
 *     workout camera.</li>
 * <li>Barcode (swipe left): ML Kit reads EAN/UPC codes on the device; the product is looked up in
 *     Room, then Open Food Facts.</li>
 * </ul>
 * The camera is bound to this Activity's lifecycle, so it is released automatically in onStop.
 */
public class CameraActivity extends BaseActivity {
    @Override
    protected boolean alwaysDark() {
        return true;
    }

    private ActivityCameraBinding b;
    private ProcessCameraProvider provider;
    private ImageCapture capture;
    private int lens = CameraSelector.LENS_FACING_BACK;
    private int flash = ImageCapture.FLASH_MODE_OFF;
    private String photoPath;
    private String meal;
    private boolean askedOnce;
    static final int MODE_WORKOUT = 0, MODE_FOOD = 1, MODE_BARCODE = 2;
    private int mode = MODE_FOOD;
    private ImageAnalysis analysis;
    private BarcodeScanner scanner;
    private final java.util.concurrent.ExecutorService scanExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean barcodeHandled;
    private String lastCode;
    private int sameCount;

    private final ActivityResultLauncher<String> permission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                askedOnce = true;
                refreshPermissionUi();
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> picker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPicked);

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.topBar, true, false);
        InsetsUtil.padding(b.controls, false, true);
        InsetsUtil.padding(b.reviewTop, true, false);
        InsetsUtil.padding(b.reviewPanel, false, true);
        InsetsUtil.padding(b.permPanel, true, true);
        meal = s != null ? s.getString("meal") : null;
        if (meal == null) meal = MealLabels.forHour(LocalTime.now().getHour());
        if (s != null) {
            photoPath = s.getString("photo");
            askedOnce = s.getBoolean("asked");
        }
        b.close.setOnClickListener(v -> finish());
        b.permClose.setOnClickListener(v -> finish());
        b.gallery.setOnClickListener(v -> pick());
        b.permGallery.setOnClickListener(v -> pick());
        b.permAllow.setOnClickListener(v -> {
            if (!askedOnce || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                permission.launch(Manifest.permission.CAMERA);
            } else Ui.openAppSettings(this);
        });
        Ui.onClick(b.shutter, v -> takePhoto());
        b.flip.setOnClickListener(v -> {
            lens = lens == CameraSelector.LENS_FACING_BACK ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            bind();
        });
        b.flash.setOnClickListener(v -> {
            flash = flash == ImageCapture.FLASH_MODE_OFF ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
            b.flash.setImageResource(flash == ImageCapture.FLASH_MODE_ON ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
            if (capture != null) capture.setFlashMode(flash);
        });
        b.retake.setOnClickListener(v -> discardPhoto(true));
        b.remove.setOnClickListener(v -> discardPhoto(false));
        b.mealChip.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.choose_meal)
                .setItems(getResources().getStringArray(R.array.meal_types), (d, w) -> {
                    meal = MealLabels.TYPES[w];
                    b.mealChip.setText(MealLabels.label(this, meal));
                }).show());
        Ui.onClick(b.analyse, v -> analyse());
        b.mealChip.setText(MealLabels.label(this, meal));
        if (s != null) mode = s.getInt("mode", MODE_FOOD);
        else if (getIntent().hasExtra(EXTRA_MODE)) mode = getIntent().getIntExtra(EXTRA_MODE, MODE_FOOD);
        b.modeWorkout.setOnClickListener(v -> setMode(MODE_WORKOUT));
        b.modeFood.setOnClickListener(v -> setMode(MODE_FOOD));
        b.modeBarcode.setOnClickListener(v -> setMode(MODE_BARCODE));
        Ui.onClick(b.startToday, v -> startToday());
        renderMode();
        if (photoPath != null && new File(photoPath).exists()) showReview();
    }

    public static final String EXTRA_MODE = "camera_mode";

    /** Every touch also goes to the swipe detector, whichever view (camera surface, frame) is under it. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (b != null) trackSwipe(ev);
        return super.dispatchTouchEvent(ev);
    }

    private float downX, downY;
    private long downAt;

    /** Horizontal swipe anywhere on the camera screen: right = Workout side, left = Barcode side. */
    private void trackSwipe(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = ev.getRawX();
            downY = ev.getRawY();
            downAt = ev.getEventTime();
            return;
        }
        if (ev.getActionMasked() != MotionEvent.ACTION_UP) return;
        if (b.review.getVisibility() == View.VISIBLE || b.permPanel.getVisibility() == View.VISIBLE) return;
        float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
        float min = 70 * getResources().getDisplayMetrics().density;
        if (Math.abs(dx) < min || Math.abs(dx) < Math.abs(dy) * 1.5f || ev.getEventTime() - downAt > 900) return;
        // horizontal scrolling of the exercise list is not a mode swipe
        if (b.workoutPanel.getVisibility() == View.VISIBLE && downY >= locationY(b.workoutPanel)
                && downY <= locationY(b.workoutPanel) + b.workoutPanel.getHeight()) return;
        setMode(Math.max(MODE_WORKOUT, Math.min(MODE_BARCODE, mode + (dx > 0 ? -1 : 1))));
    }

    private static int locationY(View v) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return xy[1];
    }

    // ------------------------------------------------------------------ modes
    private void setMode(int m) {
        if (m == mode) return;
        mode = m;
        barcodeHandled = false;
        lastCode = null;
        renderMode();
        if (b.permPanel.getVisibility() != View.VISIBLE && photoPath == null) bind();
        b.modes.announceForAccessibility(getString(m == MODE_WORKOUT ? R.string.cam_mode_workout
                : m == MODE_BARCODE ? R.string.cam_mode_barcode : R.string.cam_mode_food));
    }

    private void renderMode() {
        TextView[] tabs = {b.modeWorkout, b.modeFood, b.modeBarcode};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == mode;
            tabs[i].setBackgroundResource(on ? R.drawable.bg_pill : 0);
            if (on) tabs[i].setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.nl_lime));
            tabs[i].setTextColor(ContextCompat.getColor(this, on ? R.color.nl_on_lime : R.color.nl_text));
            tabs[i].setSelected(on);
        }
        b.hint.setText(mode == MODE_WORKOUT ? R.string.cam_hint_workout : mode == MODE_BARCODE ? R.string.cam_hint_barcode : R.string.cam_hint);
        b.shutter.setVisibility(mode == MODE_FOOD ? View.VISIBLE : View.INVISIBLE);
        b.gallery.setImageResource(mode == MODE_BARCODE ? R.drawable.ic_edit : R.drawable.ic_gallery);
        b.gallery.setContentDescription(getString(mode == MODE_BARCODE ? R.string.cam_enter_code : R.string.cam_gallery));
        b.gallery.setVisibility(mode == MODE_WORKOUT ? View.INVISIBLE : View.VISIBLE);
        b.gallery.setOnClickListener(v -> {
            if (mode == MODE_BARCODE) enterCode();
            else pick();
        });
        b.frame.setVisibility(mode == MODE_WORKOUT ? View.GONE : View.VISIBLE);
        b.workoutPanel.setVisibility(mode == MODE_WORKOUT ? View.VISIBLE : View.GONE);
        if (mode == MODE_WORKOUT && b.workoutList.getChildCount() == 0) fillWorkouts();
    }

    private void fillWorkouts() {
        graph().runIo(() -> {
            java.util.List<Exercise> list = ExerciseLibrary.get(this).cameraExercises();
            graph().main.post(() -> {
                if (b == null) return;
                b.workoutList.removeAllViews();
                float d = getResources().getDisplayMetrics().density;
                for (Exercise x : list) {
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                    item.setPadding((int) (4 * d), 0, (int) (4 * d), 0);
                    ExerciseAnimView anim = new ExerciseAnimView(this, null);
                    anim.setBackgroundResource(R.drawable.bg_photo_round);
                    anim.setExercise(x, false);
                    item.addView(anim, new LinearLayout.LayoutParams((int) (76 * d), (int) (76 * d)));
                    TextView t = new TextView(this, null, 0, R.style.NL_Text_Caption);
                    t.setText(x.name);
                    t.setMaxLines(2);
                    t.setGravity(android.view.Gravity.CENTER);
                    t.setTextColor(ContextCompat.getColor(this, R.color.nl_text));
                    item.addView(t, new LinearLayout.LayoutParams((int) (84 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
                    item.setContentDescription(getString(R.string.cd_start_exercise, x.name));
                    item.setBackgroundResource(android.R.drawable.list_selector_background);
                    item.setOnClickListener(v -> WorkoutLauncher.startSingle(this, x, 3, true, true));
                    b.workoutList.addView(item);
                }
            });
        });
    }

    private void startToday() {
        com.transformsai.nutrifitai.fitness.WorkoutPlan p = Programs.forDay(java.time.LocalDate.now().getDayOfYear(), graph().prefs.fitnessLevel());
        startActivity(WorkoutActivity.intent(this, p.id, true));
        finish();
    }

    // ------------------------------------------------------------------ barcode
    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analyseBarcode(ImageProxy proxy) {
        if (barcodeHandled || scanner == null || proxy.getImage() == null) {
            proxy.close();
            return;
        }
        android.media.Image img = proxy.getImage();
        InputImage in = InputImage.fromMediaImage(img, proxy.getImageInfo().getRotationDegrees());
        scanner.process(in).addOnSuccessListener(codes -> {
            for (Barcode bc : codes) {
                String v = bc.getRawValue();
                if (!com.transformsai.nutrifitai.data.remote.OpenFoodFacts.validCode(v)) continue;
                // require the same code twice in a row so a partial read never triggers a lookup
                if (v.equals(lastCode)) sameCount++;
                else {
                    lastCode = v;
                    sameCount = 1;
                }
                if (sameCount >= 2 && !barcodeHandled) {
                    barcodeHandled = true;
                    runOnUiThread(() -> onBarcode(v));
                }
                break;
            }
        }).addOnCompleteListener(t -> proxy.close());
    }

    private void onBarcode(String code) {
        if (isFinishing()) return;
        Ui.haptic(b.getRoot());
        if (analysis != null) analysis.clearAnalyzer();
        b.busy.setVisibility(View.VISIBLE);
        b.hint.setText(getString(R.string.cam_looking_up, code));
        graph().foods.lookupBarcode(code, r -> {
            if (isFinishing()) return;
            b.busy.setVisibility(View.GONE);
            if (r.ok() && r.value != null) {
                startActivity(FoodDetailActivity.forFood(this, r.value.id, DateUtil.today(), meal, false));
                finish();
            } else if (r.ok()) {
                new MaterialAlertDialogBuilder(this).setTitle(R.string.barcode_unknown)
                        .setMessage(getString(R.string.barcode_unknown_body, code))
                        .setPositiveButton(R.string.create_custom_food, (d, w) -> {
                            startActivity(CustomFoodActivity.create(this, ""));
                            finish();
                        })
                        .setNegativeButton(R.string.scan_again, (d, w) -> resumeScan())
                        .setOnCancelListener(d -> resumeScan()).show();
            } else {
                Ui.snack(b.getRoot(), r.error);
                resumeScan();
            }
        });
    }

    private void resumeScan() {
        barcodeHandled = false;
        lastCode = null;
        renderMode();
        bind();
    }

    private void enterCode() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(R.string.barcode_hint);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.cam_enter_code).setView(box)
                .setPositiveButton(R.string.search, (d, w) -> {
                    String code = et.getText().toString().trim();
                    if (com.transformsai.nutrifitai.data.remote.OpenFoodFacts.validCode(code)) {
                        barcodeHandled = true;
                        onBarcode(code);
                    } else Ui.snack(b.getRoot(), getString(R.string.barcode_invalid));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("photo", photoPath);
        out.putString("meal", meal);
        out.putBoolean("asked", askedOnce);
        out.putInt("mode", mode);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (photoPath == null) refreshPermissionUi();
    }

    private boolean hasCamera() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void refreshPermissionUi() {
        if (!hasCamera()) {
            b.permPanel.setVisibility(View.VISIBLE);
            b.permTitle.setText(R.string.cam_perm_title);
            b.permBody.setText(R.string.cam_unavailable);
            b.permAllow.setVisibility(View.GONE);
            return;
        }
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            b.permPanel.setVisibility(View.GONE);
            bind();
            return;
        }
        b.permPanel.setVisibility(View.VISIBLE);
        boolean permanentlyDenied = askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        b.permBody.setText(permanentlyDenied ? R.string.cam_perm_denied : R.string.cam_perm_body);
        b.permAllow.setText(permanentlyDenied ? R.string.open_settings : R.string.cam_allow);
        b.permAllow.setVisibility(View.VISIBLE);
    }

    private void bind() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                if (isFinishing() || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(b.preview.getSurfaceProvider());
                CameraSelector sel = new CameraSelector.Builder().requireLensFacing(mode == MODE_BARCODE
                        ? CameraSelector.LENS_FACING_BACK : lens).build();
                if (!provider.hasCamera(sel)) sel = CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                if (mode == MODE_BARCODE) {
                    if (scanner == null) scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                            .build());
                    analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    analysis.setAnalyzer(scanExec, this::analyseBarcode);
                    capture = null;
                    provider.bindToLifecycle(this, sel, preview, analysis);
                } else {
                    capture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setFlashMode(flash).build();
                    analysis = null;
                    provider.bindToLifecycle(this, sel, preview, capture);
                }
                b.flip.setVisibility(provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        && provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ? View.VISIBLE : View.INVISIBLE);
            } catch (Exception e) {
                b.permPanel.setVisibility(View.VISIBLE);
                b.permBody.setText(R.string.cam_unavailable);
                b.permAllow.setVisibility(View.GONE);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (capture == null) return;
        File tmp = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
        b.busy.setVisibility(View.VISIBLE);
        b.shutter.setEnabled(false);
        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(tmp).build(), ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        process(Uri.fromFile(tmp), tmp);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        b.busy.setVisibility(View.GONE);
                        b.shutter.setEnabled(true);
                        Ui.snack(b.getRoot(), getString(R.string.cam_error));
                    }
                });
    }

    private void pick() {
        picker.launch(new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null) return;
        b.busy.setVisibility(View.VISIBLE);
        process(uri, null);
    }

    /** Rotate/resize/strip metadata off the main thread. */
    private void process(Uri src, @Nullable File temp) {
        graph().runIo(() -> {
            try {
                File out = ImageUtils.newMealImageFile(this);
                ImageUtils.process(this, src, out);
                if (temp != null && !temp.delete()) temp.deleteOnExit();
                runOnUiThread(() -> {
                    photoPath = out.getAbsolutePath();
                    b.busy.setVisibility(View.GONE);
                    b.shutter.setEnabled(true);
                    showReview();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    b.busy.setVisibility(View.GONE);
                    b.shutter.setEnabled(true);
                    Ui.snack(b.getRoot(), getString(R.string.cam_error));
                });
            }
        });
    }

    private void showReview() {
        if (provider != null) provider.unbindAll(); // release the camera while reviewing
        b.review.setVisibility(View.VISIBLE);
        b.permPanel.setVisibility(View.GONE);
        Glide.with(this).load(new File(photoPath)).into(b.photo);
    }

    private void discardPhoto(boolean retake) {
        if (photoPath != null) {
            File f = new File(photoPath);
            if (f.exists() && !f.delete()) f.deleteOnExit();
        }
        photoPath = null;
        b.review.setVisibility(View.GONE);
        if (retake) refreshPermissionUi();
        else finish();
    }

    private void analyse() {
        if (photoPath == null) return;
        String note = b.note.getText() == null ? null : b.note.getText().toString().trim();
        b.analyse.setEnabled(false);
        graph().analysis.createImage(photoPath, note, meal, DateUtil.today(), r -> {
            photoPath = null; // ownership moves to the analysis record
            startActivity(AnalysisActivity.intent(this, r.value));
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (provider != null) provider.unbindAll();
        if (scanner != null) scanner.close();
        scanExec.shutdown();
        super.onDestroy();
    }
}
