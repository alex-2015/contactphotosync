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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.accounts.Account;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.OperationApplicationException;
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

import com.oxplot.contactphotosync.StoreImageDialog.StoreImageParams;

public class AssignContactPhotoActivity extends Activity {

  private static final int REQ_CODE_PICK_IMAGE = 88;

  private static final String MY_CONTACTS_GROUP = "6";
  private static final String DISK_CACHE_DIR = "thumbcache";
  private static final String ACCOUNT_TYPE = "com.google";
  private static final String CONTENT_AUTHORITY = "com.oxplot.contactphotos";

  private Drawable unchangedThumb;
  private String account;
  private ListView contactList;
  private TextView emptyList;
  private ProgressBar loadingProgress;
  private LoadContactsTask contactsLoader;
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
    contactList
        .setMultiChoiceModeListener(new ContactListMultiChoiceModeListener());
    contactList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);

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

        removeDiskCache(pickedRawContact);
        thumbMemCache.remove(pickedRawContact);

        switch (status) {
        case StoreImageDialog.RESULT_SUCCESS:
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
      Account a = new Account(account, ACCOUNT_TYPE);
      ContentResolver.setSyncAutomatically(a, CONTENT_AUTHORITY, true);
      ContentResolver.requestSync(a, CONTENT_AUTHORITY, new Bundle());
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
      onPause();
      onResume();
      break;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onPause() {
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
      asyncTasks.remove(this);
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
}
