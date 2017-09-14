package com.stego.yangxiao.stegcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.DialogInterface;
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
import android.view.animation.AnimationUtils;
import android.widget.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;

/**
 * Created by Yangxiao on 8/30/2017.
 */

public class StegCamFragment extends Fragment implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {
	
	int imgNeed = 1;
	
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
	
	private static final String TAG = "StegCam";
	
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
	
	/**
	 * Camera state: Starting still capturing
	 */
	private static final int STATE_STILLCAPTURING = 4;
	
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
	
	private RefCountedAutoCloseable<ImageReader> mJpegImageReader;
	
	private RefCountedAutoCloseable<ImageReader> mRawImageReader;
	
	private OrientationEventListener mOrientationListener;
	
	private AutoFitTextureView mTextureView;
	
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
	
	private EditText et;
	
	private EditText manualISO;
	
	private EditText manualExp;
	
	private EditText manualExp2;
	
	private PopupWindow mPopWindow;
	
	private Context mContext;
	
	private RelativeLayout mRelativeLayout;
	
	private Button setButton;
	
	private boolean manSet = false;
	
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
					//					createCameraPreviewSessionLocked();
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
	 * JPEG image is ready to be saved.
	 */
	//	private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener
	//			= new ImageReader.OnImageAvailableListener() {
	//
	//		@Override
	//		public void onImageAvailable(ImageReader reader) {
	//			dequeueAndSaveImage(mJpegResultQueue, mJpegImageReader);
	//		}
	//
	//	};
	
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
								captureStillPictureLocked(0, 0, false);
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
			int count = mRequestCounter.intValue();
			long expT = (current_EXP / 1000000);
			String expS = current_EXP == 0 ? "Auto" : Long.toString(expT);
			
