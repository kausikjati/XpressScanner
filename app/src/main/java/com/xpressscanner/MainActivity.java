package com.xpressscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.ComponentActivity;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int PERMISSION_REQUEST = 101;

    private static final byte[] HID_KEYBOARD_DESCRIPTOR = {
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x06, (byte) 0xA1, (byte) 0x01,
            (byte) 0x05, (byte) 0x07, (byte) 0x19, (byte) 0xE0, (byte) 0x29, (byte) 0xE7,
            (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x01, (byte) 0x75, (byte) 0x01,
            (byte) 0x95, (byte) 0x08, (byte) 0x81, (byte) 0x02, (byte) 0x75, (byte) 0x08,
            (byte) 0x95, (byte) 0x01, (byte) 0x81, (byte) 0x01, (byte) 0x05, (byte) 0x08,
            (byte) 0x19, (byte) 0x01, (byte) 0x29, (byte) 0x05, (byte) 0x75, (byte) 0x01,
            (byte) 0x95, (byte) 0x05, (byte) 0x91, (byte) 0x02, (byte) 0x75, (byte) 0x03,
            (byte) 0x95, (byte) 0x01, (byte) 0x91, (byte) 0x01, (byte) 0x05, (byte) 0x07,
            (byte) 0x19, (byte) 0x00, (byte) 0x29, (byte) 0x65, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x65, (byte) 0x75, (byte) 0x08, (byte) 0x95, (byte) 0x06,
            (byte) 0x81, (byte) 0x00, (byte) 0xC0
    };

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private Camera camera;
    private boolean isFlashOn = false;
    private BarcodeScanner scanner;
    private PreviewView previewView;
    private BarcodeOverlayView barcodeOverlayView;

    // UI Elements
    private LinearLayout statusCard;
    private View statusDot;
    private TextView statusText;
    private TextView lastScanText;
    private EditText manualInput;
    private Button deviceButton;
    private Button connectButton;

    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private BluetoothDevice selectedDevice;

    // Framework State Trackers
    private BluetoothHidDevice hidDeviceProxy;
    private BluetoothDevice hidConnectedDevice;
    private boolean isAppRegistered = false;

    // Concurrency Lock & Feature States
    private volatile boolean isTyping = false;
    private boolean isAutoScanMode = true;
    private boolean isCameraTouched = false;

    private ToneGenerator toneGenerator;
    private SharedPreferences prefs;

    private String lastSent = "";
    private long lastSentAt = 0L;

    // Screen Wake
    private final Handler screenHandler = new Handler(Looper.getMainLooper());
    private final Runnable screenOffRunnable = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("XpressPrefs", MODE_PRIVATE);
        scanner = BarcodeScanning.getClient();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        buildLayout();
        requestNeededPermissions();
        resetScreenTimeout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBluetoothPermission()) {
            restoreLastDevice();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                if (hidDeviceProxy == null) {
                    setupHidProfile();
                } else if (!isAppRegistered) {
                    registerHidApp();
                } else {
                    syncConnectionState();
                }
            }
        }
    }

    private static int color(String key) {
        switch(key) {
            case "bg": return Color.parseColor("#0B0F1A");
            case "card": return Color.parseColor("#141B2E");
            case "cardStroke": return Color.parseColor("#232C42");
            case "pillConnected": return Color.parseColor("#4338CA");
            case "pillDefault": return Color.parseColor("#1C2438");
            case "textMain": return Color.parseColor("#F1F5F9");
            case "textSub": return Color.parseColor("#8B93A7");
            case "textLabel": return Color.parseColor("#5B6478");
            case "btnRefresh": return Color.parseColor("#2A3350");
            case "btnDisconnect": return Color.parseColor("#DC2626");
            case "btnConnect": return Color.parseColor("#15803D");
            case "inputBg": return Color.parseColor("#0B0F1A");
            case "btnSend": return Color.parseColor("#4F46E5");
            case "accentGreen": return Color.parseColor("#2DD4BF");
            case "accentIndigo": return Color.parseColor("#6366F1");
            case "accentRed": return Color.parseColor("#F87171");
            case "overlay": return Color.parseColor("#99000000");
            case "overlayActive": return Color.parseColor("#CC1D4ED8");
            default: return Color.WHITE;
        }
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(11);
        label.setTextColor(color("textLabel"));
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, 0, dp(8));
        label.setLayoutParams(lp);
        return label;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetScreenTimeout();
    }

    private void resetScreenTimeout() {
        runOnUiThread(() -> {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            screenHandler.removeCallbacks(screenOffRunnable);
            screenHandler.postDelayed(screenOffRunnable, 15000);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color("bg"));
        root.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(16) + insets.left, dp(20) + insets.top, dp(16) + insets.right, dp(20) + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Brand Row (logo + app name)
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.setMargins(0, 0, 0, dp(10));
        brandRow.setLayoutParams(brandParams);

        ImageView logoView = new ImageView(this);
        logoView.setImageResource(R.drawable.ic_scan_logo);
        logoView.setBackground(createCardDrawable(color("card"), color("cardStroke"), 10));
        logoView.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoParams.setMargins(0, 0, dp(10), 0);
        brandRow.addView(logoView, logoParams);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTitle = new TextView(this);
        appTitle.setText("Xpress Scanner");
        appTitle.setTextSize(17);
        appTitle.setTextColor(color("textMain"));
        appTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleBlock.addView(appTitle);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("Bluetooth barcode wedge");
        appSubtitle.setTextSize(12);
        appSubtitle.setTextColor(color("textSub"));
        titleBlock.addView(appSubtitle);
        brandRow.addView(titleBlock);

        // Info Button
        ImageButton infoButton = new ImageButton(this);
        infoButton.setImageResource(R.drawable.ic_info);
        infoButton.setBackground(createCardDrawable(Color.WHITE, 0, 18));
        infoButton.setPadding(dp(9), dp(9), dp(9), dp(9));
        infoButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        infoButton.setElevation(dp(2));
        infoButton.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        infoButton.setOnClickListener(v -> showInstructionsDialog());
        brandRow.addView(infoButton);

        root.addView(brandRow);

        // Status Pill Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(12));
        headerRow.setLayoutParams(headerParams);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER);
        statusCard.setBackground(createCardDrawable(color("pillDefault"), 0, 24));
        statusCard.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusCard.setLayoutParams(cardParams);

        statusDot = new View(this);
        GradientDrawable statusDotShape = new GradientDrawable();
        statusDotShape.setShape(GradientDrawable.OVAL);
        statusDotShape.setColor(color("textSub"));
        statusDot.setBackground(statusDotShape);
        LinearLayout.LayoutParams statusDotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        statusDotParams.setMargins(0, 0, dp(8), 0);
        statusCard.addView(statusDot, statusDotParams);

        statusText = new TextView(this);
        statusText.setText("Initializing system...");
        statusText.setTextSize(14);
        statusText.setTextColor(color("textMain"));
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusCard.addView(statusText);
        headerRow.addView(statusCard);

        root.addView(headerRow);

        root.addView(sectionLabel("CONNECTION"));

        // Bluetooth Panel
        LinearLayout btPanel = new LinearLayout(this);
        btPanel.setOrientation(LinearLayout.VERTICAL);
        btPanel.setBackground(createCardDrawable(color("card"), color("cardStroke"), 14));
        btPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        btPanel.setElevation(dp(1));

        deviceButton = new Button(this);
        deviceButton.setText("  Select Bluetooth Device");
        deviceButton.setAllCaps(false);
        deviceButton.setTextSize(15);
        deviceButton.setTextColor(color("textMain"));
        deviceButton.setBackground(createCardDrawable(color("bg"), color("cardStroke"), 10));
        deviceButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bluetooth, 0, 0, 0);
        deviceButton.setCompoundDrawablePadding(dp(8));
        deviceButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        deviceButton.setPaddingRelative(dp(14), 0, dp(14), 0);
        deviceButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });
        btPanel.addView(deviceButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(12), 0, 0);
        actionRow.setLayoutParams(rowParams);

        Button refreshButton = new Button(this);
        refreshButton.setText(" Refresh");
        refreshButton.setAllCaps(false);
        refreshButton.setTextColor(color("textMain"));
        refreshButton.setBackground(createCardDrawable(color("btnRefresh"), 0, 10));
        refreshButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_refresh, 0, 0, 0);
        refreshButton.setCompoundDrawablePadding(dp(8));
        refreshButton.setPaddingRelative(dp(14), 0, dp(14), 0);
        refreshButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });

        connectButton = new Button(this);
        connectButton.setText(" Connect");
        connectButton.setAllCaps(false);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
        connectButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_link, 0, 0, 0);
        connectButton.setCompoundDrawablePadding(dp(8));
        connectButton.setPaddingRelative(dp(14), 0, dp(14), 0);
        connectButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) toggleConnection();
        });

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(46), 1);
        p1.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        p2.setMargins(dp(8), 0, 0, 0);

        actionRow.addView(refreshButton, p1);
        actionRow.addView(connectButton, p2);
        btPanel.addView(actionRow);
        root.addView(btPanel);

        LinearLayout.LayoutParams scannerLabelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scannerLabelParams.setMargins(dp(2), dp(14), 0, dp(8));
        TextView scannerLabel = sectionLabel("SCANNER");
        scannerLabel.setLayoutParams(scannerLabelParams);
        root.addView(scannerLabel);

        // Viewport / Camera Frame
        FrameLayout cameraContainer = new FrameLayout(this);
        LinearLayout.LayoutParams cameraContainerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cameraContainerParams.setMargins(0, 0, 0, dp(16));
        cameraContainer.setLayoutParams(cameraContainerParams);
        cameraContainer.setMinimumHeight(dp(220));

        cameraContainer.setBackground(createCardDrawable(Color.BLACK, color("cardStroke"), 20));
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

        barcodeOverlayView = new BarcodeOverlayView(this);
        cameraContainer.addView(barcodeOverlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Scan Mode Toggle Button (Bottom-Left)
        Button modeButton = new Button(this);
        modeButton.setText("AUTO");
        modeButton.setTextSize(12);
        modeButton.setTextColor(Color.WHITE);
        modeButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        modeButton.setBackground(createCardDrawable(color("overlay"), 0, 24));
        modeButton.setPadding(dp(12), 0, dp(12), 0);
        FrameLayout.LayoutParams modeParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(46));
        modeParams.gravity = Gravity.BOTTOM | Gravity.START;
        modeParams.setMargins(dp(14), 0, 0, dp(14));
        modeButton.setLayoutParams(modeParams);
        modeButton.setOnClickListener(v -> {
            isAutoScanMode = !isAutoScanMode;
            modeButton.setText(isAutoScanMode ? "AUTO" : "HOLD");
            modeButton.setBackground(createCardDrawable(color(isAutoScanMode ? "overlay" : "overlayActive"), 0, 24));
            toast(isAutoScanMode ? "Auto Scan Enabled" : "Tap and hold camera to scan");
        });
        cameraContainer.addView(modeButton);

        // Floating Flash Toggle (Bottom-Right)
        ImageButton flashButton = new ImageButton(this);
        flashButton.setImageResource(R.drawable.ic_flash_off);
        flashButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        flashButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        flashButton.setBackground(createCardDrawable(color("overlay"), 0, 24));
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(dp(46), dp(46));
        flashParams.gravity = Gravity.BOTTOM | Gravity.END;
        flashParams.setMargins(0, 0, dp(14), dp(14));
        flashButton.setLayoutParams(flashParams);
        flashButton.setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                isFlashOn = !isFlashOn;
                camera.getCameraControl().enableTorch(isFlashOn);
                flashButton.setImageResource(isFlashOn ? R.drawable.ic_flash : R.drawable.ic_flash_off);
                flashButton.setBackground(createCardDrawable(color(isFlashOn ? "overlayActive" : "overlay"), 0, 24));
            } else {
                toast("Flash not supported.");
            }
        });
        cameraContainer.addView(flashButton);

        root.addView(cameraContainer);

        // History Row
        LinearLayout historyRow = new LinearLayout(this);
        historyRow.setOrientation(LinearLayout.HORIZONTAL);
        historyRow.setGravity(Gravity.CENTER_VERTICAL);
        historyRow.setPadding(dp(8), 0, dp(8), 0);

        ImageButton historyBtn = new ImageButton(this);
        historyBtn.setImageResource(R.drawable.ic_history);
        historyBtn.setColorFilter(color("textSub"));
        historyBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        historyBtn.setBackground(createCardDrawable(color("card"), color("cardStroke"), 10));
        historyBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams historyBtnParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        historyBtnParams.setMargins(0, 0, dp(10), 0);
        historyBtn.setLayoutParams(historyBtnParams);
        historyBtn.setOnClickListener(v -> showHistoryDialog());
        historyRow.addView(historyBtn);

        lastScanText = new TextView(this);
        lastScanText.setText("Last scan: Ready for data...");
        lastScanText.setTextColor(color("accentGreen"));
        lastScanText.setTextSize(14);
        lastScanText.setTypeface(android.graphics.Typeface.MONOSPACE);
        lastScanText.setPadding(dp(12), 0, 0, 0);
        historyRow.addView(lastScanText);

        root.addView(historyRow);

        LinearLayout.LayoutParams manualLabelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        manualLabelParams.setMargins(dp(2), dp(8), 0, dp(8));
        TextView manualLabel = sectionLabel("MANUAL ENTRY");
        manualLabel.setLayoutParams(manualLabelParams);
        root.addView(manualLabel);

        // Manual Intervention Console
        LinearLayout manualPanel = new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.VERTICAL);
        manualPanel.setBackground(createCardDrawable(color("card"), color("cardStroke"), 14));
        manualPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        manualPanel.setElevation(dp(1));

        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setGravity(Gravity.CENTER_VERTICAL);

        manualInput = new EditText(this);
        manualInput.setSingleLine(true);
        manualInput.setHint("Enter value manually");
        manualInput.setHintTextColor(color("textSub"));
        manualInput.setTextColor(color("textMain"));
        manualInput.setTextSize(15);
        manualInput.setImeOptions(EditorInfo.IME_ACTION_SEND);

        manualInput.setBackground(createCardDrawable(color("inputBg"), color("cardStroke"), 10));
        manualInput.setPadding(dp(12), dp(10), dp(12), dp(10));

        manualInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendManualValue();
                return true;
            }
            return false;
        });

        // Icon Based Manual Send Button
        ImageButton sendButton = new ImageButton(this);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sendButton.setBackground(createCardDrawable(color("btnSend"), 0, 10));
        sendButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        sendButton.setOnClickListener(v -> sendManualValue());

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        inputParams.setMargins(0, 0, dp(10), 0);
        manualRow.addView(manualInput, inputParams);
        manualRow.addView(sendButton, new LinearLayout.LayoutParams(dp(50), dp(44)));
        manualPanel.addView(manualRow);
        root.addView(manualPanel);

        setContentView(root);
    }

    // --- SCAN HISTORY SYSTEM ---
    private void saveScanToHistory(String value) {
        String history = prefs.getString("scan_history", "");
        long now = System.currentTimeMillis();
        String newEntry = now + "|" + value + "|||";
        prefs.edit().putString("scan_history", newEntry + history).apply();
    }

    private void showHistoryDialog() {
        String history = prefs.getString("scan_history", "");
        long twoDaysMillis = 2L * 24 * 60 * 60 * 1000;
        long cutoffTime = System.currentTimeMillis() - twoDaysMillis;

        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        dialogLayout.setBackgroundColor(color("bg"));

        ScrollView scrollView = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault());
        boolean hasData = false;

        if (!history.isEmpty()) {
            String[] entries = history.split("\\|\\|\\|");
            StringBuilder updatedHistory = new StringBuilder();

            for (String entry : entries) {
                if (entry.trim().isEmpty()) continue;
                String[] parts = entry.split("\\|");
                if (parts.length == 2) {
                    try {
                        long timestamp = Long.parseLong(parts[0]);
                        String scannedData = parts[1];

                        if (timestamp >= cutoffTime) {
                            updatedHistory.append(entry).append("|||");
                            hasData = true;

                            // Create Individual History Card
                            LinearLayout itemCard = new LinearLayout(this);
                            itemCard.setOrientation(LinearLayout.VERTICAL);
                            itemCard.setBackground(createCardDrawable(color("card"), color("cardStroke"), 8));
                            itemCard.setPadding(dp(12), dp(12), dp(12), dp(12));

                            // Time and Data Text
                            TextView timeTv = new TextView(this);
                            timeTv.setText(sdf.format(new Date(timestamp)));
                            timeTv.setTextColor(color("textSub"));
                            timeTv.setTextSize(12);
                            itemCard.addView(timeTv);

                            TextView valTv = new TextView(this);
                            valTv.setText(scannedData);
                            valTv.setTextColor(color("textMain"));
                            valTv.setTextSize(16);
                            valTv.setTypeface(android.graphics.Typeface.MONOSPACE);
                            valTv.setPadding(0, dp(4), 0, 0);
                            itemCard.addView(valTv);

                            // Action Buttons Row
                            LinearLayout btnRow = new LinearLayout(this);
                            btnRow.setOrientation(LinearLayout.HORIZONTAL);
                            btnRow.setGravity(Gravity.END);
                            LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            btnRowParams.setMargins(0, dp(8), 0, 0);
                            btnRow.setLayoutParams(btnRowParams);

                            // Copy Button (Icon Only)
                            ImageButton copyBtn = new ImageButton(this);
                            copyBtn.setImageResource(R.drawable.ic_copy);
                            copyBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            copyBtn.setBackground(createCardDrawable(color("btnRefresh"), 0, 8));
                            copyBtn.setPadding(dp(11), dp(8), dp(11), dp(8));
                            copyBtn.setOnClickListener(v -> {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                android.content.ClipData clip = android.content.ClipData.newPlainText("Barcode", scannedData);
                                clipboard.setPrimaryClip(clip);
                                toast("Copied!");
                            });

                            // Send Button (Icon Only)
                            ImageButton sendBtn = new ImageButton(this);
                            sendBtn.setImageResource(R.drawable.ic_send);
                            sendBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            sendBtn.setBackground(createCardDrawable(color("btnSend"), 0, 8));
                            sendBtn.setPadding(dp(11), dp(8), dp(11), dp(8));
                            sendBtn.setOnClickListener(v -> {
                                if (isTyping) {
                                    toast("Please wait, currently sending data...");
                                    return;
                                }
                                if (hidDeviceProxy == null || hidConnectedDevice == null) {
                                    triggerErrorBeep();
                                    toast("Transmission failed: Not connected.");
                                } else {
                                    triggerSuccessBeep();
                                    sendValue(scannedData);
                                }
                            });

                            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(44), dp(36));
                            btnParams.setMargins(dp(12), 0, 0, 0);

                            btnRow.addView(copyBtn, btnParams);
                            btnRow.addView(sendBtn, btnParams);
                            itemCard.addView(btnRow);

                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            lp.setMargins(0, 0, 0, dp(8));
                            listLayout.addView(itemCard, lp);
                        }
                    } catch (Exception ignored) {}
                }
            }
            prefs.edit().putString("scan_history", updatedHistory.toString()).apply();
        }

        if (!hasData) {
            LinearLayout emptyState = new LinearLayout(this);
            emptyState.setOrientation(LinearLayout.VERTICAL);
            emptyState.setGravity(Gravity.CENTER);
            emptyState.setPadding(0, dp(32), 0, dp(32));

            ImageView emptyIcon = new ImageView(this);
            emptyIcon.setImageResource(R.drawable.ic_empty_history);
            LinearLayout.LayoutParams emptyIconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            emptyIconParams.setMargins(0, 0, 0, dp(10));
            emptyState.addView(emptyIcon, emptyIconParams);

            TextView empty = new TextView(this);
            empty.setText("No scans in the last 48 hours.");
            empty.setTextColor(color("textSub"));
            empty.setGravity(Gravity.CENTER);
            emptyState.addView(empty);

            listLayout.addView(emptyState);
        }

        scrollView.addView(listLayout);
        dialogLayout.addView(scrollView);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Last 48 Hours History")
                .setView(dialogLayout)
                .setPositiveButton("Close", null)
                .setNegativeButton("Clear All", (d, w) -> {
                    prefs.edit().remove("scan_history").apply();
                    toast("History cleared");
                })
                .show();

        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        TextView titleTv = dialog.findViewById(titleId);
        if (titleTv != null) titleTv.setTextColor(Color.WHITE);
        dialog.getWindow().setBackgroundDrawable(createCardDrawable(color("card"), color("cardStroke"), 12));
    }

    private void showInstructionsDialog() {
        String instructions = "1. Pair your Devices\n" +
                "Open your phone's Android Settings > Bluetooth, and pair your phone with your Mac/PC.\n\n" +
                "2. Start the Framework\n" +
                "Wait for the top banner to say \"Scanner ready\".\n\n" +
                "3. Connect to Computer\n" +
                "Tap \"Select Bluetooth Device\" and choose your computer. Tap \"Connect\".\n\n" +
                "4. Scan Barcodes\n" +
                "Use the MODE button to switch between automatic scanning and \"tap and hold\" scanning!";

        new AlertDialog.Builder(this)
                .setTitle("How to Use Scanner")
                .setMessage(instructions)
                .setPositiveButton("Got it", null)
                .show();
    }

    private GradientDrawable createCardDrawable(int bgColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bgColor);
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void updateStatusUI(String message, String colorKey) {
        runOnUiThread(() -> {
            statusText.setText(message);
            statusCard.setBackground(createCardDrawable(color(colorKey), 0, 24));
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(statusDotColor(colorKey));
            statusDot.setBackground(dotShape);
        });
    }

    /** Brighter accent used for the small status dot, distinct from the pill background. */
    private int statusDotColor(String colorKey) {
        switch (colorKey) {
            case "pillConnected": return color("accentGreen");
            case "btnDisconnect": return color("accentRed");
            default: return color("textSub");
        }
    }

    // --- BLUETOOTH SYNC ENGINES ---

    @SuppressLint("MissingPermission")
    private void restoreLastDevice() {
        String savedMac = prefs.getString("last_device_mac", null);
        if (savedMac != null && bluetoothAdapter != null) {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                if (device.getAddress().equals(savedMac)) {
                    selectedDevice = device;
                    String name = device.getName() == null ? "Unknown Machine" : device.getName();
                    runOnUiThread(() -> deviceButton.setText("  " + name));
                    break;
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void syncConnectionState() {
        if (hidDeviceProxy != null) {
            List<BluetoothDevice> connected = hidDeviceProxy.getConnectedDevices();
            if (!connected.isEmpty()) {
                hidConnectedDevice = connected.get(0);
                String name = hidConnectedDevice.getName() == null ? "Unknown Host" : hidConnectedDevice.getName();
                updateStatusUI("Connected to " + name, "pillConnected");
                runOnUiThread(() -> {
                    connectButton.setBackground(createCardDrawable(color("btnDisconnect"), 0, 10));
                    connectButton.setText(" Disconnect");
                });
            } else {
                hidConnectedDevice = null;
                if (isAppRegistered) {
                    updateStatusUI("Scanner ready. Select target.", "pillDefault");
                }
                runOnUiThread(() -> {
                    connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
                    connectButton.setText(" Connect");
                });
            }
        }
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (permissions.isEmpty()) {
            startCamera();
            setupHidProfile();
        } else {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                updateStatusUI("Camera denied. Scanning disabled.", "btnDisconnect");
            }
            if (hasBluetoothPermission()) {
                setupHidProfile();
            }
        }
    }

    private boolean requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<String> permissions = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!permissions.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST);
                return false;
            }
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void setupHidProfile() {
        if (bluetoothAdapter == null) return;
        bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDeviceProxy = (BluetoothHidDevice) proxy;
                    registerHidApp();
                }
            }
            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDeviceProxy = null;
                }
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    @SuppressLint("MissingPermission")
    private void registerHidApp() {
        BluetoothHidDeviceAppSdpSettings sdpSettings = new BluetoothHidDeviceAppSdpSettings(
                "Xpress HID Scanner", "Android Bluetooth Scanner", "Xpress", (byte) 0x00, HID_KEYBOARD_DESCRIPTOR
        );
        hidDeviceProxy.registerApp(sdpSettings, null, null, ContextCompat.getMainExecutor(this), new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                isAppRegistered = registered;
                if (registered) {
                    syncConnectionState();
                } else {
                    updateStatusUI("Scanner unregistered.", "btnDisconnect");
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                syncConnectionState();
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    triggerConnectBeep();
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        pairedDevices.clear();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            deviceButton.setText("Bluetooth configuration failed");
            return;
        }
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevices.addAll(bondedDevices);

        String[] labels = new String[pairedDevices.size()];
        for (int i = 0; i < pairedDevices.size(); i++) {
            BluetoothDevice device = pairedDevices.get(i);
            String name = device.getName() == null ? "Unknown Machine" : device.getName();
            labels[i] = name + "\n" + device.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Host Target")
                .setItems(labels, (dialog, which) -> {
                    selectedDevice = pairedDevices.get(which);
                    prefs.edit().putString("last_device_mac", selectedDevice.getAddress()).apply();
                    String name = selectedDevice.getName() == null ? "Unknown Machine" : selectedDevice.getName();
                    deviceButton.setText("  " + name);
                }).show();
    }

    @SuppressLint("MissingPermission")
    private void toggleConnection() {
        if (hidDeviceProxy == null) {
            toast("Framework restarting. Please wait...");
            setupHidProfile();
            return;
        }
        if (!isAppRegistered) {
            toast("Registering scanner. Please wait...");
            registerHidApp();
            return;
        }

        if (hidConnectedDevice != null) {
            updateStatusUI("Disconnecting...", "pillDefault");
            hidDeviceProxy.disconnect(hidConnectedDevice);
        } else {
            if (selectedDevice == null) {
                toast("Choose a paired configuration first.");
                return;
            }
            prefs.edit().putString("last_device_mac", selectedDevice.getAddress()).apply();

            updateStatusUI("Connecting HID Framework...", "pillDefault");
            hidDeviceProxy.connect(selectedDevice);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();
                androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);
                provider.unbindAll();

                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (ExecutionException | InterruptedException | RuntimeException ex) {
                updateStatusUI("Camera Init Failed", "btnDisconnect");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void analyzeImage(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (barcodes.isEmpty()) {
                        barcodeOverlayView.clear();
                        return;
                    }
                    for (Barcode barcode : barcodes) {
                        Rect boundingBox = barcode.getBoundingBox();
                        if (boundingBox != null) {
                            barcodeOverlayView.updateBox(boundingBox, imageProxy.getWidth(), imageProxy.getHeight());
                        }
                        String value = barcode.getRawValue();
                        if (value != null && !value.trim().isEmpty()) {
                            handleScan(value.trim());
                            break;
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleScan(String value) {
        // NEW FEATURE: Enforce tap-to-scan mode lock
        if (!isAutoScanMode && !isCameraTouched) return;

        if (isTyping) return; // Prevent concurrent overlapping hardware scans

        long now = System.currentTimeMillis();
        if (value.equals(lastSent) && now - lastSentAt < 1800) return;
        lastSent = value;
        lastSentAt = now;

        if (hidDeviceProxy == null || hidConnectedDevice == null) {
            triggerErrorBeep();
            runOnUiThread(() -> toast("Scan failed: Not connected to a computer."));
            return;
        }

        triggerSuccessBeep();
        resetScreenTimeout();
        saveScanToHistory(value);

        runOnUiThread(() -> lastScanText.setText("Last scan: " + value));
        sendValue(value);
    }

    private void sendManualValue() {
        if (isTyping) {
            toast("Please wait, currently sending data...");
            return;
        }

        String value = manualInput.getText().toString().trim();
        if (value.isEmpty()) return;

        if (hidDeviceProxy == null || hidConnectedDevice == null) {
            triggerErrorBeep();
            toast("Transmission failed: Not connected to a computer.");
            return;
        }

        triggerSuccessBeep();
        resetScreenTimeout();
        saveScanToHistory(value);
        sendValue(value);

        manualInput.setText("");
    }

    private void triggerSuccessBeep() {
        try { if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150); } catch (Exception ignored) {}
    }

    private void triggerConnectBeep() {
        try { if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 200); } catch (Exception ignored) {}
    }

    private void triggerErrorBeep() {
        try { if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 350); } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void sendValue(String value) {
        if (hidDeviceProxy == null || hidConnectedDevice == null) return;

        isTyping = true; // Lock thread to block rapid new barcode readings
        new Thread(() -> {
            try {
                for (char c : (value + "\n").toCharArray()) {
                    byte[] report = charToHidReport(c);
                    hidDeviceProxy.sendReport(hidConnectedDevice, 1, report);
                    Thread.sleep(12);
                    hidDeviceProxy.sendReport(hidConnectedDevice, 1, new byte[8]);
                    Thread.sleep(12);
                }
            } catch (Exception ex) {
                updateStatusUI("Pipeline Error", "btnDisconnect");
            } finally {
                try { Thread.sleep(250); } catch (InterruptedException ignored) {} // Delay buffer before unlock
                isTyping = false; // Unlock
            }
        }).start();
    }

    private byte[] charToHidReport(char c) {
        byte mod = 0; byte key = 0;
        if (c >= 'a' && c <= 'z') key = (byte) (c - 'a' + 4);
        else if (c >= 'A' && c <= 'Z') { mod = 2; key = (byte) (c - 'A' + 4); }
        else if (c >= '1' && c <= '9') key = (byte) (c - '1' + 30);
        else if (c == '0') key = 39;
        else if (c == '\n') key = 40;
        else if (c == ' ') key = 44;
        else if (c == '-') key = 45;
        else if (c == '=') key = 46;
        else if (c == '[') key = 47;
        else if (c == ']') key = 48;
        else if (c == '\\') key = 49;
        else if (c == ';') key = 51;
        else if (c == '\'') key = 52;
        else if (c == ',') key = 54;
        else if (c == '.') key = 55;
        else if (c == '/') key = 56;
        else if (c == '_') { mod = 2; key = 45; }
        else if (c == '+') { mod = 2; key = 46; }
        else if (c == ':') { mod = 2; key = 51; }
        return new byte[]{mod, 0, key, 0, 0, 0, 0, 0};
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
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
        screenHandler.removeCallbacks(screenOffRunnable);
        if (toneGenerator != null) toneGenerator.release();
        if (hidDeviceProxy != null) bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDeviceProxy);
    }

    // Modern Camera Overlay
    private static class BarcodeOverlayView extends View {
        private final Paint bracketPaint;
        private final Paint boxPaint;
        private final RectF calculatedRect = new RectF();
        private boolean hasTarget = false;
        private final float padding = 40f;
        private final float bracketLength = 60f;

        public BarcodeOverlayView(Context context) {
            super(context);

            bracketPaint = new Paint();
            bracketPaint.setColor(color("cardStroke"));
            bracketPaint.setStyle(Paint.Style.STROKE);
            bracketPaint.setStrokeWidth(12f);
            bracketPaint.setStrokeCap(Paint.Cap.ROUND);
            bracketPaint.setAntiAlias(true);

            boxPaint = new Paint();
            boxPaint.setColor(color("accentGreen")); // Scan target box, matches accent palette
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(8f);
            boxPaint.setAntiAlias(true);
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

            // Draw 4 corner brackets
            canvas.drawLine(padding, padding + bracketLength, padding, padding, bracketPaint);
            canvas.drawLine(padding, padding, padding + bracketLength, padding, bracketPaint);

            canvas.drawLine(w - padding - bracketLength, padding, w - padding, padding, bracketPaint);
            canvas.drawLine(w - padding, padding, w - padding, padding + bracketLength, bracketPaint);

            canvas.drawLine(padding, h - padding - bracketLength, padding, h - padding, bracketPaint);
            canvas.drawLine(padding, h - padding, padding + bracketLength, h - padding, bracketPaint);

            canvas.drawLine(w - padding - bracketLength, h - padding, w - padding, h - padding, bracketPaint);
            canvas.drawLine(w - padding, h - padding, w - padding, h - padding - bracketLength, bracketPaint);

            // Draw Dynamic Green Bounding Box around the code
            if (hasTarget) {
                canvas.drawRoundRect(calculatedRect, 16f, 16f, boxPaint);
            }
        }
    }
}