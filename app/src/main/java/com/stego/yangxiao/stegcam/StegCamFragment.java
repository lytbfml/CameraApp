package com.stego.yangxiao.stegcam;

import com.stego.yangxiao.stegcam.HelperMethod;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stego.yangxiao.stegcam.HelperMethod.generateTimestampWithMd;

/**
 * Created by Yangxiao on 8/30/2017.
 */

public class StegCamFragment extends Fragment implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {
	
	private static final String TAG = "StegCam";
	
	private String mScenePath;
	
	int imgNeed = 50;
	
	private static final String SHARED_PREFERENCES = "com.stegCam.yangxiao";
	private static final String TOTAL_IMAGECOUNTER = "totalImageCounter";
	SharedPreferences prefs;
	
	/**
	 * Conversion from screen rotation to JPEG orientation.
	 */
	private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
	
	static {
		ORIENTATIONS.append(Surface.ROTATION_0, 0);
		ORIENTATIONS.append(Surface.ROTATION_90, 90);
		ORIENTATIONS.append(Surface.ROTATION_180, 180);
		ORIENTATIONS.append(Surface.ROTATION_270, 270);
	}
	
	
	/**
	 * Iso value
	 * 100, 200, 1000
	 */
	private static final int ISO_VALUE_100 = 100;
	private static final int ISO_VALUE_200 = 200;
	private static final int ISO_VALUE_1000 = 1000;
	private int current_ISO;
	/**
	 * Exposure time
	 * 1/10 = 0.1 = 100000000 ns
	 * 1/50 = 0.02 = 20000000 ns
	 * 1/200 = 0.005 = 5000000 ns
	 */
	private static final long EXPOSURE_TIME_1_10 = 100000000;
	private static final long EXPOSURE_TIME_1_50 = 20000000;
	private static final long EXPOSURE_TIME_1_200 = 5000000;
	private long current_EXP;
	
	/**
	 * Request code for camera permissions.
	 */
	private static final int REQUEST_CAMERA_PERMISSIONS = 200;
	
	private static final String[] CAMERA_PERMISSIONS = {
			Manifest.permission.CAMERA,
			Manifest.permission.READ_EXTERNAL_STORAGE,
			Manifest.permission.WRITE_EXTERNAL_STORAGE,
	};
	
	private static final long PRECAPTURE_TIMEOUT_MS = 1000;
	
	/**
	 * Camera state: Device is closed.
	 */
	private static final int STATE_CLOSED = 0;
	
	/**
	 * Camera state: Device is opened, but is not capturing.
	 */
	private static final int STATE_OPENED = 1;
	
	/**
	 * Camera state: Showing camera preview.
	 */
	private static final int STATE_PREVIEW = 2;
	
	/**
	 * Camera state: Waiting for 3A convergence before capturing a photo.
	 */
	private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3;
	
	private CaptureRequest.Builder mPreviewRequestBuilder;
	
	private int mState = STATE_CLOSED;
	
	/**
	 * Timer to use with pre-capture sequence to ensure a timely capture if 3A convergence is
	 * taking too long.
	 */
	private long mCaptureTimer;
	
	private String mCameraId;
	
	private CameraCaptureSession mCaptureSession;
	
	private CameraDevice mCameraDevice;
	
	private Size mPreviewSize;
	
	private CameraCharacteristics mCharacteristics;
	
	private Handler mBackgroundHandler;
	
	private final AtomicInteger mRequestCounter = new AtomicInteger();
	
	private HelperMethod.RefCountedAutoCloseable<ImageReader> mJpegImageReader;
	
	private HelperMethod.RefCountedAutoCloseable<ImageReader> mRawImageReader;
	