			String currentDateTime = generateTimestamp();
			File rawFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) +
					File.separator + "StegoCam" + File.separator + "RAW_" + count + "_" +
					"I" + (current_ISO == 0 ? "Auto" : current_ISO) + "E" + expS + "_" + currentDateTime + "" + ".dng");
			
			//			File jpegFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) +
			//					File.separator + "StegoCam" + File.separator + "JPEG_" + "I" + current_ISO + "E" + current_EXP/1000000 + "_"
			//					+ currentDateTime + ".jpg");
			
			Log.d(TAG, "Saving file------------The current ISO is: " + current_ISO);
			Log.d(TAG, "Saving file------------The current EXP is: " + Long.toString(current_EXP / 1000000));
			
			
			// Look up the ImageSaverBuilder for this request and update it with the file name
			// based on the capture start time.
			ImageSaver.ImageSaverBuilder jpegBuilder;
			ImageSaver.ImageSaverBuilder rawBuilder;
			int requestId = (int) request.getTag();
			synchronized (mCameraStateLock) {
				//				jpegBuilder = mJpegResultQueue.get(requestId);
				rawBuilder = mRawResultQueue.get(requestId);
			}
			
			//			if (jpegBuilder != null)
			//				jpegBuilder.setFile(jpegFile);
			if (rawBuilder != null)
				rawBuilder.setFile(rawFile);
		}
		
		@Override
		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
		                               TotalCaptureResult result) {
			int requestId = (int) request.getTag();
			//			ImageSaver.ImageSaverBuilder jpegBuilder;
			ImageSaver.ImageSaverBuilder rawBuilder;
			StringBuilder sb = new StringBuilder();
			
			
			// Look up the ImageSaverBuilder for this request and update it with the CaptureResult
			synchronized (mCameraStateLock) {
				//				jpegBuilder = mJpegResultQueue.get(requestId);
				rawBuilder = mRawResultQueue.get(requestId);
				
				//				if (jpegBuilder != null) {
				//					jpegBuilder.setResult(result);
				//					sb.append("Saving JPEG as: ");
				//					sb.append(jpegBuilder.getSaveLocation());
				//				}
				if (rawBuilder != null) {
					rawBuilder.setResult(result);
					//					if (jpegBuilder != null)
					//						sb.append(", ");
					sb.append("Saving RAW as: ");
					sb.append(rawBuilder.getSaveLocation());
				}
				
				// If we have all the results necessary, save the image to a file in the background.
				//				handleCompletionLocked(requestId, jpegBuilder, mJpegResultQueue);
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
		Log.d(TAG, "onViewCreated()");
		
		mContext = getContext();
		mRelativeLayout = view.findViewById(R.id.mainRelLayout);
		setButton = view.findViewById(R.id.setBtn);
		
		view.findViewById(R.id.setBtn).setOnClickListener(this);
		view.findViewById(R.id.picture).setOnClickListener(this);
		view.findViewById(R.id.clearCounter).setOnClickListener(this);
		
		et = view.findViewById(R.id.editText);
		tv_current = view.findViewById(R.id.textView);
		tv_total = view.findViewById(R.id.textView4);
		bar1 = view.findViewById(R.id.bar1);
		bar2 = view.findViewById(R.id.bar2);
		
		manualISO = view.findViewById(R.id.editText2);
		manualExp = view.findViewById(R.id.editText3);
		manualExp2 = view.findViewById(R.id.editText4);
		
		
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
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
			case R.id.setBtn: {
				
				// Initialize a new instance of LayoutInflater service
				LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(LAYOUT_INFLATER_SERVICE);
						
				// Inflate the custom layout/view
				View popView = inflater.inflate(R.layout.popupw, null);
				
				popView.setAnimation(AnimationUtils.loadAnimation(getActivity(), R.anim.pop_show));
				
				// Initialize a new instance of popup window
				mPopWindow = new PopupWindow(
						popView,
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.WRAP_CONTENT
				);
				mPopWindow.setFocusable(true);
				mPopWindow.update();
				mPopWindow.setAnimationStyle(R.style.Animation);
				popView.setElevation(5.0f);
				
				// Get a reference for the custom view close button
				ImageButton closeButton = popView.findViewById(R.id.pop_close);
				
				// Set a click listener for the popup window close button
				closeButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View view) {
						// Dismiss the popup window
						mPopWindow.dismiss();
					}
				});

				// Finally, show the popup window at the center location of root relative layout
				mPopWindow.showAtLocation(mRelativeLayout, Gravity.CENTER, 0, 0);
				
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
			ErrorDialog.buildErrorDialog("This device doesn't support Camera2 API.").
					show(getFragmentManager(), "dialog");
			return false;
		}
		try {
			// Find a CameraDevice that supports RAW captures, and configure state.
			for (String cameraId : manager.getCameraIdList()) {
				CameraCharacteristics characteristics
						= manager.getCameraCharacteristics(cameraId);
				
				// We only use a camera that supports RAW in this sample.
				if (!contains(characteristics.get(
						CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
						CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
					continue;
				}
				
				StreamConfigurationMap map = characteristics.get(
						CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
				
				// For still image captures, we use the largest available size.
				Size largestJpeg = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
						new CompareSizesByArea());
				
				Size largestRaw = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
						new CompareSizesByArea());
				
				synchronized (mCameraStateLock) {
					// Set up ImageReaders for JPEG and RAW outputs.  Place these in a reference
					// counted wrapper to ensure they are only closed when all background tasks
					// using them are finished.
					if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
						mJpegImageReader = new RefCountedAutoCloseable<>(
								ImageReader.newInstance(largestJpeg.getWidth(),
										largestJpeg.getHeight(), ImageFormat.JPEG, /*maxImages*/5));
					}
					//					mJpegImageReader.get().setOnImageAvailableListener(
					//							mOnJpegImageAvailableListener, mBackgroundHandler);
					
					if (mRawImageReader == null || mRawImageReader.getAndRetain() == null) {
						mRawImageReader = new RefCountedAutoCloseable<>(
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
		ErrorDialog.buildErrorDialog("This device doesn't support capturing RAW photos").
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
			if (contains(mCharacteristics.get(
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
		if (contains(mCharacteristics.get(
				CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
				CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
			builder.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
		} else {
			builder.set(CaptureRequest.CONTROL_AE_MODE,
					CaptureRequest.CONTROL_AE_MODE_ON);
		}
		
		// If there is an auto-magical white balance control mode available, use it.
		if (contains(mCharacteristics.get(
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
			if (mState != STATE_OPENED) {
				return;
			}
			
			if (mRequestCounter.intValue() == 0) {
				//Check if user input number of photos for each setting.
				String mImgNeed = et.getText().toString().trim();
				if (!mImgNeed.isEmpty()) {
					int x = Integer.parseInt(et.getText().toString());
					imgNeed = x;
				}
				
				String miso = manualISO.getText().toString().trim();
				String mexp = manualExp.getText().toString().trim();
				String mexp2 = manualExp2.getText().toString().trim();
				if (!(miso.isEmpty() || mexp.isEmpty() || mexp2.isEmpty())) {
					manSet = true;
					current_ISO = Integer.parseInt(manualISO.getText().toString());
					long manExp = Long.parseLong(manualExp.getText().toString());
					long manExp2 = Long.parseLong(manualExp2.getText().toString());
					
					current_EXP = (manExp * 1000000000 / manExp2);
					Log.d(TAG, "Manual settings!\n" + "ISO: " + current_ISO + ", " + "EXP: " + Long.toString(current_EXP));
				}
			}
			
			// Update state machine to wait for auto-focus, auto-exposure, and auto-white-balance (aka. "3A") to converge.
			mState = STATE_STILLCAPTURING;
			
			if (mPendingUserCaptures > 0) {
				// Capture once for each user tap of the "Picture" button.
				while (mPendingUserCaptures > 0) {
					
					if (manSet) {
						manualSet();
					} else {
						nineDataSet();
					}
					
					//Set first progress bar value
					bar1.setProgress(mRequestCounter.intValue());
					//Set the second progress bar value
					bar1.setSecondaryProgress(mRequestCounter.intValue() + 1);
					
					mPendingUserCaptures--;
				}
				// After this, the camera will go back to the normal state.
				mState = STATE_OPENED;
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
	 * @param manual   true if manual setting
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
				// Use the same AE and AF modes as the preview.
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
			
			SurfaceTexture mDummyPreview = new SurfaceTexture(1);
			
			if (mRequestCounter.intValue() == 0 && mRequestCounter.intValue() == 1) {
				new Handler().postDelayed(null, 5000);
			} else {
			}
			
			mCameraDevice.createCaptureSession(Arrays.asList(new Surface(mDummyPreview), /*mJpegImageReader.get().getSurface(),*/
					mRawImageReader.get().getSurface()), new CameraCaptureSession.StateCallback() {
						@Override
						public void onConfigured(@NonNull CameraCaptureSession session) {
							synchronized (mCameraStateLock) {
								try {
									Log.d(TAG, "Capture Session onConfigured");
									session.capture(request, mCaptureCallback, mBackgroundHandler);
									
								} catch (CameraAccessException e) {
									Log.e(TAG, " exception occurred while accessing " + mCameraId, e);
									return;
								}
								mCaptureSession = session;
							}
							
						}
						
						@Override
						public void onConfigureFailed(@NonNull CameraCaptureSession session) {
							showToast("Failed to configure camera.");
						}
					}
					, mBackgroundHandler
			);
			
			//			mCaptureSession.capture(request, mCaptureCallback, mBackgroundHandler);
			
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
		if (mRequestCounter.intValue() < (imgNeed * 10 + 1)) {
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					takePicture();
				}
			}, 500);
		}
		if (mRequestCounter.intValue() >= (imgNeed * 10 + 1)) {
			detachProcessBar();
			AlertDialogFragment.buildAlertDialog("TASK FINISHED").show(getFragmentManager(), "dialog");
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
	                                 RefCountedAutoCloseable<ImageReader> reader) {
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
		private final RefCountedAutoCloseable<ImageReader> mReader;
		
		private ImageSaver(Image image, File file, CaptureResult result,
		                   CameraCharacteristics characteristics, Context context,
		                   RefCountedAutoCloseable<ImageReader> reader) {
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
			private RefCountedAutoCloseable<ImageReader> mReader;
			
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
					RefCountedAutoCloseable<ImageReader> reader) {
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
	 * Comparator based on area of the given {@link Size} objects.
	 */
	static class CompareSizesByArea implements Comparator<Size> {
		
		@Override
		public int compare(Size lhs, Size rhs) {
			// We cast here to ensure the multiplications won't overflow
			return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
					(long) rhs.getWidth() * rhs.getHeight());
		}
	}
	
	
	public static class AlertDialogFragment extends DialogFragment {
		
		private String mAlertMessage;
		
		public AlertDialogFragment() {
			mAlertMessage = "Unknown things occurred!";
		}
		
		// Build a dialog with a custom message (Fragments require default constructor).
		public static AlertDialogFragment buildAlertDialog(String message) {
			AlertDialogFragment dialog = new AlertDialogFragment();
			dialog.mAlertMessage = message;
			return dialog;
		}
		
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			// Use the Builder class for convenient dialog construction
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setMessage(mAlertMessage)
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							getActivity().finish();
							startActivity(getActivity().getIntent());
						}
					});
			//					.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
			//						public void onClick(DialogInterface dialog, int id) {
			//							// User cancelled the dialog
			//						}
			//					});
			// Create the AlertDialog object and return it
			return builder.create();
		}
	}
	
	
	/**
	 * A dialog fragment for displaying non-recoverable errors; this {@ling Activity} will be
	 * finished once the dialog has been acknowledged by the user.
	 */
	public static class ErrorDialog extends DialogFragment {
		
		private String mErrorMessage;
		
		public ErrorDialog() {
			mErrorMessage = "Unknown error occurred!";
		}
		
		// Build a dialog with a custom message (Fragments require default constructor).
		public static ErrorDialog buildErrorDialog(String errorMessage) {
			ErrorDialog dialog = new ErrorDialog();
			dialog.mErrorMessage = errorMessage;
			return dialog;
		}
		
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Activity activity = getActivity();
			return new AlertDialog.Builder(activity)
					.setMessage(mErrorMessage)
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
	 * A wrapper for an {@link AutoCloseable} object that implements reference counting to allow
	 * for resource management.
	 */
	public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
		private T mObject;
		private long mRefCount = 0;
		
		/**
		 * Wrap the given object.
		 *
		 * @param object an object to wrap.
		 */
		public RefCountedAutoCloseable(T object) {
			if (object == null)
				throw new NullPointerException();
			mObject = object;
		}
		
		/**
		 * Increment the reference count and return the wrapped object.
		 *
		 * @return the wrapped object, or null if the object has been released.
		 */
		public synchronized T getAndRetain() {
			if (mRefCount < 0) {
				return null;
			}
			mRefCount++;
			return mObject;
		}
		
		/**
		 * Return the wrapped object.
		 *
		 * @return the wrapped object, or null if the object has been released.
		 */
		public synchronized T get() {
			return mObject;
		}
		
		/**
		 * Decrement the reference count and release the wrapped object if there are no other
		 * users retaining this object.
		 */
		@Override
		public synchronized void close() {
			if (mRefCount >= 0) {
				mRefCount--;
				if (mRefCount < 0) {
					try {
						mObject.close();
					} catch (Exception e) {
						throw new RuntimeException(e);
					} finally {
						mObject = null;
					}
				}
			}
		}
	}
	
	
	/**
	 * Generate a string containing a formatted timestamp with the current date and time.
	 *
	 * @return a {@link String} representing a time.
	 */
	private static String generateTimestamp() {
		SimpleDateFormat sdf = new SimpleDateFormat("HH_mm_ss_SSS", Locale.US);
		return sdf.format(new Date());
	}
	
	
	/**
	 * Return true if the given array contains the given integer.
	 *
	 * @param arr array to check.
	 * @param j   integer to get for.
	 * @return true if the array contains the given integer, otherwise false.
	 */
	private static boolean contains(int[] arr, int j) {
		if (arr == null) {
			return false;
		}
		for (int i : arr) {
			if (i == j) {
				return true;
			}
		}
		return false;
	}
	
	
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
		AlertDialogFragment.buildAlertDialog("Refreshing total counter!").show(getFragmentManager(), "dialog");
		
	}
	
	private void nineDataSet() {
		
		if (mRequestCounter.intValue() == 0) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_50;
			
			bar1.setVisibility(View.VISIBLE);
			bar1.setMax(imgNeed * 10);
			bar2.setVisibility(View.VISIBLE);
			
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					captureStillPictureLocked(current_ISO, current_EXP, true);
				}
			}, 5000);
		}
		
		if (mRequestCounter.intValue() < (imgNeed + 1) && mRequestCounter.intValue() != 0) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_50;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed + 1) && mRequestCounter.intValue() < (imgNeed * 2 + 1)) {
			current_ISO = ISO_VALUE_200;
			current_EXP = EXPOSURE_TIME_1_50;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 2 + 1) && mRequestCounter.intValue() < (imgNeed * 3 + 1)) {
			current_ISO = ISO_VALUE_1000;
			current_EXP = EXPOSURE_TIME_1_50;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 3 + 1) && mRequestCounter.intValue() < (imgNeed * 4 + 1)) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_10;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 4 + 1) && mRequestCounter.intValue() < (imgNeed * 5 + 1)) {
			current_ISO = ISO_VALUE_200;
			current_EXP = EXPOSURE_TIME_1_10;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 5 + 1) && mRequestCounter.intValue() < (imgNeed * 6 + 1)) {
			current_ISO = ISO_VALUE_1000;
			current_EXP = EXPOSURE_TIME_1_10;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 6 + 1) && mRequestCounter.intValue() < (imgNeed * 7 + 1)) {
			current_ISO = ISO_VALUE_100;
			current_EXP = EXPOSURE_TIME_1_200;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 7 + 1) && mRequestCounter.intValue() < (imgNeed * 8 + 1)) {
			current_ISO = ISO_VALUE_200;
			current_EXP = EXPOSURE_TIME_1_200;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 8 + 1) && mRequestCounter.intValue() < (imgNeed * 9 + 1)) {
			current_ISO = ISO_VALUE_1000;
			current_EXP = EXPOSURE_TIME_1_200;
			captureStillPictureLocked(current_ISO, current_EXP, true);
		} else if (mRequestCounter.intValue() >= (imgNeed * 9 + 1) && mRequestCounter.intValue() < (imgNeed * 10 + 1)) {
			current_ISO = 0;
			current_EXP = 0;
			captureStillPictureLocked(0, 0, false);
		}
		
	}
	
	private void manualSet() {
		if (mRequestCounter.intValue() == 0) {
			bar1.setVisibility(View.VISIBLE);
			bar1.setMax(imgNeed);
			bar2.setVisibility(View.VISIBLE);
			
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					captureStillPictureLocked(current_ISO, current_EXP, true);
				}
			}, 5000);
		} else {
			captureStillPictureLocked(current_ISO, current_EXP, true);
		}
	}
}
