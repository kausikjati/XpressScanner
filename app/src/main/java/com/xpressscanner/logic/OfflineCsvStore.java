package com.xpressscanner.logic;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OfflineCsvStore {
    private static final String DIRECTORY_NAME = "offline_scan_csv";
    private static final String CSV_HEADER = "AWB Number,Date&Time\n";

    private final Context context;
    private final SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private final SimpleDateFormat displayFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public OfflineCsvStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized CsvFile createCsv(List<ScanRow> rows) throws IOException {
        File directory = getDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create offline CSV directory");
        }
        File file = new File(directory, "offline_scans_" + fileNameFormat.format(new Date()) + ".csv");
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(CSV_HEADER.getBytes(StandardCharsets.UTF_8));
            for (ScanRow row : rows) {
                String line = escapeCsv(row.awbNumber) + "," + escapeCsv(displayFormat.format(new Date(row.timestampMillis))) + "\n";
                outputStream.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return CsvFile.from(file);
    }

    public synchronized List<CsvFile> listCsvFiles() {
        File[] files = getDirectory().listFiles((dir, name) -> name.endsWith(".csv"));
        List<CsvFile> csvFiles = new ArrayList<>();
        if (files == null) return csvFiles;
        for (File file : files) csvFiles.add(CsvFile.from(file));
        csvFiles.sort((left, right) -> Long.compare(right.lastModifiedMillis, left.lastModifiedMillis));
        return csvFiles;
    }

    public synchronized void copyTo(CsvFile csvFile, OutputStream outputStream) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(csvFile.file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    public Uri getShareUri(CsvFile csvFile) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", csvFile.file);
    }

    private File getDirectory() {
        return new File(context.getFilesDir(), DIRECTORY_NAME);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        boolean needsEscaping = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsEscaping ? "\"" + escaped + "\"" : escaped;
    }

    public static class ScanRow {
        public final String awbNumber;
        public final long timestampMillis;

        public ScanRow(String awbNumber, long timestampMillis) {
            this.awbNumber = awbNumber;
            this.timestampMillis = timestampMillis;
        }
    }

    public static class CsvFile {
        public final File file;
        public final String name;
        public final long lastModifiedMillis;

        private CsvFile(File file) {
            this.file = file;
            this.name = file.getName();
            this.lastModifiedMillis = file.lastModified();
        }

        private static CsvFile from(File file) {
            return new CsvFile(file);
        }
    }
}
