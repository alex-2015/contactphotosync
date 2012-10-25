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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.accounts.Account;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ProgressDialog;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.MediaStore.Images.Media;
import android.support.v4.app.NavUtils;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Provides a similar interface to android contacts app allowing user to assign
 * photos to contacts. The reason for re-implementing this UI is to work around
 * the android API which limits certain aspects of dimension and quality of the
 * saved photo. In our app with root access, we can save any image file we wish
 * as contact a photo.
 */
public class AssignContactPhotoActivity extends Activity {

  /**
   * Debugging tag
   */
  private static final String TAG = "AssignContactPhoto";

  /**
   * JPEG quality of thumbnails saved to app's disk cache.
   */
  private static final int THUMB_QUALITY = 90;

  /**
   * Request code for starting activity to pick an image file.
   */
  private static final int REQ_CODE_PICK_IMAGE = 88;

  /**
   * Request code for starting the crop activity.
   */
  private static final int REQ_CODE_CROP_IMAGE = 99;

  /**
   * Maximum number of thumbnails kept in memory cache above which the cache is
   * completely flushed.
   */
  private static final int THUMB_MEM_CACHE_LIMIT = 100;

  /**
   * Gmail group id for "My Contacts" group which contains all the contacts that
   * have been specifically added by the user as opposed to those that are
   * automatically added by Gmail.
   */
  private static final String MY_CONTACTS_GROUP = "6";

  /**
   * Directory name under application's cache directory for storing contact
   * thumbnails. The thumbnails are used to speed up the viewing of contact
   * photos once the app is first resumed.
   */
  private static final String DISK_CACHE_DIR = "thumbcache";

  /**
   * Google account type.
   */
  private static final String ACCOUNT_TYPE = "com.google";

  /**
   * Contact photo data authority. It is used here to enable sync for our app
   * through the UI.
   */
  private static final String CONTACT_PHOTO_AUTHORITY = "com.oxplot.contactphotos";

  /**
   * Contact data authority. It is used here to disable contact sync while the
   * UI is running to prevent contact sync which may be interrupted by the
   * repetitive killing of Contacts Provider.
   */
  private static final String CONTACTS_AUTHORITY = "com.android.contacts";

  /**
   * Placeholder used to indicate that thumbnail for a contact is not modified
   * and hence should not be updated on the UI.
   */
  private Drawable unchangedThumb;

  /**
   * Account name (aka email address) of the Google account for which the
   * contacts are shown.
   */
  private String account;

  /**
   * Main UI list element on the screen.
   */
  private ListView contactList;

  /**
   * A text message shown when there are no contacts for the current account.
   */
  private TextView emptyList;

  /**
   * Progress bar shown while contacts are loading.
   */
  private ProgressBar loadingProgress;

  /**
   * Task that loads list of contacts for the current account.
   */
  private LoadContactsTask contactsLoader;

  /**
   * In memory cache of thumbnails used to speed up rendering contact photos in
   * the list.
   */
  private SparseArray<Drawable> thumbMemCache;

  /**
   * Async tasks currently running (mostly thumbnails retrievers).
   */
  private Set<AsyncTask<?, ?, ?>> asyncTasks;

  /**
   * The default thumbnail for when a contact is lacking a photo.
   */
  private Drawable defaultThumb;

  /**
   * Raw contact ID of a contact for which a new photo is being picked. This is
   * set after a contact is selected and is used in StoreImageTask after
   * CropPhotoActivity has successfully finished.
   */
  private int pickedRawContact;

  /**
   * Saves the photo that has been cropped by CropPhotoActivity as contact
   * photo.
   */
  private StoreImageTask storeImageTask;

  /**
   * Indicates if Google contact sync was ticked in preferences prior to
   * resumption of this activity.
   */
  private boolean contactsSyncAuto;

  /**
   * Indicates if Google contact photo sync was ticked in preferences prior to
   * resumption of this activity.
   */
  private boolean contactPhotoSyncAuto;

  /**
   * Width and height of a thumbnail.
   */
  private int thumbSize;

  /**
   * Temporary location for saving cropped photos. This location is turned into
   * a URI and passed to CropPhotoActivity.
   */
  private File cropTemp;

