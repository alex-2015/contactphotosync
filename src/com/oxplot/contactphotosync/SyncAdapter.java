/**
 * SyncAdapter.java - Main syncing code.
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import com.oxplot.contactphotosync.PicasawebService.PicasaAlbum;
import com.oxplot.contactphotosync.PicasawebService.PicasaAuthException;
import com.oxplot.contactphotosync.PicasawebService.PicasaPhoto;

public class SyncAdapter extends AbstractThreadedSyncAdapter {

  private static final String MY_CONTACTS_GROUP = "6";
  private static final String PHOTO_DIR = "/files/photos";
  private static final String CONTACT_PROVIDER = "com.android.providers.contacts";
  private static final String TAG = "SyncAdapter";
  private static final String ACCOUNT_TYPE = "com.google";
  private static final int WAIT_TIME_DB = 5000;
  private static final int WAIT_TIME_INT = 50;

  public SyncAdapter(Context context, boolean autoInitialize) {
    super(context, autoInitialize);
  }

  private static String toHex(byte[] arr) {
    String digits = "0123456789abcdef";
    StringBuilder sb = new StringBuilder(arr.length * 2);
    for (byte b : arr) {
      int bi = b & 0xff;
      sb.append(digits.charAt(bi >> 4));
      sb.append(digits.charAt(bi & 0xf));
    }
    return sb.toString();
  }

  private static class Contact {
    public int rawContactId;
    public String displayName;
    public String localHash;
    public String remoteHash;
    public String sourceId;
  }

  private boolean populateContactEntry(String account, Contact contact) {
    Uri contactsUri = RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    Cursor cursor = getContext().getContentResolver().query(
        contactsUri,
        new String[] { RawContacts.SOURCE_ID, RawContacts.SYNC4,
            RawContacts.DISPLAY_NAME_PRIMARY },
        RawContacts._ID + " = " + contact.rawContactId, null, null);
    if (cursor == null)
      return false;
    try {
      if (!cursor.moveToFirst())
        return false;
      contact.sourceId = cursor.getString(cursor
          .getColumnIndex(RawContacts.SOURCE_ID));
      contact.displayName = cursor.getString(cursor
          .getColumnIndex(RawContacts.DISPLAY_NAME_PRIMARY));
      String sync4 = cursor.getString(cursor.getColumnIndex(RawContacts.SYNC4));
      sync4 = sync4 == null ? "" : sync4;
      String[] sync4Parts = (sync4 + "|").split("[|]", -1);
      contact.remoteHash = sync4Parts[0];
      contact.localHash = sync4Parts[1];
      return true;
    } finally {
      cursor.close();
    }
  }

  private Collection<Contact> getLocalContacts(String account) {

    Uri groupsUri = ContactsContract.Groups.CONTENT_URI.buildUpon()
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    Cursor cursor = getContext().getContentResolver().query(groupsUri, null,
        null, null, null);
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

    if (myContactGroupId < 0)
      return null;

    Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    cursor = getContext().getContentResolver().query(contactsUri,
        new String[] { GroupMembership.RAW_CONTACT_ID },
        GroupMembership.GROUP_ROW_ID + " = " + myContactGroupId, null, null);

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
        if (!populateContactEntry(account, c))
          return null;
        contacts.add(c);
      } while (cursor.moveToNext());
    } finally {
      cursor.close();
    }

    return contacts;
  }

  @Override
  public void onPerformSync(Account account, Bundle extras, String authority,
      ContentProviderClient provider, SyncResult syncResult) {

    // Get the authentication token for the given account, invalidate it and get
    // another one as to avoid the headache of dealing with an expired token.

    String authToken;
    AccountManager manager = AccountManager.get(getContext());

    try {
      authToken = manager.blockingGetAuthToken(account,
          PicasawebService.PW_SERVICE_NAME, true);
      manager.invalidateAuthToken(ACCOUNT_TYPE, authToken);
      authToken = manager.blockingGetAuthToken(account,
          PicasawebService.PW_SERVICE_NAME, true);
    } catch (OperationCanceledException e) {
      syncResult.stats.numAuthExceptions++;
      return;
    } catch (AuthenticatorException e) {
      syncResult.stats.numAuthExceptions++;
      return;
    } catch (IOException e) {
      syncResult.stats.numIoExceptions++;
      return;
    }

    File tempPhotoPath = null;

    try {
      tempPhotoPath = File.createTempFile("syncltr", "", getContext()
          .getCacheDir());
      performSyncAuthWrapped(account, authority, syncResult, authToken,
          tempPhotoPath.getAbsolutePath());
    } catch (PicasaAuthException e) {
      syncResult.stats.numAuthExceptions++;
      return;
    } catch (IOException e) {
      syncResult.stats.numIoExceptions++;
      return;
    } catch (InterruptedException e) {
      Log.w(TAG, "Sync was interrupted by killing the thread");
    } finally {
      if (tempPhotoPath != null)
        tempPhotoPath.delete();
    }
  }

  private Hashtable<String, PicasaPhoto> retrieveServerEntries(
      PicasawebService pws, PicasaAlbum album) throws IOException,
      PicasaAuthException {
    Hashtable<String, PicasaPhoto> serverEntries = new Hashtable<String, PicasaPhoto>();
    for (PicasaPhoto p : album.listPhotos())
      serverEntries.put(p.title, p);
    return serverEntries;
  }

  private PicasaAlbum ensureAlbumExists(PicasawebService pws)
      throws IOException, PicasaAuthException {

    String albumName = getContext().getResources().getString(
        R.string.picasa_album_title);
    String albumSummary = getContext().getResources().getString(
        R.string.picasa_album_summary);

    for (PicasaAlbum a : pws.listAlbums())
      if (albumName.equals(a.title))
        return a;

    PicasaAlbum newAlbum = pws.createAlbum();
    newAlbum.access = "protected";
    newAlbum.title = albumName;
    newAlbum.summary = albumSummary;

    return newAlbum.save();
  }

  private String makeCopyInCache(int rawContactId, String tempPhotoPath)
      throws IOException {
    MessageDigest md5 = null;
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {}

    Uri rawContactPhotoUri = Uri.withAppendedPath(
        ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
        RawContacts.DisplayPhoto.CONTENT_DIRECTORY);

    AssetFileDescriptor fd;
    try {
      fd = getContext().getContentResolver().openAssetFileDescriptor(
          rawContactPhotoUri, "r");
    } catch (FileNotFoundException e) {
      return null;
    }

    InputStream is = null;
    OutputStream os = null;
    try {
      is = fd.createInputStream();
      os = new FileOutputStream(tempPhotoPath);

      byte[] buffer = new byte[4096];
      int bytesRead = is.read(buffer);
      while (bytesRead >= 0) {
        md5.update(buffer, 0, bytesRead);
        os.write(buffer, 0, bytesRead);
        bytesRead = is.read(buffer);
      }

    } finally {
      if (is != null)
        try {
          is.close();
        } catch (IOException e) {}
      if (os != null)
        try {
          os.close();
        } catch (IOException e) {}
    }

    return toHex(md5.digest());

  }

  private boolean updateLocalMeta(Contact contact) {
    ContentValues updateVals = new ContentValues();
    String selectionClause = RawContacts._ID + " = ?";
    String[] selectionArgs = new String[] { Long.toString(contact.rawContactId) };
    updateVals.put(RawContacts.SYNC4, contact.remoteHash + "|"
        + contact.localHash);
    return getContext().getContentResolver().update(RawContacts.CONTENT_URI,
        updateVals, selectionClause, selectionArgs) > 0;
  }

  private void performSyncAuthWrapped(Account account, String authority,
      SyncResult syncResult, String authToken, String tempPhotoPath)
      throws PicasaAuthException, IOException, InterruptedException {

    boolean useRootMethod = true;
    boolean localSaved = false;

    PicasawebService pws = new PicasawebService(getContext());
    pws.authToken = authToken;

    PicasaAlbum album = ensureAlbumExists(pws);
    Hashtable<String, PicasaPhoto> serverEntries = retrieveServerEntries(pws,
        album);

    Collection<Contact> localContacts = getLocalContacts(account.name);
    if (localContacts == null)
      throw new IOException("Failed to retrieve list of local contacts.");
    for (Contact contact : localContacts) {
      String localHash = makeCopyInCache(contact.rawContactId, tempPhotoPath);
      boolean localPhotoExists = localHash != null;
      localHash = localPhotoExists ? localHash : "";

      PicasaPhoto remotePhoto = serverEntries.get(contact.sourceId + ".jpg");
      boolean remotePhotoExists = remotePhoto != null;
      boolean skipEntry = false;

      FileInputStream fis = null;

      try {

        if (localPhotoExists && !contact.localHash.equals(localHash)) {
          Log.i(TAG, "Local -> Remote  for: " + contact.displayName);

          // Ensure the local file is valid by decoding it once and throwing
          // away
          // the result.

          if (BitmapFactory.decodeFile(tempPhotoPath) == null) {
            Log.w(TAG, "Local photo for " + contact.displayName
                + " is corrupted, ignoring");
            continue;
          }

          if (!remotePhotoExists) {
            remotePhoto = album.createPhoto();
            remotePhoto.title = contact.sourceId + ".jpg";
            remotePhoto.summary = contact.displayName;
          }

          fis = new FileInputStream(tempPhotoPath);
          remotePhoto.setPhotoStream(fis);
          remotePhoto = remotePhoto.save();
          fis.close();

          if (!remotePhotoExists) {
            Log.i(TAG, "Insert to remote: " + contact.displayName);
            syncResult.stats.numInserts++;
          } else {
            Log.i(TAG, "Updated remote: " + contact.displayName);
            syncResult.stats.numUpdates++;
          }

          contact.remoteHash = remotePhoto.getUpdated();
          contact.localHash = localHash;
          if (!updateLocalMeta(contact))
            Log.e(TAG, "Couldn't update local meta for " + contact.displayName);

        } else if (remotePhotoExists
            && !remotePhoto.getUpdated().equals(contact.remoteHash)) {
          Log.i(TAG, "Remote -> Local for: " + contact.displayName);

          useRootMethod = useRootMethod
              && updateLocalFromRemote(account.name, contact, remotePhoto,
                  useRootMethod);
          localSaved = true;
        }

      } catch (IOException e) {
        Log.e(TAG, "Skipping entry due to IOException: " + e.getMessage());
        syncResult.stats.numIoExceptions++;
        skipEntry = true;
      } finally {
        if (fis != null)
          try {
            fis.close();
          } catch (IOException e) {}
      }

      if (skipEntry)
        syncResult.stats.numSkippedEntries++;
    }

    if (useRootMethod && localSaved)
      killContactProvider();
  }

  private boolean updateLocalFromRemote(String account, Contact contact,
      PicasaPhoto remotePhoto, boolean useRootMethod) throws IOException,
      PicasaAuthException, InterruptedException {

    MessageDigest md5 = null;
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {}

    byte[] buffer = new byte[4096];
    int bytesRead;

    File tempRawRemote = File.createTempFile("rawremote", "", getContext()
        .getCacheDir());
    AssetFileDescriptor fd = null;
    FileOutputStream fos = null;
    FileInputStream fis = null;

    try {

      String rawPhotoHash;
      String savedPhotoHash = null;

      fos = new FileOutputStream(tempRawRemote);
      remotePhoto.downloadPhoto(fos);
      fos.close();

      // Calculate the hash of the downloaded photo

      fis = new FileInputStream(tempRawRemote);
      md5.reset();
      bytesRead = fis.read(buffer);
      while (bytesRead >= 0) {
        md5.update(buffer, 0, bytesRead);
        bytesRead = fis.read(buffer);
      }
      fis.close();
      rawPhotoHash = toHex(md5.digest());

      // Delete the current picture

      ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

      ops.add(ContentProviderOperation
          .newUpdate(
              Data.CONTENT_URI.buildUpon()
                  .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
                  .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                  .build())
          .withSelection(
              GroupMembership.RAW_CONTACT_ID + " = " + contact.rawContactId,
              null).withValue(Photo.PHOTO, null).build());

      try {
        getContext().getContentResolver().applyBatch(
            ContactsContract.AUTHORITY, ops);
      } catch (RemoteException e) {
        throw new IOException(e);
      } catch (OperationApplicationException e) {
        throw new IOException(e);
      }

      // Store the image using android API as to update its database

      fis = new FileInputStream(tempRawRemote);

      Uri rawContactPhotoUri = Uri.withAppendedPath(ContentUris.withAppendedId(
          RawContacts.CONTENT_URI, contact.rawContactId),
          RawContacts.DisplayPhoto.CONTENT_DIRECTORY);
      fd = getContext().getContentResolver().openAssetFileDescriptor(
          rawContactPhotoUri, "w");
      fos = fd.createOutputStream();

      bytesRead = fis.read(buffer);
      while (bytesRead >= 0) {
        fos.write(buffer, 0, bytesRead);
        bytesRead = fis.read(buffer);
      }

      fos.close();
      fd.close();
      fis.close();

      // Wait until its file ID is available

      int fileId = -1;
      Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
          .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
          .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();

      int retryTime = 0;
      for (; retryTime < WAIT_TIME_DB; retryTime += WAIT_TIME_INT) {

        Cursor cursor = getContext().getContentResolver()
            .query(
                contactsUri,
                new String[] { Photo.PHOTO_FILE_ID,
                    GroupMembership.RAW_CONTACT_ID },
                GroupMembership.RAW_CONTACT_ID + " = ?",
                new String[] { contact.rawContactId + "" }, null);

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
        throw new IOException("Couldn't get file ID of saved photo");

      // Wait until the actual file is available

      boolean fileAvailable = false;
      for (; retryTime < WAIT_TIME_DB; retryTime += WAIT_TIME_INT) {
        try {
          fd = getContext().getContentResolver().openAssetFileDescriptor(
              rawContactPhotoUri, "r");
          fis = fd.createInputStream();
          md5.reset();

          bytesRead = fis.read(buffer);
          while (bytesRead >= 0) {
            md5.update(buffer, 0, bytesRead);
            bytesRead = fis.read(buffer);
          }
          savedPhotoHash = toHex(md5.digest());

          fis.close();
          fd.close();
          fileAvailable = true;
          break;
        } catch (FileNotFoundException e) {} finally {
          try {
            fis.close();
          } catch (IOException e) {}
          try {
            fd.close();
          } catch (IOException e) {}
        }

        Thread.sleep(WAIT_TIME_INT);
      }

      if (!fileAvailable)
        throw new IOException("Couldn't get file content of saved photo");

      // Use the root method to replace the high quality photo

      boolean rootSuccess = false;
      if (useRootMethod) {
        if (rootReplaceImage(tempRawRemote.getAbsolutePath(), fileId))
          rootSuccess = true;
      }

      // Update local meta

      contact.localHash = rootSuccess ? rawPhotoHash : savedPhotoHash;
      contact.remoteHash = remotePhoto.getUpdated();
      updateLocalMeta(contact);

      return rootSuccess;

    } finally {
      if (fis != null)
        try {
          fis.close();
        } catch (IOException e) {}
      if (fos != null)
        try {
          fos.close();
        } catch (IOException e) {}
      if (fd != null)
        fd.close();
      tempRawRemote.delete();
    }

  }

  private boolean killContactProvider() {
    ActivityManager am = (ActivityManager) getContext().getSystemService(
        Context.ACTIVITY_SERVICE);
    for (RunningAppProcessInfo proc : am.getRunningAppProcesses())
      for (String p : proc.pkgList)
        if (CONTACT_PROVIDER.equals(p))
          if (Util.runRoot("kill " + proc.pid + "\n"))
            return true;
          else
            return false;
    return true;
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

    // Modify the permission of our tmp file and move it over to the correct
    // location + restart contact storage service

    if (!Util.runRoot("chown " + uid + ":" + uid + " " + src + "\nchmod 600 "
        + src + "\nmv " + src + " " + dstDir + "/" + fileId + "\n"))
      return false;

    return true;

  }
}
