package com.stego.yangxiao.stegcam;

import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import java.io.File;

public class CameraActivity extends AppCompatActivity {
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_camera);
		
		File filel = new File(
				Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) +
						File.separator
						+ "StegoCam" + File.separator);
		if (!filel.exists()) {
			filel.mkdir();
		}
		
		if (null == savedInstanceState) {
			getFragmentManager().beginTransaction()
					.replace(R.id.container, StegCamFragment.newInstance())
					.commit();
		}
	}
}