  @Override
  public void onCreate(Bundle savedState) {
    super.onCreate(savedState);

    thumbSize = getResources().getInteger(R.integer.config_list_thumb_size);

    if (savedState != null) {
      String cropTempPath = savedState.getString("crop_temp");
      if (cropTempPath != null)
        cropTemp = new File(cropTempPath);
      pickedRawContact = savedState.getInt("picked_raw_contact", 0);
    }

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
    contactList
        .setMultiChoiceModeListener(new ContactListMultiChoiceModeListener());
    contactList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);

    getActionBar().setDisplayHomeAsUpEnabled(true);
    account = getIntent().getStringExtra("account");
    setTitle(account);

    thumbMemCache = new SparseArray<Drawable>();
    asyncTasks = new HashSet<AsyncTask<?, ?, ?>>();
    defaultThumb = getResources().getDrawable(R.drawable.new_picture);

    contactList.setOnItemClickListener(new OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> arg0, View view, int position,
          long id) {

        pickedRawContact = ((Contact) contactList.getItemAtPosition(position)).rawContactId;
        Intent intent = new Intent();

        // We only really accept PNG and JPEG but the activities registered for
        // the intended action only accept the generic form of the mime type. We
        // will check for our constraints after the image is picked.

        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(
            Intent.createChooser(intent,
                getResources().getString(R.string.select_picture)),
            REQ_CODE_PICK_IMAGE);

      }
    });

  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    if (cropTemp != null)
      outState.putString("crop_temp", cropTemp.getAbsolutePath());
    outState.putInt("picked_raw_contact", pickedRawContact);
  }

  /**
   * Stores the given {@link Drawable} for raw contact with <code>id</code> to
   * in-memory cache. If the cache is already full (ie
   * {@link THUMB_MEM_CACHE_LIMIT} number of elements or more), the cache is
   * flushed completely and <code>d</code> added.
   * 
   * @param id
   *          Raw contact Id
   * @param d
   *          Thumbnail to store
   */
  private void putToThumbMemCache(int id, Drawable d) {
    // XXX very naive way of doing this
    if (thumbMemCache.size() >= THUMB_MEM_CACHE_LIMIT)
      thumbMemCache.clear();
    thumbMemCache.put(id, d);
  }

  /**
   * Removes a thumbnail from disk cache.
   * 
   * @param id
   *          Raw contact Id
   */
  private void removeDiskCache(int id) {
    new File(new File(getCacheDir(), DISK_CACHE_DIR), "" + id).delete();
  }

  /**
   * Stores <code>bitmap</code> as thumbnail for the given contact with raw
   * contact <code>id</code> in app's disk cache.
   * 
   * @param id
   *          Raw contact id.
   * @param bitmap
   *          Thumbnail
   */
  private void writeDiskCache(int id, Bitmap bitmap) {
    File file = new File(new File(getCacheDir(), DISK_CACHE_DIR), "" + id);
    FileOutputStream stream = null;
    try {
      stream = new FileOutputStream(file);
      bitmap.compress(CompressFormat.JPEG, THUMB_QUALITY, stream);
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (stream != null)
        try {
          stream.close();
        } catch (IOException e) {}
    }
  }

  /**
   * Loads thumbnail from disk cache for the given contact.
   * 
   * @param id
   *          Raw contact Id
   * @return Thumbnail otherwise <code>null</code> if no thumbnail exists for
   *         the contact or if an error occurs while loading.
   */
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
    case REQ_CODE_CROP_IMAGE:

      // The photo is cropped. Kick off the store image task to save it to the
      // appropriate contact.

      if (resultCode == RESULT_OK) {
        storeImageTask = new StoreImageTask();
        storeImageTask.execute();
      } else {
        cropTemp.delete();
      }
      break;

    case REQ_CODE_PICK_IMAGE:

      if (resultCode != RESULT_OK)
        return;

      // The image is picked by the user. Query its MIME type.

      Uri selectedImage = imageReturnedIntent.getData();

      Cursor cursor = getContentResolver().query(selectedImage,
          new String[] { Media.MIME_TYPE }, null, null, null);

      if (cursor == null) {
        Toast.makeText(this,
            getResources().getString(R.string.something_went_wrong),
            Toast.LENGTH_LONG).show();
        return;
      }

      String mimeType = "";

      try {
        cursor.moveToFirst();
        mimeType = cursor.getString(cursor.getColumnIndex(Media.MIME_TYPE));
      } finally {
        cursor.close();
      }

      // Currently, we only accept PNG and JPEG images. Puke on anything else.

      if (!"image/jpeg".equals(mimeType) && !"image/png".equals(mimeType)) {
        Toast.makeText(this,
            getResources().getString(R.string.only_image_allowed),
            Toast.LENGTH_LONG).show();
        return;
      }

      // Create a temporary file to pass to CropPhotoActivity for saving the
      // cropped photo.

      try {
        cropTemp = File.createTempFile("croptemp-", "", getCacheDir());
      } catch (IOException e) {
        Toast.makeText(this,
            getResources().getString(R.string.something_went_wrong),
            Toast.LENGTH_LONG).show();
        return;
      }

      // Fire up the cropper.

      Intent intent = new Intent(this, CropPhotoActivity.class);
      intent.setData(selectedImage);
      intent.putExtra("wratio", 1.0f);
      intent.putExtra("hratio", 1.0f);
      intent.putExtra("maxwidth",
          getResources().getInteger(R.integer.config_max_photo_dim));
      intent.putExtra("maxheight",
          getResources().getInteger(R.integer.config_max_photo_dim));
      // intent.putExtra("quality", 0);
      intent.putExtra("out", Uri.fromFile(cropTemp));
      startActivityForResult(intent, REQ_CODE_CROP_IMAGE);

    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.activity_assign_contact_photo, menu);
    return true;
  }

  @SuppressWarnings("unchecked")
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

    case R.id.menu_sync_now:

      // This is a convenient way of enabling and running the contact photo
      // sync.

      Account a = new Account(account, ACCOUNT_TYPE);
      ContentResolver.setSyncAutomatically(a, CONTACT_PHOTO_AUTHORITY, true);
      ContentResolver.requestSync(a, CONTACT_PHOTO_AUTHORITY, new Bundle());
      Toast.makeText(this, getResources().getString(R.string.sync_requested),
          Toast.LENGTH_LONG).show();
      break;

    case R.id.menu_download_all:
    case R.id.menu_upload_all:
      new DownloadUploadTask(
          item.getItemId() == R.id.menu_download_all ? DownloadUploadTask.TYPE_DOWNLOAD
              : DownloadUploadTask.TYPE_UPLOAD)
          .execute(((ContactAdapter) contactList.getAdapter()).getBackingList());

      break;

    case R.id.menu_refresh:

      // XXX This is ugly and hackish. This of course doesn't stop us from being
      // lazy and using it here.

      onPause();
      onResume();
      break;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onDestroy() {
    if (storeImageTask != null)
      storeImageTask.cancel(true);
    super.onDestroy();
  }

  @Override
  protected void onPause() {

    // XXX experimentally, we're gonna disable contacts sync so it doesn't
    // interfere with our evil root plans - and here we restore it
    // Account a = new Account(account, ACCOUNT_TYPE);
    // ContentResolver.setSyncAutomatically(a, CONTACTS_AUTHORITY,
    // contactsSyncAuto);
    // ContentResolver.setSyncAutomatically(a, CONTACT_PHOTO_AUTHORITY,
    // contactPhotoSyncAuto);

    thumbMemCache.clear();
    if (contactsLoader != null)
      contactsLoader.cancel(false);
    for (AsyncTask<?, ?, ?> lt : asyncTasks)
      lt.cancel(false);
    asyncTasks.clear();
    super.onPause();
  }

  @Override
  protected void onResume() {

    super.onResume();
    contactsLoader = new LoadContactsTask();
    contactsLoader.execute(account);

    // XXX experimentally, we're gonna disable contacts sync so it doesn't
    // interfere with our evil root plans
    // Account a = new Account(account, ACCOUNT_TYPE);
    // contactsSyncAuto = ContentResolver.getSyncAutomatically(a,
    // CONTACTS_AUTHORITY);
    // contactPhotoSyncAuto = ContentResolver.getSyncAutomatically(a,
    // CONTACT_PHOTO_AUTHORITY);
    // ContentResolver.setSyncAutomatically(a, CONTACTS_AUTHORITY, false);
    // ContentResolver.setSyncAutomatically(a, CONTACT_PHOTO_AUTHORITY, false);
  }

  /**
   * Loads a single thumbnail on a background thread.
   */
  private class LoadThumbTask extends AsyncTask<Integer, Void, Drawable> {

    /**
     * Raw contact ID of the contact to load the thumbnail for.
     */
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
      asyncTasks.remove(this);
      if (result == null) {
        putToThumbMemCache(rawContactId, defaultThumb);
        removeDiskCache(rawContactId);
      } else if (result != unchangedThumb) {
        putToThumbMemCache(rawContactId, result);
        writeDiskCache(rawContactId, ((BitmapDrawable) result).getBitmap());
      }
      ((ContactAdapter) contactList.getAdapter()).notifyDataSetChanged();
    }
  }

  private class LoadContactsTask extends AsyncTask<String, Void, List<Contact>> {

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
            if (isCancelled())
              return null;
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
      if (result != null) {
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

    private final int selectedColor = Color.parseColor("#8833b5e5");
    private final int unselectedColor = Color.TRANSPARENT;

    private ArrayList<Contact> items = new ArrayList<Contact>();

    public List<Contact> getBackingList() {
      return Collections.unmodifiableList(items);
    }

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
      boolean checked = contactList.isItemChecked(position);
      Contact c = items.get(position);

      Drawable thumb = thumbMemCache.get(c.rawContactId);
      if (thumb == null) {
        asyncTasks.add((LoadThumbTask) new LoadThumbTask()
            .execute(c.rawContactId));
        Bitmap fromDisk = readDiskCache(c.rawContactId);
        if (fromDisk != null)
          putToThumbMemCache(c.rawContactId, new BitmapDrawable(getResources(),
              fromDisk));
        else
          putToThumbMemCache(c.rawContactId, defaultThumb);
      }
      thumb = thumbMemCache.get(c.rawContactId);

      View topView = convertView != null ? convertView : getLayoutInflater()
          .inflate(R.layout.contact_row, null);

      ((TextView) topView.findViewById(R.id.name)).setText(c.displayName);
      ((ImageView) topView.findViewById(R.id.photo))
          .setBackgroundDrawable(thumb);
      topView.setBackgroundColor(checked ? selectedColor : unselectedColor);
      return topView;
    }
  }

  private class DownloadUploadTask extends
      AsyncTask<List<Contact>, Void, Integer> {

    public static final int TYPE_DOWNLOAD = 0;
    public static final int TYPE_UPLOAD = 1;
    private static final int BATCH_SIZE = 50;
    private ProgressDialog dialog;
    private int type;

    public DownloadUploadTask(int type) {
      super();
      this.type = type;
      dialog = new ProgressDialog(AssignContactPhotoActivity.this);
      dialog.setTitle(getResources().getString(
          type == TYPE_DOWNLOAD ? R.string.queuing_for_download
              : R.string.queuing_for_upload));

      dialog.setIndeterminate(true);
      dialog.setCancelable(true);
      dialog.setOnCancelListener(new OnCancelListener() {
        @Override
        public void onCancel(DialogInterface dialog) {
          cancel(true);
        }
      });
      dialog.show();
    }

    @Override
    protected Integer doInBackground(List<Contact>... arg0) {
      List<Contact> contacts = arg0[0];
      ContentValues updateVals = new ContentValues();

      if (type == TYPE_DOWNLOAD)
        updateVals.put(RawContacts.SYNC4, SyncAdapter.OVERRIDE_TAG + "|");
      else if (type == TYPE_UPLOAD)
        updateVals.put(RawContacts.SYNC4, "|" + SyncAdapter.OVERRIDE_TAG);

      for (int i = 0; i < contacts.size(); i += BATCH_SIZE) {
        StringBuffer inVals = new StringBuffer();
        int maxIndex = Math.min(BATCH_SIZE + i, contacts.size()) - i;
        for (int j = 0; j < maxIndex; j++)
          inVals.append(contacts.get(i + j).rawContactId + ",");
        inVals.deleteCharAt(inVals.length() - 1);
        String selectionClause = RawContacts._ID + " IN (" + inVals + ")";
        getContentResolver().update(RawContacts.CONTENT_URI, updateVals,
            selectionClause, new String[] {});
        if (isCancelled())
          return 0;
      }

      return contacts.size();
    }

    @Override
    protected void onCancelled() {
      dialog.dismiss();
    }

    @Override
    protected void onPostExecute(Integer result) {
      dialog.dismiss();
      Toast
          .makeText(
              AssignContactPhotoActivity.this,
              String.format(
                  getResources().getString(
                      type == TYPE_DOWNLOAD ? R.string.queued_for_download
                          : R.string.queued_for_upload), result),
              Toast.LENGTH_LONG).show();
    }
  }

  private class RemovePhotoTask extends AsyncTask<List<Contact>, Void, Integer> {

    private static final int BATCH_SIZE = 50;
    private ProgressDialog dialog;
    private List<Contact> contacts;

    public RemovePhotoTask() {
      super();
      dialog = new ProgressDialog(AssignContactPhotoActivity.this);
      dialog.setTitle(getResources().getString(R.string.removing_photos));

      dialog.setIndeterminate(true);
      dialog.setCancelable(true);
      dialog.setOnCancelListener(new OnCancelListener() {
        @Override
        public void onCancel(DialogInterface dialog) {
          cancel(true);
        }
      });
      dialog.show();
    }

    @Override
    protected Integer doInBackground(List<Contact>... arg0) {
      contacts = arg0[0];

      for (int i = 0; i < contacts.size(); i += BATCH_SIZE) {
        StringBuffer inVals = new StringBuffer();
        int maxIndex = Math.min(BATCH_SIZE + i, contacts.size()) - i;
        for (int j = 0; j < maxIndex; j++)
          inVals.append(contacts.get(i + j).rawContactId + ",");
        inVals.deleteCharAt(inVals.length() - 1);
        String selectionClause = GroupMembership.RAW_CONTACT_ID + " IN ("
            + inVals + ")";

        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        ops.add(ContentProviderOperation
            .newUpdate(
                Data.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                        ACCOUNT_TYPE).build())
            .withSelection(selectionClause, null).withValue(Photo.PHOTO, null)
            .build());

        try {
          getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (RemoteException e) {} catch (OperationApplicationException e) {}

        if (isCancelled())
          return 0;
      }

      return contacts.size();
    }

    @Override
    protected void onCancelled() {
      dialog.dismiss();
    }

    @Override
    protected void onPostExecute(Integer result) {
      dialog.dismiss();
      Toast.makeText(
          AssignContactPhotoActivity.this,
          String.format(getResources().getString(R.string.removed_photos),
              result), Toast.LENGTH_LONG).show();
      for (Contact c : contacts)
        thumbMemCache.remove(c.rawContactId);
      ((ContactAdapter) contactList.getAdapter()).notifyDataSetChanged();
    }
  }

  private class ContactListMultiChoiceModeListener implements
      MultiChoiceModeListener {

    private HashSet<Integer> selectedPos;

    private List<Contact> getSelectedContacts() {
      ArrayList<Contact> selectedContacts = new ArrayList<Contact>();
      List<Contact> allContacts = ((ContactAdapter) contactList.getAdapter())
          .getBackingList();
      for (int p : selectedPos)
        selectedContacts.add(allContacts.get(p));
      return selectedContacts;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      switch (item.getItemId()) {

      case R.id.menu_upload_photo:
      case R.id.menu_download_photo:
        new DownloadUploadTask(
            item.getItemId() == R.id.menu_download_photo ? DownloadUploadTask.TYPE_DOWNLOAD
                : DownloadUploadTask.TYPE_UPLOAD)
            .execute(getSelectedContacts());
        break;

      case R.id.menu_remove_photo:
        new RemovePhotoTask().execute(getSelectedContacts());
        break;

      }
      mode.finish();
      return true;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      selectedPos = new HashSet<Integer>();
      mode.setSubtitle(R.string.selected);
      mode.getMenuInflater().inflate(R.menu.action_contact_list, menu);
      return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {

    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
      setTitle(mode);
      return true;
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position,
        long id, boolean checked) {
      if (checked)
        selectedPos.add(position);
      else
        selectedPos.remove(position);
      setTitle(mode);
    }

    private void setTitle(ActionMode mode) {
      int count = contactList.getCheckedItemCount();
      mode.setTitle(count
          + " "
          + getResources().getString(
              count > 1 ? R.string.contacts : R.string.contact));
      ((ContactAdapter) contactList.getAdapter()).notifyDataSetChanged();
    }

  }

  private class StoreImageTask extends AsyncTask<Void, Void, Integer> {

    private static final String PHOTO_DIR = "/files/photos";
    private static final String CONTACT_PROVIDER = "com.android.providers.contacts";
    private static final int WAIT_TIME_DB = 5000;
    private static final int WAIT_TIME_INT = 50;
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_NO_ROOT = 1;
    public static final int RESULT_IO_ERROR = 2;
    public static final int RESULT_CANCELLED = 3;

    private ProgressDialog dialog;

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      dialog = new ProgressDialog(AssignContactPhotoActivity.this);
      dialog.setIndeterminate(true);
      dialog.setMessage(getResources().getString(R.string.saving_in_progress));
      dialog.setCancelable(false);
      dialog.show();
    }

    @Override
    protected Integer doInBackground(Void... params) {

      byte[] buffer = new byte[4096];
      int bytesRead;
      AssetFileDescriptor fdout = null;
      InputStream is = null;
      OutputStream os = null;

      try {

        // Delete the current picture

        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        ops.add(ContentProviderOperation
            .newUpdate(
                Data.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
                    .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                        ACCOUNT_TYPE).build())
            .withSelection(
                GroupMembership.RAW_CONTACT_ID + " = " + pickedRawContact, null)
            .withValue(Photo.PHOTO, null).build());

        try {
          getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (RemoteException e1) {} catch (OperationApplicationException e1) {}

        // Store the image using android API as to update its database

        is = new FileInputStream(cropTemp);

        Uri rawContactPhotoUri = Uri.withAppendedPath(ContentUris
            .withAppendedId(RawContacts.CONTENT_URI, pickedRawContact),
            RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
        fdout = getContentResolver().openAssetFileDescriptor(
            rawContactPhotoUri, "w");
        os = fdout.createOutputStream();

        bytesRead = is.read(buffer);
        while (bytesRead >= 0) {
          os.write(buffer, 0, bytesRead);
          bytesRead = is.read(buffer);
          if (isCancelled())
            return RESULT_CANCELLED;
        }

        os.close();
        fdout.close();
        is.close();

        // Wait until its file ID is available

        int fileId = -1;
        Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build();

        int retryTime = 0;
        for (; retryTime < WAIT_TIME_DB; retryTime += WAIT_TIME_INT) {

          Cursor cursor = getContentResolver().query(
              contactsUri,
              new String[] { Photo.PHOTO_FILE_ID,
                  GroupMembership.RAW_CONTACT_ID },
              GroupMembership.RAW_CONTACT_ID + " = ?",
              new String[] { pickedRawContact + "" }, null);

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

          if (isCancelled())
            return RESULT_CANCELLED;
        }

        if (fileId < 0) {
          Log.e(TAG, "File ID didn't show up in db after saving");
          return RESULT_IO_ERROR;
        }

        // Wait until the actual file is available

        boolean fileAvailable = false;
        for (; retryTime < WAIT_TIME_DB; retryTime += WAIT_TIME_INT) {
          try {
            fdout = getContentResolver().openAssetFileDescriptor(
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

          if (isCancelled())
            return RESULT_CANCELLED;
        }

        if (!fileAvailable)
          return RESULT_IO_ERROR;

        // Atomically replace the image file

        if (!rootReplaceImage(cropTemp.getAbsolutePath(), fileId))
          return RESULT_NO_ROOT;

        return RESULT_SUCCESS;

      } catch (InterruptedException e) {
        return RESULT_IO_ERROR;
      } catch (IOException e) {
        e.printStackTrace();
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
        try {
          if (fdout != null)
            fdout.close();
        } catch (IOException e) {}
      }
    }

    private boolean rootReplaceImage(String src, int fileId)
        throws InterruptedException, IOException {

      int uid;
      String dstDir;
      try {
        PackageManager pm = getPackageManager();
        PackageInfo pi = pm.getPackageInfo(CONTACT_PROVIDER, 0);
        uid = pi.applicationInfo.uid;
        dstDir = pi.applicationInfo.dataDir + PHOTO_DIR;
      } catch (NameNotFoundException e) {
        return false;
      }

      // Find the PID of contact provider

      String killCommand = "";
      ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
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
      try {
        dialog.dismiss();
      } catch (Exception e) {}
      switch (result) {
      case RESULT_SUCCESS:
        break;
      case RESULT_NO_ROOT:
        Toast.makeText(AssignContactPhotoActivity.this,
            getResources().getString(R.string.need_to_be_root),
            Toast.LENGTH_LONG).show();
        break;
      case RESULT_IO_ERROR:
        Toast.makeText(AssignContactPhotoActivity.this,
            getResources().getString(R.string.something_went_wrong),
            Toast.LENGTH_LONG).show();
        break;
      }
      removeDiskCache(pickedRawContact);
      thumbMemCache.remove(pickedRawContact);
    }

    @Override
    protected void onCancelled() {
      try {
        dialog.cancel();
      } catch (Exception e) {}
      Toast.makeText(AssignContactPhotoActivity.this,
          getResources().getString(R.string.saving_cancelled),
          Toast.LENGTH_LONG).show();
      removeDiskCache(pickedRawContact);
      thumbMemCache.remove(pickedRawContact);
    }

  }

}
