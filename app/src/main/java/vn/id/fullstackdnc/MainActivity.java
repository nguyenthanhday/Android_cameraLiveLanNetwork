package vn.id.fullstackdnc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import android.graphics.Matrix;
import android.widget.TextView;
import android.widget.Toast;

import fi.iki.elonen.NanoHTTPD;


public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private File imageFile;
    private SimpleHttpServer server;

    private TextView textView_url;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            String ipAddress = getDeviceIpAddress();
            String str_url = String.format("http://%s:8080/cam-hi.jpg",ipAddress);
            textView_url =(TextView) findViewById(R.id.textView_url);
            textView_url.setText(str_url);
        }
        catch (Exception e){
            Toast.makeText(this, "lỗi: "+e.getMessage(), Toast.LENGTH_SHORT).show();
        }


        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                startCamera(holder.getSurface());
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                stopCamera();
            }
        });

        imageFile = new File(getExternalFilesDir(null), "cam-hi.jpg");
        server = new SimpleHttpServer();
        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private String getDeviceIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();

        // Địa chỉ IP thực tế là một số nguyên 32-bit.
        // Để hiển thị địa chỉ IP dưới dạng chuỗi, chúng ta cần chuyển đổi từ dạng số nguyên sang dạng chuỗi.
        return android.text.format.Formatter.formatIpAddress(ip);
    }

    private void startCamera(Surface previewSurface) {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            Size imageSize = sizes[0];

            imageReader = ImageReader.newInstance(imageSize.getWidth(), imageSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                saveImage(bytes);
                image.close();
            }, null);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        cameraDevice.createCaptureSession(
                                Arrays.asList(previewSurface, imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        captureSession = session;
                                        try {
                                            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            builder.addTarget(previewSurface);
                                            builder.addTarget(imageReader.getSurface());
                                            session.setRepeatingRequest(builder.build(), null, null);
                                        } catch (CameraAccessException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    }
                                }, null
                        );
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    stopCamera();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    stopCamera();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void saveImage(byte[] bytes) {
        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        if (server != null) {
            server.stop();
        }
    }

    //    private byte[] compressImage(byte[] originalImageBytes) {
//        Bitmap originalBitmap = BitmapFactory.decodeByteArray(originalImageBytes, 0, originalImageBytes.length);
//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//        // Nén ảnh với chất lượng 70% và ghi vào ByteArrayOutputStream
//        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
//        return baos.toByteArray();
//    }
    private byte[] compressImage(byte[] originalImageBytes) {
        // Chuyển byte[] thành Bitmap
        Bitmap originalBitmap = BitmapFactory.decodeByteArray(originalImageBytes, 0, originalImageBytes.length);

        Matrix matrix = new Matrix();
        matrix.postRotate(90);

        Bitmap rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);

        // Thay đổi kích thước của ảnh
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 640, 480, true);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Nén ảnh với chất lượng 70% và ghi vào ByteArrayOutputStream
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);

        return baos.toByteArray();
    }


    private class SimpleHttpServer extends NanoHTTPD {

        public SimpleHttpServer() {
            super(8080);
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                FileInputStream fis = new FileInputStream(imageFile);
                byte[] imageBytes = new byte[(int) imageFile.length()];
                fis.read(imageBytes);
                fis.close();

                // Nén ảnh trước khi gửi
                byte[] compressedImageBytes = compressImage(imageBytes);

                return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(compressedImageBytes), compressedImageBytes.length);
            } catch (IOException e) {
                e.printStackTrace();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal Server Error");
            }
        }

    }
}