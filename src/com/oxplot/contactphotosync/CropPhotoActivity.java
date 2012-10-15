package com.oxplot.contactphotosync;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

public class CropPhotoActivity extends Activity {

  private boolean loaded = false;
  private ProgressBar loadingBar;
  private CropView cropView;
  private PrepareTask preparer;
  private File inCachePath;
  private int orientation;

  @Override
  public void onCreate(Bundle savedState) {
    super.onCreate(savedState);
    setContentView(R.layout.activity_crop_photo);
    loadingBar = (ProgressBar) findViewById(R.id.loading);
    cropView = (CropView) findViewById(R.id.cropView);

    if (savedState != null && savedState.containsKey("loaded"))
      loaded = true;

    if (loaded) {
      RectF bound = new RectF();
      cropView.setEnforceRatio(savedState.getBoolean("enforce_ratio"));
      cropView.setCropWRatio(savedState.getFloat("wratio"));
      cropView.setCropHRatio(savedState.getFloat("hratio"));
      cropView.setThumbnail((Bitmap) savedState.getParcelable("thumbnail"));
      cropView.setOrgWidth(savedState.getInt("width"));
      cropView.setOrgHeight(savedState.getInt("height"));
      orientation = savedState.getInt("orientation");
      bound.left = savedState.getFloat("crop_left");
      bound.top = savedState.getFloat("crop_top");
      bound.right = savedState.getFloat("crop_right");
      bound.bottom = savedState.getFloat("crop_bottom");
      cropView.setCropBound(bound);
      inCachePath = new File(savedState.getString("cache_path"));
      initAfterLoad();
    } else {
      preparer = new PrepareTask();
      preparer.execute();
    }
  }

  @Override
  protected void onDestroy() {
    if (preparer != null)
      preparer.cancel(true);
    super.onDestroy();
  }

  private void goodbye(int result) {
    if (inCachePath != null)
      inCachePath.delete();
    setResult(result);
    finish();
  }

  private void initAfterLoad() {
    loadingBar.setVisibility(View.GONE);
    cropView.setVisibility(View.VISIBLE);
  }

