package com.xpressscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    // Standard USB HID Keyboard Descriptor
    private static final byte[] HID_KEYBOARD_DESCRIPTOR = {
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x06, // Usage (Keyboard)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0x05, (byte) 0x07, // Usage Page (Key Codes)
            (byte) 0x19, (byte) 0xE0, // Usage Minimum (224)
            (byte) 0x29, (byte) 0xE7, // Usage Maximum (231)
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x25, (byte) 0x01, // Logical Maximum (1)
            (byte) 0x75, (byte) 0x01, // Report Size (1)
            (byte) 0x95, (byte) 0x08, // Report Count (8)
            (byte) 0x81, (byte) 0x02, // Input (Data, Variable, Absolute)
            (byte) 0x75, (byte) 0x08, // Report Size (8)
            (byte) 0x95, (byte) 0x01, // Report Count (1)
            (byte) 0x81, (byte) 0x01, // Input (Constant)
            (byte) 0x05, (byte) 0x08, // Usage Page (LEDs)
            (byte) 0x19, (byte) 0x01, // Usage Minimum (1)
            (byte) 0x29, (byte) 0x05, // Usage Maximum (5)
            (byte) 0x75, (byte) 0x01, // Report Size (1)
            (byte) 0x95, (byte) 0x05, // Report Count (5)
            (byte) 0x91, (byte) 0x02, // Output (Data, Variable, Absolute)
            (byte) 0x75, (byte) 0x03, // Report Size (3)
            (byte) 0x95, (byte) 0x01, // Report Count (1)
            (byte) 0x91, (byte) 0x01, // Output (Constant)
            (byte) 0x05, (byte) 0x07, // Usage Page (Key Codes)
            (byte) 0x19, (byte) 0x00, // Usage Minimum (0)
            (byte) 0x29, (byte) 0x65, // Usage Maximum (101)
            (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
            (byte) 0x25, (byte) 0x65, // Logical Maximum (101)
            (byte) 0x75, (byte) 0x08, // Report Size (8)
            (byte) 0x95, (byte) 0x06, // Report Count (6)
            (byte) 0x81, (byte) 0x00, // Input (Data, Array)
            (byte) 0xC0               // End Collection
    };

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private PreviewView previewView;
    private TextView statusText;
    private TextView lastScanText;
    private EditText manualInput;
    private Button deviceButton;

    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private BluetoothDevice selectedDevice;

    // HID Profile Variables
    private BluetoothHidDevice hidDeviceProxy;
    private BluetoothDevice hidConnectedDevice;

    private String lastSent = "";
    private long lastSentAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scanner = BarcodeScanning.getClient();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        buildLayout();
        requestNeededPermissions();
    }

    private void buildLayout() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(root);

        TextView titleText = new TextView(this);
        titleText.setText("Xpress HID Scanner");
        titleText.setTextSize(24);
        titleText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(titleText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setText("Initializing Bluetooth HID keyboard profile...");
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText);

        deviceButton = new Button(this);
        deviceButton.setText("Choose Bluetooth device");
        deviceButton.setAllCaps(false);
        deviceButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });
        root.addView(deviceButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setGravity(Gravity.CENTER);
        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });
        Button connectButton = new Button(this);
        connectButton.setText("Connect (HID)");
        connectButton.setAllCaps(false);
        connectButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) connectSelectedDevice();
        });
        connectionRow.addView(refreshButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        connectionRow.addView(connectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(connectionRow);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280));
        previewParams.setMargins(0, dp(12), 0, dp(12));
        root.addView(previewView, previewParams);

        lastScanText = new TextView(this);
        lastScanText.setText("Last scan: none");
        lastScanText.setTextSize(16);
        lastScanText.setPadding(0, 0, 0, dp(8));
        root.addView(lastScanText);

        LinearLayout manualPanel = new LinearLayout(this);
        manualPanel.setOrientation(LinearLayout.VERTICAL);
        manualPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView manualTitle = new TextView(this);
        manualTitle.setText("Manual entry");
        manualTitle.setTextSize(18);
        manualPanel.addView(manualTitle);

        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setGravity(Gravity.CENTER_VERTICAL);
        manualInput = new EditText(this);
        manualInput.setSingleLine(true);
        manualInput.setHint("Type barcode or number");
        manualInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        manualInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendManualValue();
                return true;
            }
            return false;
        });
        Button sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setAllCaps(false);
        sendButton.setOnClickListener(v -> sendManualValue());
        manualRow.addView(manualInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        manualRow.addView(sendButton, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT));
        manualPanel.addView(manualRow);
        root.addView(manualPanel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(scrollView);
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
                statusText.setText("Camera permission is required.");
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
                "Xpress HID Scanner",
                "Android Bluetooth Scanner",
                "Xpress",
                (byte) 0x00,
                HID_KEYBOARD_DESCRIPTOR
        );

        hidDeviceProxy.registerApp(sdpSettings, null, null, ContextCompat.getMainExecutor(this), new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                if (registered) {
                    runOnUiThread(() -> statusText.setText("Keyboard ready. Choose a device."));
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    hidConnectedDevice = device;
                    String name = device.getName() == null ? "Unknown" : device.getName();
                    runOnUiThread(() -> statusText.setText("Connected to " + name + " as keyboard."));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    hidConnectedDevice = null;
                    runOnUiThread(() -> statusText.setText("Keyboard disconnected."));
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        pairedDevices.clear();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            deviceButton.setText("Bluetooth unavailable/off");
            return;
        }

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevices.addAll(bondedDevices);

        String[] labels = new String[pairedDevices.size()];
        for (int i = 0; i < pairedDevices.size(); i++) {
            BluetoothDevice device = pairedDevices.get(i);
            String name = device.getName() == null ? "Unknown device" : device.getName();
            labels[i] = name + "\n" + device.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Bluetooth device")
                .setItems(labels, (dialog, which) -> {
                    selectedDevice = pairedDevices.get(which);
                    String name = selectedDevice.getName() == null ? "Unknown device" : selectedDevice.getName();
                    deviceButton.setText(name);
                })
                .show();
    }

    @SuppressLint("MissingPermission")
    private void connectSelectedDevice() {
        if (selectedDevice == null) {
            toast("Choose a paired PC/Mac first.");
            return;
        }
        if (hidDeviceProxy == null) {
            toast("HID Profile not ready. Restart app.");
            return;
        }
        statusText.setText("Connecting HID...");
        hidDeviceProxy.connect(selectedDevice);
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (ExecutionException | InterruptedException | RuntimeException ex) {
                statusText.setText("Unable to start camera: " + ex.getMessage());
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
                    for (Barcode barcode : barcodes) {
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
        if (value.equals(lastSent) && now - lastSentAt < 1500) return;
        lastSent = value;
        lastSentAt = now;
        runOnUiThread(() -> lastScanText.setText("Last scan: " + value));
        sendValue(value);
    }

    private void sendManualValue() {
        String value = manualInput.getText().toString().trim();
        if (value.isEmpty()) return;
        sendValue(value);
        manualInput.setText("");
    }

    @SuppressLint("MissingPermission")
    private void sendValue(String value) {
        if (hidDeviceProxy == null || hidConnectedDevice == null) {
            toast("Not connected as a keyboard to a PC/Mac");
            return;
        }
        new Thread(() -> {
            try {
                // Send characters one by one
                for (char c : (value + "\n").toCharArray()) {
                    byte[] report = charToHidReport(c);
                    hidDeviceProxy.sendReport(hidConnectedDevice, 1, report);
                    Thread.sleep(15); // Wait for OS to register KeyDown
                    hidDeviceProxy.sendReport(hidConnectedDevice, 1, new byte[8]); // Send KeyUp
                    Thread.sleep(15); // Wait for OS to register KeyUp
                }
                runOnUiThread(() -> toast("Sent: " + value));
            } catch (Exception ex) {
                runOnUiThread(() -> statusText.setText("Error typing: " + ex.getMessage()));
            }
        }).start();
    }

    // Translates standard characters into USB HID Keyboard raw hex scancodes
    private byte[] charToHidReport(char c) {
        byte mod = 0; // Modifier (0x02 = Left Shift)
        byte key = 0; // Keycode

        if (c >= 'a' && c <= 'z') key = (byte) (c - 'a' + 4);
        else if (c >= 'A' && c <= 'Z') { mod = 2; key = (byte) (c - 'A' + 4); }
        else if (c >= '1' && c <= '9') key = (byte) (c - '1' + 30);
        else if (c == '0') key = 39;
        else if (c == '\n') key = 40; // Enter
        else if (c == ' ') key = 44; // Space
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

            // Symbols needing SHIFT
        else if (c == '_') { mod = 2; key = 45; }
        else if (c == '+') { mod = 2; key = 46; }
        else if (c == ':') { mod = 2; key = 51; }

        // Return standard 8-byte HID input report
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
        if (hidDeviceProxy != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDeviceProxy);
        }
    }
}