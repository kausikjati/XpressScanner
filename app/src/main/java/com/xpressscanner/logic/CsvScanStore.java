package com.xpressscanner.logic;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CsvScanStore {
    private static final String CSV_FILE_NAME = "xpress_scans.csv";
    private static final String CSV_HEADER = "AWB Number,Date&Time\n";

    private final Context context;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public CsvScanStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void appendScan(String awbNumber) throws IOException {
        File csvFile = getCsvFile();
        boolean needsHeader = !csvFile.exists() || csvFile.length() == 0;
        try (FileOutputStream outputStream = new FileOutputStream(csvFile, true)) {
            if (needsHeader) {
                outputStream.write(CSV_HEADER.getBytes(StandardCharsets.UTF_8));
            }
            String row = escapeCsv(awbNumber) + "," + escapeCsv(dateFormat.format(new Date())) + "\n";
            outputStream.write(row.getBytes(StandardCharsets.UTF_8));
        }
    }

    public synchronized boolean hasStoredScans() {
        File csvFile = getCsvFile();
        return csvFile.exists() && csvFile.length() > CSV_HEADER.length();
    }

    public synchronized void copyTo(OutputStream outputStream) throws IOException {
        File csvFile = getCsvFile();
        if (!csvFile.exists() || csvFile.length() == 0) {
            outputStream.write(CSV_HEADER.getBytes(StandardCharsets.UTF_8));
            return;
        }
        java.nio.file.Files.copy(csvFile.toPath(), outputStream);
    }

    public Uri getShareUri() {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", getCsvFile());
    }

    public File getCsvFile() {
        return new File(context.getFilesDir(), CSV_FILE_NAME);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        boolean needsEscaping = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsEscaping ? "\"" + escaped + "\"" : escaped;
    }
}
