package com.ttv.fingerdemo;

import static androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.ldoublem.ringPregressLibrary.OnSelectRing;
import com.ldoublem.ringPregressLibrary.Ring;
import com.ldoublem.ringPregressLibrary.RingProgress;
//import com.ttv.finger.CoreApi;
//import com.ttv.finger.Finger;
//import com.ttv.finger.FingerprintTemplate;
import com.google.common.util.concurrent.ListenableFuture;
import com.ttv.palm.PalmEngine;
import com.ttv.palm.PalmResult;
//import com.ttv.finger.FingerSDK;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.DoubleStream;


public class FingerCaptureActivity extends AppCompatActivity implements HandLandmarkerHelper.LandmarkerListener {

    private static final String TAG = FingerCaptureActivity.class.getSimpleName();

    private final float QUALITY_THRESHOLD = 0.8f;

    /**
     * Blocking camera operations are performed using this executor
     */

    private Context context;
    private ExecutorService m_cameraExecutorService;
    private PreviewView     m_viewFinder;

    private int m_lensFacing = CameraSelector.LENS_FACING_BACK;

    private Preview       m_preview        = null;
    private ImageAnalysis m_imageAnalyzer  = null;
    private Camera        m_camera         = null;
    private CameraSelector        m_cameraSelector = null;
    private RingProgress    m_ringProgress = null;

    private ProcessCameraProvider m_cameraProvider = null;

    private TextView       m_statusTextView;

    public static Map<String, byte[] > m_registerTemplates = new HashMap<String, byte[]>();

    private Activity            m_activity;
//    private ArrayList<FingerprintTemplate> m_registerTemplate = new ArrayList<FingerprintTemplate>();

    private int             m_mode;
    private boolean         m_prepared = false;

    private double          m_quality = 0.0d;

    private HandLandmarkerHelper handLandmarkerHelper;
    private ExecutorService backgroundExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);
        setScreenBrightnessFull();

        m_mode = getIntent().getIntExtra("mode", 0);
