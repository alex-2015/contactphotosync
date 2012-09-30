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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Hashtable;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.RawContacts;

import com.google.gdata.client.photos.PicasawebService;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.media.MediaStreamSource;
import com.google.gdata.data.photos.AlbumEntry;
import com.google.gdata.data.photos.AlbumFeed;
import com.google.gdata.data.photos.GphotoEntry;
import com.google.gdata.data.photos.PhotoEntry;
import com.google.gdata.data.photos.UserFeed;
import com.google.gdata.util.ServiceException;

public class SyncAdapter extends AbstractThreadedSyncAdapter {

  private static final String ACCOUNT_TYPE = "com.google";
  private static final int SAVE_WAIT_MAX = 2000;
  private static final int SAVE_WAIT_INT = 100;

  public SyncAdapter(Context context, boolean autoInitialize) {
    super(context, autoInitialize);

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
          PicasawebService.PWA_SERVICE, true);
      manager.invalidateAuthToken(ACCOUNT_TYPE, authToken);
      authToken = manager.blockingGetAuthToken(account,
          PicasawebService.PWA_SERVICE, true);
    } catch (OperationCanceledException e) {
      e.printStackTrace();
      syncResult.stats.numAuthExceptions++;
      return;
    } catch (AuthenticatorException e) {
      e.printStackTrace();
      syncResult.stats.numAuthExceptions++;
      return;
    } catch (IOException e) {
      e.printStackTrace();
      syncResult.stats.numIoExceptions++;
      return;
    }

    // Setup Picasa service access object

    PicasawebService picasaService = new PicasawebService(getContext()
        .getResources().getString(R.string.provider_name));
    picasaService.setUserToken(authToken);

    AlbumEntry photosAlbum = null;
    Hashtable<String, PhotoEntry> serverEntries = null;

    try {

      // Ensure an appropriately named album exists for the photos

      photosAlbum = ensureAlbumExists(picasaService);

      // Get the list of all existing photos in the album

      serverEntries = retrieveServerEntries(picasaService, photosAlbum);

    } catch (IOException e) {
      e.printStackTrace();
      syncResult.stats.numIoExceptions++;
      return;
    } catch (ServiceException e) {
      e.printStackTrace();
      syncResult.stats.numIoExceptions++;
      return;
    }

    Uri rawContactUri = RawContacts.CONTENT_URI.buildUpon()
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type).build();

    Cursor cursor = getContext().getContentResolver().query(rawContactUri,
        null, null, null, null);

    for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {

      syncResult.stats.numEntries++;

      // Skip the yet to be synced contacts (ie those with no SOURCE_ID).

      String sourceId = cursor.getString(cursor
          .getColumnIndex(RawContacts.SOURCE_ID));
      if (sourceId == null) {
        syncResult.stats.numSkippedEntries++;
        continue;
      }

      String name = cursor.getString(cursor
          .getColumnIndex((RawContacts.DISPLAY_NAME_PRIMARY)));
      long id = cursor.getLong(cursor.getColumnIndex(RawContacts._ID));
      String sync = cursor
          .getString(cursor.getColumnIndex((RawContacts.SYNC4)));
      sync = sync == null ? ":" : sync;
      String metaEtag = sync.substring(0, sync.indexOf(':'));
      String metaHash = sync.substring(sync.indexOf(':') + 1);
      metaHash = "".equals(metaHash) ? toHex(getMD5DigestForNull()) : metaHash;
      boolean localPhotoExists = true;

      Uri rawContactPhotoUri = Uri.withAppendedPath(
          ContentUris.withAppendedId(RawContacts.CONTENT_URI,
              cursor.getLong(cursor.getColumnIndex("_ID"))),
          RawContacts.DisplayPhoto.CONTENT_DIRECTORY);

      AssetFileDescriptor fd = null;
      try {
        fd = getContext().getContentResolver().openAssetFileDescriptor(
            rawContactPhotoUri, "r");
      } catch (FileNotFoundException e) {
        localPhotoExists = false;
      }

      // Calculate the MD5 digest of our local photo

      String md5Digest = toHex(getMD5DigestForNull());
      try {
        if (localPhotoExists) {
          InputStream photoDataStream = fd.createInputStream();
          md5Digest = toHex(getMD5DigestForStream(photoDataStream));
          fd.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
        syncResult.stats.numIoExceptions++;
        localPhotoExists = false;
      }

      PhotoEntry serverEntry = serverEntries.get(sourceId + ".jpg");
      boolean serverEntryExists = serverEntry != null;

      // Determine which way the sync will be.

      boolean skipEntry = false;

      try {

        if (!metaHash.equals(md5Digest)
            || (localPhotoExists && !serverEntryExists)) {

          // Phone -> Server sync
          System.out.println("Phone -> Server for: " + name);

          if (localPhotoExists) {
            System.out.println("Local photo exists:" + name);

            if (!serverEntryExists) {
              serverEntry = new PhotoEntry();
              serverEntry.setTitle(new PlainTextConstruct(sourceId + ".jpg"));
              serverEntry.setDescription(new PlainTextConstruct(name));
              serverEntry.setClient(getContext().getResources().getString(
                  R.string.provider_name));
            }

            fd = getContext().getContentResolver().openAssetFileDescriptor(
                rawContactPhotoUri, "r");
            InputStream photoDataStream = fd.createInputStream();
            MediaStreamSource mediaSource = new MediaStreamSource(
                photoDataStream, "image/jpeg");
            serverEntry.setMediaSource(mediaSource);

            if (!serverEntryExists) {
              System.out.println("Insert to server:" + name);

              java.net.URL feedUrl = new java.net.URL(
                  "https://picasaweb.google.com/data/feed/api/user/default/albumid/"
                      + photosAlbum.getGphotoId());
              serverEntry = picasaService.insert(feedUrl, serverEntry);
              syncResult.stats.numInserts++;

            } else {
              System.out.println("Update to server:" + name);

              picasaService.getRequestFactory().setHeader("If-Match",
                  serverEntry.getEtag());

              // XXX throws NPE every now and then, can't be f!@#ed figuring
              // why, so we just abort and try next time
              try {
                serverEntry = serverEntry.updateMedia(false);
              } catch (NullPointerException e) {
                e.printStackTrace();
                syncResult.stats.numIoExceptions++;
                return;
              }

              picasaService.setHeader("If-Match", null);
              syncResult.stats.numUpdates++;

            }

            photoDataStream.close();
            fd.close();

          } else {
            System.out.println("Local photo doesn't exist:" + name);

            if (serverEntryExists)
              serverEntry.delete();
            syncResult.stats.numDeletes++;
          }

          String serverEtag = serverEntry == null ? "" : serverEntry.getEtag();
          String newSync = serverEtag + ":" + md5Digest;
          ContentValues updateVals = new ContentValues();
          String selectionClause = RawContacts._ID + " = ?";
          String[] selectionArgs = new String[] { Long.toString(id) };
          updateVals.put(RawContacts.SYNC4, newSync);
          getContext().getContentResolver().update(RawContacts.CONTENT_URI,
              updateVals, selectionClause, selectionArgs);

        } else if (serverEntry != null
            && !serverEntry.getEtag().equals(metaEtag)
            && serverEntry.getMediaContents().size() > 0) {

          // Server -> Phone sync
          System.out.println("Server -> Phone for: " + name);

          String newMD5 = updateLocalPhotoFromServer(serverEntry,
              rawContactPhotoUri, md5Digest);

          String newSync = serverEntry.getEtag() + ":" + newMD5;
          ContentValues updateVals = new ContentValues();
          String selectionClause = RawContacts._ID + " = ?";
          String[] selectionArgs = new String[] { Long.toString(id) };
          updateVals.put(RawContacts.SYNC4, newSync);
          getContext().getContentResolver().update(RawContacts.CONTENT_URI,
              updateVals, selectionClause, selectionArgs);

          if (localPhotoExists)
            syncResult.stats.numUpdates++;
          else
            syncResult.stats.numInserts++;

        } else {
          syncResult.stats.numSkippedEntries++;
        }

      } catch (IOException e) {
        e.printStackTrace();
        syncResult.stats.numIoExceptions++;
        skipEntry = true;
      } catch (ServiceException e) {
        e.printStackTrace();
        syncResult.stats.numIoExceptions++;
        skipEntry = true;
      }

      if (skipEntry)
        syncResult.stats.numSkippedEntries++;

    } // Contacts for loop

    cursor.close();
  }

  /**
   * Updates local display photo from the copy on the server.
   * 
   * @param serverEntry
   *          PhotoEntry of the photo on Picasaweb.
   * @param rawContactPhotoUri
   *          Local contact photo URI.
   * @return MD5 digest of the copy on Picasaweb.
   * @throws IOException
   */
  private String updateLocalPhotoFromServer(PhotoEntry serverEntry,
      Uri rawContactPhotoUri, String existingLocalMD5) throws IOException {

    byte[] buffer = new byte[4096];

    java.net.URL photoUrl = new java.net.URL(serverEntry.getMediaContents()
        .get(0).getUrl());

    InputStream serverIS = photoUrl.openStream();
    AssetFileDescriptor fd = getContext().getContentResolver()
        .openAssetFileDescriptor(rawContactPhotoUri, "w");
    OutputStream photoOutStream = fd.createOutputStream();
    int bytesRead;
    while ((bytesRead = serverIS.read(buffer)) > 0)
      photoOutStream.write(buffer, 0, bytesRead);
    photoOutStream.close();
    fd.close();

    // Read the local saved file again (it might have been changed by the
    // provider) - try until you can read it
    // UNELSS the server entry is updated because of changes to its metadata
    // which means, the photo will be the same when saved. For now, just give up
    // after 2s.
    // TODO we need to save the checksum/timestamp and check against that
    // instead of Etag
    // TODO we also need to delete the local image first, before saving the new
    // one in case the image hasn't changed

    String md5Digest = null;

    for (int i = 0; i < SAVE_WAIT_MAX / SAVE_WAIT_INT; i++) {

      try {
        fd = getContext().getContentResolver().openAssetFileDescriptor(
            rawContactPhotoUri, "r");
        InputStream photoInStream = fd.createInputStream();
        md5Digest = toHex(getMD5DigestForStream(photoInStream));
        photoInStream.close();
        fd.close();
        if (existingLocalMD5.equals(md5Digest))
          System.out.println("Waiting for photo to be updated locally ...");
        else
          break;
      } catch (FileNotFoundException e) {
        System.err
            .println("Opening the photo after writing failed! - Retrying ...");
      }

      try {
        Thread.sleep(SAVE_WAIT_INT);
      } catch (InterruptedException e1) {
        // This should never happen
        e1.printStackTrace();
      }

    }

    return md5Digest == null ? existingLocalMD5 : md5Digest;

  }

  /**
   * Retrieves metadata for all the contact photos on Picasaweb.
   * 
   * @param picasaService
   *          Picasa service object.
   * @param photosAlbum
   *          Contact photos album entry.
   * @return Map between contact SOURCE_ID and its PhotoEntry on Picasaweb.
   * @throws IOException
   * @throws ServiceException
   */
  private Hashtable<String, PhotoEntry> retrieveServerEntries(
      PicasawebService picasaService, AlbumEntry photosAlbum)
      throws IOException, ServiceException {
    Hashtable<String, PhotoEntry> serverEntries = new Hashtable<String, PhotoEntry>();

    java.net.URL feedUrl = new java.net.URL(
        "https://picasaweb.google.com/data/feed/api/user/default/albumid/"
            + photosAlbum.getGphotoId());
    AlbumFeed feed = null;

    // XXX throws NPE every now and then, can't be f!@#ed figuring why, so we
    // just abort and try next time
    try {
      feed = picasaService.getFeed(feedUrl, AlbumFeed.class);
    } catch (NullPointerException e) {
      throw new IOException(e);
    }

    for (GphotoEntry<PhotoEntry> e : feed.getEntries()) {
      PhotoEntry photo = new PhotoEntry(e);
      serverEntries.put(photo.getTitle().getPlainText(), photo);
    }

    return serverEntries;
  }

  /**
   * Ensures an appropriately named album exists on Picasaweb to contain the
   * contact photos. If one doesn't exist, it creates one.
   * 
   * @param picasaService
   *          Picasa service object.
   * @return Album entry.
   * @throws IOException
   * @throws ServiceException
   */
  private AlbumEntry ensureAlbumExists(PicasawebService picasaService)
      throws IOException, ServiceException {

    String albumName = getContext().getResources().getString(
        R.string.picasa_album);

    try {
      java.net.URL feedUrl = new java.net.URL(
          "https://picasaweb.google.com/data/feed/api/user/default?kind=album");
      UserFeed myUserFeed = null;

      // XXX throws NPE every now and then, can't be f!@#ed figuring why, so we
      // just abort and try next time
      try {
        myUserFeed = picasaService.getFeed(feedUrl, UserFeed.class);
      } catch (NullPointerException e) {
        throw new IOException(e);
      }

      AlbumEntry photosAlbum = null;
      for (GphotoEntry<AlbumEntry> e : myUserFeed.getEntries()) {
        AlbumEntry album = new AlbumEntry(e);
        if (album.getTitle().getPlainText().equals(albumName)) {
          photosAlbum = album;
          break;
        }
      }

      if (photosAlbum == null) {
        photosAlbum = new AlbumEntry();
        photosAlbum.setAccess("protected");
        photosAlbum.setTitle(new PlainTextConstruct(albumName));
        java.net.URL postUrl = new java.net.URL(
            "https://picasaweb.google.com/data/feed/api/user/default");
        photosAlbum = picasaService.insert(postUrl, photosAlbum);
      }

      return photosAlbum;

    } catch (MalformedURLException e) {
      e.printStackTrace();
    }

    // This should never happen as all our URLs are perfect!

    return null;

  }

  /**
   * Calculates the MD5 digest for the data in the given stream.
   * 
   * @param stream
   *          Stream to calculate MD5 digest for.
   * @return
   * @throws IOException
   */
  private byte[] getMD5DigestForStream(InputStream stream) throws IOException {

    MessageDigest md5 = null;
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
    }

    byte[] buffer = new byte[4096];
    int bytesRead;

    while ((bytesRead = stream.read(buffer)) > 0)
      md5.update(buffer, 0, bytesRead);

    return md5.digest();

  }

  /**
   * Calculates the MD5 digest for empty string.
   * 
   * @return MD5 digest for empty string.
   */
  private byte[] getMD5DigestForNull() {
    MessageDigest md5 = null;
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
    }

    return md5.digest();
  }

  /**
   * Converts a byte array to string of hex codes, two digit for each byte.
   * 
   * @param arr
   *          Byte array.
   * @return
   */
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

}
