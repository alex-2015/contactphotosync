package com.oxplot.contactphotosync;

import java.io.File;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

public class CropPhotoActivity extends Activity {

  File thumbTemp;
  Bitmap thumb;

  @Override
  public void onCreate(Bundle savedState) {
    super.onCreate(savedState);
    setContentView(R.layout.activity_crop_photo);

    // Make a copy of the source file
    // Determine the orientation of the image using ExifInterface
    // Create a thumbnail for drawing on the screen
    // Setup CropView
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.activity_crop_photo, menu);
    return true;
  }

  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    switch (item.getItemId()) {
    case R.id.menu_crop:
      break;
    case R.id.menu_cancel_crop:
      setResult(RESULT_CANCELED);
      finish();
      break;
    }
    return true;
  }

}
