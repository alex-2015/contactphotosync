package com.oxplot.contactphotosync;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

public class CropPhotoActivity extends Activity {

  private static final String TAG = "CropPhoto";

  private boolean loaded = false;
  private ProgressBar loadingBar;
  private CropView cropView;
  private PrepareTask preparer;
  private CropTask cropper;
  private File inCachePath;
  private int orientation;
  private Uri outUri;
  private int jpegQuality;
  private int maxWidth;
  private int maxHeight;
  private String mimeType;
  private Menu menu;

  @Override
  public void onCreate(Bundle savedState) {
    super.onCreate(savedState);
    setContentView(R.layout.activity_crop_photo);
    loadingBar = (ProgressBar) findViewById(R.id.loading);
    cropView = (CropView) findViewById(R.id.cropView);

    if (savedState != null && savedState.containsKey("loaded"))
      loaded = true;

    if (loaded) {
      mimeType = savedState.getString("mimetype");
      RectF bound = new RectF();
      jpegQuality = savedState.getInt("quality");
      outUri = (Uri) savedState.getParcelable("out");
      maxWidth = savedState.getInt("max_width");
      maxHeight = savedState.getInt("max_height");
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
    if (cropper != null)
      cropper.cancel(true);
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
    outState.putInt("max_width", maxWidth);
    outState.putInt("max_height", maxHeight);
    outState.putInt("quality", jpegQuality);
    outState.putParcelable("out", outUri);
    outState.putString("mimetype", mimeType);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    this.menu = menu;

    getMenuInflater().inflate(R.menu.activity_crop_photo, menu);
    if (loaded)
      menu.findItem(R.id.menu_crop).setVisible(true);
    return true;
  }

  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    switch (item.getItemId()) {
    case R.id.menu_crop:
      cropper = new CropTask();
      cropper.execute();
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
    private String mimeType;

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
        mimeType = opts.outMimeType;
        if (!"image/jpeg".equals(mimeType) && !"image/png".equals(mimeType))
          return FAILED;

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
        CropPhotoActivity.this.mimeType = mimeType;
        Intent it = getIntent();
        maxWidth = it.getIntExtra("maxwidth",
            getResources().getInteger(R.integer.config_max_photo_dim));
        maxHeight = it.getIntExtra("maxheight",
            getResources().getInteger(R.integer.config_max_photo_dim));
        jpegQuality = it.getIntExtra("quality",
            getResources().getInteger(R.integer.config_default_jpeg_quality));
        outUri = (Uri) it.getParcelableExtra("out");
        cropView
            .setEnforceRatio(it.hasExtra("wratio") && it.hasExtra("hratio"));
        cropView.setCropWRatio(it.getFloatExtra("wratio", 1));
        cropView.setCropHRatio(it.getFloatExtra("hratio", 1));
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
        menu.findItem(R.id.menu_crop).setVisible(true);
      } else {
        Toast.makeText(CropPhotoActivity.this,
            getResources().getString(R.string.something_went_wrong),
            Toast.LENGTH_LONG).show();
        goodbye(RESULT_CANCELED);
      }
    }

  }

  private class CropTask extends AsyncTask<Void, Void, Integer> {

    private static final int REGION_SIZE = 256;
    private static final int OK = 0;
    private static final int FAILED = 1;
    private ProgressDialog dialog;
    private RectF bounds;
    private int orgOutWidth;
    private int orgOutHeight;

    @Override
    protected void onPreExecute() {
      dialog = new ProgressDialog(CropPhotoActivity.this);
      dialog.setIndeterminate(true);
      dialog.setMessage("Cropping in progress");
      dialog.setCancelable(false);
      dialog.show();
      RectF cropBounds = cropView.getCropBound();
      bounds = new RectF(cropBounds);
      // Rotate the dimensions and bound to match the original image

      switch (orientation) {
      case 90:
        bounds.left = cropBounds.top;
        bounds.top = cropView.getOrgWidth() - cropBounds.right;
        bounds.right = cropBounds.bottom;
        bounds.bottom = cropView.getOrgWidth() - cropBounds.left;
        break;
      case 180:
        bounds.left = cropView.getOrgWidth() - cropBounds.right;
        bounds.top = cropView.getOrgHeight() - cropBounds.bottom;
        bounds.right = cropView.getOrgWidth() - cropBounds.left;
        bounds.bottom = cropView.getOrgHeight() - cropBounds.top;
        break;
      case 270:
        bounds.left = cropView.getOrgHeight() - cropBounds.bottom;
        bounds.top = cropBounds.left;
        bounds.right = cropView.getOrgHeight() - cropBounds.top;
        bounds.bottom = cropBounds.right;
        break;
      }
      orgOutWidth = orientation % 180 == 0 ? cropView.getOrgWidth() : cropView
          .getOrgHeight();
      orgOutHeight = orientation % 180 == 0 ? cropView.getOrgHeight()
          : cropView.getOrgWidth();
    }

    @Override
    protected Integer doInBackground(Void... arg0) {

      Matrix m = new Matrix();

      float finalScale = 1;
      if (bounds.right - bounds.left > maxWidth)
        finalScale = (float) maxWidth / (bounds.right - bounds.left);
      if (finalScale * (bounds.bottom - bounds.top) > maxHeight)
        finalScale = (float) maxHeight / (bounds.bottom - bounds.top);
      int outImgW = (int) Math.round(finalScale
          * (orientation % 180 == 0 ? bounds.right - bounds.left
              : bounds.bottom - bounds.top));
      int outImgH = (int) Math.round(finalScale
          * (orientation % 180 == 0 ? bounds.bottom - bounds.top : bounds.right
              - bounds.left));

      int inImgW = (int) Math.round(bounds.right - bounds.left);
      int inImgH = (int) Math.round(bounds.bottom - bounds.top);
      int inImgX = (int) Math.round(bounds.left);
      int inImgY = (int) Math.round(bounds.top);

      m.postScale(finalScale, finalScale);
      switch (orientation) {
      case 270:
        m.postTranslate(-outImgH, 0);
        break;
      case 180:
        m.postTranslate(-outImgW, -outImgH);
        break;
      case 90:
        m.postTranslate(0, -outImgW);
        break;
      }
      m.postRotate(orientation);

      AssetFileDescriptor fdout = null, fdin = null;
      InputStream is = null;
      OutputStream os = null;

      try {
        // Decide if we need to actually do a crop

        if (orientation == 0 && "image/jpeg".equals(mimeType)
            && orgOutWidth == outImgW && orgOutHeight == outImgH) {
          fdout = getContentResolver().openAssetFileDescriptor(outUri, "w");
          is = new FileInputStream(inCachePath);
          os = fdout.createOutputStream();

          byte[] buffer = new byte[4096];
          int bytesRead = is.read(buffer);
          while (bytesRead >= 0) {
            os.write(buffer, 0, bytesRead);
            bytesRead = is.read(buffer);
          }

          is.close();
          os.close();
          fdout.close();

          Log.i(TAG, "Took a shortcut and didn't crop");

        } else {

          Options opts = new Options();
          opts.inPreferQualityOverSpeed = true;
          Rect regionBound = new Rect();
          BitmapRegionDecoder regionDecoder = BitmapRegionDecoder.newInstance(
              inCachePath.getAbsolutePath(), true);
          Bitmap outBitmap = Bitmap.createBitmap(outImgW, outImgH,
              Config.ARGB_8888);
          Canvas canvas = new Canvas(outBitmap);
          Paint paint = new Paint();
          paint.setFilterBitmap(true);
          canvas.concat(m);
          for (int x = 0; x < inImgW; x += REGION_SIZE) {
            for (int y = 0; y < inImgH; y += REGION_SIZE) {
              regionBound.left = inImgX + x;
              regionBound.top = inImgY + y;
              regionBound.right = inImgX + Math.min(x + REGION_SIZE, inImgW);
              regionBound.bottom = inImgY + Math.min(y + REGION_SIZE, inImgH);
              Bitmap region = regionDecoder.decodeRegion(regionBound, opts);
              canvas.drawBitmap(region, x, y, paint);
              if (isCancelled())
                return FAILED;
            }
          }
          fdout = getContentResolver().openAssetFileDescriptor(outUri, "w");
          os = fdout.createOutputStream();
          outBitmap.compress(CompressFormat.JPEG, jpegQuality, os);
          os.close();
          fdout.close();
        }

      } catch (IOException e) {
        e.printStackTrace();
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
        if (fdout != null)
          try {
            fdout.close();
          } catch (IOException e) {}
        if (fdin != null)
          try {
            fdin.close();
          } catch (IOException e) {}
      }

      return OK;
    }

    @Override
    protected void onCancelled() {
      try {
        dialog.cancel();
      } catch (Exception e) {}
    }

    @Override
    protected void onPostExecute(Integer result) {
      try {
        dialog.dismiss();
      } catch (Exception e) {}
      if (result == OK) {
        goodbye(RESULT_OK);
      } else {
        Toast.makeText(CropPhotoActivity.this,
            getResources().getString(R.string.something_went_wrong),
            Toast.LENGTH_LONG).show();
        goodbye(RESULT_CANCELED);
      }
    }
  }
}
