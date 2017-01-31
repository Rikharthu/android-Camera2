package com.example.android.camera2;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.view.CollapsibleActionView;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String LOG_TAG = MainActivity.class.getSimpleName();
    public static final String LOG_TAG_SETUP_CAMERA = "Camera Setup";

    private TextureView textureView;
    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            // TextureView is ready to use
            Log.d(LOG_TAG, "onSurfaceTextureAvailable");

            setupCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };
    private CameraDevice cameraDevice;
    private CameraDevice.StateCallback cameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = cameraDevice;
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int i) {
            camera.close();
            cameraDevice = null;
        }
    };
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundHandlerThread;
    private Handler backgroundHandler;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // compare by sizes
            long lhsArea = (long) lhs.getHeight() * lhs.getWidth();
            long rhsArea =  (long) rhs.getHeight() * rhs.getWidth();
            return Long.signum(lhsArea-rhsArea);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.textureView);
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (textureView.isAvailable()) {
            setupCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            // TextureView is still initializing. We will wait for listener's callbacks
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // setup sticky immersive mode
        if (hasFocus) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void setupCamera(int width, int height) {
        Log.d(LOG_TAG_SETUP_CAMERA, "setup start");
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        // get list of cameras on a given device
        try {
            for (String cameraId : manager.getCameraIdList()) {
                Log.d(LOG_TAG_SETUP_CAMERA, "inspecting camera: " + cameraId);

                // inspect camera characteristics for the given camera
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                // skip front-facing camera
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    Log.d(LOG_TAG_SETUP_CAMERA, "camera " + cameraId + " is fron facing -> skipping");
                    continue;
                }
                Log.d(LOG_TAG_SETUP_CAMERA, "camera: " + cameraId + " is facing back -> OK");

                // camera's output stream configurations (resolutions, formats, etc)
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Log.d(LOG_TAG_SETUP_CAMERA, "stream configuration map: " + map.toString());

                // configure orientation, width and height for preview and camera output
                // TODO не понятно
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                Log.d(LOG_TAG_SETUP_CAMERA, "device orientation: " + ORIENTATIONS.get(deviceOrientation));
                int totalRotation = sensorToDeviceRotation(characteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270; // we are in portrait mode => swap width and height
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    Log.d(LOG_TAG_SETUP_CAMERA, "swapping rotation width and height");
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                Log.d(LOG_TAG_SETUP_CAMERA, "preview size: " + previewSize.toString());

                // get first rear-facing camera
                this.cameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.d(LOG_TAG_SETUP_CAMERA, "setup end");
    }

    private void closeCamera() {
        Log.d(LOG_TAG, "closing camera...");
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void startBackgroundThread() {
        Log.d(LOG_TAG, "starting background thread");
        backgroundHandlerThread = new HandlerThread("camera2");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        Log.d(LOG_TAG, "stopping background thread");
        backgroundHandlerThread.quitSafely();
        try {
            backgroundHandlerThread.join();
            backgroundHandlerThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // FIXME что и зачем
    private static int sensorToDeviceRotation(CameraCharacteristics characteristics, int deviceOrientation) {
        // convert both values to be between 0 and 360 degrees
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // decode orientation code to degrees
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        int finalRotation = (sensorOrientation + deviceOrientation + 360) % 360;
        return finalRotation;
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<Size>();
        float aspectRatio = height/width;
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * aspectRatio               // matches aspect ratio
                    && option.getWidth() >= width && option.getHeight() >= height)  // size is equal to or bigger than required
            {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() >= 0) {
            // return the smallest of the biggest matching resolutions
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            // default
            return choices[0];
        }
    }

}
