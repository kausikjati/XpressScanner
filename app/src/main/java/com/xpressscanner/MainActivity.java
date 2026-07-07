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
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Space;
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

import java.util.ArrayList;
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
    private TextView statusText;
    private TextView lastScanText;
    private EditText manualInput;
    private Button deviceButton;
    private Button connectButton;

    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private BluetoothDevice selectedDevice;

    private BluetoothHidDevice hidDeviceProxy;
    private BluetoothDevice hidConnectedDevice;
    private ToneGenerator toneGenerator;

    private String lastSent = "";
    private long lastSentAt = 0L;

    // Screen Wake
    private final Handler screenHandler = new Handler(Looper.getMainLooper());
    private final Runnable screenOffRunnable = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        scanner = BarcodeScanning.getClient();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        buildLayout();
        requestNeededPermissions();
        resetScreenTimeout();
    }

    // Locked Dark Theme Color Engine
    private int color(String key) {
        switch(key) {
            case "bg": return Color.parseColor("#0F172A"); // Slate 900
            case "card": return Color.parseColor("#1E293B"); // Slate 800
            case "pillDefault": return Color.parseColor("#334155"); // Slate 700
            case "textMain": return Color.parseColor("#F8FAFC"); // Slate 50
            case "textSub": return Color.parseColor("#94A3B8"); // Slate 400
            case "btnNormal": return Color.parseColor("#334155");
            case "btnRefresh": return Color.parseColor("#475569");
            case "inputBg": return Color.parseColor("#0F172A");
            case "inputStroke": return Color.parseColor("#334155");
            case "statusGreen": return Color.parseColor("#065F46");
            case "statusBlue": return Color.parseColor("#1E40AF");
            case "statusRed": return Color.parseColor("#991B1B");
            case "statusYellow": return Color.parseColor("#92400E");
            default: return Color.WHITE;
        }
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

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color("bg"));
        root.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // Enforce Safe Area
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(16) + insets.left, dp(20) + insets.top, dp(16) + insets.right, dp(20) + insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Top Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(16));
        headerRow.setLayoutParams(headerParams);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER);
        statusCard.setBackground(createCardDrawable(color("pillDefault"), 24));
        statusCard.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusCard.setLayoutParams(cardParams);

        statusText = new TextView(this);
        statusText.setText("🔄 Initializing system...");
        statusText.setTextSize(14);
        statusText.setTextColor(color("textMain"));
        statusText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusCard.addView(statusText);
        headerRow.addView(statusCard);

        // Info Button
        ImageButton infoButton = new ImageButton(this);
        infoButton.setImageResource(android.R.drawable.ic_dialog_info);
        infoButton.setBackground(null);
        infoButton.setColorFilter(color("textMain"));
        infoButton.setPadding(dp(12), dp(10), dp(4), dp(10));
        infoButton.setOnClickListener(v -> showInstructionsDialog());
        headerRow.addView(infoButton);

        root.addView(headerRow);

        // Bluetooth Panel
        LinearLayout btPanel = new LinearLayout(this);
        btPanel.setOrientation(LinearLayout.VERTICAL);
        btPanel.setBackground(createCardDrawable(color("card"), 12));
        btPanel.setPadding(dp(16), dp(16), dp(16), dp(16));

        deviceButton = new Button(this);
        deviceButton.setText("Select Bluetooth Device");
        deviceButton.setAllCaps(false);
        deviceButton.setTextSize(15);
        deviceButton.setTextColor(color("textMain"));
        deviceButton.setBackground(createCardDrawable(color("btnNormal"), 8));
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
        refreshButton.setText("Refresh");
        refreshButton.setAllCaps(false);
        refreshButton.setTextColor(color("textMain"));
        refreshButton.setBackground(createCardDrawable(color("btnRefresh"), 8));
        refreshButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });

        connectButton = new Button(this);
        connectButton.setText("Connect (HID)");
        connectButton.setAllCaps(false);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        connectButton.setBackground(createCardDrawable(Color.parseColor("#10B981"), 8));
        connectButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) toggleConnection();
        });

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(44), 1);
        p1.setMargins(0, 0, dp(8), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(44), 1);
        p2.setMargins(dp(8), 0, 0, 0);

        actionRow.addView(refreshButton, p1);
        actionRow.addView(connectButton, p2);
        btPanel.addView(actionRow);
        root.addView(btPanel);

        // Viewport / Camera Frame
        FrameLayout cameraContainer = new FrameLayout(this);
        LinearLayout.LayoutParams cameraContainerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        cameraContainerParams.setMargins(0, dp(16), 0, dp(16));
        cameraContainer.setLayoutParams(cameraContainerParams);

        cameraContainer.setBackground(createCardDrawable(Color.parseColor("#000000"), 12));
        cameraContainer.setClipToOutline(true);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraContainer.addView(previewView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        barcodeOverlayView = new BarcodeOverlayView(this);
        cameraContainer.addView(barcodeOverlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Floating Camera Flash Toggle
        Button flashButton = new Button(this);
        flashButton.setText("⚡");
        flashButton.setTextSize(18);
        flashButton.setBackground(createCardDrawable(Color.parseColor("#88000000"), 24));
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        flashParams.gravity = Gravity.BOTTOM | Gravity.END;
        flashParams.setMargins(0, 0, dp(12), dp(12));
        flashButton.setLayoutParams(flashParams);
        flashButton.setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                isFlashOn = !isFlashOn;
                camera.getCameraControl().enableTorch(isFlashOn);
                flashButton.setBackground(createCardDrawable(Color.parseColor(isFlashOn ? "#AA10B981" : "#88000000"), 24));
            } else {
                toast("Flash not supported on this lens.");
            }
        });
        cameraContainer.addView(flashButton);

        root.addView(cameraContainer);

        // Realtime Scan Logs Card
        LinearLayout logsCard = new LinearLayout(this);
        logsCard.setBackground(createCardDrawable(Color.parseColor("#0F172A"), 12));
        logsCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        lastScanText = new TextView(this);
        lastScanText.setText("Last scan: Ready for data...");
        lastScanText.setTextColor(Color.parseColor("#34D399"));
        lastScanText.setTextSize(14);
        lastScanText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logsCard.addView(lastScanText);
        root.addView(logsCard);

        // Flexible Spacer
        Space spacer = new Space(this);
        root.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Manual Intervention Console
        LinearLayout manualPanel = new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.VERTICAL);
        manualPanel.setBackground(createCardDrawable(color("card"), 12));
        manualPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        manualParams.setMargins(0, dp(16), 0, 0);
        manualPanel.setLayoutParams(manualParams);

        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setGravity(Gravity.CENTER_VERTICAL);

        manualInput = new EditText(this);
        manualInput.setSingleLine(true);
        manualInput.setHint("Input value manually...");
        manualInput.setHintTextColor(color("textSub"));
        manualInput.setTextColor(color("textMain"));
        manualInput.setTextSize(15);
        manualInput.setImeOptions(EditorInfo.IME_ACTION_SEND);

        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(color("inputBg"));
        etBg.setStroke(dp(1), color("inputStroke"));
        etBg.setCornerRadius(dp(6));
        manualInput.setBackground(etBg);
        manualInput.setPadding(dp(12), dp(10), dp(12), dp(10));

        manualInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendManualValue();
                return true;
            }
            return false;
        });

        ImageButton sendButton = new ImageButton(this);
        sendButton.setImageResource(android.R.drawable.ic_menu_send);
        sendButton.setColorFilter(Color.WHITE);
        sendButton.setBackground(createCardDrawable(Color.parseColor("#4F46E5"), 6));
        sendButton.setOnClickListener(v -> sendManualValue());

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        inputParams.setMargins(0, 0, dp(10), 0);
        manualRow.addView(manualInput, inputParams);
        manualRow.addView(sendButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        manualPanel.addView(manualRow);
        root.addView(manualPanel);

        setContentView(root);
    }

    private void showInstructionsDialog() {
        String instructions = "1. Pair your Devices\n" +
                "Open your phone's standard Android Settings > Bluetooth, and pair your phone with your Mac/PC.\n\n" +
                "2. Start the Framework\n" +
                "Open this app. Wait for the top banner to say \"✅ Scanner ready\". Your phone is now actively mimicking a wireless keyboard.\n\n" +
                "3. Connect to Computer\n" +
                "Tap \"Select Bluetooth Device\" and choose your computer. Then tap \"Connect (HID)\".\n\n" +
                "4. Scan Barcodes\n" +
                "Click into any text field on your computer. Point your camera at a barcode. The app will automatically type the code on your computer and hit Enter!";

        new AlertDialog.Builder(this)
                .setTitle("How to Use Xpress Scanner")
                .setMessage(instructions)
                .setPositiveButton("Got it", null)
                .show();
    }

    private GradientDrawable createCardDrawable(int colorInt, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colorInt);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void updateStatusUI(String message, String colorKey) {
        runOnUiThread(() -> {
            statusText.setText(message);
            statusCard.setBackground(createCardDrawable(color(colorKey), 24));
        });
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
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
                updateStatusUI("❌ Camera denied. Scanning disabled.", "statusRed");
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
                if (registered) {
                    updateStatusUI("✅ Scanner ready. Select target.", "statusGreen");
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    hidConnectedDevice = device;
                    String name = device.getName() == null ? "Unknown Host" : device.getName();
                    updateStatusUI("🔗 Connected to: " + name, "statusBlue");

                    triggerConnectBeep();

                    runOnUiThread(() -> {
                        connectButton.setBackground(createCardDrawable(Color.parseColor("#DC2626"), 8));
                        connectButton.setText("Disconnect");
                    });
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    hidConnectedDevice = null;
                    updateStatusUI("❌ Link broken. Disconnected.", "statusRed");

                    runOnUiThread(() -> {
                        connectButton.setBackground(createCardDrawable(Color.parseColor("#10B981"), 8));
                        connectButton.setText("Connect (HID)");
                    });
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
                    String name = selectedDevice.getName() == null ? "Unknown Machine" : selectedDevice.getName();
                    deviceButton.setText(name);
                }).show();
    }

    @SuppressLint("MissingPermission")
    private void toggleConnection() {
        if (hidDeviceProxy == null) {
            toast("Core link framework dead. Restart execution.");
            return;
        }

        if (hidConnectedDevice != null) {
            updateStatusUI("⏳ Disconnecting...", "statusYellow");
            hidDeviceProxy.disconnect(hidConnectedDevice);
        } else {
            if (selectedDevice == null) {
                toast("Choose a paired configuration first.");
                return;
            }
            updateStatusUI("⏳ Connecting HID Framework...", "statusYellow");
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
                updateStatusUI("❌ Camera Init Failed", "statusRed");
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

        runOnUiThread(() -> lastScanText.setText("📡 Last payload: " + value));
        sendValue(value);
    }

    private void sendManualValue() {
        String value = manualInput.getText().toString().trim();
        if (value.isEmpty()) return;

        if (hidDeviceProxy == null || hidConnectedDevice == null) {
            triggerErrorBeep();
            toast("Transmission failed: Not connected to a computer.");
            return;
        }

        triggerSuccessBeep();
        resetScreenTimeout();
        sendValue(value);

        manualInput.setText("");
    }

    // Audio Engines
    private void triggerSuccessBeep() {
        try {
            if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception ignored) {}
    }

    private void triggerConnectBeep() {
        try {
            if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, 200);
        } catch (Exception ignored) {}
    }

    private void triggerErrorBeep() {
        try {
            if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 350);
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void sendValue(String value) {
        if (hidDeviceProxy == null || hidConnectedDevice == null) return;
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
                updateStatusUI("❌ Pipeline Error", "statusRed");
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
        if (toneGenerator != null) {
            toneGenerator.release();
        }
        if (hidDeviceProxy != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDeviceProxy);
        }
    }

    private static class BarcodeOverlayView extends View {
        private final Paint paint;
        private final RectF calculatedRect = new RectF();
        private boolean hasTarget = false;

        public BarcodeOverlayView(Context context) {
            super(context);
            paint = new Paint();
            paint.setColor(Color.parseColor("#10B981"));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6f);
            paint.setAntiAlias(true);
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
            if (hasTarget) {
                canvas.drawRoundRect(calculatedRect, 12f, 12f, paint);
            }
        }
    }
}