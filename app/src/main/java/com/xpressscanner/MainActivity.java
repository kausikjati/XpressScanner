package com.xpressscanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import android.app.Activity;
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

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 101;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private PreviewView previewView;
    private TextView statusText;
    private TextView lastScanText;
    private EditText manualInput;
    private Spinner deviceSpinner;
    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        statusText = new TextView(this);
        statusText.setText("Select a paired Bluetooth device, connect, then scan.");
        root.addView(statusText);

        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh devices");
        refreshButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) loadPairedDevices();
        });
        Button connectButton = new Button(this);
        connectButton.setText("Connect");
        connectButton.setOnClickListener(v -> {
            if (requestBluetoothPermissionIfNeeded()) connectSelectedDevice();
        });
        row.addView(refreshButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(connectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        previewView = new PreviewView(this);
        root.addView(previewView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        lastScanText = new TextView(this);
        lastScanText.setText("Last scan: none");
        root.addView(lastScanText);

        LinearLayout manualRow = new LinearLayout(this);
        manualInput = new EditText(this);
        manualInput.setHint("Type barcode / number manually");
        Button sendButton = new Button(this);
        sendButton.setText("Send");
        sendButton.setOnClickListener(v -> sendManualValue());
        manualRow.addView(manualInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        manualRow.addView(sendButton);
        root.addView(manualRow);

        setContentView(root);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
            return false;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void loadPairedDevices() {
        pairedDevices.clear();
        ArrayList<String> names = new ArrayList<>();
        if (bluetoothAdapter == null) {
            names.add("Bluetooth not available");
            statusText.setText("This device does not support Bluetooth.");
        } else if (!hasBluetoothPermission()) {
            names.add("Bluetooth permission needed");
            statusText.setText("Grant Nearby devices/Bluetooth permission, then refresh devices.");
        } else if (!bluetoothAdapter.isEnabled()) {
            names.add("Bluetooth is off");
            statusText.setText("Turn on Bluetooth in Android settings, then refresh devices.");
        } else {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                pairedDevices.add(device);
                String name = device.getName() == null ? "Unknown device" : device.getName();
                names.add(name + "\n" + device.getAddress());
            }
            if (names.isEmpty()) names.add("No paired devices found");
        }
        deviceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    @SuppressLint("MissingPermission")
    private void connectSelectedDevice() {
        int selected = deviceSpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= pairedDevices.size()) {
            toast("Pair your PC/Mac in Android Bluetooth settings first.");
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
        BluetoothDevice device = pairedDevices.get(selected);
        String deviceName = device.getName() == null ? "selected device" : device.getName();
        statusText.setText("Connecting to " + deviceName + "...");
        new Thread(() -> {
            try {
                closeConnection();
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                socket.connect();
                outputStream = socket.getOutputStream();
                runOnUiThread(() -> statusText.setText("Connected to " + deviceName));
            } catch (IOException | SecurityException ex) {
                runOnUiThread(() -> statusText.setText("Connection failed: " + ex.getMessage()));
            }
        }).start();
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
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
