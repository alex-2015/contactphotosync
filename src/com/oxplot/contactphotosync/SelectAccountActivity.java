package com.oxplot.contactphotosync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
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
    setContentView(R.layout.activity_select_account);
    setTitle(getResources().getString(R.string.title_activity_select_account));
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
