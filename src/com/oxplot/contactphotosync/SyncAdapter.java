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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

  private static final String README_TITLE = "acps-readme.png";
  private static final String REMOTE_TITLE_PREFIX = "acps-";
  public static final String OVERRIDE_TAG = "@*@";
  private static final String MY_CONTACTS_GROUP = "6";
  private static final String PHOTO_DIR = "/files/photos";
  private static final String CONTACT_PROVIDER = "com.android.providers.contacts";
  private static final String TAG = "SyncAdapter";
  private static final String ACCOUNT_TYPE = "com.google";
  private static final int WAIT_TIME_DB = 5000;
  private static final int WAIT_TIME_INT = 50;

  private final int maxPhotoDim;
  private final String picasaReadmeText;

  public SyncAdapter(Context context, boolean autoInitialize) {
    super(context, autoInitialize);
    maxPhotoDim = context.getResources().getInteger(
        R.integer.config_max_photo_dim);
    picasaReadmeText = String.format(
        context.getResources().getString(R.string.picasaweb_readme),
        maxPhotoDim, maxPhotoDim);
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

  private static byte[] toMD5(InputStream stream) throws IOException {

    MessageDigest md5 = null;
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException(e);
    }

    byte[] buffer = new byte[4096];
    int bytesRead = stream.read(buffer);

    while (bytesRead >= 0) {
      md5.update(buffer, 0, bytesRead);
      bytesRead = stream.read(buffer);
    }

    return md5.digest();

  }

  private static byte[] toMD5(String string) throws IOException {
    try {
      return toMD5(new ByteArrayInputStream(string.getBytes("UTF-8")));
    } catch (UnsupportedEncodingException e) {
      throw new IOException(e);
    }
  }

  private static String sourceIdToFilename(String sourceId) {
    return REMOTE_TITLE_PREFIX + sourceId + ".jpg";
  }

  private static String sourceIdToOldStyleFilename(String sourceId) {
    return sourceId + ".jpg";
  }

  private static class Contact {
    public int rawContactId;
    public String displayName;
    public String localHash;
    public String remoteHash;
    public String sourceId;
  }

  private Collection<Contact> getLocalContacts(String account) {

    ArrayList<Contact> contacts = new ArrayList<Contact>();

    // Find the group ID of My Contacts group

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

    // Get the list of contacts in My Contacts group

    Uri contactsUri = ContactsContract.Data.CONTENT_URI.buildUpon()
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    cursor = getContext().getContentResolver().query(contactsUri,
        new String[] { GroupMembership.RAW_CONTACT_ID },
        GroupMembership.GROUP_ROW_ID + " = " + myContactGroupId, null, null);

    if (cursor == null)
      return null;

    HashSet<Integer> ids = new HashSet<Integer>();

    try {
      if (!cursor.moveToFirst())
        return contacts;
      do {
        ids.add(cursor.getInt(cursor
            .getColumnIndex(GroupMembership.RAW_CONTACT_ID)));
      } while (cursor.moveToNext());
    } finally {
      cursor.close();
    }

    // Get the rest of raw contact data

    Uri rawContactsUri = RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE).build();
    cursor = getContext().getContentResolver().query(
        rawContactsUri,
        new String[] { RawContacts._ID, RawContacts.SOURCE_ID,
            RawContacts.SYNC4, RawContacts.DISPLAY_NAME_PRIMARY }, null, null,
        null);
    if (cursor == null)
      return null;
    try {
      if (!cursor.moveToFirst())
        return contacts;
      do {
        int id = cursor.getInt(cursor.getColumnIndex(RawContacts._ID));
        if (ids.contains(id)) {
          Contact c = new Contact();
          c.rawContactId = id;
          c.sourceId = cursor.getString(cursor
              .getColumnIndex(RawContacts.SOURCE_ID));
          c.displayName = cursor.getString(cursor
              .getColumnIndex(RawContacts.DISPLAY_NAME_PRIMARY));
          String sync4 = cursor.getString(cursor
              .getColumnIndex(RawContacts.SYNC4));
          sync4 = sync4 == null ? "" : sync4;
          String[] sync4Parts = (sync4 + ":").split("[:]", -1);
          c.remoteHash = sync4Parts[0];
          c.localHash = sync4Parts[1];
          contacts.add(c);
        }
      } while (cursor.moveToNext());

    } finally {
      cursor.close();
    }

    return contacts;
  }

  @Override
  public void onPerformSync(Account account, Bundle extras, String authority,
      ContentProviderClient provider, SyncResult syncResult) {

    // Let's see if the user is willing to give us root permission at the very
    // start

    Util.runRoot("");

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
          tempPhotoPath);
    } catch (PicasaAuthException e) {
      System.err.println(e);
      syncResult.stats.numAuthExceptions++;
      return;
    } catch (IOException e) {
      System.err.println(e);
      syncResult.stats.numIoExceptions++;
      return;
    } catch (InterruptedException e) {
      Log.w(TAG, "Sync was interrupted by killing the thread");
    } finally {
      if (tempPhotoPath != null)
        tempPhotoPath.delete();
    }
  }

  private Hashtable<String, PicasaPhoto> retrieveServerEntriesFromCache(
      PicasaAlbum album, File path) throws IOException {
    DataInputStream is = null;
    Hashtable<String, PicasaPhoto> entries = new Hashtable<String, PicasaPhoto>();
    try {
      is = new DataInputStream(new FileInputStream(path));
      while (true) {
        PicasaPhoto p = album.deserializePhoto(is);
        if (p == null)
          break;
        entries.put(p.title, p);
      }
    } finally {
      if (is != null)
        try {
          is.close();
        } catch (IOException e) {}
    }
    return entries;
  }

  private void storeServerEntriesToCache(Collection<PicasaPhoto> photos,
      File path) throws IOException {
    File tmpPath = null;
    DataOutputStream os = null;
    try {
      tmpPath = File.createTempFile("storeentriestmp-", "", getContext()
          .getCacheDir());
      os = new DataOutputStream(new FileOutputStream(tmpPath));
      for (PicasaPhoto p : photos)
        p.serialize(os);
      os.close();
      tmpPath.renameTo(path);
    } finally {
      if (os != null)
        try {
          os.close();
        } catch (IOException e) {}
      if (tmpPath != null)
        tmpPath.delete();
    }
  }

  private Hashtable<String, PicasaPhoto> retrieveServerEntries(String account,
      PicasawebService pws, PicasaAlbum album) throws IOException,
      PicasaAuthException {
    Hashtable<String, PicasaPhoto> finalEntries;

    String accNameHash = toHex(toMD5(account));
    String tsHash = toHex(toMD5(album.getUpdated()));
    String baseName = "serverentries-" + accNameHash + "-";
    File cachePath = new File(getContext().getCacheDir(), baseName + tsHash);

    // XXX Unfortunately the timestamp on album entry doesn't change for trivial
    // edits to photos (e.g. edit of summary text) so we have to request a fresh
    // list for the time being.
    if (false && cachePath.exists()) {
      Log.d(TAG, "Loaded server entries from cache.");
      finalEntries = retrieveServerEntriesFromCache(album, cachePath);
    } else {
      Log.d(TAG, "Loading server entries fresh.");

      for (File f : getContext().getCacheDir().listFiles())
        if (f.getName().startsWith(baseName))
          f.delete();

      PicasaPhoto readmeEntry = null;
      finalEntries = new Hashtable<String, PicasaPhoto>();
      for (PicasaPhoto p : album.listPhotos()) {
        if ("image/png".equals(p.getMimeType()) && README_TITLE.equals(p.title)) {
          readmeEntry = p;
          continue;
        }
        if (!"image/jpeg".equals(p.getMimeType())
            || p.getWidth() != p.getHeight() || p.getWidth() > maxPhotoDim
            || p.getHeight() > maxPhotoDim) {
          Log.d(TAG, "Ignored " + p.title + " due to failing img req.");
          continue;
        }
        finalEntries.put(p.title, p);
      }

      if (readmeEntry == null) {
        readmeEntry = album.createPhoto();
        Log.d(TAG, "Readme photo is missing. Adding it.");
      }
      if (!picasaReadmeText.equals(readmeEntry.summary)) {
        Log.d(TAG, "Readme photo has wrong summary.");
        PicasaPhoto readmePhoto = album.createPhoto();
        readmePhoto.title = README_TITLE;
        readmePhoto.summary = picasaReadmeText;
        InputStream is = getContext().getResources().openRawResource(
            R.drawable.readme);
        readmePhoto.setPhotoStream(is);
        readmePhoto.save();
        is.close();
      }

      storeServerEntriesToCache(finalEntries.values(), cachePath);
    }
    return finalEntries;
  }

  private PicasaAlbum ensureAlbumExists(PicasawebService pws)
      throws IOException, PicasaAuthException {

    String albumName = getContext().getResources().getString(
        R.string.picasa_album_title);

    PicasaAlbum album = null;
    for (PicasaAlbum a : pws.listAlbums())
      if (albumName.equals(a.title)) {
        album = a;
        break;
      }

    if (album == null) {
      Log.d(TAG, "Album doesn't exist");
      album = pws.createAlbum();
    }
    if (picasaReadmeText.equals(album.summary))
      return album;
    Log.d(TAG, "Album doesn't have correct summary");

    album.access = "protected";
    album.title = albumName;
    album.summary = picasaReadmeText;

    return album.save();
  }

  private String makeCopyInCache(int rawContactId, File tempPhoto)
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
      os = new FileOutputStream(tempPhoto);

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
    updateVals.put(RawContacts.SYNC4, contact.remoteHash + ":"
        + contact.localHash);
    return getContext().getContentResolver().update(RawContacts.CONTENT_URI,
        updateVals, selectionClause, selectionArgs) > 0;
  }

  private PicasaPhoto getRemoteEntry(
      Hashtable<String, PicasaPhoto> serverEntries, Contact contact)
      throws PicasaAuthException, IOException {
    PicasaPhoto picked = serverEntries
        .get(sourceIdToFilename(contact.sourceId));
    if (picked != null)
      return picked;
    String oldStyleFilename = sourceIdToOldStyleFilename(contact.sourceId);
    picked = serverEntries.get(oldStyleFilename);
    if (picked != null) {
      Log.d(TAG, "Old style picture for " + contact.displayName + "("
          + contact.sourceId + ") found.");
      picked.title = sourceIdToFilename(contact.sourceId);
      picked = picked.save();
      serverEntries.put(picked.title, picked);
      return picked;
    }
    if (contact.displayName == null)
      return null;
    // XXX the worst way of doing this, need a hashtable or something
    String contactName = contact.displayName.trim().toLowerCase();
    for (PicasaPhoto p : serverEntries.values()) {
      if (p.summary.trim().toLowerCase().equals(contactName)) {
        serverEntries.remove(p.title);
        p.title = sourceIdToFilename(contact.sourceId);
        p = p.save();
        serverEntries.put(p.title, p);
        return p;
      }
    }
    return null;
  }

  private void performSyncAuthWrapped(Account account, String authority,
      SyncResult syncResult, String authToken, File tempPhoto)
      throws PicasaAuthException, IOException, InterruptedException {
    tempPhoto.delete();

    boolean useRootMethod = true;
    boolean localSaved = false;

    PicasawebService pws = new PicasawebService(getContext());
    pws.authToken = authToken;

    PicasaAlbum album = ensureAlbumExists(pws);
    Hashtable<String, PicasaPhoto> serverEntries = retrieveServerEntries(
        account.name, pws, album);

    Collection<Contact> localContacts = getLocalContacts(account.name);
    if (localContacts == null)
      throw new IOException("Failed to retrieve list of local contacts.");

    for (Contact contact : localContacts) {

      String localHash = makeCopyInCache(contact.rawContactId, tempPhoto);
      boolean localPhotoExists = localHash != null;
      localHash = localPhotoExists ? localHash : "";

      PicasaPhoto remotePhoto = getRemoteEntry(serverEntries, contact);
      boolean remotePhotoExists = remotePhoto != null;
      boolean skipEntry = false;

      FileInputStream fis = null;
      boolean metaUpdated = false;

      if (OVERRIDE_TAG.equals(contact.localHash)) {
        contact.remoteHash = remotePhotoExists ? remotePhoto.getUniqueId() : "";
        contact.localHash = "";
        metaUpdated = true;
      } else if (OVERRIDE_TAG.equals(contact.remoteHash)) {
        contact.localHash = localHash;
        contact.remoteHash = "";
        metaUpdated = true;
      }

      try {

        if (localPhotoExists && !contact.localHash.equals(localHash)) {
          Log.i(TAG, "Local -> Remote  for: " + contact.displayName);

          // Ensure the local file is valid by decoding it once and throwing
          // away the result.

          if (!tempPhoto.exists())
            makeCopyInCache(contact.rawContactId, tempPhoto);

          if (BitmapFactory.decodeFile(tempPhoto.getAbsolutePath()) == null) {
            Log.w(TAG, "Local photo for " + contact.displayName
                + " is corrupted, ignoring");
            continue;
          }

          if (!remotePhotoExists) {
            remotePhoto = album.createPhoto();
            remotePhoto.title = sourceIdToFilename(contact.sourceId);
            remotePhoto.summary = contact.displayName;
          }

          fis = new FileInputStream(tempPhoto);
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

          contact.remoteHash = remotePhoto.getUniqueId();
          contact.localHash = localHash;
          metaUpdated = true;

        } else if (remotePhotoExists
            && !remotePhoto.getUniqueId().equals(contact.remoteHash)) {
          Log.i(TAG, "Remote -> Local for: " + contact.displayName);

          useRootMethod = useRootMethod
              && updateLocalFromRemote(account.name, contact, remotePhoto,
                  useRootMethod);
          localSaved = true;
          metaUpdated = true;
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
        if (metaUpdated && !updateLocalMeta(contact))
          Log.e(TAG, "Couldn't update local meta for " + contact.displayName);
        tempPhoto.delete();
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
              Data.CONTENT_URI
                  .buildUpon()
                  .appendQueryParameter(RawContacts.ACCOUNT_NAME, account)
                  .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                  .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER,
                      "true").build())
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

      Uri rawContactPhotoUri = Uri
          .withAppendedPath(
              ContentUris.withAppendedId(RawContacts.CONTENT_URI,
                  contact.rawContactId),
              RawContacts.DisplayPhoto.CONTENT_DIRECTORY).buildUpon()
          .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
          .build();
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
      contact.remoteHash = remotePhoto.getUniqueId();

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
