package com.xpressscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
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
import androidx.lifecycle.ViewModelProvider;

import com.xpressscanner.logic.HidKeyboardMapper;
import com.xpressscanner.logic.ScanHistoryRepository;
import com.xpressscanner.ui.AppColors;
import com.xpressscanner.ui.BarcodeOverlayView;
import com.xpressscanner.viewmodel.ScannerViewModel;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RequiresApi(api = Build.VERSION_CODES.P)
public class MainActivity extends ComponentActivity {
    private static final int PERMISSION_REQUEST = 101;
    // Lower this value to send keys faster; raise it if the host misses characters.
    private static final int KEY_SEND_DELAY_MS = 10;
    private static final int KEY_SEND_SETTLE_DELAY_MS = 100;
    private static final int BULK_REPEAT_SEND_DELAY_MS = 1000;
    private static final byte[] EMPTY_HID_REPORT = new byte[8];
    private static final String CONNECTION_LOST_MESSAGE = "Connection stale. Reconnect from Windows Bluetooth.";

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService hidExecutor = Executors.newSingleThreadExecutor();
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
    private ImageButton connectButton;

    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private BluetoothDevice selectedDevice;

    // Framework State Trackers
    private BluetoothHidDevice hidDeviceProxy;
    private BluetoothDevice hidConnectedDevice;
    private boolean isAppRegistered = false;

    private ScannerViewModel viewModel;
    private ScanHistoryRepository historyRepository;

    private ToneGenerator toneGenerator;
    private SharedPreferences prefs;

    // Screen Wake
    private final Handler screenHandler = new Handler(Looper.getMainLooper());
    private final Runnable screenOffRunnable = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("XpressPrefs", MODE_PRIVATE);
        viewModel = new ViewModelProvider(this).get(ScannerViewModel.class);
        historyRepository = new ScanHistoryRepository(prefs);
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