  @Override
  public void onBackPressed() {
    goodbye(RESULT_CANCELED);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    if (!loaded)
      return;
    outState.putBoolean("loaded", true);
    outState.putParcelable("thumbnail", cropView.getThumbnail());
    outState.putInt("width", cropView.getOrgWidth());
    outState.putInt("height", cropView.getOrgHeight());
    outState.putInt("orientation", orientation);
    outState.putString("cache_path", inCachePath.getAbsolutePath());
    RectF bound = cropView.getCropBound();
    outState.putFloat("crop_left", bound.left);
    outState.putFloat("crop_right", bound.right);
    outState.putFloat("crop_top", bound.top);
    outState.putFloat("crop_bottom", bound.bottom);
    outState.putFloat("wratio", cropView.getCropWRatio());
    outState.putFloat("hratio", cropView.getCropHRatio());
    outState.putBoolean("enforce_ratio", cropView.isEnforceRatio());
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.activity_crop_photo, menu);
    return true;
  }

  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    switch (item.getItemId()) {
    case R.id.menu_crop:
      // TODO
      break;
    case R.id.menu_cancel_crop:
      goodbye(RESULT_CANCELED);
      break;
    }
    return true;
  }

  private class PrepareTask extends AsyncTask<Void, Void, Integer> {

    private static final int OK = 0;
    private static final int FAILED = 1;

    private Uri src;
    private Uri dst;
    private File inCachePath;
    private Bitmap thumbnail;
    private int orgWidth, orgHeight, orientation;

    public PrepareTask() {
      src = getIntent().getData();
      cropView.setVisibility(View.GONE);
      loadingBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected Integer doInBackground(Void... params) {
      AssetFileDescriptor fdin = null, fdout = null;
      InputStream is = null;
      OutputStream os = null;
      byte[] buffer = new byte[4096];
      int bytesRead;
      Bitmap interm = null;

      try {

        // Make a copy of the source content in local cache

        inCachePath = File.createTempFile("cropper-", "", getCacheDir());
        dst = Uri.fromFile(inCachePath);

        fdin = getContentResolver().openAssetFileDescriptor(src, "r");
        is = fdin.createInputStream();
        fdout = getContentResolver().openAssetFileDescriptor(dst, "w");
        os = fdout.createOutputStream();

        bytesRead = is.read(buffer);
        while (bytesRead >= 0) {
          if (isCancelled())
            return FAILED;
          os.write(buffer, 0, bytesRead);
          bytesRead = is.read(buffer);
        }

        is.close();
        os.close();
        fdin.close();
        fdout.close();

        // Retrieve primary information about the image

        Options opts = new Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(inCachePath.getAbsolutePath(), opts);
        if (opts.outWidth < 0 || opts.outHeight < 0)
          return FAILED;
        orgWidth = opts.outWidth;
        orgHeight = opts.outHeight;

        // For JPEG files, read the orientation details

        orientation = 0;
        if ("image/jpeg".equals(opts.outMimeType)) {
          ExifInterface exif = new ExifInterface(inCachePath.getAbsolutePath());
          switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
          case 3:
            orientation = 180;
            break;
          case 6:
            orientation = 90;
            break;
          case 8:
            orientation = 270;
            break;
          }
        }

        // Create a thumbnail for drawing on the screen

        Display display = getWindowManager().getDefaultDisplay();
        Point scr = new Point();
        display.getSize(scr);

        // Determine a suitable thumbnail dimension

        int orgMaxDim = Math.max(orgWidth, orgHeight);
        int orgMinDim = Math.min(orgWidth, orgHeight);
        int scrMaxDim = Math.max(scr.x, scr.y);
        int scrMinDim = Math.min(scr.x, scr.y);
        double scale = 1;
        if (orgMaxDim > scrMaxDim)
          scale = ((double) scrMaxDim) / orgMaxDim;
        if (scale * orgMinDim > scrMinDim)
          scale = ((double) scrMinDim) / orgMinDim;
        int thumbWidth = (int) Math.round(scale * orgWidth);
        int thumbHeight = (int) Math.round(scale * orgHeight);

        opts.inJustDecodeBounds = false;
        opts.inSampleSize = orgWidth / thumbWidth;
        interm = BitmapFactory.decodeFile(inCachePath.getAbsolutePath(), opts);
        if (isCancelled())
          return FAILED;
        Matrix m = new Matrix();
        m.postRotate(orientation);
        m.postScale(((float) thumbWidth) / opts.outWidth, ((float) thumbHeight)
            / opts.outHeight);
        thumbnail = Bitmap.createBitmap(interm, 0, 0, opts.outWidth,
            opts.outHeight, m, true);

        if (orientation == 90 || orientation == 270) {
          int t = orgWidth;
          orgWidth = orgHeight;
          orgHeight = t;
        }

        return OK;

      } catch (IOException e) {
        return FAILED;
      } finally {
        if (os != null)
          try {
            os.close();
          } catch (IOException e) {}
        if (is != null)
          try {
            is.close();
          } catch (IOException e) {}
        if (fdin != null)
          try {
            fdin.close();
          } catch (IOException e) {}
        if (fdout != null)
          try {
            fdout.close();
          } catch (IOException e) {}
      }
    }

    @Override
    protected void onCancelled() {
    }

    @Override
    protected void onPostExecute(Integer result) {
      if (result == OK) {
        cropView.setEnforceRatio(true);
        cropView.setCropHRatio(1);
        cropView.setCropWRatio(1);
        cropView.setThumbnail(thumbnail);
        CropPhotoActivity.this.orientation = orientation;
        cropView.setOrgWidth(orgWidth);
        cropView.setOrgHeight(orgHeight);
        CropPhotoActivity.this.inCachePath = inCachePath;
        cropView.setThumbnail(thumbnail);
        RectF bound = new RectF();
        bound.left = 0;
        bound.top = 0;
        bound.right = orgWidth;
        bound.bottom = orgHeight;
        cropView.setCropBound(bound);
        loaded = true;
        initAfterLoad();
      } else {
        Toast.makeText(CropPhotoActivity.this,
            getResources().getString(R.string.something_went_wrong),
            Toast.LENGTH_LONG).show();
        goodbye(RESULT_CANCELED);
      }
    }

  }
}
