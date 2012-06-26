/**
 * PhotoProvider.java - Stub provider impl.
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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

public class PhotoProvider extends ContentProvider {

  @Override
  public int delete(Uri arg0, String arg1, String[] arg2) {
    return 0;
  }

  @Override
  public String getType(Uri arg0) {
    return "android.cursor.item/vnd.com.oxplot.mimetype";
  }

  @Override
  public Uri insert(Uri arg0, ContentValues arg1) {
    return null;
  }

  @Override
  public boolean onCreate() {

    return true;
  }

  @Override
  public Cursor query(Uri arg0, String[] arg1, String arg2, String[] arg3,
      String arg4) {
    return null;
  }

  @Override
  public int update(Uri arg0, ContentValues arg1, String arg2, String[] arg3) {
    return 0;
  }

}
