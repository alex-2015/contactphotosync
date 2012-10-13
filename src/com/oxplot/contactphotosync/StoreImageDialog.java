/**
 * StoreImageDialog.java - Stores picked photo to phone storage.
 * 
 * Copyright (C) 2012 Mansour <mansour@oxplot.com>
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.oxplot.contactphotosync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ProgressDialog;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;

public class StoreImageDialog extends ProgressDialog {

  public static final int RESULT_NA = -1;
  public static final int RESULT_SUCCESS = 0;
  public static final int RESULT_NO_ROOT = 1;
  public static final int RESULT_IO_ERROR = 2;
  public static final int RESULT_CANCELLED = 3;
  public static final int RESULT_IN_PROGRESS = 4;

  private static final String ACCOUNT_TYPE = "com.google";
  private static final String PHOTO_DIR = "/files/photos";
  private static final String CONTACT_PROVIDER = "com.android.providers.contacts";

  private static final int TILE_SIZE = 256;
  private static final int WAIT_TIME_DB = 5000;
  private static final int WAIT_TIME_INT = 50;

  private StoreImageTask task;
  private int finalResult = RESULT_NA;

  private final int maxPhotoDim;
  private final int defaultJPEGQuality;

  public StoreImageDialog(Context context) {
    super(context);
    maxPhotoDim = context.getResources().getInteger(
        R.integer.config_max_photo_dim);
    defaultJPEGQuality = context.getResources().getInteger(
        R.integer.config_default_jpeg_quality);
    setIndeterminate(true);
    setCancelable(true);
    setMessage(context.getResources().getString(R.string.saving_in_progress));
  }

  @Override
  protected void onStop() {
    if (finalResult == RESULT_IN_PROGRESS) {
      task.cancel(true);
      task = null;
      finalResult = RESULT_CANCELLED;
    }
    super.onStop();
  }

  public int getResult() {
    return finalResult;
  }

  public void start(StoreImageParams params) {
    if (task != null)
      task.cancel(true);
    task = (StoreImageTask) new StoreImageTask().execute(params);
    show();
    finalResult = RESULT_IN_PROGRESS;
  }

  private class StoreImageTask extends
      AsyncTask<StoreImageParams, Void, Integer> {

    @Override
    protected Integer doInBackground(StoreImageParams... params) {

      byte[] buffer = new byte[4096];
      int bytesRead;
      boolean needProcessing = false;
      AssetFileDescriptor fdin, fdout;
      InputStream is = null;
      OutputStream os = null;
      StoreImageParams p = params[0];
      Options opts = new Options();
      String tmpPath = new File(getContext().getCacheDir(), "imgtmp").getPath();
      new File(tmpPath).delete();

      opts.inPreferQualityOverSpeed = true;

      try {
        opts.inJustDecodeBounds = true;

        fdin = getContext().getContentResolver().openAssetFileDescriptor(p.uri,
            "r");
        is = fdin.createInputStream();
        BitmapFactory.decodeStream(is, null, opts);
        is.close();
        fdin.close();

        // Interruptable check point
        Thread.sleep(0);

        if (opts.outHeight < 0 || opts.outWidth < 0)
          return RESULT_IO_ERROR;

        // Do we need to process the image at all?

        if (opts.outHeight > maxPhotoDim || opts.outWidth > maxPhotoDim
            || opts.outHeight != opts.outWidth
            || !opts.outMimeType.equals("image/jpeg"))
          needProcessing = true;

        if (needProcessing) {

          // Read the image to a bitmap

          fdin = getContext().getContentResolver().openAssetFileDescriptor(
              p.uri, "r");
          is = fdin.createInputStream();
          BitmapRegionDecoder regionDecoder = BitmapRegionDecoder.newInstance(
              is, false);
          is.close();
          fdin.close();

          Thread.sleep(0);

          // Center crop the image

          int minDimen = Math.min(opts.outHeight, opts.outWidth);
          int finalDimen = Math.min(minDimen, maxPhotoDim);
          Bitmap cropped = Bitmap.createBitmap(finalDimen, finalDimen,
              Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(cropped);
          canvas.drawColor(Color.argb(255, 255, 255, 255));

          // Rotate the final draw based on orientation of the JPEG

          canvas.rotate(p.orientation);
          canvas.translate(
              p.orientation == 180 || p.orientation == 270 ? -minDimen : 0,
              p.orientation == 90 || p.orientation == 180 ? -minDimen : 0);
          canvas.scale(((float) finalDimen) / minDimen, ((float) finalDimen)
              / minDimen);
          Paint canvasPaint = new Paint();
          canvasPaint.setFilterBitmap(true);
          canvasPaint.setDither(false);

          int xOff = opts.outWidth / 2 - minDimen / 2;
          int yOff = opts.outHeight / 2 - minDimen / 2;
          for (int x = 0; x < minDimen; x += TILE_SIZE) {
            for (int y = 0; y < minDimen; y += TILE_SIZE) {
              int r = Math.min(TILE_SIZE + x + xOff, opts.outWidth);
              int b = Math.min(TILE_SIZE + y + yOff, opts.outHeight);
              Bitmap tile = regionDecoder.decodeRegion(new Rect(x + xOff, y
                  + yOff, r, b), null);
              canvas.drawBitmap(tile, null,
                  new RectF(x, y, r - xOff, b - yOff), canvasPaint);
              Thread.sleep(0);
            }
          }

          os = new FileOutputStream(tmpPath);
          cropped.compress(CompressFormat.JPEG, defaultJPEGQuality, os);
          os.close();

          cropped.recycle();
          cropped = null;

          // Interruptable check point
          Thread.sleep(0);

        } else { // Copy the image file verbatim to the tmp location

          fdin = getContext().getContentResolver().openAssetFileDescriptor(
              p.uri, "r");
          // TODO Getting a compiler hint that this leaks the previous stream.
          // Gotta check the code paths again.
          is = fdin.createInputStream();
          os = new FileOutputStream(tmpPath);

          bytesRead = is.read(buffer);
          while (bytesRead >= 0) {
            os.write(buffer, 0, bytesRead);
            bytesRead = is.read(buffer);
          }

          os.close();
          is.close();
          fdin.close();

        }

        // Delete the current picture

        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        ops.add(ContentProviderOperation
            .newUpdate(
                Data.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, p.account)
                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                        ACCOUNT_TYPE).build())
            .withSelection(
                GroupMembership.RAW_CONTACT_ID + " = " + p.rawContactId, null)
            .withValue(Photo.PHOTO, null).build());

        try {
          getContext().getContentResolver().applyBatch(
              ContactsContract.AUTHORITY, ops);
        } catch (RemoteException e1) {
          e1.printStackTrace();
        } catch (OperationApplicationException e1) {
          e1.printStackTrace();
        }

        // Store the image using android API as to update its database

        is = new FileInputStream(tmpPath);

        Uri rawContactPhotoUri = Uri
            .withAppendedPath(ContentUris.withAppendedId(
                RawContacts.CONTENT_URI, p.rawContactId),
                RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
        fdout = getContext().getContentResolver().openAssetFileDescriptor(
            rawContactPhotoUri, "w");
        os = fdout.createOutputStream();

        bytesRead = is.read(buffer);
        while (bytesRead >= 0) {
          os.write(buffer, 0, bytesRead);
          bytesRead = is.read(buffer);
        }

        os.close();
        fdout.close();
        is.close();

        // Wait until its file ID is available

        int fileId = -1;
        Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, p.account)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build();

        int retryTime = 0;
        for (; retryTime < WAIT_TIME_DB; retryTime += WAIT_TIME_INT) {

          Cursor cursor = getContext().getContentResolver().query(
              contactsUri,
              new String[] { Photo.PHOTO_FILE_ID,
                  GroupMembership.RAW_CONTACT_ID },
              GroupMembership.RAW_CONTACT_ID + " = ?",
              new String[] { p.rawContactId + "" }, null);

          try {
            if (cursor.moveToFirst()) {

              int colIndex = cursor.getColumnIndex(Photo.PHOTO_FILE_ID);
              if (!cursor.isNull(colIndex)) {
                fileId = cursor.getInt(colIndex);
                break;
              }
            }
          } finally {
            cursor.close();
          }

          Thread.sleep(WAIT_TIME_INT);
        }

        if (fileId < 0)
          return RESULT_IO_ERROR;

        // Wait until the actual file is available

        boolean fileAvailable = false;
        for (; retryTime < WAIT_TIME_DB; retryTime += WAIT_TIME_INT) {
          try {
            fdout = getContext().getContentResolver().openAssetFileDescriptor(
                rawContactPhotoUri, "r");
            is = fdout.createInputStream();
            is.close();
            fdout.close();
            fileAvailable = true;
            break;
          } catch (FileNotFoundException e) {} finally {
            try {
              is.close();
            } catch (IOException e) {}
            try {
              fdout.close();
            } catch (IOException e) {}
          }

          Thread.sleep(WAIT_TIME_INT);
        }

        if (!fileAvailable)
          return RESULT_IO_ERROR;

        // Atomically replace the image file

        if (!rootReplaceImage(tmpPath, fileId))
          return RESULT_NO_ROOT;

        return RESULT_SUCCESS;

      } catch (InterruptedException e) {
        return RESULT_IO_ERROR;
      } catch (IOException e) {
        return RESULT_IO_ERROR;
      } finally {
        try {
          if (is != null)
            is.close();
        } catch (IOException e) {}
        try {
          if (os != null)
            os.close();
        } catch (IOException e) {}
      }
    }

    private boolean rootReplaceImage(String src, int fileId)
        throws InterruptedException, IOException {

      int uid;
      String dstDir;
      try {
        PackageManager pm = getContext().getPackageManager();
        PackageInfo pi = pm.getPackageInfo(CONTACT_PROVIDER, 0);
        uid = pi.applicationInfo.uid;
        dstDir = pi.applicationInfo.dataDir + PHOTO_DIR;
      } catch (NameNotFoundException e) {
        return false;
      }

      // Find the PID of contact provider

      String killCommand = "";
      ActivityManager am = (ActivityManager) getContext().getSystemService(
          Context.ACTIVITY_SERVICE);
      for (RunningAppProcessInfo proc : am.getRunningAppProcesses())
        for (String p : proc.pkgList)
          if (CONTACT_PROVIDER.equals(p)) {
            killCommand = "kill " + proc.pid + "\n";
            break;
          }

      // Modify the permission of our tmp file and move it over to the correct
      // location + restart contact storage service

      if (!Util.runRoot("chown " + uid + ":" + uid + " " + src + "\nchmod 600 "
          + src + "\nmv " + src + " " + dstDir + "/" + fileId + "\n"
          + killCommand))
        return false;

      return true;

    }

    @Override
    protected void onPostExecute(Integer result) {
      super.onPostExecute(result);
      if (!isCancelled()) {
        finalResult = result;
      } else {
        finalResult = RESULT_CANCELLED;
      }
      dismiss();
    }

  }

  public static class StoreImageParams {
    public int rawContactId;
    public Uri uri;
    public int orientation;
    public String account;
    public String mimeType;
  }
}
