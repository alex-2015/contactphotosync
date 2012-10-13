/**
 * AssignContactPhotoActivity.java - Assign photos to contacts of a
 *                                   specific Google account.
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.MediaStore.Images.Media;
import android.support.v4.app.NavUtils;
import android.support.v4.app.TaskStackBuilder;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.oxplot.contactphotosync.StoreImageDialog.StoreImageParams;

public class AssignContactPhotoActivity extends Activity {

  private static final int REQ_CODE_PICK_IMAGE = 88;

  private static final String MY_CONTACTS_GROUP = "6";
  private static final String DISK_CACHE_DIR = "thumbcache";
  private static final String ACCOUNT_TYPE = "com.google";

  private Drawable unchangedThumb;
  private String account;
  private ListView contactList;
  private TextView emptyList;
  private ProgressBar loadingProgress;
  private LoadContactCursorTask contactCursorLoader;
  private SparseArray<Drawable> thumbMemCache;
  private Set<AsyncTask<?, ?, ?>> asyncTasks;
  private Drawable defaultThumb;
  private int pickedRawContact;
  private StoreImageDialog storeImageDialog;

  private int thumbSize;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    thumbSize = getResources().getInteger(R.integer.config_list_thumb_size);

    // Initialize cache directory

    new File(getCacheDir(), DISK_CACHE_DIR).mkdir();

    unchangedThumb = new BitmapDrawable(getResources(), Bitmap.createBitmap(1,
        1, Config.ALPHA_8));

    setContentView(R.layout.activity_assign_contact_photo);
    contactList = (ListView) findViewById(R.id.contactList);
    emptyList = (TextView) findViewById(R.id.empty);
    loadingProgress = (ProgressBar) findViewById(R.id.loading);

    contactList.setEmptyView(loadingProgress);
    contactList.setAdapter(new ContactAdapter());
    contactList.setDividerHeight(1);

    getActionBar().setDisplayHomeAsUpEnabled(true);
    account = getIntent().getStringExtra("account");
    setTitle(account);

    thumbMemCache = new SparseArray<Drawable>();
    asyncTasks = new HashSet<AsyncTask<?, ?, ?>>();
    defaultThumb = getResources().getDrawable(R.drawable.new_picture);

    storeImageDialog = new StoreImageDialog(this);
    storeImageDialog.setOnDismissListener(new OnDismissListener() {

      @Override
      public void onDismiss(DialogInterface dialog) {
        int status = storeImageDialog.getResult();
        switch (status) {
        case StoreImageDialog.RESULT_SUCCESS:
          removeDiskCache(pickedRawContact);
          thumbMemCache.remove(pickedRawContact);
          // Toast.makeText(AssignContactPhotoActivity.this, "Voila!",
          // Toast.LENGTH_LONG).show();
          break;
        case StoreImageDialog.RESULT_NO_ROOT:
          Toast.makeText(AssignContactPhotoActivity.this,
              getResources().getString(R.string.need_to_be_root),
              Toast.LENGTH_LONG).show();
          break;
        case StoreImageDialog.RESULT_IO_ERROR:
          Toast.makeText(AssignContactPhotoActivity.this,
              getResources().getString(R.string.something_went_wrong),
              Toast.LENGTH_LONG).show();
          break;
        default:
          Toast.makeText(AssignContactPhotoActivity.this,
              getResources().getString(R.string.saving_cancelled),
              Toast.LENGTH_LONG).show();
        }

      }
    });

    contactList.setOnItemClickListener(new OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> arg0, View view, int position,
          long id) {
        pickedRawContact = ((Contact) contactList.getItemAtPosition(position)).rawContactId;
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Picture"),
            REQ_CODE_PICK_IMAGE);

      }
    });

  }

  private void removeDiskCache(int id) {
    new File(new File(getCacheDir(), DISK_CACHE_DIR), "" + id).delete();
  }

  private void writeDiskCache(int id, Bitmap bitmap) {
    File file = new File(new File(getCacheDir(), DISK_CACHE_DIR), "" + id);
    FileOutputStream stream = null;
    try {
      stream = new FileOutputStream(file);
      bitmap.compress(CompressFormat.JPEG, 90, stream);
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (stream != null)
        try {
          stream.close();
        } catch (IOException e) {}
    }
  }

  private Bitmap readDiskCache(int id) {
    File file = new File(new File(getCacheDir(), DISK_CACHE_DIR), "" + id);
    FileInputStream stream = null;
    Bitmap result = null;
    try {
      stream = new FileInputStream(file);
      result = BitmapFactory.decodeStream(stream);
    } catch (IOException e) {} finally {
      if (stream != null)
        try {
          stream.close();
        } catch (IOException e) {}
    }
    return result;
  }

  protected void onActivityResult(int requestCode, int resultCode,
      Intent imageReturnedIntent) {
    super.onActivityResult(requestCode, resultCode, imageReturnedIntent);

    switch (requestCode) {
    case REQ_CODE_PICK_IMAGE:
      if (resultCode == RESULT_OK) {
        Uri selectedImage = imageReturnedIntent.getData();

        Cursor cursor = getContentResolver().query(selectedImage,
            new String[] { Media.MIME_TYPE, Media.ORIENTATION }, null, null,
            null);

        if (cursor == null) {
          Toast.makeText(this,
              getResources().getString(R.string.something_went_wrong),
              Toast.LENGTH_LONG).show();
          return;
        }

        StoreImageParams params = new StoreImageParams();

        try {
          cursor.moveToFirst();

          params.mimeType = cursor.getString(cursor
              .getColumnIndex(Media.MIME_TYPE));
          params.orientation = cursor.getInt(cursor
              .getColumnIndex(Media.ORIENTATION));
        } finally {
          cursor.close();
        }

        if (!params.mimeType.startsWith("image/")) {
          // XXX this is almost invisible in Holo theme (Holo.Light is fine)
          Toast.makeText(this,
              getResources().getString(R.string.only_image_allowed),
              Toast.LENGTH_LONG).show();
          return;
        }

        // Run a background job to load+crop+save the image

        params.rawContactId = pickedRawContact;
        params.uri = selectedImage;
        params.account = account;
        storeImageDialog.start(params);

      }
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.activity_assign_contact_photo, menu);
    return true;
  }

  @Override
  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    switch (item.getItemId()) {
    case android.R.id.home:
      Intent upIntent = new Intent(this, SelectAccountActivity.class);
      if (NavUtils.shouldUpRecreateTask(this, upIntent)) {
        TaskStackBuilder.create(this).addNextIntent(upIntent).startActivities();
      } else {
        NavUtils.navigateUpTo(this, upIntent);
      }
      return true;

    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onPause() {
    if (contactCursorLoader != null)
      contactCursorLoader.cancel(false);
    for (AsyncTask<?, ?, ?> lt : asyncTasks)
      lt.cancel(false);
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    if (contactCursorLoader != null)
      contactCursorLoader.cancel(false);
    contactCursorLoader = new LoadContactCursorTask();
    contactCursorLoader.execute(account);
  }

  private class LoadThumbTask extends AsyncTask<Integer, Void, Drawable> {

    private int rawContactId;

    @Override
    protected Drawable doInBackground(Integer... params) {
      AssetFileDescriptor fd = null;
      InputStream is = null;
      BitmapFactory.Options opts;

      rawContactId = params[0];
      BitmapDrawable result = null;
      Uri rawContactPhotoUri = Uri.withAppendedPath(
          ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
          RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
      try {

        // Get the bounds for later resampling

        fd = getContentResolver().openAssetFileDescriptor(rawContactPhotoUri,
            "r");
        is = fd.createInputStream();
        opts = new Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, opts);
        is.close();
        fd.close();

        opts.inSampleSize = opts.outHeight / thumbSize;
        opts.inSampleSize = opts.inSampleSize < 1 ? 1 : opts.inSampleSize;
        opts.inJustDecodeBounds = false;
        fd = getContentResolver().openAssetFileDescriptor(rawContactPhotoUri,
            "r");
        is = fd.createInputStream();

        Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
        if (bitmap == null)
          return unchangedThumb;

        result = new BitmapDrawable(getResources(), bitmap);

        return result;

      } catch (FileNotFoundException e) {
        return null;
      } catch (IOException e) {
        return null;
      } finally {
        if (is != null)
          try {
            is.close();
          } catch (IOException e) {}
        if (fd != null)
          try {
            fd.close();
          } catch (IOException e) {}
      }
    }

    @Override
    protected void onPostExecute(Drawable result) {
      super.onPostExecute(result);
      asyncTasks.remove(this);
      if (!isCancelled()) {
        if (result == null) {
          thumbMemCache.put(rawContactId, defaultThumb);
          removeDiskCache(rawContactId);
        } else if (result != unchangedThumb) {
          thumbMemCache.put(rawContactId, result);
          writeDiskCache(rawContactId, ((BitmapDrawable) result).getBitmap());
        }
        ((ContactAdapter) contactList.getAdapter()).notifyDataSetChanged();
      }
    }
  }

  private class LoadContactCursorTask extends
      AsyncTask<String, Void, List<Contact>> {

    @Override
    protected List<Contact> doInBackground(String... params) {
      Uri groupsUri = ContactsContract.Groups.CONTENT_URI.buildUpon()
          .appendQueryParameter(RawContacts.ACCOUNT_NAME, params[0])
          .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();

      Cursor cursor = getContentResolver().query(groupsUri, null, null, null,
          null);
      if (cursor == null)
        return null;

      int myContactGroupId = -1;

      try {
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {

          String sourceId = cursor.getString(cursor
              .getColumnIndex(ContactsContract.Groups.SOURCE_ID));
          if (MY_CONTACTS_GROUP.equals(sourceId)) {
            myContactGroupId = cursor.getInt(cursor
                .getColumnIndex(ContactsContract.Groups._ID));
            break;
          }
        }
      } finally {
        cursor.close();
      }

      if (myContactGroupId >= 0) {
        Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, params[0])
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build();
        cursor = getContentResolver().query(
            contactsUri,
            new String[] { GroupMembership.DISPLAY_NAME,
                GroupMembership.RAW_CONTACT_ID },
            GroupMembership.GROUP_ROW_ID + " = " + myContactGroupId, null,
            "lower(" + GroupMembership.DISPLAY_NAME + ")");

        if (cursor == null)
          return null;

        ArrayList<Contact> contacts = new ArrayList<Contact>();
        try {
          if (!cursor.moveToFirst())
            return contacts;
          do {
            Contact c = new Contact();
            c.rawContactId = cursor.getInt(cursor
                .getColumnIndex(GroupMembership.RAW_CONTACT_ID));
            c.displayName = cursor.getString(cursor
                .getColumnIndex(Data.DISPLAY_NAME));
            contacts.add(c);
          } while (cursor.moveToNext());
        } finally {
          cursor.close();
        }

        return contacts;
      } else {
        return null;
      }
    }

    @Override
    protected void onPostExecute(List<Contact> result) {
      super.onPostExecute(result);
      if (!isCancelled() && result != null) {
        ContactAdapter adapter = (ContactAdapter) contactList.getAdapter();
        adapter.refresh(result);
      }
      // FIXME we don't have to do this every time!
      loadingProgress.setVisibility(View.GONE);
      contactList.setEmptyView(emptyList);
    }

  }

  private static class Contact {
    public int rawContactId;
    public String displayName;
  }

  private class ContactAdapter extends BaseAdapter {

    private ArrayList<Contact> items = new ArrayList<Contact>();

    public void refresh(List<Contact> newList) {
      items.clear();
      items.addAll(newList);
      notifyDataSetChanged();
    }

    @Override
    public int getCount() {
      return items.size();
    }

    @Override
    public Object getItem(int position) {
      return items.get(position);
    }

    @Override
    public long getItemId(int position) {
      return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      Contact c = items.get(position);

      Drawable thumb = thumbMemCache.get(c.rawContactId);
      if (thumb == null) {
        asyncTasks.add((LoadThumbTask) new LoadThumbTask()
            .execute(c.rawContactId));
        Bitmap fromDisk = readDiskCache(c.rawContactId);
        if (fromDisk != null)
          thumbMemCache.put(c.rawContactId, new BitmapDrawable(getResources(),
              fromDisk));
        else
          thumbMemCache.put(c.rawContactId, defaultThumb);
      }
      thumb = thumbMemCache.get(c.rawContactId);

      View topView = convertView != null ? convertView : getLayoutInflater()
          .inflate(R.layout.contact_row, null);

      ((TextView) topView.findViewById(R.id.name)).setText(c.displayName);
      ((ImageView) topView.findViewById(R.id.photo))
          .setBackgroundDrawable(thumb);
      return topView;
    }

  }

}