	private OrientationEventListener mOrientationListener;
	
	
	/**
	 * {@link TextureView.SurfaceTextureListener} handles several lifecycle events of a
	 * {@link TextureView}.
	 */
	private final TextureView.SurfaceTextureListener mSurfaceTextureListener
			= new TextureView.SurfaceTextureListener() {
		
		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
			//			configureTransform(width, height);
			if (mState != STATE_CLOSED) {
				createCameraPreviewSessionLocked();
			}
		}
		
		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
			//			configureTransform(width, height);
		}
		
		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
			synchronized (mCameraStateLock) {
				mPreviewSize = null;
			}
			return true;
		}
		
		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture texture) {
		}
		
	};
	
	
	private TextureView mTextureView;
	
	/**
	 * An additional thread for running tasks that shouldn't block the UI.  This is used for all
	 * callbacks from the {@link CameraDevice} and {@link CameraCaptureSession}s.
	 */
	private HandlerThread mBackgroundThread;
	
	/**
	 * A {@link Semaphore} to prevent the app from exiting before closing the camera.
	 */
	private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
	
	/**
	 * A lock protecting camera state.
	 */
	private final Object mCameraStateLock = new Object();
	
	/**
	 * Whether or not the currently configured camera device is fixed-focus.
	 */
	private boolean mNoAFRun = false;
	
	private int mPendingUserCaptures = 0;
	
	private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mJpegResultQueue = new TreeMap<>();
	
	private final TreeMap<Integer, ImageSaver.ImageSaverBuilder> mRawResultQueue = new TreeMap<>();
	
	private TextView tv_current;
	
	private TextView tv_total;
	
	private EditText imNeedEditText;
	
	private ProgressBar bar1 = null;
	
	private ProgressBar bar2 = null;
	/**
	 * {@link CameraDevice.StateCallback} is called when the currently active {@link CameraDevice}
	 * changes its state.
	 */
	private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
		
		
		@Override
		public void onOpened(@NonNull CameraDevice cameraDevice) {
			// This method is called when the camera is opened.
			// We start camera preview here if the TextureView displaying this has been set up.
			synchronized (mCameraStateLock) {
				mState = STATE_OPENED;
				mCameraOpenCloseLock.release();
				mCameraDevice = cameraDevice;
				
				// Start the preview session if the TextureView has been set up already.
				if (mPreviewSize != null && mTextureView.isAvailable()) {
					createCameraPreviewSessionLocked();
				}
			}
		}
		
		@Override
		public void onDisconnected(@NonNull CameraDevice cameraDevice) {
			synchronized (mCameraStateLock) {
				Log.d(TAG, "Camera disconnected");
				mState = STATE_CLOSED;
				mCameraOpenCloseLock.release();
				cameraDevice.close();
				mCameraDevice = null;
			}
		}
		
		@Override
		public void onClosed(CameraDevice cameraDevice) {
			synchronized (mCameraStateLock) {
				Log.d(TAG, "Camera " + cameraDevice.getId() + " closed");
				// TODO: 8/30/2017
			}
		}
		
		@Override
		public void onError(@NonNull CameraDevice cameraDevice, int i) {
			Log.e(TAG, "Received camera device error: " + i);
			synchronized (mCameraStateLock) {
				mState = STATE_CLOSED;
				mCameraOpenCloseLock.release();
				cameraDevice.close();
				mCameraDevice = null;
			}
			Activity activity = getActivity();
			if (null != activity) {
				activity.finish();
			}
		}
	};
	
	
	/**
	 * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
	 * RAW image is ready to be saved.
	 */
	private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
			= new ImageReader.OnImageAvailableListener() {
		
		@Override
		public void onImageAvailable(ImageReader reader) {
			dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
		}
		
	};
	
	
	/**
	 * A {@link CameraCaptureSession.CaptureCallback} that handles events for the preview and
	 * pre-capture sequence.
	 */
	private CameraCaptureSession.CaptureCallback mPreCaptureCallback = new CameraCaptureSession.CaptureCallback() {
		
		private void process(CaptureResult result) {
			synchronized (mCameraStateLock) {
				switch (mState) {
					case STATE_PREVIEW: {
						// We have nothing to do when the camera preview is running normally.
						break;
					}
					case STATE_WAITING_FOR_3A_CONVERGENCE: {
						boolean readyToCapture = true;
						
						if (!mNoAFRun) {
							Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
							if (afState == null) {
								break;
							}
							
							// If auto-focus has reached locked state, we are ready to capture
							readyToCapture =
									(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
											afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
						}
						
						// If we are running on an non-legacy device, we should also wait until
						// auto-exposure and auto-white-balance have converged as well before
						// taking a picture.
						Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
						Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
						if (aeState == null || awbState == null) {
							break;
						}
						
						readyToCapture = readyToCapture &&
								aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
								awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
						
						if (!readyToCapture && hitTimeoutLocked()) {
							Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
							readyToCapture = true;
						}
						
						if (readyToCapture && mPendingUserCaptures > 0) {
							// Capture once for each user tap of the "Picture" button.
							while (mPendingUserCaptures > 0) {
								nineDataSet();
								//Set first progress bar value
								bar1.setProgress(mRequestCounter.intValue());
								//Set the second progress bar value
								bar1.setSecondaryProgress(mRequestCounter.intValue() + 1);
								mPendingUserCaptures--;
							}
							// After this, the camera will go back to the normal state of preview.
							mState = STATE_PREVIEW;
						}
					}
				}
			}
		}
		
		@Override
		public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
		                                CaptureResult partialResult) {
			process(partialResult);
		}
		
		@Override
		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
		                               TotalCaptureResult result) {
			process(result);
		}
	};
	
	
	/**
	 * A {@link CameraCaptureSession.CaptureCallback} that handles the still JPEG and RAW capture
	 * request.
	 */
	private final CameraCaptureSession.CaptureCallback mCaptureCallback
			= new CameraCaptureSession.CaptureCallback() {
		@Override
		public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
		                             long timestamp, long frameNumber) {
			long expTime = (current_EXP / 1000000);
			String expString = (current_EXP == 0 ? "Auto" : Long.toString(expTime));
			String isoString = (current_ISO == 0 ? "Auto" : Integer.toString(current_ISO));
			
			if (mRequestCounter.intValue() == 1) {
				mScenePath = "Scene_" + generateTimestampWithMd();
				File fileT = new File(
						Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) +
								File.separator + "StegoCam" + File.separator + mScenePath);
				if (!fileT.exists()) {
					fileT.mkdir();
				}
			}
			
			
			File fileTemp = new File(
					Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) +
							File.separator + "StegoCam" +
							File.separator + mScenePath +
							File.separator + "I" + isoString + "E" + expString +
							File.separator);
			if (!fileTemp.exists()) {
				fileTemp.mkdir();
			}
			
			File rawFile = HelperMethod.generateFileName(isoString, expString, fileTemp, mScenePath);
			
			Log.d(TAG, "----Pre ISO is: " + request.get(CaptureRequest.SENSOR_SENSITIVITY).toString());
			Log.d(TAG, "----Pre EXP is: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME).toString());
			Log.d(TAG, "----Pre FRAME_DURATION is: " + request.get(CaptureRequest.SENSOR_FRAME_DURATION).toString());
			
			// Look up the ImageSaverBuilder for this request and update it with the file name
			// based on the capture start time.
			ImageSaver.ImageSaverBuilder rawBuilder;
			int requestId = (int) request.getTag();
			synchronized (mCameraStateLock) {
				rawBuilder = mRawResultQueue.get(requestId);
			}
			
			if (rawBuilder != null)
				rawBuilder.setFile(rawFile);
		}
		
		@Override
		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
		                               TotalCaptureResult result) {
			boolean readyToCapture = true;
			if (!mNoAFRun) {
				Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
				if (afState == null) {
					Log.d(TAG, "afState == null");
				}
				
				// If auto-focus has reached locked state, we are ready to capture
				readyToCapture = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
						afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
				Log.d(TAG, "readyToCapture = " + readyToCapture);
			}
			
			// If we are running on an non-legacy device, we should also wait until
			// auto-exposure and auto-white-balance have converged as well before
			// taking a picture.
			if (!isLegacyLocked()) {
				Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
				Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
				if (aeState == null || awbState == null) {
					Log.w(TAG, "aeState == null");
				}
				
				readyToCapture = readyToCapture && aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
						awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
				
				Log.d(TAG, "readyToCapture aeState&awbState = " + readyToCapture);
			}
			
			
			Log.d(TAG, "----The current ISO is: " + result.get(CaptureResult.SENSOR_SENSITIVITY).toString());
			Log.d(TAG, "----The current EXP is: " + result.get(CaptureResult.SENSOR_EXPOSURE_TIME).toString());
			Log.d(TAG, "----The current FRAME_DURATION is: " + result.get(CaptureResult.SENSOR_FRAME_DURATION).toString());
			
			
			int requestId = (int) request.getTag();
			ImageSaver.ImageSaverBuilder rawBuilder;
			StringBuilder sb = new StringBuilder();
			
			// Look up the ImageSaverBuilder for this request and update it with the CaptureResult
			synchronized (mCameraStateLock) {
				rawBuilder = mRawResultQueue.get(requestId);
				
				if (rawBuilder != null) {
					rawBuilder.setResult(result);
					sb.append("Saving RAW as: ");
					sb.append(rawBuilder.getSaveLocation());
				}
				
				// If we have all the results necessary, save the image to a file in the background.
				handleCompletionLocked(requestId, rawBuilder, mRawResultQueue);
				
				finishedCaptureLocked();
			}
			
			showToast(sb.toString());
		}
		
		@Override
		public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
		                            CaptureFailure failure) {
			int requestId = (int) request.getTag();
			synchronized (mCameraStateLock) {
				mJpegResultQueue.remove(requestId);
				mRawResultQueue.remove(requestId);
				finishedCaptureLocked();
			}
			showToast("Capture failed!");
		}
		
	};
	
	
	/**
	 * A {@link Handler} for showing {@link Toast}s on the UI thread.
	 */
	private final Handler mMessageHandler = new Handler(Looper.getMainLooper()) {
		@Override
		public void handleMessage(Message msg) {
			Activity activity = getActivity();
			if (activity != null) {
				Toast.makeText(activity, (String) msg.obj, Toast.LENGTH_SHORT).show();
			}
		}
	};
	
	
	public static StegCamFragment newInstance() {
		return new StegCamFragment();
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
	                         Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_steg, container, false);
	}
	
	@Override
	public void onViewCreated(final View view, Bundle savedInstanceState) {
		view.findViewById(R.id.picture).setOnClickListener(this);
		view.findViewById(R.id.clearCounter).setOnClickListener(this);
		mTextureView = view.findViewById(R.id.texture);
		
		imNeedEditText = view.findViewById(R.id.editText);
		
		tv_current = view.findViewById(R.id.textView);
		tv_total = view.findViewById(R.id.textView4);
		bar1 = view.findViewById(R.id.bar1);
		bar2 = view.findViewById(R.id.bar2);
	}
	
	@Override
	public void onResume() {
		prefs = getActivity().getSharedPreferences(SHARED_PREFERENCES, Context.MODE_PRIVATE);
		int num = getTotalCaptures();
		Log.v(TAG, "onResume");
		String one = " " + num;
		String two = " " + 0;
		tv_total.setText(one);
		tv_current.setText(two);
		
		super.onResume();
		startBackgroundThread();
		openCamera();
		
		if (mTextureView.isAvailable()) {
			if (mState != STATE_CLOSED) {
				createCameraPreviewSessionLocked();
			}
		} else {
			mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
		}
		
	}
	
	@Override
	public void onPause() {
		Log.d(TAG, "onPause: " + saveTotalCaptures());
		
		if (mOrientationListener != null) {
			mOrientationListener.disable();
		}
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}
	
	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions,
	                                       int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
			for (int result : grantResults) {
				if (result != PackageManager.PERMISSION_GRANTED) {
					showMissingPermissionError();
					return;
				}
			}
		} else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}
	
	@Override
	public void onClick(View view) {
		switch (view.getId()) {
			case R.id.picture: {
				takePicture();
				break;
			}
			case R.id.clearCounter: {
				cleanCounter();
				break;
			}
		}
	}
	
	private void updateCounter() {
		getActivity().runOnUiThread(new Runnable() {
			@SuppressLint("SetTextI18n")
			@Override
			public void run() {
				Log.w(TAG, "updateCounter()");
				String temp = " " + Integer.toString(mRequestCounter.intValue());
				tv_current.setText(temp);
			}
		});
	}
	
	/**
	 * Sets up state related to camera that is needed before opening a {@link CameraDevice}.
	 */
	private boolean setUpCameraOutputs() {
		Activity activity = getActivity();
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		
		if (manager == null) {
			HelperMethod.ErrorDialog.buildErrorDialog("This device doesn't support Camera2 API.").
					show(getFragmentManager(), "dialog");
			return false;
		}
		try {
			// Find a CameraDevice that supports RAW captures, and configure state.
			for (String cameraId : manager.getCameraIdList()) {
				CameraCharacteristics characteristics
						= manager.getCameraCharacteristics(cameraId);
				
				// We only use a camera that supports RAW in this sample.
				if (!HelperMethod.contains(characteristics.get(
						CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
						CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
					continue;
				}
				
				StreamConfigurationMap map = characteristics.get(
						CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
				
				// For still image captures, we use the largest available size.
				Size largestJpeg = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
						new HelperMethod.CompareSizesByArea());
				
				Size largestRaw = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
						new HelperMethod.CompareSizesByArea());
				
				synchronized (mCameraStateLock) {
					// Set up ImageReaders for JPEG and RAW outputs.  Place these in a reference
					// counted wrapper to ensure they are only closed when all background tasks
					// using them are finished.
					if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
						mJpegImageReader = new HelperMethod.RefCountedAutoCloseable<>(
								ImageReader.newInstance(largestJpeg.getWidth(),
										largestJpeg.getHeight(), ImageFormat.JPEG, /*maxImages*/5));
					}
					//					mJpegImageReader.get().setOnImageAvailableListener(
					//							mOnJpegImageAvailableListener, mBackgroundHandler);
					
					if (mRawImageReader == null || mRawImageReader.getAndRetain() == null) {
						mRawImageReader = new HelperMethod.RefCountedAutoCloseable<>(
								ImageReader.newInstance(largestRaw.getWidth(),
										largestRaw.getHeight(), ImageFormat.RAW_SENSOR, /*maxImages*/ 5));
					}
					mRawImageReader.get().setOnImageAvailableListener(
							mOnRawImageAvailableListener, mBackgroundHandler);
					
					mCharacteristics = characteristics;
					mCameraId = cameraId;
				}
				return true;
			}
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
		
		// If we found no suitable cameras for capturing RAW, warn the user.
		HelperMethod.ErrorDialog.buildErrorDialog("This device doesn't support capturing RAW photos").
				show(getFragmentManager(), "dialog");
		return false;
	}
	
	
	private void openCamera() {
		if (!setUpCameraOutputs()) {
			return;
		}
		if (!hasAllPermissionsGranted()) {
			checkPermission();
			return;
		}
		
		Activity activity = getActivity();
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		try {
			// Wait for any previously running session to finish.
			if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Time out waiting to lock camera opening.");
			}
			
			String cameraId;
			Handler backgroundHandler;
			synchronized (mCameraStateLock) {
				cameraId = mCameraId;
				backgroundHandler = mBackgroundHandler;
			}
			
			// Attempt to open the camera. mStateCallback will be called on the background handler's
			// thread when this succeeds or fails.
			manager.openCamera(cameraId, mStateCallback, backgroundHandler);
			
		} catch (CameraAccessException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
		}
		
		
	}
	
	
	/**
	 * Tells whether all the necessary permissions are granted to this app.
	 *
	 * @return True if all the required permissions are granted.
	 */
	private boolean hasAllPermissionsGranted() {
		for (String permission : CAMERA_PERMISSIONS) {
			if (ActivityCompat.checkSelfPermission(getActivity(), permission)
					!= PackageManager.PERMISSION_GRANTED) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Checking permissions
	 */
	private void checkPermission() {
		
		final List<String> neededPermissions = new ArrayList<>();
		for (final String permission : CAMERA_PERMISSIONS) {
			if (ContextCompat.checkSelfPermission(getActivity(),
					permission) != PackageManager.PERMISSION_GRANTED) {
				neededPermissions.add(permission);
			}
		}
		
		if (!neededPermissions.isEmpty()) {
			FragmentCompat.requestPermissions(this, neededPermissions.toArray(new String[]{}), REQUEST_CAMERA_PERMISSIONS);
		}
	}
	
	
	/**
	 * Closes the current {@link CameraDevice}.
	 */
	private void closeCamera() {
		try {
			mCameraOpenCloseLock.acquire();
			synchronized (mCameraStateLock) {
				
				// Reset state and clean up resources used by the camera.
				// Note: After calling this, the ImageReaders will be closed after any background
				// tasks saving Images from these readers have been completed.
				mPendingUserCaptures = 0;
				mState = STATE_CLOSED;
				if (null != mCaptureSession) {
					mCaptureSession.close();
					mCaptureSession = null;
				}
				if (null != mCameraDevice) {
					mCameraDevice.close();
					mCameraDevice = null;
				}
				if (null != mJpegImageReader) {
					mJpegImageReader.close();
					mJpegImageReader = null;
				}
				if (null != mRawImageReader) {
					mRawImageReader.close();
					mRawImageReader = null;
				}
			}
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
		} finally {
			mCameraOpenCloseLock.release();
		}
	}
	
	/**
	 * Starts a background thread and its {@link Handler}.
	 */
	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		synchronized (mCameraStateLock) {
			mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
		}
	}
	
	/**
	 * Stops the background thread and its {@link Handler}.
	 */
	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join();
			mBackgroundThread = null;
			synchronized (mCameraStateLock) {
				mBackgroundHandler = null;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 * Creates a new {@link CameraCaptureSession} for camera preview.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void createCameraPreviewSessionLocked() {
		try {
			SurfaceTexture texture = mTextureView.getSurfaceTexture();
			// We configure the size of default buffer to be the size of camera preview we want.
			//			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
			texture.setDefaultBufferSize(960, 540);
			
			// This is the output Surface we need to start preview.
			Surface surface = new Surface(texture);
			
			// We set up a CaptureRequest.Builder with the output Surface.
			mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			mPreviewRequestBuilder.addTarget(surface);
			
			// Here, we create a CameraCaptureSession for camera preview.
			mCameraDevice.createCaptureSession(Arrays.asList(surface, mRawImageReader.get().getSurface()),
					new CameraCaptureSession.StateCallback() {
						@Override
						public void onConfigured(CameraCaptureSession cameraCaptureSession) {
							synchronized (mCameraStateLock) {
								// The camera is already closed
								if (null == mCameraDevice) {
									return;
								}
								
								try {
									setup3AControlsLocked(mPreviewRequestBuilder);
									// Finally, we start displaying the camera preview.
									cameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
											mPreCaptureCallback, mBackgroundHandler);
									mState = STATE_PREVIEW;
								} catch (CameraAccessException | IllegalStateException e) {
									e.printStackTrace();
									return;
								}
								// When the session is ready, we start displaying the preview.
								mCaptureSession = cameraCaptureSession;
							}
						}
						
						@Override
						public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
							showToast("Failed to configure camera.");
						}
					}, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 * Configure the given {@link CaptureRequest.Builder} to use auto-focus, auto-exposure, and
	 * auto-white-balance controls if available.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @param builder the builder to configure.
	 */
	private void setup3AControlsLocked(CaptureRequest.Builder builder) {
		// Enable auto-magical 3A run by camera device
		builder.set(CaptureRequest.CONTROL_MODE,
				CaptureRequest.CONTROL_MODE_AUTO);
		
		Float minFocusDist =
				mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
		
		// If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
		mNoAFRun = (minFocusDist == null || minFocusDist == 0);
		
		if (!mNoAFRun) {
			// If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
			if (HelperMethod.contains(mCharacteristics.get(
					CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
					CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
				builder.set(CaptureRequest.CONTROL_AF_MODE,
						CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
			} else {
				builder.set(CaptureRequest.CONTROL_AF_MODE,
						CaptureRequest.CONTROL_AF_MODE_AUTO);
			}
		}
		
		// If there is an auto-magical flash control mode available, use it, otherwise default to
		// the "on" mode, which is guaranteed to always be available.
		if (HelperMethod.contains(mCharacteristics.get(
				CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
				CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
			builder.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
		} else {
			builder.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON);
		}
		
		// If there is an auto-magical white balance control mode available, use it.
		if (HelperMethod.contains(mCharacteristics.get(
				CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
				CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
			// Allow AWB to run auto-magically if this device supports this
			builder.set(CaptureRequest.CONTROL_AWB_MODE,
					CaptureRequest.CONTROL_AWB_MODE_AUTO);
		}
		
	}
	
	
	/**
	 * Initiate a still image capture.
	 * <p/>
	 * This function sends a capture request that initiates a pre-capture sequence in our state
	 * machine that waits for auto-focus to finish, ending in a "locked" state where the lens is no
	 * longer moving, waits for auto-exposure to choose a good exposure value, and waits for
	 * auto-white-balance to converge.
	 */
	private void takePicture() {
		synchronized (mCameraStateLock) {
			
			mPendingUserCaptures++;
			Log.d(TAG, "Pending captures: " + mPendingUserCaptures);
			//			tv.setText(mRequestCounter + "");
			// If we already triggered a pre-capture sequence, or are in a state where we cannot
			// do this, return immediately.
			if (mState != STATE_PREVIEW) {
				return;
			}
			
			if (mRequestCounter.intValue() == 0) {
				//Check if user input number of photos for each setting.
				String mImgNeed = imNeedEditText.getText().toString().trim();
				if (!mImgNeed.isEmpty()) {
					int x = Integer.parseInt(imNeedEditText.getText().toString());
					imgNeed = x;
				}
			}
			
			try {
				// Trigger an auto-focus run if camera is capable. If the camera is already focused,
				// this should do nothing.
				if (!mNoAFRun) {
					mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
							CameraMetadata.CONTROL_AF_TRIGGER_START);
				}
				
				// If this is not a legacy device, we can also trigger an auto-exposure metering
				// run.
				if (!isLegacyLocked()) {
					// Tell the camera to lock focus.
					mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
							CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
				}
				
				// Update state machine to wait for auto-focus, auto-exposure, and
				// auto-white-balance (aka. "3A") to converge.
				mState = STATE_WAITING_FOR_3A_CONVERGENCE;
				
				// Start a timer for the pre-capture sequence.
				startTimerLocked();
				
				// Replace the existing repeating request with one with updated 3A triggers.
				mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
						mBackgroundHandler);
			} catch (CameraAccessException e) {
				e.printStackTrace();
			}
		}
	}
	
	
	/**
	 * * Send a capture request to the camera device that initiates a capture targeting the JPEG and
	 * RAW outputs.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @param isoValue manual set iso
	 * @param exp      manual set exp
	 * @param manual   true if manual setting, otherwise, will use
	 *                 {@link #setup3AControlsLocked(CaptureRequest.Builder captureBuilder)}
	 */
	private void captureStillPictureLocked(int isoValue, long exp, boolean manual) {
		
		try {
			final Activity activity = getActivity();
			if (null == activity || null == mCameraDevice) {
				return;
			}
			
			// This is the CaptureRequest.Builder that we use to take a picture.
			final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			
			//			captureBuilder.addTarget(mJpegImageReader.get().getSurface());
			captureBuilder.addTarget(mRawImageReader.get().getSurface());
			
			
			if (manual) {
				captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
				captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);
				captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
			} else {
				setup3AControlsLocked(captureBuilder);
			}
			
			// Set orientation.
			int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
			
			captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
			
			// Set request tag to easily track results in callbacks.
			Log.d(TAG, "Request counter: " + mRequestCounter.intValue());
			captureBuilder.setTag(mRequestCounter.getAndIncrement());
			
			final CaptureRequest request = captureBuilder.build();
			
			// Create an ImageSaverBuilder in which to collect results, and add it to the queue
			// of active requests.
			ImageSaver.ImageSaverBuilder jpegBuilder = new ImageSaver.ImageSaverBuilder(activity)
					.setCharacteristics(mCharacteristics);
			ImageSaver.ImageSaverBuilder rawBuilder = new ImageSaver.ImageSaverBuilder(activity)
					.setCharacteristics(mCharacteristics);
			
			mJpegResultQueue.put((int) request.getTag(), jpegBuilder);
			mRawResultQueue.put((int) request.getTag(), rawBuilder);
			
			//			SurfaceTexture mDummyPreview = new SurfaceTexture(1);
			//
			//			mCameraDevice.createCaptureSession(Arrays.asList(new Surface(mDummyPreview), /*mJpegImageReader.get().getSurface(),*/
			//					mRawImageReader.get().getSurface()), new CameraCaptureSession.StateCallback() {
			//						@Override
			//						public void onConfigured(@NonNull CameraCaptureSession session) {
			//							synchronized (mCameraStateLock) {
			//								try {
			//									session.capture(request, mCaptureCallback, mBackgroundHandler);
			//								} catch (CameraAccessException e) {
			//									Log.e(TAG, " exception occurred while accessing " + mCameraId, e);
			//									return;
			//								}
			//								mCaptureSession = session;
			//							}
			//
			//						}
			//
			//						@Override
			//						public void onConfigureFailed(@NonNull CameraCaptureSession session) {
			//							showToast("Failed to configure camera.");
			//						}
			//					}
			//					, mBackgroundHandler
			//			);
			
			mCaptureSession.capture(request, mCaptureCallback, mBackgroundHandler);
			
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 * Called after a RAW/JPEG capture has completed; resets the AF trigger state for the
	 * pre-capture sequence.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void finishedCaptureLocked() {
		updateCounter();
		Log.d(TAG, "\nFinishing -------------------------" + mRequestCounter.intValue() + " --------------------------\n\n");
		
		try {
			// Reset the auto-focus trigger in case AF didn't run quickly enough.
			if (!mNoAFRun) {
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
						CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
				
				mCaptureSession.capture(mPreviewRequestBuilder.build(), mPreCaptureCallback,
						mBackgroundHandler);
				
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
						CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
				Log.w(TAG, "Reset AF");
			}
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
		
		if (mRequestCounter.intValue() < (imgNeed * 10)) {
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					takePicture();
				}
			}, 500);
		}
		if (mRequestCounter.intValue() >= (imgNeed * 10)) {
			detachProcessBar();
			HelperMethod.AlertDialogFragment.buildAlertDialog("TASK FINISHED").show(getFragmentManager(), "dialog");
		}
	}
	
	private void detachProcessBar() {
		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				bar1.setVisibility(View.GONE);
				bar2.setVisibility(View.GONE);
			}
		});
	}
	
	
	/**
	 * Check if we are using a device that only supports the LEGACY hardware level.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @return true if this is a legacy device.
	 */
	private boolean isLegacyLocked() {
		return mCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
				CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
	}
	
	
	/**
	 * Start the timer for the pre-capture sequence.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 */
	private void startTimerLocked() {
		mCaptureTimer = SystemClock.elapsedRealtime();
	}
	
	/**
	 * Check if the timer for the pre-capture sequence has been hit.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @return true if the timeout occurred.
	 */
	private boolean hitTimeoutLocked() {
		return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
	}
	
	
	/**
	 * Retrieve the next {@link Image} from a reference counted {@link ImageReader}, retaining
	 * that {@link ImageReader} until that {@link Image} is no longer in use, and set this
	 * {@link Image} as the result for the next request in the queue of pending requests.  If
	 * all necessary information is available, begin saving the image to a file in a background
	 * thread.
	 *
	 * @param pendingQueue the currently active requests.
	 * @param reader       a reference counted wrapper containing an {@link ImageReader} from which
	 *                     to acquire an image.
	 */
	private void dequeueAndSaveImage(TreeMap<Integer, ImageSaver.ImageSaverBuilder> pendingQueue,
	                                 HelperMethod.RefCountedAutoCloseable<ImageReader> reader) {
		synchronized (mCameraStateLock) {
			Map.Entry<Integer, ImageSaver.ImageSaverBuilder> entry =
					pendingQueue.firstEntry();
			ImageSaver.ImageSaverBuilder builder = entry.getValue();
			
			// Increment reference count to prevent ImageReader from being closed while we
			// are saving its Images in a background thread (otherwise their resources may
			// be freed while we are writing to a file).
			if (reader == null || reader.getAndRetain() == null) {
				Log.e(TAG, "Paused the activity before we could save the image," +
						" ImageReader already closed.");
				pendingQueue.remove(entry.getKey());
				return;
			}
			
			Image image;
			try {
				image = reader.get().acquireNextImage();
			} catch (IllegalStateException e) {
				Log.e(TAG, "Too many images queued for saving, dropping image for request: " +
						entry.getKey());
				pendingQueue.remove(entry.getKey());
				return;
			}
			
			builder.setRefCountedReader(reader).setImage(image);
			
			handleCompletionLocked(entry.getKey(), builder, pendingQueue);
		}
	}
	
	
	/**
	 * Runnable that saves an {@link Image} into the specified {@link File}, and updates
	 * {@link android.provider.MediaStore} to include the resulting file.
	 * <p/>
	 * This can be constructed through an {@link ImageSaverBuilder} as the necessary image and
	 * result information becomes available.
	 */
	private static class ImageSaver implements Runnable {
		
		/**
		 * The image to save.
		 */
		private final Image mImage;
		/**
		 * The file we save the image into.
		 */
		private final File mFile;
		
		/**
		 * The CaptureResult for this image capture.
		 */
		private final CaptureResult mCaptureResult;
		
		/**
		 * The CameraCharacteristics for this camera device.
		 */
		private final CameraCharacteristics mCharacteristics;
		
		/**
		 * The Context to use when updating MediaStore with the saved images.
		 */
		private final Context mContext;
		
		/**
		 * A reference counted wrapper for the ImageReader that owns the given image.
		 */
		private final HelperMethod.RefCountedAutoCloseable<ImageReader> mReader;
		
		private ImageSaver(Image image, File file, CaptureResult result,
		                   CameraCharacteristics characteristics, Context context,
		                   HelperMethod.RefCountedAutoCloseable<ImageReader> reader) {
			mImage = image;
			mFile = file;
			mCaptureResult = result;
			mCharacteristics = characteristics;
			mContext = context;
			mReader = reader;
		}
		
		@Override
		public void run() {
			boolean success = false;
			int format = mImage.getFormat();
			switch (format) {
				case ImageFormat.JPEG: {
					Log.v(TAG, "Saving jpeg picture");
					
					ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
					byte[] bytes = new byte[buffer.remaining()];
					buffer.get(bytes);
					
					FileOutputStream output = null;
					
					try {
						output = new FileOutputStream(mFile);
						output.write(bytes);
						success = true;
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						mImage.close();
						if (null != output) {
							try {
								output.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
					break;
				}
				case ImageFormat.RAW_SENSOR: {
					Log.v(TAG, "Saving raw picture.");
					
					DngCreator dngCreator = new DngCreator(mCharacteristics, mCaptureResult);
					FileOutputStream output = null;
					try {
						output = new FileOutputStream(mFile);
						dngCreator.writeImage(output, mImage);
						
						Image.Plane pl[] = mImage.getPlanes();
						
						success = true;
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						mImage.close();
						if (null != output) {
							try {
								output.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
					break;
				}
				default: {
					Log.e(TAG, "Cannot save image, unexpected image format:" + format);
					break;
				}
			}
			
			// Decrement reference count to allow ImageReader to be closed to free up resources.
			mReader.close();
			
			// If saving the file succeeded, update MediaStore.
			if (success) {
				MediaScannerConnection.scanFile(mContext, new String[]{mFile.getPath()},
				/*mimeTypes*/null, new MediaScannerConnection.MediaScannerConnectionClient() {
							@Override
							public void onMediaScannerConnected() {
								// Do nothing
							}
							
							@Override
							public void onScanCompleted(String path, Uri uri) {
								Log.i(TAG, "Scanned " + path + ":");
								Log.i(TAG, "-> uri=" + uri);
							}
						});
			}
		}
		
		/**
		 * Builder class for constructing {@link ImageSaver}s.
		 * <p/>
		 * This class is thread safe.
		 */
		public static class ImageSaverBuilder {
			private Image mImage;
			private File mFile;
			private CaptureResult mCaptureResult;
			private CameraCharacteristics mCharacteristics;
			private Context mContext;
			private HelperMethod.RefCountedAutoCloseable<ImageReader> mReader;
			
			/**
			 * Construct a new ImageSaverBuilder using the given {@link Context}.
			 *
			 * @param context a {@link Context} to for accessing the
			 *                {@link android.provider.MediaStore}.
			 */
			public ImageSaverBuilder(final Context context) {
				mContext = context;
			}
			
			public synchronized ImageSaverBuilder setRefCountedReader(
					HelperMethod.RefCountedAutoCloseable<ImageReader> reader) {
				if (reader == null)
					throw new NullPointerException();
				
				mReader = reader;
				return this;
			}
			
			public synchronized ImageSaverBuilder setImage(final Image image) {
				if (image == null)
					throw new NullPointerException();
				mImage = image;
				return this;
			}
			
			public synchronized ImageSaverBuilder setFile(final File file) {
				if (file == null)
					throw new NullPointerException();
				mFile = file;
				return this;
			}
			
			public synchronized ImageSaverBuilder setResult(final CaptureResult result) {
				if (result == null)
					throw new NullPointerException();
				mCaptureResult = result;
				return this;
			}
			
			public synchronized ImageSaverBuilder setCharacteristics(
					final CameraCharacteristics characteristics) {
				if (characteristics == null)
					throw new NullPointerException();
				mCharacteristics = characteristics;
				return this;
			}
			
			public synchronized ImageSaver buildIfComplete() {
				if (!isComplete()) {
					return null;
				}
				return new ImageSaver(mImage, mFile, mCaptureResult, mCharacteristics, mContext,
						mReader);
			}
			
			public synchronized String getSaveLocation() {
				return (mFile == null) ? "Unknown" : mFile.toString();
			}
			
			private boolean isComplete() {
				return mImage != null && mFile != null && mCaptureResult != null
						&& mCharacteristics != null;
			}
		}
	}
	
	
	//Utility
	//======================================================================================
	
	
	/**
	 * Shows a {@link Toast} on the UI thread.
	 *
	 * @param text The message to show.
	 */
	private void showToast(String text) {
		// We show a Toast by sending request message to mMessageHandler. This makes sure that the
		// Toast is shown on the UI thread.
		Message message = Message.obtain();
		message.obj = text;
		mMessageHandler.sendMessage(message);
	}
	
	
	/**
	 * If the given request has been completed, remove it from the queue of active requests and
	 * send an {@link ImageSaver} with the results from this request to a background thread to
	 * save a file.
	 * <p/>
	 * Call this only with {@link #mCameraStateLock} held.
	 *
	 * @param requestId the ID of the {@link CaptureRequest} to handle.
	 * @param builder   the {@link ImageSaver.ImageSaverBuilder} for this request.
	 * @param queue     the queue to remove this request from, if completed.
	 */
	private void handleCompletionLocked(int requestId, ImageSaver.ImageSaverBuilder builder,
	                                    TreeMap<Integer, ImageSaver.ImageSaverBuilder> queue) {
		if (builder == null)
			return;
		ImageSaver saver = builder.buildIfComplete();
		if (saver != null) {
			queue.remove(requestId);
			AsyncTask.THREAD_POOL_EXECUTOR.execute(saver);
		}
	}
	
	
	/**
	 * Shows that this app really needs the permission and finishes the app.
	 */
	private void showMissingPermissionError() {
		Activity activity = getActivity();
		if (activity != null) {
			Toast.makeText(activity, R.string.request_permission, Toast.LENGTH_SHORT).show();
			activity.finish();
		}
	}
	
	//-------------------------------------Pref values----------------------------------
	
	private int getTotalCaptures() {
		return prefs.getInt(TOTAL_IMAGECOUNTER, 0);
	}
	
	private int saveTotalCaptures() {
		int counter = getTotalCaptures() + mRequestCounter.intValue();
		prefs.edit().putInt(TOTAL_IMAGECOUNTER, counter).apply();
		return counter;
	}
	
	private void cleanCounter() {
		Log.v(TAG, "Cleaning counter!");
		prefs.edit().putInt(TOTAL_IMAGECOUNTER, 0).apply();
		HelperMethod.AlertDialogFragment.buildAlertDialog("Refreshing total counter!").show(getFragmentManager(), "dialog");
	}
	
	//-------------------------------------End Pref values-------------------------------
	
	private void nineDataSet() {
		
		if (mRequestCounter.intValue() == 0) {
			current_ISO = 0;
			current_EXP = 0;
			
			getActivity().runOnUiThread(new Runnable() {
				@SuppressLint("SetTextI18n")
				@Override
				public void run() {
					bar1.setVisibility(View.VISIBLE);
					bar1.setMax(imgNeed * 10);
					bar2.setVisibility(View.VISIBLE);
				}
			});
			
			
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					captureStillPictureLocked(current_ISO, current_EXP, false);
				}
			}, 5000);
		}
		
		if (mRequestCounter.intValue() < (imgNeed) && mRequestCounter.intValue() != 0) {
			current_ISO = 0;
			current_EXP = 0;
			captureStillPictureLocked(0, 0, false);
		} else if (mRequestCounter.intValue() >= (imgNeed) && mRequestCounter.intValue() < (imgNeed * 2)) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_10;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 2) && mRequestCounter.intValue() < (imgNeed * 3)) {
			current_ISO = ISO_VALUE_200;
			current_EXP = EXPOSURE_TIME_1_10;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 3) && mRequestCounter.intValue() < (imgNeed * 4)) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_50;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 4) && mRequestCounter.intValue() < (imgNeed * 5)) {
			current_ISO = ISO_VALUE_200;
			current_EXP = EXPOSURE_TIME_1_50;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 5) && mRequestCounter.intValue() < (imgNeed * 6)) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_200;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 6) && mRequestCounter.intValue() < (imgNeed * 7)) {
			current_ISO = ISO_VALUE_200;
			current_EXP = EXPOSURE_TIME_1_200;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 7) && mRequestCounter.intValue() < (imgNeed * 8)) {
			current_ISO = ISO_VALUE_1000;
			current_EXP = EXPOSURE_TIME_1_10;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 8) && mRequestCounter.intValue() < (imgNeed * 9)) {
			current_ISO = ISO_VALUE_1000;
			current_EXP = EXPOSURE_TIME_1_50;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 9) && mRequestCounter.intValue() < (imgNeed * 10)) {
			current_ISO = ISO_VALUE_1000;
			current_EXP = EXPOSURE_TIME_1_200;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		}
	}
	
}