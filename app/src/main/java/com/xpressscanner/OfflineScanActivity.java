package com.xpressscanner;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
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
import com.xpressscanner.ui.BarcodeOverlayView;

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
    private BarcodeScanner scanner;
    private PreviewView previewView;
    private BarcodeOverlayView overlayView;
    private LinearLayout pendingList;
    private LinearLayout csvList;
    private TextView statusText;
    private Button startButton;
    private Button endButton;
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
        refreshCsvList();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.color("bg"));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(16) + insets.left, dp(16) + insets.top, dp(16) + insets.right, dp(16) + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        TextView title = new TextView(this);
        title.setText("Offline CSV Scanner");
        title.setTextColor(MainActivity.color("textMain"));
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText("Tap Start Scan to collect AWB numbers without Bluetooth.");
        statusText.setTextColor(MainActivity.color("textSub"));
        statusText.setTextSize(13);
        statusText.setPadding(0, dp(6), 0, dp(10));
        root.addView(statusText);

        FrameLayout cameraFrame = new FrameLayout(this);
        cameraFrame.setBackground(card(Color.BLACK, MainActivity.color("cardStroke"), 18));
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cameraParams.setMargins(0, 0, 0, dp(12));
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraFrame.addView(previewView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayView = new BarcodeOverlayView(this);
        cameraFrame.addView(overlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(cameraFrame, cameraParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("Start Scan");
        startButton.setAllCaps(false);
        startButton.setTextColor(Color.WHITE);
        startButton.setBackground(card(MainActivity.color("btnConnect"), 0, 10));
        startButton.setOnClickListener(v -> startOfflineSession());
        endButton = new Button(this);
        endButton.setText("End Scan");
        endButton.setAllCaps(false);
        endButton.setEnabled(false);
        endButton.setTextColor(Color.WHITE);
        endButton.setBackground(card(MainActivity.color("btnDisconnect"), 0, 10));
        endButton.setOnClickListener(v -> endOfflineSession());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(46), 1f);
        left.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(46), 1f);
        right.setMargins(dp(8), 0, 0, 0);
        actionRow.addView(startButton, left);
        actionRow.addView(endButton, right);
        root.addView(actionRow);

        pendingList = section(root, "CURRENT SCAN SESSION");
        csvList = section(root, "CSV FILES");
        setContentView(root);
    }

    private LinearLayout section(LinearLayout root, String label) {
        TextView sectionLabel = new TextView(this);
        sectionLabel.setText(label);
        sectionLabel.setTextColor(MainActivity.color("textLabel"));
        sectionLabel.setTextSize(11);
        sectionLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(12), 0, dp(6));
        root.addView(sectionLabel, labelParams);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list);
        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)));
        return list;
    }

    private void startOfflineSession() {
        pendingRows.clear();
        pendingList.removeAllViews();
        scanningEnabled = true;
        lastScan = "";
        lastScanAt = 0L;
        startButton.setEnabled(false);
        endButton.setEnabled(true);
        statusText.setText("Scanning offline. Tap End Scan to create a CSV file.");
    }

    private void endOfflineSession() {
        scanningEnabled = false;
        startButton.setEnabled(true);
        endButton.setEnabled(false);
        if (pendingRows.isEmpty()) {
            statusText.setText("No scans collected. Start again when ready.");
            return;
        }
        try {
            OfflineCsvStore.CsvFile csvFile = csvStore.createCsv(new ArrayList<>(pendingRows));
            pendingRows.clear();
            pendingList.removeAllViews();
            statusText.setText("Saved " + csvFile.name + ". Start again to create another CSV.");
            refreshCsvList();
        } catch (IOException e) {
            statusText.setText("Could not save CSV file.");
        }
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
        long now = System.currentTimeMillis();
        if (value.equals(lastScan) && now - lastScanAt < DUPLICATE_SCAN_WINDOW_MS) return;
        lastScan = value;
        lastScanAt = now;
        OfflineCsvStore.ScanRow row = new OfflineCsvStore.ScanRow(value, now);
        pendingRows.add(row);
        runOnUiThread(() -> {
            statusText.setText("Collected " + pendingRows.size() + " scans. Tap End Scan to store CSV.");
            addScanRow(row);
        });
    }

    private void addScanRow(OfflineCsvStore.ScanRow row) {
        TextView view = new TextView(this);
        view.setText(row.awbNumber + "  •  " + displayFormat.format(new Date(row.timestampMillis)));
        view.setTextColor(MainActivity.color("textMain"));
        view.setTextSize(13);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(card(MainActivity.color("card"), MainActivity.color("cardStroke"), 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(6));
        pendingList.addView(view, 0, params);
    }

    private void refreshCsvList() {
        csvList.removeAllViews();
        List<OfflineCsvStore.CsvFile> files = csvStore.listCsvFiles();
        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No offline CSV files yet.");
            empty.setTextColor(MainActivity.color("textSub"));
            csvList.addView(empty);
            return;
        }
        for (OfflineCsvStore.CsvFile file : files) csvList.addView(csvFileRow(file));
    }

    private LinearLayout csvFileRow(OfflineCsvStore.CsvFile file) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(card(MainActivity.color("card"), MainActivity.color("cardStroke"), 8));
        TextView name = new TextView(this);
        name.setText(file.name);
        name.setTextColor(MainActivity.color("textMain"));
        name.setTextSize(12);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener(v -> saveCsv(file));
        row.addView(save, new LinearLayout.LayoutParams(dp(70), dp(38)));
        Button share = new Button(this);
        share.setText("Share");
        share.setAllCaps(false);
        share.setOnClickListener(v -> shareCsv(file));
        row.addView(share, new LinearLayout.LayoutParams(dp(78), dp(38)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(params);
        return row;
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
                    toast("CSV saved.");
                }
            } catch (IOException e) {
                toast("Could not save CSV.");
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                statusText.setText("Camera init failed.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            statusText.setText("Camera permission denied.");
        }
    }

    private GradientDrawable card(int bgColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
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
}
