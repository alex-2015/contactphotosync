/**
 * SelectAccountActivity.java - Allows user to choose a Google account.
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
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class SelectAccountActivity extends Activity {

  private static final String ACCOUNT_TYPE = "com.google";
  private ListView list;
  private ArrayAdapter<String> adapter;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setTitle(getResources().getString(R.string.title_activity_select_account));

    // Let's see if the user is willing to give us root permission at the very
    // start
    Util.runRoot("");

    setContentView(R.layout.activity_select_account);
    getActionBar().setHomeButtonEnabled(false);
    list = (ListView) findViewById(R.id.list);
    adapter = new ArrayAdapter<String>(this, R.layout.account_row, R.id.row);
    list.setAdapter(adapter);

    list.setEmptyView(findViewById(R.id.empty));

    list.setOnItemClickListener(new OnItemClickListener() {

      @Override
      public void onItemClick(AdapterView<?> a, View view, int curViewId,
          long id) {
        String account = adapter.getItem((int) id);
        Intent intent = new Intent(SelectAccountActivity.this,
            AssignContactPhotoActivity.class);
        intent.putExtra("account", account);
        startActivity(intent);
      }
    });
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.activity_select_account, menu);
    return true;
  }

  @Override
  protected void onResume() {
    super.onResume();
    AccountManager manager = AccountManager.get(this);
    adapter.clear();
    for (Account account : manager.getAccountsByType(ACCOUNT_TYPE))
      adapter.add(account.name);
    adapter.notifyDataSetChanged();
  }

}
