package com.stego.yangxiao.stegcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.util.Size;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * @author Yangxiao on 9/27/2017.
 */

public class HelperMethod {
	
	/**
	 * Return true if the given array contains the given integer.
	 *
	 * @param arr array to check.
	 * @param j   integer to get for.
	 * @return true if the array contains the given integer, otherwise false.
	 */
	protected static boolean contains(int[] arr, int j) {
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
	 * Generate file based on iso and exp settings
	 */
	protected static File generateFileName(String isoString, String expString, File dir, String
			mScenePath, String id) {
		File files[] = dir.listFiles();
		int num= dir.listFiles().length + 1;
		
		int numD = 4 - String.valueOf(num).length();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < numD; i++) {
			sb.append("0");
		}
		sb.append(num + "");
		
		File rawFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) +
				File.separator + "StegoCam" + File.separator + id + File.separator + mScenePath + File
				.separator +
				File.separator + "I" + isoString + "E" + expString +
				File.separator + "" + sb.toString() + ".dng");
		
		return rawFile;
	}
	
	
	/**
	 * Generate a string containing a formatted timestamp with the current date and time.
	 *
	 * @return a {@link String} representing a time.
	 */
	protected static String generateTimestamp() {
		SimpleDateFormat sdf = new SimpleDateFormat("HH_mm_ss_SSS", Locale.US);
		return sdf.format(new Date());
	}
	
	
	/**
	 * Generate a string containing a formatted timestamp with the current date and time.
	 *
	 * @return a {@link String} representing a time.
	 */
	protected static String generateTimestampWithMd() {
		SimpleDateFormat sdf = new SimpleDateFormat("MM_dd_HH_mm_ss_SSS", Locale.US);
		return sdf.format(new Date());
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
	
	
}