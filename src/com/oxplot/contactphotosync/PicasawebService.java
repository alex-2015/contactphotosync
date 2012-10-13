/**
 * PicasawebService.java - Simple Picasaweb API Library.
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.res.Resources.NotFoundException;

public class PicasawebService {

  public static final String PW_SERVICE_NAME = "lh2";

  public String authToken;

  private String albumXML;
  private String albumUpdateXML;
  private String photoXML;
  private String photoUpdateXML;

  private String encodeXML(String text) {
    Matcher m = Pattern.compile("[&\"'<>]").matcher(text);
    if (!m.find())
      return text;
    StringBuffer buffer = new StringBuffer(text.substring(0, m.start()));
    do {
      char c = m.group().charAt(0);
      String repl;
      switch (c) {
      case '&':
        repl = "amp";
        break;
      case '"':
        repl = "quot";
        break;
      case '\'':
        repl = "apos";
        break;
      case '<':
        repl = "lt";
        break;
      case '>':
        repl = "gt";
        break;
      default:
        repl = null;
      }
      m.appendReplacement(buffer, "&" + repl + ";");
    } while (m.find());
    m.appendTail(buffer);
    return buffer.toString();
  }

  private String decodeXML(String text) {
    Matcher m = Pattern.compile("&(amp|quot|apos|lt|gt);").matcher(text);
    if (!m.find())
      return text;
    StringBuffer buffer = new StringBuffer(text.substring(0, m.start()));
    do {
      String ent = m.group(1);
      String repl;
      if ("amp".equals(ent))
        repl = "&";
      else if ("quot".equals(ent))
        repl = "\"";
      else if ("apos".equals(ent))
        repl = "'";
      else if ("lt".equals(ent))
        repl = "<";
      else if ("gt".equals(ent))
        repl = ">";
      else
        repl = null;
      m.appendReplacement(buffer, repl);
    } while (m.find());
    m.appendTail(buffer);
    return buffer.toString();
  }

  private String extractRegex(String data, String pattern) {
    Matcher m = Pattern.compile(pattern, Pattern.DOTALL).matcher(data);
    return m.find() ? m.group(1) : "";
  }

  public PicasawebService(Context context) throws IOException {
    try {
      albumXML = inputStreamToString(context.getResources().openRawResource(
          R.raw.album));
      albumUpdateXML = inputStreamToString(context.getResources()
          .openRawResource(R.raw.album_update));
      photoXML = inputStreamToString(context.getResources().openRawResource(
          R.raw.photo));
      photoUpdateXML = inputStreamToString(context.getResources()
          .openRawResource(R.raw.photo_update));
    } catch (NotFoundException e) {}
  }

  private static String inputStreamToString(InputStream stream)
      throws IOException {
    StringBuffer sb = new StringBuffer();
    BufferedReader br = new BufferedReader(new InputStreamReader(stream,
        "UTF-8"));
    for (int c = br.read(); c != -1; c = br.read())
      sb.append((char) c);
    return sb.toString();
  }

  public PicasaAlbum createAlbum() {
    return new PicasaAlbum();
  }

  public PicasaAlbum getAlbum(String id) throws IOException,
      PicasaAuthException {
    String url = "https://picasaweb.google.com/data/entry/api/user/default/albumid/";
    ByteArrayOutputStream baOut = new ByteArrayOutputStream();
    int status = performPWCmd("GET", url, null, null, baOut);

    if (status == HttpURLConnection.HTTP_NOT_FOUND)
      return null;
    if (status != HttpURLConnection.HTTP_OK)
      throw new IOException("Got " + status
          + " HTTP status when retrieving album " + id + ".");

    Pattern entryPat = Pattern.compile("<entry>.+?</entry>", Pattern.DOTALL);
    Matcher m = entryPat.matcher(baOut.toString("UTF-8"));
    if (m.find())
      return new PicasaAlbum(m.group());

    return null;
  }

  public Collection<PicasaAlbum> listAlbums() throws IOException,
      PicasaAuthException {
    String url = "https://picasaweb.google.com/data/feed/api/user/default?max-results=1000000";
    ByteArrayOutputStream baOut = new ByteArrayOutputStream();
    int status = performPWCmd("GET", url, null, null, baOut);

    if (status != HttpURLConnection.HTTP_OK)
      throw new IOException("Got " + status
          + " HTTP status when retrieving list of albums.");

    ArrayList<PicasaAlbum> list = new ArrayList<PicasaAlbum>();

    Pattern entryPat = Pattern.compile("<entry>.+?</entry>", Pattern.DOTALL);
    Matcher m = entryPat.matcher(baOut.toString("UTF-8"));

    while (m.find())
      list.add(new PicasaAlbum(m.group()));

    return list;
  }

  private int performPWCmd(String method, String url,
      Map<String, String> headers, InputStream dataIn, OutputStream dataOut)
      throws IOException, PicasaAuthException {

    int bytesRead;
    byte[] buffer = new byte[4096];
    OutputStream outStream = null;
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod(method);
    conn.setRequestProperty("Authorization", "GoogleLogin auth=" + authToken);
    if (headers != null)
      for (String k : headers.keySet())
        conn.setRequestProperty(k, headers.get(k));

    // Send POST data

    if (dataIn != null) {
      conn.setDoOutput(true);
      outStream = conn.getOutputStream();
      bytesRead = dataIn.read(buffer);
      while (bytesRead >= 0) {
        outStream.write(buffer, 0, bytesRead);
        bytesRead = dataIn.read(buffer);
      }
      outStream.flush();
    }

    int responseCode = conn.getResponseCode();

    if (responseCode == HttpURLConnection.HTTP_FORBIDDEN)
      throw new PicasaAuthException();

    // Get response
    InputStream inStream = null;
    if (dataOut != null) {
      try {
        inStream = conn.getInputStream();
      } catch (FileNotFoundException e1) {
        inStream = conn.getErrorStream();
      }

      bytesRead = inStream.read(buffer);
      while (bytesRead >= 0) {
        dataOut.write(buffer, 0, bytesRead);
        bytesRead = inStream.read(buffer);
      }
    }

    if (outStream != null)
      outStream.close();
    if (inStream != null)
      inStream.close();

    return responseCode;
  }

  public class PicasaAlbum {
    private String id;
    private String editUrl;
    private String updated;
    public String title;
    public String summary;
    public String access;

    private PicasaAlbum(String data) {
      title = decodeXML(extractRegex(data, "<title type='text'>([^<]+)</title>"));
      summary = decodeXML(extractRegex(data,
          "<summary type='text'>([^<]+)</summary>"));
      access = decodeXML(extractRegex(data,
          "<gphoto:access>([^<]+)</gphoto:access>"));
      id = decodeXML(extractRegex(data, "<gphoto:id>([^<]+)</gphoto:id>"));
      updated = decodeXML(extractRegex(data, "<updated>([^<]+)</updated>"));
      editUrl = decodeXML(extractRegex(data,
          "<link rel='edit' type='application/atom[+]xml' href='([^']*)"));
    }

    private PicasaAlbum() {

    }

    public String getUpdated() {
      return updated;
    }

    public PicasaAlbum save() throws PicasaAuthException, IOException {
      ByteArrayInputStream newEntry = null;
      ByteArrayOutputStream baOut = new ByteArrayOutputStream();
      String url;

      try {
        if (id != null) {
          url = editUrl;
          newEntry = new ByteArrayInputStream(String.format(albumUpdateXML,
              encodeXML(id), encodeXML(title), encodeXML(summary),
              encodeXML(access), new Date().getTime()).getBytes("UTF-8"));

        } else {
          newEntry = new ByteArrayInputStream(String.format(albumXML,
              encodeXML(title), encodeXML(summary), encodeXML(access),
              new Date().getTime()).getBytes("UTF-8"));
          url = "https://picasaweb.google.com/data/feed/api/user/default";
        }
      } catch (UnsupportedEncodingException e) {
        e.printStackTrace();
        return null;
      }

      HashMap<String, String> headers = new HashMap<String, String>();
      headers.put("Content-Type", "application/atom+xml");
      int status = performPWCmd(id == null ? "POST" : "PUT", url, headers,
          newEntry, baOut);

      if (status != HttpURLConnection.HTTP_CREATED
          && status != HttpURLConnection.HTTP_OK)
        throw new IOException("Got " + status
            + " HTTP status when inserting/updating new album.");

      return new PicasaAlbum(baOut.toString("UTF-8"));
    }

    public PicasaPhoto createPhoto() {
      return new PicasaPhoto(this);
    }

    public PicasaPhoto getPhoto(String id) {
      return null;
    }

    public Collection<PicasaPhoto> listPhotos() throws IOException,
        PicasaAuthException {
      String url = "https://picasaweb.google.com/data/feed/api/user/default/albumid/"
          + id + "?imgmax=1600&max-results=1000000";
      ByteArrayOutputStream baOut = new ByteArrayOutputStream();
      int status = performPWCmd("GET", url, null, null, baOut);

      if (status != HttpURLConnection.HTTP_OK)
        throw new IOException("Got " + status
            + " HTTP status when retrieving list of photos for album " + id
            + ".");

      ArrayList<PicasaPhoto> list = new ArrayList<PicasaPhoto>();

      Pattern entryPat = Pattern.compile("<entry>.+?</entry>", Pattern.DOTALL);
      Matcher m = entryPat.matcher(baOut.toString("UTF-8"));
      while (m.find())
        list.add(new PicasaPhoto(this, m.group()));

      return list;
    }

    public String getId() {
      return id;
    }
  }

  public class PicasaPhoto {
    private String updated;
    private String id;
    private PicasaAlbum album;
    private InputStream photoStream;
    private String editUrl;
    private String editMediaUrl;
    private String photoUrl;
    public String title;
    public String summary;

    private PicasaPhoto(PicasaAlbum album, String data) {
      this.album = album;

      title = decodeXML(extractRegex(data, "<title type='text'>([^<]+)</title>"));
      summary = decodeXML(extractRegex(data,
          "<summary type='text'>([^<]+)</summary>"));
      updated = decodeXML(extractRegex(data, "<updated>([^<]+)</updated>"));
      id = decodeXML(extractRegex(data, "<gphoto:id>([^<]+)</gphoto:id>"));
      editUrl = decodeXML(extractRegex(data,
          "<link rel='edit' type='application/atom[+]xml' href='([^']*)"));
      editMediaUrl = decodeXML(extractRegex(data,
          "<link rel='edit-media' type='image/jpeg' href='([^']*)"));
      photoUrl = decodeXML(
          extractRegex(data, "<content type='image/jpeg' src='([^']*)"))
          .replace("/s1600/", "/s0/");
    }

    private PicasaPhoto(PicasaAlbum album) {
      this.album = album;
    }

    public String getUpdated() {
      return updated;
    }

    public String getId() {
      return id;
    }

    public PicasaAlbum getAlbum() {
      return album;
    }

    public PicasaPhoto save() throws PicasaAuthException, IOException {
      ByteArrayInputStream newEntry = null;
      ByteArrayOutputStream baOut = new ByteArrayOutputStream();

      String url;

      try {
        if (id != null) {
          url = photoStream == null ? editUrl : editMediaUrl;
          newEntry = new ByteArrayInputStream(String.format(photoUpdateXML,
              encodeXML(id), encodeXML(album.getId()), encodeXML(title),
              encodeXML(summary), new Date().getTime()).getBytes("UTF-8"));
        } else {
          newEntry = new ByteArrayInputStream(String.format(photoXML,
              encodeXML(title), encodeXML(summary)).getBytes("UTF-8"));
          url = "https://picasaweb.google.com/data/feed/api/user/default/albumid/"
              + album.getId();
        }
      } catch (UnsupportedEncodingException e) {
        e.printStackTrace();
        return null;
      }

      HashMap<String, String> headers = new HashMap<String, String>();
      InputStream baIn;
      if (photoStream == null) {
        headers.put("Content-Type", "application/atom+xml");
        baIn = newEntry;
      } else {
        headers.put("Content-Type",
            "multipart/related; boundary=\"END_OF_PART\"");
        headers.put("MIME-version", "1.0");

        ByteArrayInputStream bit1 = new ByteArrayInputStream(
            "Media multipart posting\r\n--END_OF_PART\r\nContent-Type: application/atom+xml\r\n\r\n"
                .getBytes("UTF-8"));
        ByteArrayInputStream bit2 = new ByteArrayInputStream(
            "\r\n--END_OF_PART\r\nContent-Type: image/jpeg\r\n\r\n"
                .getBytes("UTF-8"));
        ByteArrayInputStream bit3 = new ByteArrayInputStream(
            "\r\n--END_OF_PART--".getBytes("UTF-8"));

        baIn = new ConcatInputStreams(Arrays.asList(new InputStream[] { bit1,
            newEntry, bit2, photoStream, bit3 }));
      }
      int status = performPWCmd(id == null ? "POST" : "PUT", url, headers,
          baIn, baOut);

      if (status != HttpURLConnection.HTTP_CREATED
          && status != HttpURLConnection.HTTP_OK)
        throw new IOException("Got " + status
            + " HTTP status when inserting/updating photo.");

      return new PicasaPhoto(album, baOut.toString("UTF-8"));

    }

    public void downloadPhoto(OutputStream out) throws IOException,
        PicasaAuthException {
      int status = performPWCmd("GET", photoUrl, null, null, out);
      if (status == HttpURLConnection.HTTP_NOT_FOUND)
        throw new FileNotFoundException();
      if (status != HttpURLConnection.HTTP_OK)
        throw new IOException("Got " + status
            + " HTTP status when inserting/updating photo.");
    }

    public void setPhotoStream(InputStream photoStream) {
      this.photoStream = photoStream;
    }

    public boolean delete() throws IOException, PicasaAuthException {
      return performPWCmd("DELETE", editUrl, null, null, null) == HttpURLConnection.HTTP_OK;
    }
  }

  @SuppressWarnings("serial")
  public static class PicasaAuthException extends Exception {
  }

  @SuppressWarnings("serial")
  public static class PicasaDataException extends Exception {
  }

}
