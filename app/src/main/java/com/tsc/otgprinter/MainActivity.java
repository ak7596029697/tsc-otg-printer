package com.tsc.otgprinter;

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
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.tsc.otgprinter.USB_PERMISSION";
    private static final int PICK_PDF_FILE = 1;

    private TextView tvStatus, tvFileInfo;
    private Button btnSelectPdf, btnPrint;
    private Uri pdfUri;
    private UsbManager usbManager;
    private UsbDevice targetDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvFileInfo = findViewById(R.id.tvFileInfo);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        btnPrint = findViewById(R.id.btnPrint);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        btnSelectPdf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            startActivityForResult(intent, PICK_PDF_FILE);
        });

        btnPrint.setOnClickListener(v -> checkUsbAndPrint());

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF_FILE && resultCode == RESULT_OK && data != null) {
            pdfUri = data.getData();
            tvFileInfo.setText("PDF Loaded! Ready to print.");
            btnPrint.setEnabled(true);
        }
    }

    private void checkUsbAndPrint() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        targetDevice = null;

        for (UsbDevice device : deviceList.values()) {
            targetDevice = device;
            break;
        }

        if (targetDevice == null) {
            Toast.makeText(this, "No USB/OTG Printer Detected!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (usbManager.hasPermission(targetDevice)) {
            startPrintingProcess();
        } else {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            usbManager.requestPermission(targetDevice, permissionIntent);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        startPrintingProcess();
                    } else {
                        Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    private void startPrintingProcess() {
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(pdfUri, "r");
            PdfRenderer renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();

            UsbInterface usbInterface = targetDevice.getInterface(0);
            UsbEndpoint endpointOut = null;
            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                UsbEndpoint ep = usbInterface.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    endpointOut = ep;
                    break;
                }
            }

            UsbDeviceConnection connection = usbManager.openDevice(targetDevice);
            connection.claimInterface(usbInterface, true);

            for (int i = 0; i < pageCount; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                // 4x6 inch at 203 DPI = 812 x 1218 dots
                Bitmap bitmap = Bitmap.createBitmap(812, 1218, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();

                byte[] tsplCommands = buildTsplBitmapCommand(bitmap);
                connection.bulkTransfer(endpointOut, tsplCommands, tsplCommands.length, 10000);
            }

            connection.close();
            renderer.close();
            pfd.close();
            Toast.makeText(this, "Printed " + pageCount + " labels successfully!", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "Print Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
                            int red = (pixel >> 16) & 0xFF;
                            int green = (pixel >> 8) & 0xFF;
                            int blue = pixel & 0xFF;
                            int luminance = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
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