//        FingerSDK.getInstance().initCapture();

        m_activity = this;
        context = this;

        m_viewFinder = findViewById(R.id.view_finder);
        m_statusTextView = findViewById(R.id.txt_status);

        m_cameraExecutorService = Executors.newFixedThreadPool(1);
        backgroundExecutor = Executors.newSingleThreadExecutor();

        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                handLandmarkerHelper = new HandLandmarkerHelper(
                        0.5f,
                        0.5f,
                        0.5f,
                        1,
                        HandLandmarkerHelper.DELEGATE_CPU,
                        RunningMode.LIVE_STREAM,
                        context,
                        m_mode,
                        FingerCaptureActivity.this
                );
            }
        });

        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (handLandmarkerHelper.isClose()) {
                    handLandmarkerHelper.setupHandLandmarker();
                }
            }
        });

        m_viewFinder.post(() ->
        {
            setUpCamera();
        });

        m_ringProgress = findViewById(R.id.ring_progress);
        m_ringProgress.setSweepAngle(360);
        m_ringProgress.setDrawBg(true, Color.rgb(168, 168, 168));
        m_ringProgress.setDrawBgShadow(false);
        m_ringProgress.setCorner(false);
        m_ringProgress.setRingWidthScale(0.0f);

        Ring r = new Ring((int)(0),"","", Color.rgb(86, 171, 228), Color.argb(100, 86, 171, 228));
        List<Ring> mlistRing = new ArrayList<>();
        mlistRing.add(r);
        m_ringProgress.setData(mlistRing, 0);// if >0 animation ==0 null
    }

    /**
     * Initialize CameraX, and prepare to bind the camera use cases
     */
    private void setUpCamera()
    {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(FingerCaptureActivity.this);
        cameraProviderFuture.addListener(() -> {

            // CameraProvider
            try {
                m_cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException e) {
            } catch (InterruptedException e) {
            }

            // Build and bind the camera use cases
            bindCameraUseCases();

        }, ContextCompat.getMainExecutor(FingerCaptureActivity.this));
    }

    /**
     * Declare and bind preview, capture and analysis use cases
     */
    @SuppressLint({"RestrictedApi", "UnsafeExperimentalUsageError"})
    private void bindCameraUseCases()
    {
        int rotation = m_viewFinder.getDisplay().getRotation();

        m_cameraSelector = new CameraSelector.Builder().requireLensFacing(m_lensFacing).build();

        m_preview = new Preview.Builder()
                .setTargetResolution(new Size(1080, 1920))
                .setTargetRotation(rotation)
                .build();

        m_imageAnalyzer = new ImageAnalysis.Builder()
                .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(1080, 1920))
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build();

        m_imageAnalyzer.setAnalyzer(backgroundExecutor, new FingerAnalyzer());

        // Must unbind the use-cases before rebinding them
        m_cameraProvider.unbindAll();

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            m_camera = m_cameraProvider.bindToLifecycle(
                    this, m_cameraSelector, m_preview, m_imageAnalyzer);

            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String cameraId : manager.getCameraIdList()) {
                //CameraCharacteristics characteristics
                CameraCharacteristics mCameraInfo = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = mCameraInfo.get(CameraCharacteristics.LENS_FACING);
                if ((facing != null) && (facing == CameraCharacteristics.LENS_FACING_FRONT)) {
                    continue;
                }

                FingerCaptureActivity.setCameraControl(m_camera);
            }

            // Attach the viewfinder's surface provider to preview use case
            m_preview.setSurfaceProvider(m_viewFinder.getSurfaceProvider());

            autoFocus();
            toggleFlash(true);
        } catch (Exception exc) {
        }
    }


    boolean isRunning = false;

    @Override
    public void onResults(HandLandmarkerHelper.ResultBundle resultBundle) {
        Log.e("TestEngine", "onResult: " + isRunning);
        isRunning = false;

        if(resultBundle.getResults().size() > 0) {
            if(m_prepared == false) {
                if(resultBundle.getPalmResults() != null && resultBundle.getPalmResults().size() > 0) {
                    if(m_mode == 0 || m_mode == 1) {
                        if(resultBundle.getRoiBitmap() != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ImageView imgView = (ImageView)findViewById(R.id.img_gallery);
                                    imgView.setImageBitmap(resultBundle.getRoiBitmap());
                                }
                            });
                        }
                    } else if(m_mode == 2 || m_mode == 3) {
                        if(resultBundle.getRoiBitmap() != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ImageView imgView = (ImageView)findViewById(R.id.img_gallery);
                                    imgView.setImageBitmap(resultBundle.getRoiBitmap());
                                }
                            });
                        }
                    }
                    double quality = resultBundle.getPalmResults().get(0).quality;
                    this.m_quality = (quality * 2 / 3) + (this.m_quality / 3);

                    if(quality > QUALITY_THRESHOLD) {
                        m_prepared = true;
                    }
                }
            } else {
                if(resultBundle.getPalmResults() != null && resultBundle.getPalmResults().size() > 0) {
                    if(m_mode == 0 || m_mode == 1) {
                        if(resultBundle.getRoiBitmap() != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ImageView imgView = (ImageView)findViewById(R.id.img_gallery);
                                    imgView.setImageBitmap(resultBundle.getRoiBitmap());
                                }
                            });
                        }
                    } else if(m_mode == 2 || m_mode == 3) {
                        if(resultBundle.getRoiBitmap() != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ImageView imgView = (ImageView)findViewById(R.id.img_gallery);
                                    imgView.setImageBitmap(resultBundle.getRoiBitmap());
                                }
                            });
                        }
                    }

                    double quality = resultBundle.getPalmResults().get(0).quality;
                    if(quality > QUALITY_THRESHOLD) {
                        if(m_mode == 0) {
                            String registerID = String.format("Palm User%03d", m_registerTemplates.size() + 1);
                            m_registerTemplates.put(registerID, resultBundle.getPalmResults().get(0).feature);

                            isFinished = true;
                            Intent intent = new Intent();
                            intent.putExtra("registerID", registerID);
                            setResult(RESULT_OK, intent);
                            finish();
                        } else if(m_mode == 2) {
                            String registerID = String.format("Writer User%03d", m_registerTemplates.size() + 1);
                            m_registerTemplates.put(registerID, resultBundle.getPalmResults().get(0).feature);

                            isFinished = true;
                            Intent intent = new Intent();
                            intent.putExtra("registerID", registerID);
                            setResult(RESULT_OK, intent);
                            finish();
                        } else {
                            float maxScore = 0.0f;
                            String maxScoreID = "";
                            for(Map.Entry<String, byte[] > entry: m_registerTemplates.entrySet()) {
                                float score = PalmEngine.getInstance().compareFeature(resultBundle.getPalmResults().get(0).feature, entry.getValue());
                                if(maxScore < score) {
                                    maxScore = score;
                                    maxScoreID = entry.getKey();
                                }
                            }

                            setStatus(String.format("Score: %f", maxScore));
                            if(maxScore > 0.9) {
                                isFinished = true;
                                Intent intent = new Intent();
                                intent.putExtra("verifyResult", 1);
                                intent.putExtra("verifyID", maxScoreID);
                                setResult(RESULT_OK, intent);
                                finish();
                            }
                        }
                    } else {
                        setStatus("Hold still");
                    }
                } else {
                    setStatus("Palm no detected!");
                }
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Ring r = new Ring((int)(m_quality * 100),"","", Color.rgb(0xFF, 0x57, 0x22), Color.argb(150, 0xFF, 0x57, 0x22));
                    List<Ring> mlistRing = new ArrayList<>();
                    mlistRing.add(r);
                    m_ringProgress.setData(mlistRing, 0);// if >0 animation ==0 null
                }
            });
        }
    }

    @Override
    public void onError(String error, int errorCode) {
        Log.e("TestEngine", "OnError: " + error + " " + errorCode);
        isRunning = false;
    }

    class FingerAnalyzer implements ImageAnalysis.Analyzer
    {
        @SuppressLint("UnsafeExperimentalUsageError")
        @Override
        public void analyze(@NonNull ImageProxy imageProxy)
        {
            Log.e("TestEngine", "isRunning " + isRunning);
            if(isRunning == true) {
                imageProxy.close();
                return;
            }

            isRunning = true;
            analyzeImage(imageProxy);
            imageProxy.close();
        }
    }

    Boolean isFinished = false;
    @SuppressLint("UnsafeExperimentalUsageError")
    private void analyzeImage(ImageProxy imageProxy)
    {
        if(isFinished == true) {
            return;
        }

        handLandmarkerHelper.detectLiveStream(imageProxy, false);
    }

    public static void setCameraControl(Camera camera) {
        CameraControl cameraControl = camera.getCameraControl();
        cameraControl.setExposureCompensationIndex(0);
//        cameraControl.setZoomRatio(2.0f);
    }

    public static void setCameraAutoFocus(Camera camera, int viewWidth, int viewHeight) {
        try {

            MeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight);

            int centerWidth  = viewWidth / 2;
            int centreHeight = viewHeight / 2;

            MeteringPoint autoFocusPoint = factory.createPoint(centerWidth, centreHeight);

            FocusMeteringAction.Builder builder =
                    new FocusMeteringAction.Builder(autoFocusPoint,
                            FocusMeteringAction.FLAG_AF |
                                    FocusMeteringAction.FLAG_AE |
                                    FocusMeteringAction.FLAG_AWB);

            builder.setAutoCancelDuration(500, TimeUnit.MILLISECONDS);
            camera.getCameraControl().startFocusAndMetering(builder.build());

        } catch (Exception e) {}
    }


    private void autoFocus() {
        FingerCaptureActivity.setCameraAutoFocus(m_camera, m_viewFinder.getWidth(), m_viewFinder.getHeight());
    }

    void toggleFlash(boolean enable) {
        if (m_camera != null) {

            CameraInfo cameraInfo = m_camera.getCameraInfo();
            if (m_camera.getCameraInfo().hasFlashUnit() && cameraInfo.getTorchState().getValue() != null) {
                int torchState = cameraInfo.getTorchState().getValue();
                m_camera.getCameraControl().enableTorch(enable);
                //preview.en
            }

            FingerCaptureActivity.setCameraControl(m_camera);
        }
    }

    private void setScreenBrightnessFull() {

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        getWindow().setAttributes(params);
    }

    private void setStatus(String msg) {

        runOnUiThread(() -> {
            if (msg != null && !msg.equals("")) {
                m_statusTextView.setText(msg);
            } else {
                m_statusTextView.setText("");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        toggleFlash(true);

        // Start the HandLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (handLandmarkerHelper.isClose()) {
                    handLandmarkerHelper.setupHandLandmarker();
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        toggleFlash(false);

        if (handLandmarkerHelper != null) {
            // Close the HandLandmarkerHelper and release resources
            backgroundExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    handLandmarkerHelper.clearHandLandmarker();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Shut down our background executor
        backgroundExecutor.shutdown();
        try {
            backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // Handle the interruption exception if needed
        }
    }

}
