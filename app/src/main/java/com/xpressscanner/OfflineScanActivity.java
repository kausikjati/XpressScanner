package com.xpressscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.xpressscanner.logic.OfflineCsvStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OfflineScanActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 201;
    private static final int CREATE_CSV_FILE_REQUEST = 202;
    private static final long DUPLICATE_SCAN_WINDOW_MS = 1800;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<OfflineCsvStore.ScanRow> pendingRows = new ArrayList<>();
    private final SimpleDateFormat displayFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private OfflineCsvStore csvStore;
    private Camera camera;
    private BarcodeScanner scanner;
    private PreviewView previewView;
    private BarcodeOverlayView overlayView;
    private LinearLayout pendingList;

    // UI Styling Elements
    private LinearLayout statusCard;
    private View statusDot;
    private TextView statusText;
    private Button startButton;
    private Button endButton;

    // Scanning Features
    private boolean isFlashOn = false;
    private boolean isAutoScanMode = true;
    private boolean isCameraTouched = false;
    private boolean scanningEnabled = false;

    private String lastScan = "";
    private long lastScanAt = 0L;
    private OfflineCsvStore.CsvFile csvFileToSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        csvStore = new OfflineCsvStore(this);
        scanner = BarcodeScanning.getClient();

        buildLayout();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(11);
        label.setTextColor(MainActivity.color("textLabel"));
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(12), 0, dp(8));
        label.setLayoutParams(lp);
        return label;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildLayout() {
        // Master Container
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.color("bg"));
        root.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(20) + insets.left, dp(18) + insets.top, dp(20) + insets.right, dp(18) + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // 1. Brand Row
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.setMargins(0, 0, 0, dp(12));
        brandRow.setLayoutParams(brandParams);

        // Back Button (Top Left)
        TextView backBtn = new TextView(this);
        backBtn.setText("❮"); // Clean modern chevron
        backBtn.setTextSize(18);
        backBtn.setTextColor(Color.WHITE);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setBackground(createCardDrawable(MainActivity.color("cardStroke"), 0, 18));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        backParams.setMargins(0, 0, dp(10), 0);
        backBtn.setLayoutParams(backParams);
        backBtn.setOnClickListener(v -> finish());
        brandRow.addView(backBtn);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTitle = new TextView(this);
        appTitle.setText("Offline Scanner");
        appTitle.setTextSize(18);
        appTitle.setTextColor(MainActivity.color("textMain"));
        appTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appTitle.setSingleLine(true);
        appTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(appTitle);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("Local CSV generation");
        appSubtitle.setTextSize(12);
        appSubtitle.setTextColor(MainActivity.color("textSub"));
        appSubtitle.setSingleLine(true);
        appSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(appSubtitle);
        brandRow.addView(titleBlock);

        // CSV Files Folder Button (Top Right)
        ImageButton filesButton = new ImageButton(this);
        filesButton.setImageResource(android.R.drawable.ic_menu_agenda); // Folder/list icon
        filesButton.setColorFilter(Color.WHITE);
        filesButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        filesButton.setBackground(createCardDrawable(MainActivity.color("cardStroke"), 0, 18));
        filesButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        filesButton.setOnClickListener(v -> showCsvListDialog());
        LinearLayout.LayoutParams filesParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        brandRow.addView(filesButton, filesParams);

        root.addView(brandRow);

        // 2. Status Pill Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(12));
        headerRow.setLayoutParams(headerParams);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER);
        statusCard.setBackground(createCardDrawable(MainActivity.color("pillDefault"), MainActivity.color("cardStroke"), 28));
        statusCard.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusCard.setLayoutParams(cardParams);

        statusDot = new View(this);
        GradientDrawable statusDotShape = new GradientDrawable();
        statusDotShape.setShape(GradientDrawable.OVAL);
        statusDotShape.setColor(MainActivity.color("textSub"));
        statusDot.setBackground(statusDotShape);
        LinearLayout.LayoutParams statusDotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        statusDotParams.setMargins(0, 0, dp(8), 0);
        statusCard.addView(statusDot, statusDotParams);

        statusText = new TextView(this);
        statusText.setText("Ready to scan offline.");
        statusText.setTextSize(14);
        statusText.setTextColor(MainActivity.color("textMain"));
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusCard.addView(statusText);
        headerRow.addView(statusCard);

        root.addView(headerRow);

        // 3. Control Panel (Start / Save)
        root.addView(sectionLabel("SCAN CONTROLS"));

        LinearLayout controlPanel = new LinearLayout(this);
        controlPanel.setOrientation(LinearLayout.HORIZONTAL);
        controlPanel.setBackground(createCardDrawable(MainActivity.color("card"), MainActivity.color("cardStroke"), 22));
        controlPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        controlPanel.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cpParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cpParams.setMargins(0, 0, 0, dp(12));
        controlPanel.setLayoutParams(cpParams);

        startButton = new Button(this);
        startButton.setText("Start Session");
        startButton.setAllCaps(false);
        startButton.setTextSize(15);
        startButton.setTextColor(Color.WHITE);
        startButton.setBackground(createCardDrawable(MainActivity.color("btnConnect"), 0, 12));
        startButton.setOnClickListener(v -> startOfflineSession());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        startParams.setMargins(0, 0, dp(8), 0);
        controlPanel.addView(startButton, startParams);

        endButton = new Button(this);
        endButton.setText("End & Save");
        endButton.setAllCaps(false);
        endButton.setTextSize(15);
        endButton.setEnabled(false);
        endButton.setAlpha(0.5f);
        endButton.setTextColor(Color.WHITE);
        endButton.setBackground(createCardDrawable(MainActivity.color("btnDisconnect"), 0, 12));
        endButton.setOnClickListener(v -> endOfflineSession());
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        controlPanel.addView(endButton, endParams);

        root.addView(controlPanel);

        // 4. Camera Viewport (Drastically increased dynamic space)
        FrameLayout cameraContainer = new FrameLayout(this);
        LinearLayout.LayoutParams cameraContainerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cameraContainerParams.setMargins(0, dp(6), 0, dp(12));
        cameraContainer.setLayoutParams(cameraContainerParams);
        cameraContainer.setMinimumHeight(dp(250));

        cameraContainer.setBackground(createCardDrawable(Color.BLACK, MainActivity.color("cardStroke"), 28));
        cameraContainer.setClipToOutline(true);
        cameraContainer.setElevation(dp(2));

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        // Touch Listener for Tap-to-Scan Feature
        previewView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isCameraTouched = true;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isCameraTouched = false;
                    return true;
            }
            return false;
        });

        cameraContainer.addView(previewView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = new BarcodeOverlayView(this);
        cameraContainer.addView(overlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Scan Mode Toggle Button (Bottom-Left)
        ImageButton modeButton = new ImageButton(this);
        modeButton.setImageResource(android.R.drawable.ic_media_play); // Play = Auto
        modeButton.setColorFilter(Color.WHITE);
        modeButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        modeButton.setBackground(createCardDrawable(MainActivity.color("overlay"), 0, 24));
        modeButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams modeParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        modeParams.gravity = Gravity.BOTTOM | Gravity.START;
        modeParams.setMargins(dp(14), 0, 0, dp(14));
        modeButton.setLayoutParams(modeParams);
        modeButton.setOnClickListener(v -> {
            isAutoScanMode = !isAutoScanMode;
            modeButton.setImageResource(isAutoScanMode ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause); // Pause = Hold
            modeButton.setBackground(createCardDrawable(MainActivity.color(isAutoScanMode ? "overlay" : "overlayActive"), 0, 24));
        });
        cameraContainer.addView(modeButton);

        // Floating Flash Toggle (Bottom-Right)
        ImageButton flashButton = new ImageButton(this);
        flashButton.setImageResource(android.R.drawable.ic_menu_camera); // Flash Icon fallback
        flashButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        flashButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        flashButton.setBackground(createCardDrawable(MainActivity.color("overlay"), 0, 24));
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        flashParams.gravity = Gravity.BOTTOM | Gravity.END;
        flashParams.setMargins(0, 0, dp(14), dp(14));
        flashButton.setLayoutParams(flashParams);
        flashButton.setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                isFlashOn = !isFlashOn;
                camera.getCameraControl().enableTorch(isFlashOn);
                flashButton.setBackground(createCardDrawable(MainActivity.color(isFlashOn ? "overlayActive" : "overlay"), 0, 24));
            }
        });
        cameraContainer.addView(flashButton);

        root.addView(cameraContainer);

        // 5. Active Session Scans (Height significantly reduced to give Camera max space)
        root.addView(sectionLabel("CURRENT SESSION"));
        ScrollView pendingScroll = new ScrollView(this);
        pendingList = new LinearLayout(this);
        pendingList.setOrientation(LinearLayout.VERTICAL);
        pendingScroll.addView(pendingList);

        // Decreased height to dp(90) which drastically scales up the Camera View weight
        root.addView(pendingScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)));

        setContentView(root);
    }

    private void updateStatusUI(String message, String colorKey) {
        runOnUiThread(() -> {
            statusText.setText(message);
            statusCard.setBackground(createCardDrawable(MainActivity.color(colorKey), MainActivity.color("cardStroke"), 28));
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            int dotColor;
            switch (colorKey) {
                case "pillConnected": dotColor = MainActivity.color("accentGreen"); break;
                case "btnDisconnect": dotColor = MainActivity.color("accentRed"); break;
                default: dotColor = MainActivity.color("textSub"); break;
            }
            dotShape.setColor(dotColor);
            statusDot.setBackground(dotShape);
        });
    }

    private void startOfflineSession() {
        pendingRows.clear();
        pendingList.removeAllViews();
        scanningEnabled = true;
        lastScan = "";
        lastScanAt = 0L;
        startButton.setEnabled(false);
        startButton.setAlpha(0.5f);
        endButton.setEnabled(true);
        endButton.setAlpha(1.0f);
        updateStatusUI("Scanning offline...", "pillConnected");
    }

    private void endOfflineSession() {
        scanningEnabled = false;
        startButton.setEnabled(true);
        startButton.setAlpha(1.0f);
        endButton.setEnabled(false);
        endButton.setAlpha(0.5f);
        if (pendingRows.isEmpty()) {
            updateStatusUI("No scans collected.", "pillDefault");
            return;
        }
        try {
            OfflineCsvStore.CsvFile csvFile = csvStore.createCsv(new ArrayList<>(pendingRows));
            pendingRows.clear();
            pendingList.removeAllViews();
            updateStatusUI("Saved " + csvFile.name, "pillDefault");
        } catch (IOException e) {
            updateStatusUI("Could not save CSV file.", "btnDisconnect");
        }
    }

    // --- CSV DIALOG SYSTEM ---
    private void showCsvListDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        dialogLayout.setBackgroundColor(MainActivity.color("bg"));

        // Custom Header with Close Button
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(16));
        headerRow.setLayoutParams(headerParams);

        TextView titleTv = new TextView(this);
        titleTv.setText("Saved CSV Files");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(18);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackground(createCardDrawable(MainActivity.color("cardStroke"), 0, 20));
        closeBtn.setColorFilter(Color.WHITE);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        headerRow.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        dialogLayout.addView(headerRow);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        List<OfflineCsvStore.CsvFile> files = csvStore.listCsvFiles();
        if (files.isEmpty()) {
            LinearLayout emptyState = new LinearLayout(this);
            emptyState.setOrientation(LinearLayout.VERTICAL);
            emptyState.setGravity(Gravity.CENTER);
            emptyState.setPadding(0, dp(32), 0, dp(32));

            ImageView emptyIcon = new ImageView(this);
            emptyIcon.setImageResource(android.R.drawable.ic_menu_save);
            emptyIcon.setColorFilter(MainActivity.color("textSub"));
            LinearLayout.LayoutParams emptyIconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            emptyIconParams.setMargins(0, 0, 0, dp(10));
            emptyState.addView(emptyIcon, emptyIconParams);

            TextView empty = new TextView(this);
            empty.setText("No offline CSV files yet.");
            empty.setTextColor(MainActivity.color("textSub"));
            empty.setGravity(Gravity.CENTER);
            emptyState.addView(empty);

            listLayout.addView(emptyState);
        } else {
            for (OfflineCsvStore.CsvFile file : files) {
                listLayout.addView(csvFileRow(file));
            }
        }

        scrollView.addView(listLayout);

        // Flexible height limiting to keep it bounded
        LinearLayout.LayoutParams scrlLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        dialogLayout.addView(scrollView, scrlLp);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(dialogLayout)
                .show();

        dialog.getWindow().setBackgroundDrawable(createCardDrawable(MainActivity.color("card"), MainActivity.color("cardStroke"), 12));

        closeBtn.setOnClickListener(v -> dialog.dismiss());
    }

    private LinearLayout csvFileRow(OfflineCsvStore.CsvFile file) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(createCardDrawable(MainActivity.color("card"), MainActivity.color("cardStroke"), 12));

        TextView name = new TextView(this);
        name.setText(file.name);
        name.setTextColor(MainActivity.color("textMain"));
        name.setTextSize(13);
        name.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton saveBtn = new ImageButton(this);
        saveBtn.setImageResource(android.R.drawable.ic_menu_save);
        saveBtn.setColorFilter(Color.WHITE);
        saveBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        saveBtn.setBackground(createCardDrawable(MainActivity.color("btnRefresh"), 0, 24));
        saveBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        saveBtn.setOnClickListener(v -> saveCsv(file));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        saveParams.setMargins(dp(8), 0, 0, 0);
        row.addView(saveBtn, saveParams);

        ImageButton shareBtn = new ImageButton(this);
        shareBtn.setImageResource(android.R.drawable.ic_menu_share);
        shareBtn.setColorFilter(Color.WHITE);
        shareBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        shareBtn.setBackground(createCardDrawable(MainActivity.color("btnSend"), 0, 24));
        shareBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        shareBtn.setOnClickListener(v -> shareCsv(file));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        shareParams.setMargins(dp(8), 0, 0, 0);
        row.addView(shareBtn, shareParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        return row;
    }

    @ExperimentalGetImage
    private void analyzeImage(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(image).addOnSuccessListener(barcodes -> {
            if (barcodes.isEmpty()) {
                overlayView.clear();
                return;
            }
            for (Barcode barcode : barcodes) {
                Rect box = barcode.getBoundingBox();
                if (box != null) overlayView.updateBox(box, imageProxy.getWidth(), imageProxy.getHeight());
                String value = barcode.getRawValue();
                if (value != null && !value.trim().isEmpty()) {
                    collectScan(value.trim());
                    break;
                }
            }
        }).addOnCompleteListener(task -> imageProxy.close());
    }

    private void collectScan(String value) {
        if (!scanningEnabled) return;

        // Auto/Hold Scanning Enforcement
        if (!isAutoScanMode && !isCameraTouched) return;

        long now = System.currentTimeMillis();
        if (value.equals(lastScan) && now - lastScanAt < DUPLICATE_SCAN_WINDOW_MS) return;
        lastScan = value;
        lastScanAt = now;

        OfflineCsvStore.ScanRow row = new OfflineCsvStore.ScanRow(value, now);
        pendingRows.add(row);
        runOnUiThread(() -> {
            updateStatusUI("Collected " + pendingRows.size() + " scans.", "pillConnected");
            addScanRow(row);
        });
    }

    private void addScanRow(OfflineCsvStore.ScanRow row) {
        TextView view = new TextView(this);
        view.setText(row.awbNumber + "  •  " + displayFormat.format(new Date(row.timestampMillis)));
        view.setTextColor(MainActivity.color("textMain"));
        view.setTextSize(13);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(createCardDrawable(MainActivity.color("card"), MainActivity.color("cardStroke"), 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(6));
        pendingList.addView(view, 0, params);
    }

    private void saveCsv(OfflineCsvStore.CsvFile file) {
        csvFileToSave = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, file.name);
        startActivityForResult(intent, CREATE_CSV_FILE_REQUEST);
    }

    private void shareCsv(OfflineCsvStore.CsvFile file) {
        Uri uri = csvStore.getShareUri(file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share offline CSV"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_CSV_FILE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null && csvFileToSave != null) {
            try (OutputStream outputStream = getContentResolver().openOutputStream(data.getData())) {
                if (outputStream != null) {
                    csvStore.copyTo(csvFileToSave, outputStream);
                    Toast.makeText(this, "CSV saved.", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                Toast.makeText(this, "Could not save CSV.", Toast.LENGTH_SHORT).show();
            }
        }
        csvFileToSave = null;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                updateStatusUI("Camera init failed.", "btnDisconnect");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            updateStatusUI("Camera permission denied.", "btnDisconnect");
        }
    }

    private GradientDrawable createCardDrawable(int bgColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanner.close();
        cameraExecutor.shutdown();
    }

    // Modern Camera Overlay inner class ensuring UI consistency with MainActivity
    private static class BarcodeOverlayView extends View {
        private final Paint overlayPaint;
        private final Paint clearPaint;
        private final Paint framePaint;
        private final Paint boxPaint;
        private final RectF calculatedRect = new RectF();
        private boolean hasTarget = false;

        public BarcodeOverlayView(Context context) {
            super(context);
            // Disable hardware acceleration to ensure CLEAR mode works properly
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            overlayPaint = new Paint();
            overlayPaint.setColor(Color.parseColor("#800B0F1A")); // Semi-transparent dark overlay

            clearPaint = new Paint();
            clearPaint.setAntiAlias(true);
            clearPaint.setColor(Color.TRANSPARENT);
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            framePaint = new Paint();
            framePaint.setColor(MainActivity.color("cardStroke"));
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(6f);
            framePaint.setAntiAlias(true);

            boxPaint = new Paint();
            boxPaint.setColor(MainActivity.color("accentGreen"));
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(8f);
            boxPaint.setAntiAlias(true);
        }

        private float dpToPx(float dp) {
            return dp * getContext().getResources().getDisplayMetrics().density;
        }

        public void updateBox(Rect rect, int imageWidth, int imageHeight) {
            float scaleX = (float) getWidth() / imageHeight;
            float scaleY = (float) getHeight() / imageWidth;

            calculatedRect.left = rect.left * scaleX;
            calculatedRect.right = rect.right * scaleX;
            calculatedRect.top = rect.top * scaleY;
            calculatedRect.bottom = rect.bottom * scaleY;

            hasTarget = true;
            postInvalidate();
        }

        public void clear() {
            if (hasTarget) {
                hasTarget = false;
                postInvalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();

            // Draw full semi-transparent dark background
            canvas.drawRect(0, 0, w, h, overlayPaint);

            // Define central rounded rectangle cutout area matching MainActivity
            float boxSize = (Math.min(w, h) * 0.98f) - dpToPx(5);
            float cx = w / 2f;
            float cy = h / 2f;
            RectF scanArea = new RectF(cx - boxSize / 2f, cy - boxSize / 2f, cx + boxSize / 2f, cy + boxSize / 2f);
            float cornerRadius = 60f;

            // Cut out the center hole
            canvas.drawRoundRect(scanArea, cornerRadius, cornerRadius, clearPaint);

            // Draw stroke outline around the hole
            canvas.drawRoundRect(scanArea, cornerRadius, cornerRadius, framePaint);

            // Draw Dynamic Green Bounding Box around detected code
            if (hasTarget) {
                canvas.drawRoundRect(calculatedRect, 16f, 16f, boxPaint);
            }
        }
    }
}