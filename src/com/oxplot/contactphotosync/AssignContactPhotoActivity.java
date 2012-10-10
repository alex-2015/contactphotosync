package com.oxplot.contactphotosync;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.support.v4.app.NavUtils;
import android.support.v4.app.TaskStackBuilder;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class AssignContactPhotoActivity extends Activity {

  private static final String ACCOUNT_TYPE = "com.google";
  private static final int THUMB_SIZE = 80;
  private String account;
  private ListView contactList;
  private TextView emptyList;
  private LoadContactCursor contactCursorLoader;
  private Set<Integer> thumbQueried;
  private SparseArray<Drawable> thumbCache;
  private Set<LoadThumb> thumbLoaders;
  private Drawable defaultThumb;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_assign_contact_photo);
    contactList = (ListView) findViewById(R.id.contactList);
    emptyList = (TextView) findViewById(R.id.empty);

    contactList.setEmptyView(emptyList);
    contactList.setAdapter(new ContactAdapter());

    getActionBar().setDisplayHomeAsUpEnabled(true);
    account = getIntent().getStringExtra("account");
    setTitle(account);

    thumbCache = new SparseArray<Drawable>();
    thumbQueried = new HashSet<Integer>();
    thumbLoaders = new HashSet<LoadThumb>();
    defaultThumb = getResources().getDrawable(R.drawable.new_picture);

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
    for (LoadThumb lt : thumbLoaders)
      lt.cancel(false);
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    thumbQueried.clear();
    if (contactCursorLoader != null)
      contactCursorLoader.cancel(false);
    contactCursorLoader = new LoadContactCursor();
    contactCursorLoader.execute(account);
  }

  private class LoadThumb extends AsyncTask<Integer, Void, Drawable> {

    private int rawContactId;

    @Override
    protected Drawable doInBackground(Integer... params) {
      rawContactId = params[0];
      BitmapDrawable result = null;
      Uri rawContactPhotoUri = Uri.withAppendedPath(
          ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
          RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
      try {
        AssetFileDescriptor fd;
        InputStream is;
        BitmapFactory.Options opts;

        // Get the bounds for later resampling

        fd = getContentResolver().openAssetFileDescriptor(rawContactPhotoUri,
            "r");
        is = fd.createInputStream();
        opts = new Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, opts);
        is.close();
        fd.close();

        opts.inSampleSize = opts.outHeight / THUMB_SIZE;
        opts.inSampleSize = opts.inSampleSize < 1 ? 1 : opts.inSampleSize;
        opts.inJustDecodeBounds = false;
        fd = getContentResolver().openAssetFileDescriptor(rawContactPhotoUri,
            "r");
        is = fd.createInputStream();

        result = new BitmapDrawable(getResources(), BitmapFactory.decodeStream(
            is, null, opts));

        is.close();
        fd.close();
        return result;

      } catch (FileNotFoundException e) {
        return null;
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    }

    @Override
    protected void onPostExecute(Drawable result) {
      super.onPostExecute(result);
      thumbLoaders.remove(this);
      if (!isCancelled() && result != null) {
        thumbCache.put(rawContactId, result);
        ((ContactAdapter) contactList.getAdapter()).notifyDataSetChanged();
      }
    }
  }

  private class LoadContactCursor extends AsyncTask<String, Void, Cursor> {

    @Override
    protected Cursor doInBackground(String... params) {
      Uri groupsUri = ContactsContract.Groups.CONTENT_URI.buildUpon()
          .appendQueryParameter(RawContacts.ACCOUNT_NAME, params[0])
          .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
      Cursor cursor = getContentResolver().query(groupsUri, null, null, null,
          null);
      int myContactGroupId = -1;
      for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {

        String sourceId = cursor.getString(cursor
            .getColumnIndex(ContactsContract.Groups.SOURCE_ID));
        if (sourceId.equals("6")) {
          myContactGroupId = cursor.getInt(cursor
              .getColumnIndex(ContactsContract.Groups._ID));
          break;
        }
      }

      cursor.close();

      if (myContactGroupId >= 0) {
        Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, params[0])
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build();
        cursor = getContentResolver().query(
            contactsUri,
            new String[] { GroupMembership.DISPLAY_NAME,
                GroupMembership.RAW_CONTACT_ID, GroupMembership._ID },
            GroupMembership.GROUP_ROW_ID + " = " + myContactGroupId, null,
            GroupMembership.DISPLAY_NAME);

        return cursor;
      } else {
        return null;
      }
    }

    @Override
    protected void onPostExecute(Cursor result) {
      super.onPostExecute(result);
      if (!isCancelled() && result != null) {
        ContactAdapter adapter = (ContactAdapter) contactList.getAdapter();
        if (adapter.getCursor() != null)
          adapter.getCursor().close();
        adapter.setCursor(result);

      } else if (result != null) {
        result.close();
      }
    }

  }

  private class ContactAdapter extends BaseAdapter {

    private Cursor cursor;

    public Cursor getCursor() {
      return cursor;
    }

    public void setCursor(Cursor cursor) {
      this.cursor = cursor;
      notifyDataSetChanged();
    }

    @Override
    public int getCount() {
      if (cursor != null)
        return cursor.getCount();
      return 0;
    }

    @Override
    public Object getItem(int position) {
      if (cursor != null) {
        cursor.moveToPosition(position);
        return cursor.getInt(cursor.getColumnIndex(BaseColumns._ID));
      }
      return null;
    }

    @Override
    public long getItemId(int position) {
      return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      cursor.moveToPosition(position);
      int rawContactId = cursor.getInt(cursor
          .getColumnIndex(GroupMembership.RAW_CONTACT_ID));

      if (!thumbQueried.contains(rawContactId)) {
        thumbLoaders.add((LoadThumb) new LoadThumb().execute(rawContactId));
        thumbQueried.add(rawContactId);
      }
      Drawable thumb = thumbCache.get(rawContactId);

      View topView = convertView != null ? convertView : getLayoutInflater()
          .inflate(R.layout.contact_row, null);

      ((TextView) topView.findViewById(R.id.name)).setText(cursor
          .getString(cursor.getColumnIndex(Data.DISPLAY_NAME)));
      ((ImageView) topView.findViewById(R.id.photo))
          .setBackgroundDrawable(thumb == null ? defaultThumb : thumb);
      return topView;
    }
  }

}
