package com.voiceprompter.fa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** List of scripts, plus the one-time setup of the Persian speech model. */
public class MainActivity extends Activity {

    private static final int REQ_MODEL = 11;
    private static boolean bundledModelFailed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ScriptStore.Item> items = new ArrayList<>();
    private ArrayAdapter<ScriptStore.Item> adapter;
    private TextView modelStatus, empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        modelStatus = findViewById(R.id.modelStatus);
        empty = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, rowId) -> openEditor(items.get(pos).id));
        list.setOnItemLongClickListener((p, v, pos, rowId) -> {
            final ScriptStore.Item it = items.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(it.title)
                    .setMessage("این متن حذف بشه؟")
                    .setPositiveButton("حذف", (d, w) -> {
                        ScriptStore.delete(this, it.id);
                        refresh();
                    })
                    .setNegativeButton("انصراف", null)
                    .show();
            return true;
        });

        findViewById(R.id.btnNew).setOnClickListener(v -> openEditor(null));
        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        modelStatus.setOnClickListener(v -> {
            if (!ModelStore.isInstalling()) pickModel();
        });

        seedSample();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        checkModel();
    }

    private void openEditor(String id) {
        Intent i = new Intent(this, EditActivity.class);
        if (id != null) i.putExtra(EditActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void refresh() {
        items.clear();
        items.addAll(ScriptStore.list(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void seedSample() {
        if (Prefs.b(this, Prefs.SEEDED)) return;
        Prefs.put(this, Prefs.SEEDED, true);
        String sample = "سلام. این یه متن آزمایشیه برای تست تله‌پرامپتر صوتی.\n"
                + "دکمه‌ی پخش رو بزن و با صدای معمولی شروع کن به خوندن.\n"
                + "هر جا مکث کنی، متن هم می‌ایسته. هر وقت تندتر بخونی، متن هم تندتر بالا میاد.\n"
                + "اگه یه جمله رو جا انداختی نگران نباش؛ چند کلمه که جلوتر بخونی، متن خودش رو بهت می‌رسونه.\n"
                + "حالا متن خودت رو بنویس و ضبط رو شروع کن.";
        try {
            ScriptStore.save(this, null, "متن آزمایشی", sample);
        } catch (IOException ignored) {
            // not important
        }
    }

    // ---------------------------------------------------------------- speech model

    private void checkModel() {
        if (ModelStore.isInstalling()) {
            modelStatus.setText("در حال آماده‌سازی مدل گفتار فارسی… (فقط بار اول، حدود یک دقیقه)");
        } else if (ModelStore.isInstalled(this)) {
            modelStatus.setText("✓ مدل گفتار فارسی آماده است (برای جایگزینی با مدل دیگه اینجا بزن)");
        } else if (!bundledModelFailed && ModelStore.hasAsset(this)) {
            install(null);
        } else {
            modelStatus.setText("مدل گفتار فارسی نصب نیست. اینجا بزن و فایل zip مدل Vosk فارسی رو انتخاب کن."
                    + " تا اون موقع فقط حالت دستی کار می‌کنه.");
        }
    }

    private void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_MODEL);
        } catch (RuntimeException e) {
            Toast.makeText(this, "برنامه‌ای برای انتخاب فایل پیدا نشد", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MODEL && resultCode == RESULT_OK && data != null && data.getData() != null) {
            install(data.getData());
        }
    }

    /** Unzips the model in the background; a null uri means the copy bundled in the app. */
    private void install(final Uri uri) {
        if (ModelStore.isInstalling()) return;
        modelStatus.setText("در حال آماده‌سازی مدل گفتار فارسی… (فقط بار اول، حدود یک دقیقه)");
        final Context app = getApplicationContext();
        ModelStore.installStarting();
        new Thread(() -> {
            String err = null;
            try (InputStream in = uri == null
                    ? app.getAssets().open(ModelStore.ASSET)
                    : app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("فایل باز نشد");
                ModelStore.install(app, in);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
                if (uri == null) bundledModelFailed = true;
            } finally {
                ModelStore.installDone();
            }
            final String e = err;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (e != null) {
                    modelStatus.setText("نصب مدل انجام نشد: " + e + " — برای انتخاب فایل اینجا بزن.");
                } else {
                    checkModel();
                }
            });
        }, "model-install").start();
    }
}