        // FIX: Re-bind the camera whenever the user returns to MainActivity
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }

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

    public static int color(String key) {
        return AppColors.color(key);
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
            v.setPadding(dp(20) + insets.left, dp(18) + insets.top, dp(20) + insets.right, dp(18) + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Brand Row
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.setMargins(0, 0, 0, dp(12));
        brandRow.setLayoutParams(brandParams);

        ImageView logoView = new ImageView(this);
        logoView.setImageResource(R.drawable.ic_scan_logo);
        logoView.setPadding(0, 0, 0, 0);
        logoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoParams.setMargins(0, 0, dp(8), 0);
        brandRow.addView(logoView, logoParams);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTitle = new TextView(this);
        appTitle.setText("Xpress Scanner");
        appTitle.setTextSize(18);
        appTitle.setTextColor(color("textMain"));
        appTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appTitle.setSingleLine(true);
        appTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(appTitle);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("Bluetooth barcode wedge");
        appSubtitle.setTextSize(12);
        appSubtitle.setTextColor(color("textSub"));
        appSubtitle.setSingleLine(true);
        appSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(appSubtitle);
        brandRow.addView(titleBlock);

        LinearLayout offlineScanButton = new LinearLayout(this);
        offlineScanButton.setOrientation(LinearLayout.HORIZONTAL);
        offlineScanButton.setGravity(Gravity.CENTER);
        offlineScanButton.setBackground(createCardDrawable(color("card"), color("cardStroke"), 38));
        offlineScanButton.setOnClickListener(v -> startActivity(new android.content.Intent(this, OfflineScanActivity.class)));
        ImageView offlineScanIcon = new ImageView(this);
        offlineScanIcon.setImageResource(R.drawable.ic_offline_scan);
        offlineScanIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams offlineIconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        offlineIconParams.setMargins(0, 0, dp(6), 0);
        offlineScanButton.addView(offlineScanIcon, offlineIconParams);
        TextView offlineScanText = new TextView(this);
        offlineScanText.setText("OFFLINE");
        offlineScanText.setTextSize(12);
        offlineScanText.setTextColor(color("accentIndigo"));
        offlineScanText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        offlineScanButton.addView(offlineScanText);
        brandRow.addView(offlineScanButton, new LinearLayout.LayoutParams(dp(120), dp(42)));

        root.addView(brandRow);

        // Status Pill Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(16));
        headerRow.setLayoutParams(headerParams);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER);
        statusCard.setBackground(createCardDrawable(color("pillDefault"), color("cardStroke"), 28));
        statusCard.setPadding(dp(16), dp(11), dp(16), dp(12));
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
        statusText.setTextSize(15);
        statusText.setTextColor(color("textMain"));
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusCard.addView(statusText);
        headerRow.addView(statusCard);

        root.addView(headerRow);

        root.addView(sectionLabel("QUICK CONNECT"));

        // Bluetooth Panel
        LinearLayout btPanel = new LinearLayout(this);
        btPanel.setOrientation(LinearLayout.VERTICAL);
        btPanel.setBackground(createCardDrawable(color("card"), color("cardStroke"), 22));
        btPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        btPanel.setElevation(dp(1));

        LinearLayout quickConnectRow = new LinearLayout(this);
        quickConnectRow.setOrientation(LinearLayout.HORIZONTAL);
        quickConnectRow.setGravity(Gravity.CENTER_VERTICAL);

        deviceButton = new Button(this);
        deviceButton.setText("Select Bluetooth Device");
        deviceButton.setAllCaps(false);
        deviceButton.setTextSize(14);
        deviceButton.setTextColor(color("textMain"));
        deviceButton.setBackground(createCardDrawable(color("card"), 0, 12));
        deviceButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bluetooth, 0, 0, 0);
        deviceButton.setCompoundDrawablePadding(dp(12));
        deviceButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        deviceButton.setPadding(dp(16), 0, dp(16), 0);
        deviceButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });

        ImageButton refreshButton = new ImageButton(this);
        refreshButton.setBackground(createCardDrawable(color("btnRefresh"), 0, 10));
        refreshButton.setImageResource(R.drawable.ic_refresh);
        refreshButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        refreshButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        refreshButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });

        connectButton = new ImageButton(this);
        connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
        connectButton.setImageResource(R.drawable.ic_link);
        connectButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        connectButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        connectButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) toggleConnection();
        });

        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        deviceParams.setMargins(0, 0, dp(8), 0);
        quickConnectRow.addView(deviceButton, deviceParams);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        refreshParams.setMargins(0, 0, dp(8), 0);
        quickConnectRow.addView(refreshButton, refreshParams);
        quickConnectRow.addView(connectButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        btPanel.addView(quickConnectRow);
        root.addView(btPanel);

        // Viewport / Camera Frame
        FrameLayout cameraContainer = new FrameLayout(this);
        LinearLayout.LayoutParams cameraContainerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cameraContainerParams.setMargins(0, dp(14), 0, dp(12));
        cameraContainer.setLayoutParams(cameraContainerParams);

        cameraContainer.setBackground(createCardDrawable(Color.BLACK, color("cardStroke"), 28));
        cameraContainer.setClipToOutline(true);
        cameraContainer.setElevation(dp(2));

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        // Touch Listener for Tap-to-Scan Feature
        previewView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    viewModel.setCameraTouched(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    viewModel.setCameraTouched(false);
                    return true;
            }
            return false;
        });

        cameraContainer.addView(previewView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        barcodeOverlayView = new BarcodeOverlayView(this);
        cameraContainer.addView(barcodeOverlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Scan Mode Toggle Button (Bottom-Left)
        ImageButton modeButton = new ImageButton(this);
        modeButton.setImageResource(android.R.drawable.ic_media_play); // Play = Auto
        modeButton.setColorFilter(Color.WHITE);
        modeButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        modeButton.setBackground(createCardDrawable(color("overlay"), 0, 24));
        modeButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        FrameLayout.LayoutParams modeParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        modeParams.gravity = Gravity.BOTTOM | Gravity.START;
        modeParams.setMargins(dp(14), 0, 0, dp(14));
        modeButton.setLayoutParams(modeParams);
        modeButton.setOnClickListener(v -> {
            boolean autoScan = viewModel.toggleAutoScanMode();
            modeButton.setImageResource(autoScan ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause); // Pause = Hold
            modeButton.setBackground(createCardDrawable(color(autoScan ? "overlay" : "overlayActive"), 0, 24));
            toast(autoScan ? "Auto Scan Enabled" : "Tap and hold camera to scan");
        });
        cameraContainer.addView(modeButton);

        // Floating Flash Toggle (Bottom-Right)
        ImageButton flashButton = new ImageButton(this);
        flashButton.setImageResource(R.drawable.ic_flash_off);
        flashButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        flashButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        flashButton.setBackground(createCardDrawable(color("overlay"), 0, 24));
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(dp(44), dp(44));
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
        historyRow.setPadding(0, 0, 0, 0);

        ImageButton historyBtn = new ImageButton(this);
        historyBtn.setImageResource(R.drawable.ic_history);
        historyBtn.setColorFilter(color("textSub"));
        historyBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        historyBtn.setBackground(createCardDrawable(color("card"), color("cardStroke"), 18));
        historyBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams historyBtnParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        historyBtnParams.setMargins(0, 0, dp(10), 0);
        historyBtn.setLayoutParams(historyBtnParams);
        historyBtn.setOnClickListener(v -> showHistoryDialog());
        historyRow.addView(historyBtn);

        lastScanText = new TextView(this);
        lastScanText.setText("Ready for data...");
        lastScanText.setTextColor(color("textSub"));
        lastScanText.setTextSize(13);
        lastScanText.setTypeface(android.graphics.Typeface.MONOSPACE);
        lastScanText.setPadding(dp(12), 0, 0, 0);
        lastScanText.setSingleLine(true);
        lastScanText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lastScanText.setGravity(Gravity.CENTER_VERTICAL);
        lastScanText.setBackground(createCardDrawable(color("card"), color("cardStroke"), 18));

        LinearLayout.LayoutParams lastScanParams =
                new LinearLayout.LayoutParams(0, dp(48), 1f);
        historyRow.addView(lastScanText, lastScanParams);

        // Bulk Auto Sender Button
        ImageButton bulkSendBtn = new ImageButton(this);
        bulkSendBtn.setImageResource(android.R.drawable.ic_menu_agenda);
        bulkSendBtn.setColorFilter(color("textSub"));
        bulkSendBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bulkSendBtn.setBackground(createCardDrawable(color("card"), color("cardStroke"), 18));
        bulkSendBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams bulkBtnParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        bulkBtnParams.setMargins(dp(10), 0, 0, 0);
        bulkSendBtn.setLayoutParams(bulkBtnParams);
        bulkSendBtn.setOnClickListener(v -> showBulkSendDialog());
        historyRow.addView(bulkSendBtn);

        root.addView(historyRow);

        LinearLayout.LayoutParams manualLabelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        manualLabelParams.setMargins(dp(2), dp(10), 0, dp(6));
        TextView manualLabel = sectionLabel("MANUAL ENTRY");
        manualLabel.setLayoutParams(manualLabelParams);
        root.addView(manualLabel);

        // Manual Intervention Console
        LinearLayout manualPanel = new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.HORIZONTAL);
        manualPanel.setGravity(Gravity.CENTER_VERTICAL);
        manualPanel.setBackground(createCardDrawable(color("card"), color("cardStroke"), 22));
        manualPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        manualPanel.setElevation(dp(1));

        manualInput = new EditText(this);
        manualInput.setSingleLine(true);
        manualInput.setHint("Enter value manually");
        manualInput.setHintTextColor(color("textSub"));
        manualInput.setTextColor(color("textMain"));
        manualInput.setTextSize(16);
        manualInput.setImeOptions(EditorInfo.IME_ACTION_SEND);

        manualInput.setBackground(createCardDrawable(color("inputBg"), 0, 16));
        manualInput.setPadding(dp(14), dp(8), dp(14), dp(8));

        manualInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendManualValue();
                return true;
            }
            return false;
        });

        // Icon Based Manual Send Button (Height and padding refined)
        ImageButton sendButton = new ImageButton(this);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sendButton.setBackground(createCardDrawable(color("btnSend"), 0, 16));
        sendButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        sendButton.setOnClickListener(v -> sendManualValue());

        // Disable by default and reduce alpha
        sendButton.setEnabled(false);
        sendButton.setAlpha(0.5f);

        // Listen to text changes to enable/disable the button
        manualInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.toString().trim().length() > 0;
                sendButton.setEnabled(hasText);
                sendButton.setAlpha(hasText ? 1.0f : 0.5f);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // Reduced height to dp(43)
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(43), 1f);
        inputParams.setMargins(0, 0, dp(10), 0);
        manualPanel.addView(manualInput, inputParams);
        manualPanel.addView(sendButton, new LinearLayout.LayoutParams(dp(43), dp(43)));

        root.addView(manualPanel);

        setContentView(root);
    }

    // --- BULK AUTO SENDER SYSTEM ---
    private void showBulkSendDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        dialogLayout.setBackgroundColor(color("bg"));

        // Custom Header with Redesigned Close Button
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(16));
        headerRow.setLayoutParams(headerParams);

        TextView titleTv = new TextView(this);
        titleTv.setText("Bulk Auto-Sender");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(18);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackground(createCardDrawable(color("cardStroke"), 0, 20)); // Circular subtle background
        closeBtn.setColorFilter(Color.WHITE);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        headerRow.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        dialogLayout.addView(headerRow);

        // Text Area (Increased Height)
        EditText bulkInput = new EditText(this);
        bulkInput.setHint("Paste multiple barcodes here\n14344965381363\n134096105661695\n...");
        bulkInput.setHintTextColor(color("textSub"));
        bulkInput.setTextColor(color("textMain"));
        bulkInput.setTextSize(15);
        bulkInput.setBackground(createCardDrawable(color("inputBg"), color("cardStroke"), 8));
        bulkInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        bulkInput.setGravity(Gravity.TOP | Gravity.START);
        bulkInput.setSingleLine(false);
        bulkInput.setMinLines(12);
        bulkInput.setMaxLines(16);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, 0, 0, dp(16));
        dialogLayout.addView(bulkInput, inputParams);

        // Bottom Control Row
        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlRow.setLayoutParams(controlParams);

        // Left Side: Switch (Switch first, then label)
        LinearLayout switchContainer = new LinearLayout(this);
        switchContainer.setOrientation(LinearLayout.HORIZONTAL);
        switchContainer.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        Switch repeatSwitch = new Switch(this);
        // "On/Off" text removed completely
        switchContainer.addView(repeatSwitch);

        TextView repeatLabel = new TextView(this);
        repeatLabel.setText("Send Twice");
        repeatLabel.setTextColor(color("textMain"));
        repeatLabel.setTextSize(14);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(dp(8), 0, 0, 0); // Spacing between switch and text
        switchContainer.addView(repeatLabel, labelParams);

        controlRow.addView(switchContainer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Right Side: Action Buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        // Stop Button (Square Icon)
        ImageButton stopBtn = new ImageButton(this);
        GradientDrawable stopSquare = new GradientDrawable();
        stopSquare.setShape(GradientDrawable.RECTANGLE);
        stopSquare.setColor(Color.WHITE);
        stopSquare.setSize(dp(16), dp(16));
        stopSquare.setCornerRadius(dp(3));
        stopBtn.setImageDrawable(stopSquare);
        stopBtn.setScaleType(ImageView.ScaleType.CENTER);
        stopBtn.setBackground(createCardDrawable(color("btnDisconnect"), 0, 24)); // Round button
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        stopParams.setMargins(0, 0, dp(12), 0);
        btnRow.addView(stopBtn, stopParams);

        // Send Button (Send Icon)
        ImageButton sendBtn = new ImageButton(this);
        sendBtn.setImageResource(R.drawable.ic_send);
        sendBtn.setColorFilter(Color.WHITE);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        sendBtn.setBackground(createCardDrawable(color("btnConnect"), 0, 24)); // Round button
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        btnRow.addView(sendBtn, sendParams);

        controlRow.addView(btnRow);
        dialogLayout.addView(controlRow);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(dialogLayout)
                .show();

        dialog.getWindow().setBackgroundDrawable(createCardDrawable(color("card"), color("cardStroke"), 12));

        closeBtn.setOnClickListener(v -> dialog.dismiss());

        final boolean[] isBulkSending = {false};

        stopBtn.setOnClickListener(v -> {
            isBulkSending[0] = false;
            toast("Bulk sending stopped.");
            resetScreenTimeout();
        });

        sendBtn.setOnClickListener(v -> {
            if (!hasActiveHidConnection()) {
                triggerErrorBeep();
                toast("Transmission failed: Not connected.");
                return;
            }
            if (isBulkSending[0]) {
                toast("Already sending...");
                return;
            }
            isBulkSending[0] = true;
            toast("Starting bulk send...");

            runOnUiThread(() -> {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                screenHandler.removeCallbacks(screenOffRunnable);
            });

            hidExecutor.execute(() -> {
                while (isBulkSending[0]) {
                    String[] currentText = new String[1];
                    boolean[] shouldRepeatLine = new boolean[1];
                    runOnUiThread(() -> {
                        currentText[0] = bulkInput.getText().toString();
                        shouldRepeatLine[0] = repeatSwitch.isChecked();
                    });
                    try { Thread.sleep(50); } catch (InterruptedException e) {}

                    if (currentText[0] == null || currentText[0].trim().isEmpty()) {
                        isBulkSending[0] = false;
                        runOnUiThread(() -> {
                            toast("Finished bulk sending.");
                            resetScreenTimeout();
                        });
                        break;
                    }

                    String[] lines = currentText[0].split("\n");
                    String lineToSend = null;
                    int firstValidIndex = -1;
                    for (int i = 0; i < lines.length; i++) {
                        if (!lines[i].trim().isEmpty()) {
                            lineToSend = lines[i].trim();
                            firstValidIndex = i;
                            break;
                        }
                    }

                    if (lineToSend == null) {
                        isBulkSending[0] = false;
                        runOnUiThread(() -> {
                            toast("Finished bulk sending.");
                            resetScreenTimeout();
                        });
                        break;
                    }

                    while (viewModel.isTyping() && isBulkSending[0]) {
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                    }
                    if (!isBulkSending[0]) break;

                    viewModel.setTyping(true);
                    String finalLine = lineToSend;
                    int repeatCount = shouldRepeatLine[0] ? 2 : 1;

                    boolean transmissionSucceeded = false;
                    try {
                        for (int repeatIndex = 0; repeatIndex < repeatCount && isBulkSending[0]; repeatIndex++) {
                            triggerSuccessBeep();
                            int displayRepeatIndex = repeatIndex + 1;
                            runOnUiThread(() -> lastScanText.setText(repeatCount > 1
                                    ? "Bulk scan " + displayRepeatIndex + "/" + repeatCount + ": " + finalLine
                                    : "Bulk scan: " + finalLine));

                            for (int charIndex = 0; charIndex <= finalLine.length(); charIndex++) {
                                char c = charIndex == finalLine.length() ? '\n' : finalLine.charAt(charIndex);
                                byte[] report = HidKeyboardMapper.charToHidReport(c);
                                sendHidReportOrThrow(report);
                                Thread.sleep(KEY_SEND_DELAY_MS);
                                sendHidReportOrThrow(EMPTY_HID_REPORT);
                                Thread.sleep(KEY_SEND_DELAY_MS);
                            }
                            sleepQuietly(KEY_SEND_SETTLE_DELAY_MS);
                            if (repeatIndex < repeatCount - 1 && isBulkSending[0]) {
                                sleepQuietly(BULK_REPEAT_SEND_DELAY_MS);
                            }
                        }
                        transmissionSucceeded = true;
                    } catch (Exception e) {
                        handleHidSendFailure();
                        isBulkSending[0] = false;
                    } finally {
                        releaseAllHidKeysQuietly();
                    }
                    viewModel.setTyping(false);
                    processQueue();

                    if (transmissionSucceeded) {
                        final int indexToRemove = firstValidIndex;
                        runOnUiThread(() -> {
                            StringBuilder newText = new StringBuilder();
                            for (int i = 0; i < lines.length; i++) {
                                if (i == indexToRemove) continue;
                                newText.append(lines[i]);
                                if (i < lines.length - 1) newText.append("\n");
                            }
                            bulkInput.setText(newText.toString());
                            bulkInput.setSelection(0);
                        });
                    }

                    try {
                        for(int i=0; i<30; i++) {
                            if(!isBulkSending[0]) break;
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException e) {}
                }
            });
        });

        dialog.setOnDismissListener(d -> {
            isBulkSending[0] = false;
            resetScreenTimeout();
        });
    }

    // --- SCAN HISTORY SYSTEM ---

// --- SCAN HISTORY SYSTEM ---

    private void showHistoryDialog() {
        List<ScanHistoryRepository.ScanHistoryItem> historyItems = historyRepository.getRecentHistory();

        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        dialogLayout.setBackgroundColor(color("bg"));

        // Custom Header with Redesigned Close Button (matches Bulk Auto-Sender)
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(16));
        headerRow.setLayoutParams(headerParams);

        TextView titleTv = new TextView(this);
        titleTv.setText("Scan History");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(18);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackground(createCardDrawable(color("cardStroke"), 0, 20)); // Circular subtle background
        closeBtn.setColorFilter(Color.WHITE);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        headerRow.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        dialogLayout.addView(headerRow);

        // History List View (with layout weight to pin bottom button)
        ScrollView scrollView = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        if (historyItems.isEmpty()) {
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
        } else {
            for (ScanHistoryRepository.ScanHistoryItem item : historyItems) {
                listLayout.addView(createHistoryItemView(item), historyItemLayoutParams());
            }
        }

        scrollView.addView(listLayout);

        // Add scrollview with weight 1f so it expands, keeping bottom button visible
        LinearLayout.LayoutParams scrlLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        dialogLayout.addView(scrollView, scrlLp);

        // Add "Clear All" button at the bottom of the dialog
        Button clearAllBtn = null;
        if (!historyItems.isEmpty()) {
            clearAllBtn = new Button(this);
            clearAllBtn.setText("Clear All History");
            clearAllBtn.setAllCaps(false);
            clearAllBtn.setTextColor(Color.WHITE);
            clearAllBtn.setBackground(createCardDrawable(color("btnDisconnect"), 0, 8));
            LinearLayout.LayoutParams clearAllParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            clearAllParams.setMargins(0, dp(16), 0, 0);
            dialogLayout.addView(clearAllBtn, clearAllParams);
        }

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(dialogLayout)
                .show();

        dialog.getWindow().setBackgroundDrawable(createCardDrawable(color("card"), color("cardStroke"), 12));

        closeBtn.setOnClickListener(v -> dialog.dismiss());

        if (clearAllBtn != null) {
            clearAllBtn.setOnClickListener(v -> {
                historyRepository.clearHistory();
                toast("History cleared");
                dialog.dismiss();
            });
        }
    }

    private View createHistoryItemView(ScanHistoryRepository.ScanHistoryItem item) {
        LinearLayout itemCard = new LinearLayout(this);
        itemCard.setOrientation(LinearLayout.VERTICAL);
        itemCard.setBackground(createCardDrawable(color("card"), color("cardStroke"), 8));
        itemCard.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView timeTv = new TextView(this);
        timeTv.setText(item.formattedTime);
        timeTv.setTextColor(color("textSub"));
        timeTv.setTextSize(12);
        itemCard.addView(timeTv);

        TextView valTv = new TextView(this);
        valTv.setText(item.value);
        valTv.setTextColor(color("textMain"));
        valTv.setTextSize(16);
        valTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        valTv.setPadding(0, dp(4), 0, 0);
        itemCard.addView(valTv);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.setMargins(0, dp(8), 0, 0);
        btnRow.setLayoutParams(btnRowParams);

        // Updated button backgrounds to radius '24' for perfect circular pills
        ImageButton copyBtn = new ImageButton(this);
        copyBtn.setImageResource(R.drawable.ic_copy);
        copyBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        copyBtn.setBackground(createCardDrawable(color("btnRefresh"), 0, 24));
        copyBtn.setPadding(dp(11), dp(8), dp(11), dp(8));
        copyBtn.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Barcode", item.value);
            clipboard.setPrimaryClip(clip);
            toast("Copied!");
        });

        ImageButton sendBtn = new ImageButton(this);
        sendBtn.setImageResource(R.drawable.ic_send);
        sendBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sendBtn.setBackground(createCardDrawable(color("btnSend"), 0, 24));
        sendBtn.setPadding(dp(11), dp(8), dp(11), dp(8));
        sendBtn.setOnClickListener(v -> enqueueScan(item.value));

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(44), dp(36));
        btnParams.setMargins(dp(12), 0, 0, 0);
        btnRow.addView(copyBtn, btnParams);
        btnRow.addView(sendBtn, btnParams);
        itemCard.addView(btnRow);
        return itemCard;
    }

    private LinearLayout.LayoutParams historyItemLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        return lp;
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

    private int statusDotColor(String colorKey) {
        switch (colorKey) {
            case "pillConnected": return color("accentGreen");
            case "btnDisconnect": return color("accentRed");
            default: return color("textSub");
        }
    }

    @SuppressLint("MissingPermission")
    private void restoreLastDevice() {
        String savedMac = prefs.getString("last_device_mac", null);
        if (savedMac != null && bluetoothAdapter != null) {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                if (device.getAddress().equals(savedMac)) {
                    selectedDevice = device;
                    String name = device.getName() == null ? "Unknown Machine" : device.getName();
                    runOnUiThread(() -> deviceButton.setText(name));
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
                    connectButton.setImageResource(R.drawable.ic_link_off);
                });
            } else {
                hidConnectedDevice = null;
                if (isAppRegistered) {
                    updateStatusUI("Scanner ready. Select target.", "pillDefault");
                }
                runOnUiThread(() -> {
                    connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
                    connectButton.setImageResource(R.drawable.ic_link);
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

    private void showBluetoothOffMessage() {
        updateStatusUI("Your Bluetooth is off. Turn it on to connect.", "btnDisconnect");
        runOnUiThread(() -> {
            deviceButton.setText("Bluetooth is off");
            connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
            connectButton.setImageResource(R.drawable.ic_link);
        });
        toast("Your Bluetooth is off. Turn it on to connect.");
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
                "Xpress Scanner",
                "Android Bluetooth Scanner",
                "Xpress",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                HidKeyboardMapper.HID_KEYBOARD_DESCRIPTOR
        );

        // FIX: Windows strictly requires explicit QoS parameters for HID endpoints.
        // These are standard USB-HID timing defaults to prevent Windows from dropping the connection.
        BluetoothHidDeviceAppQosSettings qosSettings = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                800,   // Token rate
                9,     // Token bucket size
                0,     // Peak bandwidth
                11250, // Latency
                BluetoothHidDeviceAppQosSettings.MAX // Delay variation
        );

        // Inject qosSettings instead of passing null
        hidDeviceProxy.registerApp(sdpSettings, qosSettings, qosSettings, ContextCompat.getMainExecutor(this), new BluetoothHidDevice.Callback() {
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
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    // Instantly reset UI if Windows abruptly drops the connection
                    runOnUiThread(() -> {
                        connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
                        connectButton.setImageResource(R.drawable.ic_link);
                        updateStatusUI("Disconnected.", "btnDisconnect");
                    });
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void toggleConnection() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            showBluetoothOffMessage();
            return;
        }

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

            // FIX: Catch Windows immediate security rejections
            boolean connectInitiated = hidDeviceProxy.connect(selectedDevice);
            if (!connectInitiated) {
                updateStatusUI("Connection rejected.", "btnDisconnect");

                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Windows Blocked Connection")
                        .setMessage("Windows is blocking the keyboard profile because it cached your phone as a standard device.\n\nTo fix this:\n1. Go to Windows Bluetooth Settings.\n2. Remove/Unpair your phone.\n3. KEEP THIS APP OPEN so the scanner is running.\n4. Pair your phone again from Windows.")
                        .setPositiveButton("Got it", null)
                        .show().getWindow().setBackgroundDrawable(createCardDrawable(color("card"), color("cardStroke"), 12));
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        pairedDevices.clear();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            showBluetoothOffMessage();
            return;
        }
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevices.addAll(bondedDevices);

        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        dialogLayout.setBackgroundColor(color("bg"));

        // Custom Header with Close Button
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(16));
        headerRow.setLayoutParams(headerParams);

        TextView titleTv = new TextView(this);
        titleTv.setText("Select Host Target");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(18);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackground(createCardDrawable(color("cardStroke"), 0, 20)); // Circular button background
        closeBtn.setColorFilter(Color.WHITE);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        headerRow.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        dialogLayout.addView(headerRow);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(dialogLayout)
                .show();

        dialog.getWindow().setBackgroundDrawable(createCardDrawable(color("card"), color("cardStroke"), 12));
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        if (pairedDevices.isEmpty()) {
            LinearLayout emptyState = new LinearLayout(this);
            emptyState.setOrientation(LinearLayout.VERTICAL);
            emptyState.setGravity(Gravity.CENTER);
            emptyState.setPadding(0, dp(32), 0, dp(32));

            ImageView emptyIcon = new ImageView(this);
            emptyIcon.setImageResource(R.drawable.ic_bluetooth);
            emptyIcon.setColorFilter(color("textSub"));
            LinearLayout.LayoutParams emptyIconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            emptyIconParams.setMargins(0, 0, 0, dp(10));
            emptyState.addView(emptyIcon, emptyIconParams);

            TextView empty = new TextView(this);
            empty.setText("No paired devices found.\nPair in Android Settings first.");
            empty.setTextColor(color("textSub"));
            empty.setGravity(Gravity.CENTER);
            emptyState.addView(empty);

            listLayout.addView(emptyState);
        } else {
            for (BluetoothDevice device : pairedDevices) {
                String name = device.getName() == null ? "Unknown Machine" : device.getName();
                String mac = device.getAddress();

                // Custom Device Card
                LinearLayout itemCard = new LinearLayout(this);
                itemCard.setOrientation(LinearLayout.HORIZONTAL);
                itemCard.setGravity(Gravity.CENTER_VERTICAL);
                itemCard.setBackground(createCardDrawable(color("card"), color("cardStroke"), 12));
                itemCard.setPadding(dp(16), dp(14), dp(16), dp(14));
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 0, 0, dp(8));
                itemCard.setLayoutParams(cardParams);

                // Bluetooth Icon
                ImageView btIcon = new ImageView(this);
                btIcon.setImageResource(R.drawable.ic_bluetooth);
                btIcon.setColorFilter(color("accentIndigo"));
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(26), dp(26));
                iconParams.setMargins(0, 0, dp(16), 0);
                itemCard.addView(btIcon, iconParams);

                // Text Container (Name & MAC Address)
                LinearLayout textContainer = new LinearLayout(this);
                textContainer.setOrientation(LinearLayout.VERTICAL);

                TextView nameTv = new TextView(this);
                nameTv.setText(name);
                nameTv.setTextColor(color("textMain"));
                nameTv.setTextSize(15);
                nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                textContainer.addView(nameTv);

                TextView macTv = new TextView(this);
                macTv.setText(mac);
                macTv.setTextColor(color("textSub"));
                macTv.setTextSize(12);
                macTv.setTypeface(android.graphics.Typeface.MONOSPACE);
                textContainer.addView(macTv);

                itemCard.addView(textContainer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                // Selection Logic
                itemCard.setOnClickListener(v -> {
                    selectedDevice = device;
                    prefs.edit().putString("last_device_mac", selectedDevice.getAddress()).apply();
                    deviceButton.setText(name);
                    dialog.dismiss();
                });

                listLayout.addView(itemCard);
            }
        }

        scrollView.addView(listLayout);
        dialogLayout.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
        if (!viewModel.shouldAcceptScan(value)) return;
        enqueueScan(value);
    }

    private void enqueueScan(String value) {
        if (viewModel.isTyping()) {
            toast("Please wait, currently sending data...");
            return;
        }
        if (!hasActiveHidConnection()) {
            triggerErrorBeep();
            toast("Transmission failed: Not connected.");
            return;
        }
        viewModel.enqueueScan(value);
        processQueue();
    }

    private void sendManualValue() {
        String value = manualInput.getText().toString().trim();
        if (value.isEmpty()) return;

        if (!hasActiveHidConnection()) {
            triggerErrorBeep();
            toast("Transmission failed: Not connected to a computer.");
            return;
        }

        viewModel.enqueueScan(value);
        manualInput.setText("");
        processQueue();
    }

    // Core Queue Processor - handles overlapping scans sequentially
    private synchronized void processQueue() {
        if (viewModel.isTyping() || !viewModel.hasPendingScans()) return;
        String nextValue = viewModel.pollScan();
        if (nextValue != null) {
            sendValueFromQueue(nextValue);
        }
    }

    private void sendValueFromQueue(String value) {
        if (!hasActiveHidConnection()) {
            triggerErrorBeep();
            runOnUiThread(() -> toast("Scan failed: Not connected to a computer."));
            processQueue();
            return;
        }

        viewModel.setTyping(true);
        triggerSuccessBeep();
        resetScreenTimeout();
        historyRepository.saveScan(value);
        runOnUiThread(() -> lastScanText.setText(value));

        hidExecutor.execute(() -> {
            try {
                for (int i = 0; i <= value.length(); i++) {
                    char c = i == value.length() ? '\n' : value.charAt(i);
                    byte[] report = HidKeyboardMapper.charToHidReport(c);
                    sendHidReportOrThrow(report);
                    Thread.sleep(KEY_SEND_DELAY_MS);
                    sendHidReportOrThrow(EMPTY_HID_REPORT);
                    Thread.sleep(KEY_SEND_DELAY_MS);
                }
            } catch (Exception ex) {
                handleHidSendFailure();
            } finally {
                // FORCE RELEASE ALL KEYS on completion/error to prevent infinite loop typing on windows
                releaseAllHidKeysQuietly();

                sleepQuietly(KEY_SEND_SETTLE_DELAY_MS);
                viewModel.setTyping(false);
                processQueue(); // Automatically process the next queued item
            }
        });
    }

    @SuppressLint("MissingPermission")
    private boolean hasActiveHidConnection() {
        if (hidDeviceProxy == null || hidConnectedDevice == null) return false;
        try {
            if (hidDeviceProxy.getConnectionState(hidConnectedDevice) == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }
        } catch (RuntimeException ignored) {}
        handleHidSendFailure();
        return false;
    }

    @SuppressLint("MissingPermission")
    private void sendHidReportOrThrow(byte[] report) {
        if (!hasActiveHidConnection() || !hidDeviceProxy.sendReport(hidConnectedDevice, 0, report)) {
            throw new IllegalStateException("HID report was not accepted by the Bluetooth stack");
        }
    }

    @SuppressLint("MissingPermission")
    private void handleHidSendFailure() {
        BluetoothDevice staleDevice = hidConnectedDevice;
        hidConnectedDevice = null;
        runOnUiThread(() -> {
            connectButton.setBackground(createCardDrawable(color("btnConnect"), 0, 10));
            connectButton.setImageResource(R.drawable.ic_link); // Updated
            updateStatusUI(CONNECTION_LOST_MESSAGE, "btnDisconnect");
            toast("Bluetooth connection lost. Disconnect/reconnect Windows if needed.");
        });
        try {
            if (hidDeviceProxy != null && staleDevice != null) {
                hidDeviceProxy.disconnect(staleDevice);
            }
        } catch (RuntimeException ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void releaseAllHidKeysQuietly() {
        try {
            if (hidDeviceProxy != null && hidConnectedDevice != null) {
                hidDeviceProxy.sendReport(hidConnectedDevice, 0, EMPTY_HID_REPORT);
            }
        } catch (RuntimeException ignored) {}
    }

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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
        hidExecutor.shutdown();
        screenHandler.removeCallbacks(screenOffRunnable);
        if (toneGenerator != null) toneGenerator.release();
        if (hidDeviceProxy != null) bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDeviceProxy);
    }

    private class BarcodeOverlayView extends View {
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
            framePaint.setColor(color("cardStroke"));
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(6f);
            framePaint.setAntiAlias(true);

            boxPaint = new Paint();
            boxPaint.setColor(color("accentGreen"));
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

            // Define central rounded rectangle cutout area. Subtracted dp(5) to reduce size.
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