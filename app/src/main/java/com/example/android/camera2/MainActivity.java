package com.example.android.camera2;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String LOG_TAG = MainActivity.class.getSimpleName();
    public static final String LOG_TAG_SETUP_CAMERA = "Camera Setup";
    private static final int PERMISSION_REQUEST_CAMERA = 0;
    private static final String FRAGMENT_DIALOG = "dialog";
    private static final String VIDEO_FOLDER_NAME = "Camera2VideoImage";
    private static final int PERMISSION_REQUEST_WRITE_EXTERNAL = 1;

    private TextureView mTextureView;
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            // TextureView is ready to use
            Log.d(LOG_TAG, "onSurfaceTextureAvailable");

            setupCamera(width, height);
            connectCamera();
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

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(LOG_TAG, "connected to camera");
            mCameraDevice = camera;
            // start the preview
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int i) {
            camera.close();
            mCameraDevice = null;
        }
    };
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private String mCameraId;
    private Size mPreviewSize;
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private ImageButton mRecordImageButton;
    private File mVideoFolder;
    private String mVideoFileName;
    private boolean mIsRecording = false;
    // maps device rotation codes to degrees
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createVideoFolder();

        mTextureView = (TextureView) findViewById(R.id.textureView);

        mRecordImageButton = (ImageButton) findViewById(R.id.videoOnlineImageButton);
        mRecordImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mIsRecording) {
                    mIsRecording = false;
                    mRecordImageButton.setImageResource(R.mipmap.btn_video_online);
                } else {
                    checkWriteStoragePermission();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            connectCamera();
        } else {
            // TextureView is still initializing. We will wait for listener's callbacks
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                // notify user and shutdown app
                ErrorDialog.newInstance("This app needs camera permission.")
                        .show(getFragmentManager(), FRAGMENT_DIALOG);
            } else {

            }
        }else if(requestCode == PERMISSION_REQUEST_WRITE_EXTERNAL) {
            if (grantResults.length != 1 || grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // permission granted
                mIsRecording = true;
                mRecordImageButton.setImageResource(R.mipmap.btn_video_busy);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }else{
                Toast.makeText(this, "App need to save videos", Toast.LENGTH_SHORT).show();
            }
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
                // 90+90=180 => no need to swap, 0+90=90 => camera in landscape, device in portraitn => swap
                int rotatedWidth = width;
                int rotatedHeight = height;
                // force enter landscape mode if not already (since camera preview resolution is in landscape mode)
                // in other words MAKE DEVICE ORIENTATION TO MATCH CAMERA PREVIEW ORIENTATION
                if (swapRotation) {
                    Log.d(LOG_TAG_SETUP_CAMERA, "swapping rotation width and height");
                    rotatedWidth = height;
                    rotatedHeight = width;
                }
                // choose surface preview size to be closest to the camera preview size while maintaining aspect ratio
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                Log.d(LOG_TAG_SETUP_CAMERA, "preview size: " + mPreviewSize.toString());

                // get first rear-facing camera
                mCameraId = cameraId;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.d(LOG_TAG_SETUP_CAMERA, "setup end");
    }

    private void connectCamera() {
        Log.d(LOG_TAG, "connecting camera");
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // check if marshmallow (need to check live permissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // check camera permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    // permission granted
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
                } else {
                    // ask for camera permission
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        // describe user why you need permission
                        // TODO make a dialogfragment
                        Toast.makeText(this, "This app requires access to camera", Toast.LENGTH_SHORT).show();
                    }
                    // request permission
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
                }
            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void startPreview() {
        Log.d(LOG_TAG, "starting preview");

        SurfaceTexture surfaceTextre = mTextureView.getSurfaceTexture();
        surfaceTextre.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTextre);

        try {
            // initialize capture request builder (preview request)
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // set output
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            Log.d(LOG_TAG, "Camera preview configured");

                            try {
                                // loop a preview request
                                session.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                        null, // callback
                                        mBackgroundHandler); // worker thread handler
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.d(LOG_TAG, "Unable to setup camera preview");
                        }
                    },
                    null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        Log.d(LOG_TAG, "closing camera...");
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void startBackgroundThread() {
        Log.d(LOG_TAG, "starting background thread");
        mBackgroundHandlerThread = new HandlerThread("camera2");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        Log.d(LOG_TAG, "stopping background thread");
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // FIXME что и зачем
    private static int sensorToDeviceRotation(CameraCharacteristics characteristics, int deviceOrientation) {
        // get camera sensor rotation
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // decode orientation code to degrees
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        int finalRotation = (sensorOrientation + deviceOrientation + 360) % 360;
        return finalRotation;
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<>();
        float aspectRatio = height / (float) width;
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * aspectRatio               // matches aspect ratio
                    && option.getWidth() >= width && option.getHeight() >= height)  // size is equal to or bigger than required
            {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            // return the smallest of the biggest matching resolutions
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            // default
            return choices[0];
        }
    }

    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // compare by sizes
            long lhsArea = (long) lhs.getHeight() * lhs.getWidth();
            long rhsArea = (long) rhs.getHeight() * rhs.getWidth();
            return Long.signum(lhsArea - rhsArea);
        }
    }

    /**
     * Create a new video folder on external storage for this application (if it was not previously created)
     */
    private void createVideoFolder() {
        File moviesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        mVideoFolder = new File(moviesDirectory, VIDEO_FOLDER_NAME);
        // check if that folder already exists (previously created)
        if (!mVideoFolder.exists()) {
            // create that folder
            mVideoFolder.mkdirs();
        }
    }

    private File createVideoFileName() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "VIDEO_" + timestamp + "_";
        File videoFile = File.createTempFile(prepend, "mp4", mVideoFolder);
        // FIXME maybe move this assignment somewhere else?
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }

    private void checkWriteStoragePermission(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED){
                mIsRecording = true;
                mRecordImageButton.setImageResource(R.mipmap.btn_video_busy);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }else{
                // ask for permission
                if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)){
                    Toast.makeText(this, "This app needs to be able to save videos", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_WRITE_EXTERNAL);
            }
        }else{
            mIsRecording = true;
            mRecordImageButton.setImageResource(R.mipmap.btn_video_busy);
            try {
                createVideoFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new android.app.AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new android.app.AlertDialog.Builder(getActivity())
                    .setMessage("This app needs camera permission")
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // request camera permission
//                            MainActivity.requestPermissions(
//                                    new String[]{Manifest.permission.CAMERA},
//                                    PERMISSION_REQUEST_CAMERA);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    // shutdown
                                    Activity activity = parent.getActivity();
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }
    }
}
