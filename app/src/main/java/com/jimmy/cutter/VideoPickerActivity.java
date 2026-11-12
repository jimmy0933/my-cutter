package com.jimmy.cutter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 影片庫：列出媒體庫中的所有影片，可搜尋、依資料夾篩選、排序，點選後把 Uri 回傳給呼叫者。 */
public class VideoPickerActivity extends Activity {
    private static final int BG = 0xFF0F1115, CARD = 0xFF1A1D23, TEXT = 0xFFE8EAED, MUTED = 0xFF9AA0A6,
            TONAL = 0xFF2A2E36, ACCENT = 0xFFFF7043;
    private static final int REQ_PERM = 7;
    private static final String[] SORTS = {"日期（新→舊）", "日期（舊→新）", "大小（大→小）", "時長（長→短）", "名稱 A→Z"};

    static final class Item {
        long id, durationMs, size, dateSec;
        String name, bucket;
        Uri uri;
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<Long, Bitmap> thumbs = new LruCache<>(120);

    private String query = "", bucketFilter = null;
    private int sort = 0;
    private Button btnBucket, btnSort;
    private TextView count, empty;
    private BaseAdapter adapter;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        if (hasPermission()) load(); else requestPermission();
    }

    @Override protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private String permName() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_VIDEO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return checkSelfPermission(permName()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        requestPermissions(new String[]{permName()}, REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            empty.setText("需要「讀取影片」權限才能列出影片。\n可返回改用「檔案」或「相簿」。");
            empty.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------ UI

    private Button chip(String label) {
        Button bt = new Button(this);
        bt.setAllCaps(false);
        bt.setText(label);
        bt.setTextSize(13);
        bt.setTextColor(TEXT);
        bt.setSingleLine(true);
        bt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable();
        g.setColor(TONAL);
        g.setCornerRadius(dp(12));
        bt.setBackground(g);
        bt.setMinHeight(0);
        bt.setMinimumHeight(0);
        bt.setStateListAnimator(null);
        bt.setPadding(dp(10), dp(9), dp(10), dp(9));
        return bt;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(16), dp(14), 0);

        TextView title = new TextView(this);
        title.setText("影片庫");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        root.addView(title);

        EditText search = new EditText(this);
        search.setHint("搜尋檔名");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(CARD);
        sg.setCornerRadius(dp(12));
        search.setBackground(sg);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(Locale.ROOT); refresh(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(10);
        root.addView(search, sp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnBucket = chip("全部資料夾");
        btnSort = chip(SORTS[0]);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
        a.rightMargin = dp(4);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, -2, 1f);
        c.leftMargin = dp(4);
        row.addView(btnBucket, a);
        row.addView(btnSort, c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        root.addView(row, rp);

        btnBucket.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseBucket(); }
        });
        btnSort.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSort(); }
        });

        count = new TextView(this);
        count.setTextColor(MUTED);
        count.setTextSize(12);
        count.setPadding(dp(2), dp(10), 0, dp(6));
        root.addView(count);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Item it = shown.get(pos);
                setResult(RESULT_OK, new Intent().setData(it.uri));
                finish();
            }
        });
        FrameLayoutHolder holder = new FrameLayoutHolder();
        root.addView(holder.wrap(list), new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    /** 列表與「沒有影片」提示疊在一起。 */
    private final class FrameLayoutHolder {
        View wrap(ListView list) {
            android.widget.FrameLayout f = new android.widget.FrameLayout(VideoPickerActivity.this);
            f.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
            empty = new TextView(VideoPickerActivity.this);
            empty.setTextColor(MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setVisibility(View.GONE);
            f.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
            return f;
        }
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    private void chooseSort() {
        dialog().setTitle("排序").setItems(SORTS, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) {
                sort = which;
                btnSort.setText(SORTS[which]);
                refresh();
            }
        }).show();
    }

    private void chooseBucket() {
        final List<String> names = new ArrayList<>();
        for (Item it : all) if (it.bucket != null && !names.contains(it.bucket)) names.add(it.bucket);
        Collections.sort(names);
        names.add(0, "全部資料夾");
        dialog().setTitle("資料夾").setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) {
                bucketFilter = which == 0 ? null : names.get(which);
                btnBucket.setText(names.get(which));
                refresh();
            }
        }).show();
    }

    // ------------------------------------------------------------ data

    private void load() {
        empty.setText("載入中…");
        empty.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Item> found = new ArrayList<>();
                String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
                try (Cursor cur = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
                        null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            Item it = new Item();
                            it.id = cur.getLong(0);
                            it.name = cur.getString(1);
                            it.durationMs = cur.getLong(2);
                            it.size = cur.getLong(3);
                            it.dateSec = cur.getLong(4);
                            it.bucket = cur.getString(5);
                            it.uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.id);
                            found.add(it);
                        }
                    }
                } catch (Exception ignored) { }
                ui.post(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(found);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        shown.clear();
        for (Item it : all) {
            if (bucketFilter != null && !bucketFilter.equals(it.bucket)) continue;
            if (!query.isEmpty() && (it.name == null || !it.name.toLowerCase(Locale.ROOT).contains(query))) continue;
            shown.add(it);
        }
        Collections.sort(shown, new Comparator<Item>() {
            @Override public int compare(Item x, Item y) {
                switch (sort) {
                    case 1: return Long.compare(x.dateSec, y.dateSec);
                    case 2: return Long.compare(y.size, x.size);
                    case 3: return Long.compare(y.durationMs, x.durationMs);
                    case 4: return String.valueOf(x.name).compareToIgnoreCase(String.valueOf(y.name));
                    default: return Long.compare(y.dateSec, x.dateSec);
                }
            }
        });
        count.setText(shown.size() + " 部影片");
        empty.setText(all.isEmpty() ? "找不到影片" : "沒有符合的影片");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ list

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).id; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageView img;
            TextView t1, t2;
            if (convert == null) {
                row = new LinearLayout(VideoPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable g = new GradientDrawable();
                g.setColor(CARD);
                g.setCornerRadius(dp(12));
                row.setBackground(g);
                row.setPadding(dp(8), dp(8), dp(12), dp(8));
                AbsListViewParams.apply(row);
                img = new ImageView(VideoPickerActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(0xFF000000);
                row.addView(img, new LinearLayout.LayoutParams(dp(96), dp(64)));
                LinearLayout col = new LinearLayout(VideoPickerActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                t1 = new TextView(VideoPickerActivity.this);
                t1.setTextColor(TEXT);
                t1.setTextSize(14);
                t1.setMaxLines(2);
                t1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                t2 = new TextView(VideoPickerActivity.this);
                t2.setTextColor(MUTED);
                t2.setTextSize(11);
                t2.setPadding(0, dp(4), 0, 0);
                col.addView(t1);
                col.addView(t2);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                row.setTag(new View[]{img, t1, t2});
            } else {
                row = (LinearLayout) convert;
            }
            View[] v = (View[]) row.getTag();
            img = (ImageView) v[0];
            t1 = (TextView) v[1];
            t2 = (TextView) v[2];

            final Item it = shown.get(pos);
            t1.setText(it.name);
            t2.setText(String.format(Locale.US, "%s · %s · %s\n%s",
                    MainActivity.fmt(it.durationMs * 1000L), MainActivity.size(it.size),
                    new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(it.dateSec * 1000L)),
                    it.bucket == null ? "" : it.bucket));
            bindThumb(img, it);
            return row;
        }
    }

    /** ListView 的子項需要 AbsListView.LayoutParams，另外放以保持 getView 簡潔。 */
    private static final class AbsListViewParams {
        static void apply(View v) {
            android.widget.AbsListView.LayoutParams lp =
                    new android.widget.AbsListView.LayoutParams(-1, -2);
            v.setLayoutParams(lp);
        }
    }

    private void bindThumb(final ImageView img, final Item it) {
        img.setTag(it.id);
        Bitmap cached = thumbs.get(it.id);
        if (cached != null) { img.setImageBitmap(cached); return; }
        img.setImageBitmap(null);
        try {
            pool.execute(new Runnable() {
                @Override public void run() {
                    Bitmap bm = null;
                    try {
                        bm = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), it.id,
                                MediaStore.Video.Thumbnails.MINI_KIND, null);
                    } catch (Throwable ignored) { }
                    if (bm == null) return;
                    thumbs.put(it.id, bm);
                    final Bitmap fin = bm;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Object tag = img.getTag();
                            if (tag instanceof Long && (Long) tag == it.id) img.setImageBitmap(fin);
                        }
                    });
                }
            });
        } catch (Exception ignored) { }
    }
}
