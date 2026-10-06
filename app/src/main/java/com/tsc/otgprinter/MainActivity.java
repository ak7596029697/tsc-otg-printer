package com.tsc.otgprinter;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.tsc.otgprinter.USB_PERMISSION";
    private static final int PICK_PDF_FILE = 1;
    private static final String PREF_NAME = "ThermalPrinterPref";
    private static final String KEY_PAPER_INDEX = "saved_paper_index";

    private TextView tvFileInfo, tvProgress, tvPageIndicator, tvCurrentPaperSize;
    private EditText etPageRange;
    private ImageView ivPreview;
    private Spinner spPaperSize;
    private Button btnSelectPdf, btnPrint, btnPrevPage, btnNextPage;
    private Uri pdfUri;
    private UsbManager usbManager;
    private UsbDevice targetDevice;
    private SharedPreferences sharedPreferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int totalPdfPages = 0;
    private int currentPreviewPage = 0;

    // পেপার সাইজের তালিকা (3x5 প্রথমে রাখা হয়েছে)
    private final String[] paperOptions = {
        "3x5 inch (75x125 mm)",
        "4x6 inch (100x150 mm) - Standard",
        "4x4 inch (100x100 mm)",
        "2x4 inch (50x100 mm)",
        "4x2 inch (100x50 mm)"
    };

    // প্রিন্টার ডটস এবং মিলিমিটার মাপ
    private final int[][] paperDimensionsMm = {
        {75, 125},  // 3x5
        {100, 150}, // 4x6
        {100, 100}, // 4x4
        {50, 100},  // 2x4
        {100, 50}   // 4x2
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            mainHandler.post(() -> {
                if (tvProgress != null) {
                    tvProgress.setText("Fatal: " + throwable.getMessage());
                }
                Toast.makeText(getApplicationContext(), "Error: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
            });
        });

        setContentView(R.layout.activity_main);

        tvFileInfo = findViewById(R.id.tvFileInfo);
        tvProgress = findViewById(R.id.tvProgress);
        tvPageIndicator = findViewById(R.id.tvPageIndicator);
        tvCurrentPaperSize = findViewById(R.id.tvCurrentPaperSize);
        etPageRange = findViewById(R.id.etPageRange);
        ivPreview = findViewById(R.id.ivPreview);
        spPaperSize = findViewById(R.id.spPaperSize);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        btnPrint = findViewById(R.id.btnPrint);
        btnPrevPage = findViewById(R.id.btnPrevPage);
        btnNextPage = findViewById(R.id.btnNextPage);

        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // স্পিনার ড্রপডাউন ও মেমরি সেটআপ
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, paperOptions);
        spPaperSize.setAdapter(adapter);

        // শেষবার সেভ করা সাইজ লোড করা
        int savedIndex = sharedPreferences.getInt(KEY_PAPER_INDEX, 0);
        if (savedIndex >= paperOptions.length) savedIndex = 0;
        spPaperSize.setSelection(savedIndex);
        updateBannerDisplay(savedIndex);

        spPaperSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateBannerDisplay(position);

                // মেমরিতে পাকাপাকি সেভ
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(KEY_PAPER_INDEX, position);
                editor.apply();

                // সাইজ বদলালে প্রিভিউ রিফ্রেশ করা
                if (pdfUri != null && totalPdfPages > 0) {
                    renderPreviewPage(currentPreviewPage);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        btnSelectPdf.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(intent, PICK_PDF_FILE);
            } catch (Exception e) {
                showToast("Picker error: " + e.getMessage());
            }
        });

        btnPrevPage.setOnClickListener(v -> {
            if (currentPreviewPage > 0) {
                currentPreviewPage--;
                renderPreviewPage(currentPreviewPage);
            }
        });

        btnNextPage.setOnClickListener(v -> {
            if (currentPreviewPage < totalPdfPages - 1) {
                currentPreviewPage++;
                renderPreviewPage(currentPreviewPage);
            }
        });

        btnPrint.setOnClickListener(v -> checkUsbAndPrint());

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void updateBannerDisplay(int index) {
        if (tvCurrentPaperSize != null) {
            tvCurrentPaperSize.setText("Active Label: " + paperOptions[index]);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF_FILE && resultCode == RESULT_OK && data != null) {
            pdfUri = data.getData();
            if (pdfUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(pdfUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}

                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(pdfUri, "r")) {
                    if (pfd != null) {
                        PdfRenderer renderer = new PdfRenderer(pfd);
                        totalPdfPages = renderer.getPageCount();
                        renderer.close();

                        currentPreviewPage = 0;
                        renderPreviewPage(currentPreviewPage);

                        tvFileInfo.setText("PDF Loaded! Total Pages: " + totalPdfPages);
                        btnPrint.setEnabled(true);
                    }
                } catch (Exception e) {
                    tvFileInfo.setText("PDF issue: " + e.getMessage());
                }
            }
        }
    }

    // প্রিভিউতেও সঠিক অনুপাতে ঘোরানো ছবি দেখানো
    private void renderPreviewPage(int pageIndex) {
        if (pdfUri == null || totalPdfPages == 0) return;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(pdfUri, "r")) {
            if (pfd != null) {
                PdfRenderer renderer = new PdfRenderer(pfd);
                PdfRenderer.Page page = renderer.openPage(pageIndex);

                int selectedIndex = spPaperSize.getSelectedItemPosition();
                int widthMm = paperDimensionsMm[selectedIndex][0];
                int heightMm = paperDimensionsMm[selectedIndex][1];

                // প্রিভিউ স্কেলিং
                int previewW = (widthMm * 4);
                int previewH = (heightMm * 4);

                Bitmap renderedBmp = renderPageAutoRotated(page, previewW, previewH);
                page.close();
                renderer.close();

                ivPreview.setImageBitmap(renderedBmp);
                tvPageIndicator.setText("Page " + (pageIndex + 1) + "/" + totalPdfPages);

                btnPrevPage.setEnabled(pageIndex > 0);
                btnNextPage.setEnabled(pageIndex < totalPdfPages - 1);
            }
        } catch (Exception ignored) {}
    }

    // পেজ যদি ল্যান্ডস্কেপ (Meesho) হয় তবে অটোমেটিক ৯০ ডিগ্রি ঘুরিয়ে নিখুঁত খাড়া করার মেথড
    private Bitmap renderPageAutoRotated(PdfRenderer.Page page, int targetW, int targetH) {
        int pw = page.getWidth();
        int ph = page.getHeight();
        boolean isLandscape = pw > ph;

        int rw = isLandscape ? targetH : targetW;
        int rh = isLandscape ? targetW : targetH;

        Bitmap tempBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tempBmp);
        canvas.drawColor(Color.WHITE);
        page.render(tempBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        if (isLandscape) {
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotatedBmp = Bitmap.createBitmap(tempBmp, 0, 0, tempBmp.getWidth(), tempBmp.getHeight(), matrix, true);
            tempBmp.recycle();
            return rotatedBmp;
        }

        return tempBmp;
    }

    private void checkUsbAndPrint() {
        try {
            if (pdfUri == null) {
                showToast("Select a PDF first!");
                return;
            }

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            targetDevice = null;

            if (deviceList != null && !deviceList.isEmpty()) {
                for (UsbDevice device : deviceList.values()) {
                    targetDevice = device;
                    break;
                }
            }

            if (targetDevice == null) {
                showToast("No OTG Printer detected! Check cable.");
                return;
            }

            if (usbManager.hasPermission(targetDevice)) {
                runBackgroundPrint();
            } else {
                Intent intent = new Intent(ACTION_USB_PERMISSION);
                intent.setPackage(getPackageName());

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }

                PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
                usbManager.requestPermission(targetDevice, permissionIntent);
            }
        } catch (Exception e) {
            updateStatus("Check Error: " + e.getMessage());
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            try {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    synchronized (this) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                            runBackgroundPrint();
                        } else {
                            showToast("Permission Denied for USB!");
                        }
                    }
                }
            } catch (Exception e) {
                updateStatus("Permission Error: " + e.getMessage());
            }
        }
    };

    private void runBackgroundPrint() {
        btnPrint.setEnabled(false);
        updateStatus("Starting print engine...");

        int selectedIndex = spPaperSize.getSelectedItemPosition();
        int widthMm = paperDimensionsMm[selectedIndex][0];
        int heightMm = paperDimensionsMm[selectedIndex][1];

        // 203 DPI: 1 mm = 8 dots
        final int targetWidth = widthMm * 8;
        final int targetHeight = (int) (heightMm * 7.5); // ব্যালেন্সড হাইট রেন্ডারিং
        final int finalCanvasHeight = heightMm * 8;
        final String sizeCmd = "SIZE " + widthMm + " mm, " + heightMm + " mm\n";

        new Thread(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            UsbDeviceConnection connection = null;

            try {
                pfd = getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pfd == null) throw new Exception("Cannot access PDF descriptor");

                renderer = new PdfRenderer(pfd);
                int total = renderer.getPageCount();

                List<Integer> pagesToPrint = parsePageSelection(etPageRange.getText().toString().trim(), total);

                UsbInterface usbInterface = null;
                UsbEndpoint endpointOut = null;

                for (int i = 0; i < targetDevice.getInterfaceCount(); i++) {
                    UsbInterface uif = targetDevice.getInterface(i);
                    for (int j = 0; j < uif.getEndpointCount(); j++) {
                        UsbEndpoint ep = uif.getEndpoint(j);
                        if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                            usbInterface = uif;
                            endpointOut = ep;
                            break;
                        }
                    }
                    if (endpointOut != null) break;
                }

                if (usbInterface == null || endpointOut == null) {
                    throw new Exception("Printer OUT endpoint not recognized!");
                }

                connection = usbManager.openDevice(targetDevice);
                if (connection == null) {
                    throw new Exception("USB Connection failed to open.");
                }

                boolean claimed = connection.claimInterface(usbInterface, true);
                if (!claimed) {
                    throw new Exception("Could not claim USB printer interface.");
                }

                for (int pageIdx : pagesToPrint) {
                    final int currPage = pageIdx + 1;
                    updateStatus("Sending page " + currPage + " to printer...");

                    PdfRenderer.Page page = renderer.openPage(pageIdx);
                    Bitmap labelBitmap = renderPageAutoRotated(page, targetWidth, targetHeight);
                    page.close();

                    Bitmap printCanvas = Bitmap.createBitmap(targetWidth, finalCanvasHeight, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(printCanvas);
                    canvas.drawColor(Color.WHITE);

                    // টপ মার্জিন অ্যাডজাস্ট
                    int topOffset = (finalCanvasHeight - targetHeight) / 2;
                    if (topOffset < 0) topOffset = 0;
                    canvas.drawBitmap(labelBitmap, 0, topOffset, null);
                    labelBitmap.recycle();

                    byte[] tsplCommands = buildTsplBitmapCommand(printCanvas, sizeCmd);
                    printCanvas.recycle();

                    int offset = 0;
                    int chunkSize = 4096;
                    while (offset < tsplCommands.length) {
                        int len = Math.min(chunkSize, tsplCommands.length - offset);
                        int res = connection.bulkTransfer(endpointOut, tsplCommands, offset, len, 5000);
                        if (res < 0) {
                            throw new Exception("USB Transfer error at block offset: " + offset);
                        }
                        offset += res;
                    }
                }

                updateStatus("Printed successfully!");
                showToast("Done! Printed successfully.");

            } catch (Throwable e) {
                updateStatus("Error: " + e.getMessage());
                showToast("Failed: " + e.getMessage());
            } finally {
                try { if (connection != null) connection.close(); } catch (Exception ignored) {}
                try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
                mainHandler.post(() -> btnPrint.setEnabled(true));
            }
        }).start();
    }

    private void updateStatus(String msg) {
        mainHandler.post(() -> {
            if (tvProgress != null) tvProgress.setText(msg);
        });
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private List<Integer> parsePageSelection(String input, int total) {
        List<Integer> list = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            for (int i = 0; i < total; i++) list.add(i);
            return list;
        }

        try {
            if (input.contains("-")) {
                String[] parts = input.split("-");
                int start = Math.max(1, Integer.parseInt(parts[0].trim()));
                int end = Math.min(total, Integer.parseInt(parts[1].trim()));
                for (int i = start; i <= end; i++) list.add(i - 1);
            } else {
                int p = Integer.parseInt(input);
                if (p >= 1 && p <= total) list.add(p - 1);
            }
        } catch (Exception e) {
            for (int i = 0; i < total; i++) list.add(i);
        }
        return list;
    }

    private byte[] buildTsplBitmapCommand(Bitmap bitmap, String sizeCommand) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int widthBytes = (width + 7) / 8;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            String init = sizeCommand + "GAP 3 mm, 0 mm\nDIRECTION 1\nCLS\nBITMAP 0,0," + widthBytes + "," + height + ",0,";
            baos.write(init.getBytes());

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < widthBytes; x++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int pixelX = x * 8 + bit;
                        if (pixelX < width) {
                            int pixel = bitmap.getPixel(pixelX, y);
                            int luminance = (int) (0.299 * ((pixel >> 16) & 0xFF) + 0.587 * ((pixel >> 8) & 0xFF) + 0.114 * (pixel & 0xFF));
                            if (luminance >= 128) {
                                b |= (1 << (7 - bit));
                            }
                        }
                    }
                    baos.write(b);
                }
            }
            baos.write("\nPRINT 1,1\n".getBytes());
        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }
}
