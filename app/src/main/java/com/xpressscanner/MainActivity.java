package com.xpressscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int PERMISSION_REQUEST = 101;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

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
    private BluetoothSocket socket;
    private OutputStream outputStream;
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
        titleText.setText("Xpress Scanner");
        titleText.setTextSize(24);
        titleText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(titleText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setText("Choose a paired Bluetooth device, connect, then scan.");
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
        connectButton.setText("Connect");
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
                statusText.setText("Camera permission is required for scanning.");
            }
            if (hasBluetoothPermission()) {
                loadPairedDevices();
            }
        }
    }

    private boolean requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<String> permissions = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!permissions.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST);
                return false;
            }
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        pairedDevices.clear();
        if (bluetoothAdapter == null) {
            selectedDevice = null;
            deviceButton.setText("Bluetooth not available");
            statusText.setText("This device does not support Bluetooth.");
            return;
        }
        if (!hasBluetoothPermission()) {
            selectedDevice = null;
            deviceButton.setText("Bluetooth permission needed");
            statusText.setText("Grant Nearby devices/Bluetooth permission, then refresh devices.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            selectedDevice = null;
            deviceButton.setText("Bluetooth is off");
            statusText.setText("Turn on Bluetooth in Android settings, then refresh devices.");
            return;
        }

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevices.addAll(bondedDevices);
        if (pairedDevices.isEmpty()) {
            selectedDevice = null;
            deviceButton.setText("No paired devices found");
            statusText.setText("Pair your PC/Mac in Android Bluetooth settings, then refresh devices.");
            return;
        }
        showBluetoothDeviceDialog();
    }

    @SuppressLint("MissingPermission")
    private void showBluetoothDeviceDialog() {
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
                    statusText.setText("Selected " + name + ". Tap Connect to start sending scans.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void connectSelectedDevice() {
        if (selectedDevice == null) {
            toast("Choose a paired PC/Mac first.");
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
            return;
        }
        if (bluetoothAdapter == null) {
            statusText.setText("This device does not support Bluetooth.");
            return;
        }
        if (!hasBluetoothPermission()) {
            toast("Bluetooth permission is required before connecting.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            toast("Turn on Bluetooth before connecting.");
            return;
        }
        BluetoothDevice device = selectedDevice;
        String deviceName = device.getName() == null ? "selected device" : device.getName();
        statusText.setText("Connecting to " + deviceName + "...");
        new Thread(() -> {
            try {
                closeConnection();
                cancelDiscoveryIfAllowed();
                socket = connectBluetoothSocket(device);
                outputStream = socket.getOutputStream();
                runOnUiThread(() -> statusText.setText("Connected to " + deviceName + ". Scan or send manually."));
            } catch (IOException | SecurityException ex) {
                runOnUiThread(() -> statusText.setText("Connection failed: " + ex.getMessage()
                        + " Make sure your PC/Mac is paired and running a Bluetooth SPP/RFCOMM receiver."));
            }
        }).start();
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket connectBluetoothSocket(BluetoothDevice device) throws IOException {
        IOException lastError = null;
        BluetoothSocket secureSocket = null;
        try {
            secureSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            secureSocket.connect();
            return secureSocket;
        } catch (IOException secureError) {
            lastError = secureError;
            closeQuietly(secureSocket);
        }

        BluetoothSocket insecureSocket = null;
        try {
            insecureSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            insecureSocket.connect();
            return insecureSocket;
        } catch (IOException insecureError) {
            lastError = insecureError;
            closeQuietly(insecureSocket);
        }

        throw lastError == null ? new IOException("Unable to connect") : lastError;
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
        if (value.isEmpty()) {
            manualInput.setError("Enter a value to send");
            return;
        }
        sendValue(value);
    }

    private void sendValue(String value) {
        new Thread(() -> {
            try {
                if (outputStream == null) throw new IOException("Bluetooth is not connected");
                outputStream.write((value + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                runOnUiThread(() -> toast("Sent: " + value));
            } catch (IOException ex) {
                runOnUiThread(() -> statusText.setText("Send failed: " + ex.getMessage()));
            }
        }).start();
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
    }

    @SuppressLint("MissingPermission")
    private void cancelDiscoveryIfAllowed() {
        if (bluetoothAdapter == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void closeQuietly(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket == null) return;
        try {
            bluetoothSocket.close();
        } catch (IOException ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void closeConnection() throws IOException {
        if (outputStream != null) outputStream.close();
        if (socket != null) socket.close();
        outputStream = null;
        socket = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanner.close();
        cameraExecutor.shutdown();
        try {
            closeConnection();
        } catch (IOException ignored) {
        }
    }
}
