package com.eink.weread;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 书架页：Skill 网关拉书架 → 点击进入阅读器。
 * 菜单键：设置 API Key / 重新登录。
 */
public class MainActivity extends Activity {

    private ListView listView;
    private TextView hintView;
    private final List<Api.ShelfBook> books = new ArrayList<Api.ShelfBook>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Session.init(this);

        hintView = new TextView(this);
        hintView.setTextSize(15);
        hintView.setTextColor(0xFF333333);
        hintView.setPadding(dip(16), dip(12), dip(16), dip(12));

        listView = new ListView(this);
        listView.setBackgroundColor(0xFFFFFFFF);
        listView.setAdapter(new ArrayAdapter<Api.ShelfBook>(
                this, android.R.layout.simple_list_item_1, books) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                Api.ShelfBook b = books.get(position);
                v.setTextColor(0xFF111111);
                v.setTextSize(17);
                v.setText(b.title + (b.author.length() > 0 ? "  ·  " + b.author : ""));
                return v;
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Api.ShelfBook b = books.get(position);
                Intent it = new Intent(MainActivity.this, ReaderActivity.class);
                it.putExtra("bookId", b.bookId);
                it.putExtra("title", b.title);
                startActivity(it);
            }
        });

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(hintView, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listView, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!Session.isLoggedIn()) {
            startActivityForResult(new Intent(this, LoginActivity.class), 1);
            return;
        }
        if (Session.getApiKey().length() == 0) {
            askApiKey();
            return;
        }
        hintView.setText("书架加载中…");
        books.clear();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Api.ShelfBook> result;
                String err = null;
                try {
                    result = Api.fetchShelf();
                } catch (Throwable e) {
                    result = null;
                    err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                }
                final List<Api.ShelfBook> fResult = result;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fResult != null) {
                            books.addAll(fResult);
                            hintView.setText("书架（" + books.size() + " 本）  菜单键=设置/重新登录");
                            ((ArrayAdapter<?>) listView.getAdapter()).notifyDataSetChanged();
                        } else {
                            hintView.setText("加载失败：" + fErr);
                            Toast.makeText(MainActivity.this, fErr, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void askApiKey() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(Session.getApiKey());
        input.setHint("微信读书 App → 我 → 设置 → 微信读书 Skill → 获取 API Key");
        input.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle("粘贴你的官方 API Key")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Session.setApiKey(input.getText().toString());
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            final String[] items = {"设置 API Key", "重新扫码登录"};
            new AlertDialog.Builder(this)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            if (w == 0) {
                                askApiKey();
                            } else {
                                Session.clearLogin();
                                startActivityForResult(
                                        new Intent(MainActivity.this, LoginActivity.class), 1);
                            }
                        }
                    }).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            refresh();
        }
    }
}
