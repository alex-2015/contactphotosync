/**
 * SyncService.java - Sync service.
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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;

public class SyncService extends Service {

  private static final String ACCOUNT_TYPE = "com.google";
  private static final Object sSyncAdapterLock = new Object();
  private static SyncAdapter sSyncAdapter = null;
  private static final String PREF_FILE = "default";
  private static final String FIRST_RUN_PREF_KEY = "first-run";
  private static final String PHOTO_PROVIDER = "com.oxplot.contactphotos";
  private static long syncFreq = 8 * 60 * 60; // Every 8 hours

  /*
   * {@inheritDoc}
   */
  @Override
  public void onCreate() {

    synchronized (sSyncAdapterLock) {

      // For the first run, add our sync option to all existing Google accounts.

      SharedPreferences prefs = getSharedPreferences(PREF_FILE,
          Context.MODE_PRIVATE);

      // Turn on our sync for all existing accounts

      AccountManager manager = AccountManager.get(getApplicationContext());
      for (Account account : manager.getAccountsByType(ACCOUNT_TYPE)) {
        ContentResolver.setIsSyncable(account, PHOTO_PROVIDER, 1);
        ContentResolver.addPeriodicSync(account, PHOTO_PROVIDER, new Bundle(),
            syncFreq);
      }

      if (!prefs.contains(FIRST_RUN_PREF_KEY)) {
        SharedPreferences.Editor prefsEditor = prefs.edit();
        prefsEditor.putBoolean(FIRST_RUN_PREF_KEY, true);
        prefsEditor.commit();

        // Turn on our sync for all existing accounts when the program is first started

        for (Account account : manager.getAccountsByType(ACCOUNT_TYPE))
          ContentResolver.setSyncAutomatically(account, PHOTO_PROVIDER, true);

      }

      if (sSyncAdapter == null) {
        sSyncAdapter = new SyncAdapter(getApplicationContext(), true);
      }

    }
  }

  /*
   * {@inheritDoc}
   */
  @Override
  public IBinder onBind(Intent intent) {
    return sSyncAdapter.getSyncAdapterBinder();
  }

}
