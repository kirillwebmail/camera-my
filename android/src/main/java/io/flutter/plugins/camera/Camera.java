// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugins.camera.features.CameraFeature;
import io.flutter.plugins.camera.features.CameraFeatureFactory;
import io.flutter.plugins.camera.features.CameraFeatures;
import io.flutter.plugins.camera.features.Point;
import io.flutter.plugins.camera.features.autofocus.AutoFocusFeature;
import io.flutter.plugins.camera.features.autofocus.FocusMode;
import io.flutter.plugins.camera.features.exposurelock.ExposureLockFeature;
import io.flutter.plugins.camera.features.exposurelock.ExposureMode;
import io.flutter.plugins.camera.features.exposureoffset.ExposureOffsetFeature;
import io.flutter.plugins.camera.features.exposurepoint.ExposurePointFeature;
import io.flutter.plugins.camera.features.flash.FlashFeature;
import io.flutter.plugins.camera.features.flash.FlashMode;
import io.flutter.plugins.camera.features.focuspoint.FocusPointFeature;
import io.flutter.plugins.camera.features.resolution.ResolutionFeature;
import io.flutter.plugins.camera.features.resolution.ResolutionPreset;
import io.flutter.plugins.camera.features.sensororientation.DeviceOrientationManager;
import io.flutter.plugins.camera.features.sensororientation.SensorOrientationFeature;
import io.flutter.plugins.camera.features.zoomlevel.ZoomLevelFeature;
import io.flutter.plugins.camera.media.MediaRecorderBuilder;
import io.flutter.plugins.camera.types.CameraCaptureProperties;
import io.flutter.plugins.camera.types.CaptureTimeoutsWrapper;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

@FunctionalInterface
interface ErrorCallback {
  void onError(String errorCode, String errorMessage);
}

