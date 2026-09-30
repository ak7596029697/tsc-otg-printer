package com.tsc.otgprinter;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.tsc.otgprinter.USB_PERMISSION";
    private static final int PICK_PDF_FILE = 1;

    private TextView tvFileInfo, tvProgress;
    private EditText etPageRange;
    private Button btnSelectPdf, btnPrint;
    private Uri pdfUri;
    private UsbManager usbManager;
    private UsbDevice targetDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFileInfo = findViewById(R.id.tvFileInfo);
        tvProgress = findViewById(R.id.tvProgress);
        etPageRange = findViewById(R.id.etPageRange);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        btnPrint = findViewById(R.id.btnPrint);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        btnSelectPdf.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_PDF_FILE);
            } catch (Exception e) {
                Toast.makeText(this, "File Picker Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF_FILE && resultCode == RESULT_OK && data != null) {
            pdfUri = data.getData();
            if (pdfUri != null) {
                try {
                    final int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(pdfUri, takeFlags);
                } catch (Exception ignored) {}

                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(pdfUri, "r")) {
                    if (pfd != null) {
                        PdfRenderer renderer = new PdfRenderer(pfd);
                        int totalPages = renderer.getPageCount();
                        renderer.close();
                        tvFileInfo.setText("PDF Loaded! Total Pages: " + totalPages);
                        btnPrint.setEnabled(true);
                    }
                } catch (Exception e) {
                    tvFileInfo.setText("Error reading PDF: " + e.getMessage());
                }
            }
        }
    }

    private void checkUsbAndPrint() {
        if (pdfUri == null) {
            Toast.makeText(this, "Select a PDF first!", Toast.LENGTH_SHORT).show();
            return;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        targetDevice = null;

        for (UsbDevice device : deviceList.values()) {
            targetDevice = device;
            break;
        }

        if (targetDevice == null) {
            Toast.makeText(this, "Connect TSC Printer via OTG!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (usbManager.hasPermission(targetDevice)) {
            runBackgroundPrint();
        } else {
            int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(targetDevice, permissionIntent);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        runBackgroundPrint();
                    } else {
                        Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    private void runBackgroundPrint() {
        btnPrint.setEnabled(false);
        tvProgress.setText("Processing print job...");

        new Thread(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            UsbDeviceConnection connection = null;
            try {
                pfd = getContentResolver().openFileDescriptor(pdfUri, "r");
                if (pfd == null) throw new Exception("Cannot read PDF descriptor");
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
                    throw new Exception("Printer USB Output endpoint not found!");
                }

                connection = usbManager.openDevice(targetDevice);
                if (connection == null) {
                    throw new Exception("Unable to open USB connection to printer.");
                }

                connection.claimInterface(usbInterface, true);

                for (int pageIdx : pagesToPrint) {
                    final int currentPageNum = pageIdx + 1;
                    runOnUiThread(() -> tvProgress.setText("Printing page " + currentPageNum + "..."));

                    PdfRenderer.Page page = renderer.openPage(pageIdx);
                    // 203 DPI standard label size (4x6 inch: 812x1218)
                    Bitmap bitmap = Bitmap.createBitmap(812, 1218, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    page.close();

                    byte[] tsplCommands = buildTsplBitmapCommand(bitmap);
                    connection.bulkTransfer(endpointOut, tsplCommands, tsplCommands.length, 10000);
                }

                runOnUiThread(() -> {
                    tvProgress.setText("Printed successfully!");
                    btnPrint.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Printing Completed!", Toast.LENGTH_SHORT).show();
                });

            } catch (final Exception e) {
                runOnUiThread(() -> {
                    tvProgress.setText("Error: " + e.getMessage());
                    btnPrint.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                try { if (connection != null) connection.close(); } catch (Exception ignored) {}
                try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            }
        }).start();
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

    private byte[] buildTsplBitmapCommand(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int widthBytes = (width + 7) / 8;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            String init = "SIZE 100 mm, 150 mm\nGAP 3 mm, 0 mm\nDIRECTION 1\nCLS\nBITMAP 0,0," + widthBytes + "," + height + ",0,";
            baos.write(init.getBytes());

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < widthBytes; x++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int pixelX = x * 8 + bit;
                        if (pixelX < width) {
                            int pixel = bitmap.getPixel(pixelX, y);
                            int luminance = (int) (0.299 * ((pixel >> 16) & 0xFF) + 0.587 * ((pixel >> 8) & 0xFF) + 0.114 * (pixel & 0xFF));
                            if (luminance < 128) {
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
