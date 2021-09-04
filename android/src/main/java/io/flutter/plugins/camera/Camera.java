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
              + resolutionFeature);

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
              Device = de

      tPreview(); Messenger.sendCameraInitializedEv
    resoluinFeaturresolutionFature.getPreviewScameraFeatures.getExposureLock().getValue(),came 

     
  

  
      

  

  
      

  

  
        verride
        blic void onError(@N
          g.i(T     

  
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
    
       

    dartMes
  }
    
  ta

    // Collect all surf
    ist<OutputConfigraion> configs = nw

  configs.add(new OutputConfiguration(f
  for (Surface surface : remainingSurfaces) {  configs.add(new OutputConfiguration(surface));}
  eateCaptureSessionWithSessionConfig(configs, ce// Collect all surfaces to render to. ListS
  surfaceList.add(flutterSurface);
  surfaceList.addAll(remainingSurfaces);createCaptureSession(surfaceList 

  ERSION_CODES.P)id createCaptureSessionWithSessionCon utputConfigura
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
   

      

  f (captureSession == null) {
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
      eturn;flutterResult = result;

    // Create temoary file.
    final File outputDir = applicationContext.getCacheDir();
    try {
      captureFile = FileceateTempFile("CAP", ".jpg", outpu  cpcatch (IOExetion | SecurityException e {dartMessenger.error(flut retur;// Listen for picture being taken.
    pictureImageReader.setOnImageAvailableListener(this, backgroundHandler);

    final AutoFocusFeature autoFocusFeature = cameraFeatres.getAutoFocus();
    final boolean isAutoFocusSupported = autoFocusFeature.checkIsSupported();
    if (isAutoFocusSupported && autoFocusFetre.getValue() == FocusMode.aut  runPictureAutoFocus();} lse {runPrecaptureSequence( 
  /**
   * Run the precapture sequence for capturing a still image. This method should be called when a
   * response is received in {@link #cameraCaptureCallback} from l
      // ckFocus().
   *  void runPrecaptureSequence() {
   *  g.i(TAG, "runPrecaptureSequence");
    t
  ry {irst set precapture state to idle or else it can hang in STATE_WAITING_PRECAPTURE_START.
      previewRquestBuilder.set(     CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,    CaptureRequest.CONTROLpreviewRequestBuilder.build(), cameraCaptureCepeating request to refresh prview session.

        null,
        (code, message) -> dartart raCaptreCallbak.etC
    
    previewRequestBuilder.sCaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,

    rigger one capture to start AE sequence.

        previewRequestBuilder.build(), cameraCap
    h (CameraAccessException e) {
      e.printStackTrace();
   * 
  /**
   * Capture a still picture. This method should be called when a response is received {@link
   * #cameraCaptureCallback} from both lockFocus().
   */
   *  vate void takePictureAfterPrecapture() {
    Log.i(TAG, "captureStillPicture");
    cameraCaptureCallback.setCameraState(CameraState.STATE_CAPTURING);
if (cameraDevice == null) {
      return;
    } This iCaptureRequest.Builder stillBuilder;
    try {
      stillBuilder = cameraDevice.create} ctdartMessenge.rror(flutterResult, "cameraAccess", e.getMessage(), null); retur; lBuilder.addTarget(pictr Zoom.
    stillBuilder.set(
        CaptureRequest.SCALER_CROP_REGION,
        previewReques features update the builder.ilderSettings(stillBuilder);ientation.l PlatformChannel.DeviceO ns           stillBuilder.set(ureRequest.JPEG_ORIENTATION, lo    ? getDe: getDeviceOrientationManager urSession.CaptureCalbck c meraCvrride   @NonN       @NonNull TotalCaptureResult resultlockAutoFocus();{
      captureSession.stopRepeating();
      cpLog.i(TAG, "sending capture reqcaptureSession.capture(stillBuicatch (CamraAccessException e) {dartMessenger.error(flutterResult, "cameraAcess", e.getMessae(), null);}
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
      Log.i(TA, "[unlockAutoFtu}// Trigger AF to start.
    previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
{
      captureSession.capture(previewRequestBuilder.build(), null, backgroundHandler);
    } ctdartMessenger.sendCameraErrorEvent(e.getMessage());}
  /** Cancel and reset auto focus state and refresh the preview session. */
  private void unlockAutoFocus() {
    Log.i(TAG, "unlockAutoFocus");
    if (captureSession == null) {
      Log.i(TA, "[unlockAutoFocrn}y {   pe
    captureSession.capture(previ
    // Set AF state to idle again.

        CaptureRequest.CONTROL_AF_
    ureSession.capture(previewRequestBilder.build(), null, backgroundHandler);

    return; freshPr

        (errorCode, errorMessage)    d
  public void startVideoRecording(@NonNull Result result) {
    final File outputDir = applicationContext.getCacheDir();
    try {
      captureFile = FileceateTempFile("REC", ".mp4", outpu} ctresult.erro(cannotCreateFile", e.getMesage(),null); retur;y { } ctrecordingVideo = false; captueFile = null;sutreturn; Re-cre    cameraFeatureFactory.createAutoFocusFeature(cameraProperties, true));
    recordingVideo = true;
    {  createCapturSssion    result.success(null);h (CameraAccessException e) {recordingVideo = fals captueFile = null;result.error("ieoReco
  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {sult.success(null);
      return;
    }
      
    // Re-create autofocus feature so it's using continuous capture focus mode now.
    cameraFeatures.setAutoFocus(
        cameraFeatureFactory.createAutoFocusFeature(cameraProperties, false));
    recordingVideo = false; 
    try {
    catureSession.abortCaptures();
      mediaRecorder.stop();
    } catch (CameraAccessException | IllegalStateException e) {
      // Ignore exceptions and try to continue (changes are camera session already aborted capture).
    }
    mediaRecorder.reset();
      // 
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

     

    } else {
      result.error(
          "v
      return;
      tch (Il
    r
   retur;

  
  r

  /

   *
   * @param result Flutter result.
   * @param newMode new mode.
   */  
  public void setFlashMode(@NonNull final Result result, @NonNull FlashMode newMode) {
    // Save the new flash mode sel FlashFeature flashFeature = cameraFeatures.getFlash();
    flashFeature.setValue(newMode);
    flashFeature.updateBuilder(previewRequestBuilder);
    // 

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result

  /

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
  
  /

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

  /

   *
   * @param result Flutter result.
   * @param newMode New mode.
   */  
  public void setFocusMode(final Result result, @NonNull FocusMode newMode) {
    final AutoFocusFeature autoFocusFeature = cameraFeatures.getAutoF oFocusFeatur
     * .setValue(newMode);
    autoFocusFeature.updateBuilder(previewRqestBuilder);/*
     * For focus mode an extra step of actually locking/unlocking the
     * focus has to be done, in order to ensure it goes into the correct state.
     */ (!pausedPrev
     * ew) {
      switch (newMode) {
       case locked:    //Perform as  if (captur
      Log.i(TAG, "[unlockAutoFocus] ca
      eturn;ckAutoFocu();// Set AF state 

    
    {eSession.setRepeatingRequest(

    } ctch (CameraAccessException e) result != null) {   reslt.error(     "setocsModea;eak;s// Can  unlockAu
    break;
    if (result != null) {
      result.success(null);
    }
  /

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
   
  /

   *
   * @param result Flutter result.
   * @param zoom new value.
   */
  public void se   ZoomLevel(@NonNull final Result result, float zoom) throws CameraAccessException {
    final ZoomLevelFeature  maxZoom = zoomoom = zoomLevel
            maxZoom rrorMessage =
          String.format(    Locale.ENGLISH,    "Zoom leveminZoom,maxZoom);rror("ZOomLevel
  zrefreshPreviewCaptureSession(

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
  p

    this.pausedPreview = false;
    this.refreshPreviewCaptureSession(
      
        null, (code, message) -> dartM

        
  public void startPreview() throws CameraAccessException {
    if (pictureImageReader == null || pictureImageReader.getSurface() == null) return;
    Log.i(TAG, "startPreview"); 
    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.get
      urface());
  }
    
  public void startPreviewWithImageStrearows CameraAccessException {
      CaptureSe
      TAG, "startPreviewWithImageStream");
    
      t

  @

    setImageStreamImageAvailableListener(imageStreamSink);
      }
    
       

    imageSt
  }
    
  

  /**
   * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
   * still image is ready to be saved.
   */ide
   *     d onImageAvailable(ImageReader reader) {
     
     

    ageSaver(
           Use acquireNextImage since image reader is o
            er.acquireNextImage(),
          p

          @

    dartMes
  }
    
   

    dartMes
  }
    raCaptureCa
  

  
  er -> {

     Ue c

  ByteBuffer bufer = plae.getBfer

  byte[] bytes = ne yte[buffer.remaini

  Map<String, Objec> lan

  

  planes.add(planeBuffer);

  M

  imageBufferput("heght", img.gteight());imageBuffer.put(eBuffer.ut("planes", plaimageBuffer.put("sensorExposureTime",this.cap

  imageBuffer.       final Ha

  img.close();ckgroundHandprivate void closeCaptureSession() {
    if (captureSession != null) {
      Log.i(TAG, "closeCaptureSessio"captureSesion.close();

  public void close() {
    Log.i(TAG, "close");
    closeCaptureSessinif (cameraDevice != nu

    ameraDevice =nul; (pictureImaeeader   ictureImageReader =nul; (imageStreamReade = nul   mageStreamReader =nul; (mediaRecorder ! ull)    ediaRecorder.rlese();mediaRecorder = null;s

  public void dispose() {
    Log.i(TAG, "dispose");
close();

  getDevic