class Camera implements CameraCaptureCallback.CameraCaptureStateListener, ImageReader.OnImageAvailableListener,
    LifecycleObserver {
  private static final String TAG = "Camera";

  private static final HashMap<String, Integer> supportedImageFormats;

  // Current supported outputs.
  static {
    supportedImageFormats = new HashMap<>();
    supportedImageFormats.put("yuv420", ImageFormat.YUV_420_888);
    supportedImageFormats.put("jpeg", ImageFormat.JPEG);
  }

  /**
   * Holds all of the camera features/settings and will be used to update the
   * request builder when one changes.
   */
  private final CameraFeatures cameraFeatures;

  private final SurfaceTextureEntry flutterTexture;
  private final boolean enableAudio;
  private final Context applicationContext;
  private final DartMessenger dartMessenger;
  private final CameraProperties cameraProperties;
  private final CameraFeatureFactory cameraFeatureFactory;
  private final Activity activity;
  /**
   * A {@link CameraCaptureSession.CaptureCallback} that handles events related to
   * JPEG capture.
   */
  private final CameraCaptureCallback cameraCaptureCallback;
  /** A {@link Handler} for running tasks in the background. */
  private Handler backgroundHandler;

  /** An additional thread for running tasks that shouldn't block the UI. */
  private HandlerThread backgroundHandlerThread;

  private CameraDevice cameraDevice;
  private CameraCaptureSession captureSession;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  /** {@link CaptureRequest.Builder} for the camera preview */
  private CaptureRequest.Builder previewRequestBuilder;

  private MediaRecorder mediaRecorder;
  /** True when recording video. */
  private boolean recordingVideo;
  /** True when the preview is paused. */
  private boolean pausedPreview;

  private File captureFile;

  /** Holds the current capture timeouts */
  private CaptureTimeoutsWrapper captureTimeouts;
  /** Holds the last known capture properties */
  private CameraCaptureProperties captureProps;

  private MethodChannel.Result flutterResult;

  public Camera(final Activity activity, final SurfaceTextureEntry flutterTexture,
      final CameraFeatureFactory cameraFeatureFactory, final DartMessenger dartMessenger,
      final CameraProperties cameraProperties, final ResolutionPreset resolutionPreset, final boolean enableAudio) {

    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }
    this.activity = activity;
    this.enableAudio = enableAudio;
    this.flutterTexture = flutterTexture;
    this.dartMessenger = dartMessenger;
    this.applicationContext = activity.getApplicationContext();
    this.cameraProperties = cameraProperties;
    this.cameraFeatureFactory = cameraFeatureFactory;
    this.cameraFeatures = CameraFeatures.init(cameraFeatureFactory, cameraProperties, activity, dartMessenger,
        resolutionPreset);

    // Create capture callback.
    captureTimeouts = new CaptureTimeoutsWrapper(3000, 3000);
    captureProps = new CameraCaptureProperties();
    cameraCaptureCallback = CameraCaptureCallback.create(this, captureTimeouts, captureProps);

    startBackgroundThread();
  }

  @Override
  public void onConverged() {
    takePictureAfterPrecapture();
  }

  @Override
  public void onPrecapture() {
    runPrecaptureSequence();
  }

  /**
   * Updates the builder settings with all of the available features.
   *
   * @param requestBuilder request builder to update.
   */
  private void updateBuilderSettings(CaptureRequest.Builder requestBuilder) {
    for (CameraFeature feature : cameraFeatures.getAllFeatures()) {
      Log.d(TAG, "Updating builder with feature: " + feature.getDebugName());
      feature.updateBuilder(requestBuilder);
    }
  }

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    Log.i(TAG, "prepareMediaRecorder");

    if (mediaRecorder != null) {
      mediaRecorder.release();
    }

    final PlatformChannel.DeviceOrientation lockedOrientation = ((SensorOrientationFeature) cameraFeatures
        .getSensorOrientation()).getLockedCaptureOrientation();

    mediaRecorder = new MediaRecorderBuilder(getRecordingProfile(), outputFilePath).setEnableAudio(enableAudio)
        .setMediaOrientation(lockedOrientation == null ? getDeviceOrientationManager().getVideoOrientation()
            : getDeviceOrientationManager().getVideoOrientation(lockedOrientation))
        .build();
  }

  @SuppressLint("MissingPermission")
  public void open(String imageFormatGroup) throws CameraAccessException {
    final ResolutionFeature resolutionFeature = cameraFeatures.getResolution();

    dartMessenger.sendCameraInitializedEvent(
          "resolutionFeature \""
              + resolutionFeature
      // 
              + "\" ");

    if (!resolutionFeature.chec the user that the camera they are  s {@link android.media.CamcorderProfile} cannot be fetched due to the name
      // not being a valid parsable integer.
      dartMessenger.sendCameraErrorEvent(
          "Camera with name \""
              + cameraProperties.getCameraName()
              + "\" is n turn;
         s capture using J mageReader =
        ImageReader.newInstance(
            resolutionFeature.getCaptureSize().getWidth(),
            resolutionFeature.getCaptureSize().getHeight(),
            ImageFormat.JPEG,
            1);

    // For image streaming, use the provided image format or fall back to YUV420.
    Integer imageFormat imageFormat == null) {TAG, "The selected imageFormatGroup is not sup
        ormat = ImageFormat.YUV_420_888;  eamReader =
        ImageReader.newInstance(
            resolutionFeature.getPreviewSize().getWidth(),
            resolutionFeature.getPreviewSize().getHeight(),
            imageFormat,    1); 
      n the cam
      Manager cameraManager = CameraUtils.getCameraManager
        nager.openCamera(
        raPro
          meraDevice.Stat
          rrideoid onOpened(@NonNull CameraDevice device) {
              Device = device; 
              tPreview(); Messenger.sendCameraInitializedEvent(
              resolutionFeature.getPreviewSize().getWidth(),
              resolutionFeature.getPreviewS
              cameraFeatures.getExposureLock().getValue(),
              came
         
       

          dartM
          close();
        }

        
        verride
      p

      
        dartMessenger.sendCameraClosingEvent();
        super.onClosed(camera);

        
        verride
      p

      
        close();
        dartMessenger.sendCameraError

        
        verride
        blic void onError(@N
          g.i(TAG, "open | onError"
            
            e();
          ring errorDescription;
            ch (errorCode) {
            se ERR
            errorDescription = "The c
            break;
            se ERR
            errorDescription = "Max
            break;
            se ERR
            errorDescription = "The 
            break;
            se ERR
            errorD
            break;
         
            errorDescription = "The camera service has encoun
       
               errorDescription = "Unknown camera error";
            }
            dartMessenger.sendCameraErrorEvent(errorDescription);
          }   },
        backgroundHandler);
  }

  private void createCaptureSession(throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
      int templateType, Runnable onSuccessCallback, Surface... surfaces)
      throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    previewRequestBuilder = cameraDevice
    // Build Flutter surface to render to.
    ResolutionFeature resolutionFeature = cameraFeatures.getResolution();
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(
        resolutionFeature.getPreviewSize().getWidth(),
        resolutionFeature.getPreviewSize().getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    previewRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets.
      for (Surface surface : remainingSurfaces) {
        previewRequestBuild 
    }

    // Update camera regions.
    Size cameraBoundaries =
        CameraRegionUtils.getCameraBoundaries(cam raFeatures.getExposurePoint().setCameraBou
      Features.
      
        re the callback.
        ptureSession.StateCallback 
          meraCaptureSession.StateCallback() {
          rride
        b
        // Camera was already clo

          dartMessenger.sendCameraErrorEvent("Th
          return;

        captureSession = session;
       

      
        refreshPreviewCaptureSession(
            onSuccessCallback, (code, message) -> dartMessenger.sendCameraErrorEve
      }
    
          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            dartMessenger.sendCameraErrorEvent("Failed to configure camera session.");
          }
        };

    // Start the session.
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      // Collect all surfaces to render to.
      List<OutputConfiguration> configs = new ArrayList<>();
      configs.add(new OutputConfiguration(flutterSurface));
      for (Surface surface : remainingSurfaces) {
        configs.add(new OutputConfiguration(surface));
      }
      createCaptureSessionWithSessionConfig(configs, callback);
    } else {
      // Collect all surfaces to render to.
      List<Surface> surfaceList = new ArrayList<>();
      surfaceList.add(flutterSurface);
      surfaceList.addAll(remainingSurfaces);
      createCaptureSession(surfaceList, callback);
       
tApi(VERSION_CODES.P)id createCaptureSessionWithSessionCon utputConfigura
         CameraAccessException { vice.createCaptureSession(
        new SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            Executors.newSingleThreadExecutor(),
            callback));

  @TargetApi(VERSION_CODES.LOLLIPOP)
  @SuppressWarnings("deprecation")
  private void createCaptureSession(
      List<Surface> surfaces, CameraCapt eSession.StateCallback callback)
      throws CameraAccessException {meraDevice.createCaptureSession(surfa
      es, callback, backgroundHandler);
  }
a re void refreshPreviewCaptureSession(
          e Runnable onSuccessCallback, @NonNull ErrorCallback onErrorCallback) {
    if (captureSession == null) {
      Log.i(
          TAG,
          "[refreshPreviewCaptureSession] captureSession not yet initialized, "
              + "skipping preview capture session refresh.");
      return;

    try {
      if (!pausedPreview) {
        captureSession.setRepeatingRequest(
            previewRequestBuilder.build(), cameraCaptureCallback, backgroundHandler);
      }

      if (onSuccessCallback != null) {
        onSuccessCallback.run();
      }

    } catch (CameraAccessException e) {
      onErrorCallback.onError("cameraAccess", e.getMessage());
    }
  }

  public void takePicture(@NonNull final Result result) {
    // Only take one picture at a time.
    if (cameraCaptureCallback.getCameraState() != CameraState.STATE_PREVIEW) {
      result.error("captureAlreadyActive", "Picture is currently already being captured", null);
      return;
    }

    flutterResult = result;

    // Create temporary file.
    final File outputDir = applicationContext.getCacheDir();
    try {
      captureFile = File.createTempFile("CAP", ".jpg", outputDir);
      captureTimeouts.reset();
    } catch (IOException | SecurityException e) {
      dartMessenger.error(flutterResult, "cannotCreateFile", e.getMessage(), null);
      return;
    }

    // Listen for picture being taken.
    pictureImageReader.setOnImageAvailableListener(this, backgroundHandler);

    final AutoFocusFeature autoFocusFeature = cameraFeatures.getAutoFocus();
    final boolean isAutoFocusSupported = autoFocusFeature.checkIsSupported();
    if (isAutoFocusSupported && autoFocusFeature.getValue() == FocusMode.auto) {
      runPictureAutoFocus();
    } else {
      runPrecaptureSequence();
   *  
   * 
  }

  /**
   * Run the precapture sequence for capturing a still image. This method should be called when a
   * response is received in {@link #cameraCaptureCallback} from l
      // ckFocus().
   */void runPrecaptureSequence() {
    Log.i(TAG, "runPrecaptureSequence");
    try {irst set precapture state to idle or else it can hang in STATE_WAITING_PRECAPTURE_START.
      previewRequestBuilder.set(
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
          CaptureRequest.CONTROL_AEureSession.capture(
          previewRequestBuilder.build(), cameraCaptureCallback, backgroundHandler);

      // Repeating request to refresh preview session.
      refreshPreviewCaptureSession(
          null,
          (code, message) -> dar
      // Start precapture.
      cameraCaptureCallback.setCameraState(CameraState.STATE_WAITING_PRECAPTURE_START);

      previewRequestBuilder.sCaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

      // Trigger one capture to start AE sequence.
      captureSession.capture(
          previewRequestBuilder.build(), cameraCaptureCallback, backgroundHandler);

    } catch (CameraAccessException e) {
      e.printStackTrace();
   *  
  }

  /**
   * Capture a still picture. This method should be called when a response is received {@link
   * #cameraCaptureCallback} from both lockFocus().
   */
  private void takePictureAfterPrecapture() {
    Log.i(TAG, "captureStillPicture");
    cameraCaptureCallback.setCameraState(CameraState.STATE_CAPTURING);

    if (cameraDevice == null) {
      return;
    }
    // This is the CaptureRequest.Builder that is used to take a picture.
    CaptureRequest.Builder stillBuilder;
    try {
      stillBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
    } catch (CameraAccessException e) {
      dartMessenger.error(flutterResult, "cameraAccess", e.getMessage(), null);
      return; lBuilder.addTarget(pictureImageReader.getSurface());

    // Zoom.
    stillBuilder.set(
        CaptureRequest.SCALER_CROP_REGION,
        previewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));
 ave all features update the builder.
        ilderSettings(stillBuilder);
rientation.
    final PlatformChannel.DeviceO nsorOrientationFeature) cameraFeatures.getSensorOrientation())
            .getLockedCaptureOrientation();
    stillBuilder.set(
        CaptureRequest.JPEG_ORIENTATION, lockedOrientation == null
        ? getDe
        : getDeviceOrientationManager ureSession.CaptureCallback captu
          meraCaptureSession.CaptureCallback() 
        verride
      p
          @NonNull CameraCaptureSession session,
              @NonNull CaptureRequest request,
              @NonNull TotalCaptureResult result) {
            unlockAutoFocus();
          }
        };

    try {
      captureSession.stopRepeating();
      captureSession.abortCaptures();
      Log.i(TAG, "sending capture request");
      captureSession.capture(stillBuilder.build(), captureCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      dartMessenger.error(flutterResult, "cameraAccess", e.getMessage(), null);
    }
  }

  @SuppressWarnings("deprecation")
  private Display getDefaultDisplay() {
    return activity.getWindowManager().getDefaultDisplay();
  }

  /** Starts a background thread and its {@link Handler}. */
  @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
  public void startBackgroundThread() {
    backgroundHandlerThread = new HandlerThread("CameraBackground");
    try {
      backgroundHandlerThread.start();
    } catch (IllegalThreadStateException e) {
      // Ignore exception in case the thread has already started.
    }
    backgroundHandler = new Handler(backgroundHandlerThread.getLooper());
  }

  /** Stops the background thread and its {@link Handler}. */
  @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
  public void stopBackgroundThread() {
    if (backgroundHandlerThread != null) {
      backgroundHandlerThread.quitSafely();
      try {
        backgroundHandlerThread.join();
      } catch (InterruptedException e) {
        dartMessenger.error(flutterResult, "cameraAccess", e.getMessage(), null);
      }
    }
    backgroundHandlerThread = null;
    backgroundHandler = null;
  }

  /** Start capturing a picture, doing autofocus first. */
  private void runPictureAutoFocus() {
    Log.i(TAG, "runPictureAutoFocus");

    cameraCaptureCallback.setCameraState(CameraState.STATE_WAITING_FOCUS);
    lockAutoFocus();
  }

  private void lockAutoFocus() {
    Log.i(TAG, "lockAutoFocus");
    if (captureSession == null) {
      Log.i(TAG, "[unlockAutoFturn;
    }

    // Trigger AF to start.
    previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);

    try {
      captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
    } catch (CameraAccessException e) {
      dartMessenger.sendCameraErrorEvent(e.getMessage());
    }
  }

  /** Cancel and reset auto focus state and refresh the preview session. */
  private void unlockAutoFocus() {
    Log.i(TAG, "unlockAutoFocus");
    if (captureSession == null) {
      Log.i(TAG, "[unlockAutoFocrn;
    }
    try {
      // Cancel existing AF state.
      previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
      captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);

      // Set AF state to idle again.
      previewRequestBuilder.set(
          CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

      captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
    } catch (CameraAccessExceptiortMessenger.sendCameraErrorEvent(e.getMessage());
      return; 

    refreshPreviewCaptureSession(
        null,
        (errorCode, errorMessage) ->
            dartMessenger.error(flutterResult, errorCode, errorMessage, null));
  }

  public void startVideoRecording(@NonNull Result result) {
    final File outputDir = applicationContext.getCacheDir();
    try {
      captureFile = File.createTempFile("REC", ".mp4", outputDir);
    } catch (IOException | SecurityException e) {
      result.error("cannotCreateFile", e.getMessage(), null);
      return;
    }
    try {
      prepareMediaRecorder(captureFile.getAbsolutePath());
    } catch (IOException e) {
      recordingVideo = false;
      captureFile = null;sult.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }
    // Re-create autofocus Features.setAutoFocus(
        cameraFeatureFactory.createAutoFocusFeature(cameraProperties, true));
    recordingVideo = true;
    try {
      createCaptureSession(
          CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
      result.success(null);
    } catch (CameraAccessException e) {
      recordingVideo = false;
      captureFile = null;
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {sult.success(null);
      return;
    }
    // Re-create autofocus feature so it's using continuous capture focus mode now.
    cameraFeatures.setAutoFocus(
        cameraFeatureFactory.createAutoFocusFeature(cameraProperties, false));
    recordingVideo = false;
      // 
    try {
      captureSession.abortCaptures();
      mediaRecorder.stop();
    } catch (CameraAccessException | IllegalStateException e) {
      // Ignore exceptions and try to continue (changes are camera session already aborted capture).
    }
    mediaRecorder.reset();
    try {
      startPreview();
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }
    result.success(captureFile.getAbsolutePath());
    captureFile = null;
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }  

  /**
   * Method handler for setting new flash modes.
   *
   * @param result Flutter result.
   * @param newMode new mode.
   */
  public void setFlashMode(@NonNull final Result result, @NonNull FlashMode newMode) {
    // Save the new flash mode sel FlashFeature flashFeature = cameraFeatures.getFlash();
    flashFeature.setValue(newMode);
    flashFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result.error("setFlashModeFailed", "Could not set flash mode.", null));
  }  

  /**
   * Method handler for setting new exposure modes.
   *
   * @param result Flutter result.
   * @param newMode new mode.
   */
  public void setExposureMode(@Nol ExposureLockFeature exposureLockFeature = cameraFeatures.getExposureLock();
    exposureLockFeature.se LockFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) ->
            result.error("setExposureModeFailed", "Could not set exposure mode.", null));
  }
  
  /**
   * Sets new exposure point from dart.
   *
   * @param result Flutter result.
   * @param point The exposure point.
   */
  public void setExposurePoint(@Nl ExposurePointFeature exposurePointFeature = cameraFeatures.getExposurePoint();
    exposurePointFeature.s PointFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) ->
            result.error("setExposurePointFailed", "Could not set exposure point.", null));
  }

  /** Return the max exposure offset value supported by the camera to dart. */
  public double getMaxExposureOffset() {
    return cameraFeatures.getExposureOffset().getMaxExposureOffset();
  }

  /** Return the min exposure offset value supported by the camera to dart. */
  public double getMinExposureOffset() {
    return cameraFeatures.getExposureOffset().getMinExposureOffset();
  }

  /** Return the exposure offset step size to dart. */
  public double getExposureOffsetStepSize() {
    return cameraFeatures.getExposureOffset().getExposureOffsetStepSize();
  }  

  /**
   * Sets new focus mode from dart.
   *
   * @param result Flutter result.
   * @param newMode New mode.
   */
  public void setFocusMode(final Result result, @NonNull FocusMode newMode) {
    final AutoFocusFeature autoFocusFeature = cameraFeatures.getAutoF oFocusFeatur
     * .setValue(newMode);
    autoFocusFeature.updateBuilder(previewRequestBuilder);

    /*
     * For focus mode an extra step of actually locking/unlocking the
     * focus has to be done, in order to ensure it goes into the correct state.
     */
    if (!pausedPreview) {
      switch (newMode) {
        case locked:
          // Perform a single focus trigger.
          if (captureSession == null) {
            Log.i(TAG, "[unlockAutoFocus] captureSession null, returning");
            return;
          lockAutoFocus();

          // Set AF state to idle again.wRequestBuilder.set(
              CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

          try {eSession.setRepeatingRequest(
                previewRequestBuilder.build(), null, backgroundHandler);
          } catch (CameraAccessException e) {
            if (result != null) {
              result.error(
                  "setFocusModeFailed", "Error setting focus mode: " + e.getMessage(), null);
            }
            return;
          }
          break;
        case auto:
          // Cancel current AF trigger and set AF to idle again.
          unlockAutoFocus();
          break;
      }
    }

    if (result != null) {
      result.success(null);
    }
  }
  
  /**
   * Sets new focus point from dart.
   *
   * @param result Flutter result.
   * @param point the new coordinates.
   */
  public void setFocusPoint(@NonNl FocusPointFeature focusPointFeature = cameraFeatures.getFocusPoint();
    focusPointFeature.setValue(point);
    focusPointFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result.error("setFocusPointFailed", "Could not set focus point.", null));

   *  his.setFocusMode(null, cameraFeatures.getAutoFocus().getValue());
  }

  /**
   * Sets a new exposure offset from dart. From dart the offset comes as a double, like +1.3 or
   * -1.3.
   *
   * @param result flutter result.
   * @param offset new value.
   */
  public void setExposureOffset(@l ExposureOffsetFeature exposureOffsetFeature = cameraFeatures.getExposureOffset();
    exposureOffsetFeature. OffsetFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(exposureOffsetFeature.getValue()),
        (code, message) ->
            result.error("setExposureOffsetFailed", "Could not set exposure offset.", null));
  }

  public float getMaxZoomLevel() {
    return cameraFeatures.getZoomLevel().getMaximumZoomLevel();
  }

  public float getMinZoomLevel() {
    return cameraFeatures.getZoomLevel().getMinimumZoomLevel();
  }

  /** Shortcut to get current recording profile. */
  CamcorderProfile getRecordingProfile() {
    return cameraFeatures.getResolution().getRecordingProfile();
  }

  /** Shortut to get deviceOrientationListener. */
  DeviceOrientationManager getDeviceOrientationManager() {
    return cameraFeatures.getSensorOrientation().getDeviceOrientationManager();
  }
   
  /**
   * Sets zoom level from dart.
   *
   * @param result Flutter result.
   * @param zoom new value.
   */
  public void setZoomLevel(@NonNull final Result result, float zoom) throws CameraAccessException {
    final ZoomLevelFeature  maxZoom = zoomoom = zoomLevel
            maxZoom rrorMessage =
          String.format(
              Locale.ENGLISH,
              "Zoom level out of bounds (zoom level should be between %f and %f).",
              minZoom,
              maxZoom);
      result.error("ZOOM_ERROR", errorMessage, null);
      return;
    }
    zoomLevel.setValue(zoom);
    zoomLevel.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result.error("setZoomLevelFailed", "Could not set zoom level.", null));
  }

  /**
   * Lock capture orientation from dart.
   *
   * @param orientation new orientation.
   */
  public void lockCaptureOrientation(PlatformChannel.DeviceOrientation orientation) {
    cameraFeatures.getSensorOrientation().lockCaptureOrientation(orientation);
  }

  /** Unlock capture orientation from dart. */
  public void unlockCaptureOrientation() {
    cameraFeatures.getSensorOrientation().unlockCaptureOrientation();
  }

  /** Pause the preview from dart. */
  public void pausePreview() throws CameraAccessException {
    this.pausedPreview = true;
    this.captureSession.stopRepeating();
  }
  /** Resume the preview from dart. */
  public void resumePreview() {
    this.pausedPreview = false;
    this.refreshPreviewCaptureSession(
      
        null, (code, message) -> dartMessenger.sendCameraErrorEvent(message));
  }

  public void startPreview() throws CameraAccessException {
    if (pictureImageReader == null || pictureImageReader.getSurface() == null) return;
    Log.i(TAG, "startPreview"); 
    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
  }

  public void startPreviewWithImageStrearows CameraAccessException {
      CaptureSe
      TAG, "startPreviewWithImageStream");
        
      t

      @Override
      public void onListen(Object o, E
        setImageStreamImageAvailableListener(imageStreamSink);
      }
    
          @Override
          public void onCancel(Object o) {
            imageStreamReader.setOnImageAvailableListener(null, backgroundHandler);
          }
   *     });
  }

  /**
   * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
   * still image is ready to be saved.
   */ide
        d onImageAvailable(ImageReader reader) {
        G, "onImageAvailable");  ndHandler.post(
          ageSaver(
           Use acquireNextImage since image reader is o
            er.acquireNextImage(),
          p

          @Override
          public void onComplete(String absolutePath) {
            dartMessenger.finish(flutterResult, absolutePath);
          }
        
              @Override
              public void onError(String errorCode, String errorMessage) {
                dartMessenger.error(flutterResult, errorCode, errorMessage, null);
              }
            }));raCaptureCa
      
      
      void setImageStr
        amImage

      ader -> {
      Image img = reader.acquireNextImage();
         Use acquireNextImage since image read

        
        st<Map<String, Object>> planes = ne

        ByteBuffer buffer = plane.getBuffer();
        
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes, 0, bytes.lengt

        Map<String, Object> plan
       

        planeBuffer.put("bytes", bytes);
      
        planes.add(planeBuffer);
      }
      
      Map<String, Object> imageBuffer = new HashMap<>();
      imageBuffer.put("width", img.getWidth());
      imageBuffer.put("height", img.getHeight());
      imageBuffer.put(eBuffer.put("planes", planes);

      imageBuffer.put("sensorExposureTime", this.captureProps.getL
      Integer sensorSensitivity = this.captureProps.getLastSens
      imageBuffer.
       
          final Handler handler = new Handler(Looper.getMainLooper());
          handler.post(() -> imageStreamSink.success(imageBuffer));
          img.close();
        },
        backgroundHandler);
  }

  private void closeCaptureSession() {
    if (captureSession != null) {
      Log.i(TAG, "closeCaptureSession");

      captureSession.close();
      captureSession = null;
    }
  }

  public void close() {
    Log.i(TAG, "close");
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }

    stopBackgroundThread();
  }

  public void dispose() {
    Log.i(TAG, "dispose");

    close();
    flutterTexture.release();
    getDeviceOrientationManager().stop();
  }
}
