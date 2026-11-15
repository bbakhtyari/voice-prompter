package com.voiceprompter.fa;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Write, paste or import a script, then start the prompter. */
public class EditActivity extends Activity {

    public static final String EXTRA_ID = "id";
    private static final int REQ_IMPORT = 21;

    private EditText title, body;
    private String id;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_edit);
        title = findViewById(R.id.title);
        body = findViewById(R.id.body);

        id = state != null ? state.getString("id") : getIntent().getStringExtra(EXTRA_ID);
        if (state == null && id != null) {
            String[] s = ScriptStore.load(this, id);
            if (s != null) {
                title.setText(s[0]);
                body.setText(s[1]);
            }
        }

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (save()) toast("ذخیره شد");
        });
        findViewById(R.id.btnStart).setOnClickListener(v -> {
            if (body.getText().toString().trim().isEmpty()) {
                toast("اول متن رو وارد کن");
                return;
            }
            if (save()) {
                startActivity(new Intent(this, PrompterActivity.class).putExtra(PrompterActivity.EXTRA_ID, id));
            }
        });
        findViewById(R.id.btnEditSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnImport).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/*");
            try {
                startActivityForResult(i, REQ_IMPORT);
            } catch (RuntimeException e) {
                toast("برنامه‌ای برای انتخاب فایل پیدا نشد");
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("id", id);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    /** Saves when there is something to save. Returns false only on an error. */
    private boolean save() {
        String t = title.getText().toString().trim();
        String b = body.getText().toString();
        if (id == null && t.isEmpty() && b.trim().isEmpty()) return true;
        if (t.isEmpty()) {
            String first = b.trim();
            int nl = first.indexOf('\n');
            if (nl >= 0) first = first.substring(0, nl);
            t = first.length() > 40 ? first.substring(0, 40) + "…" : first;
            if (t.isEmpty()) t = "بدون عنوان";
        }
        try {
            id = ScriptStore.save(this, id, t, b);
            return true;
        } catch (IOException e) {
            toast("ذخیره نشد: " + e.getMessage());
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("فایل باز نشد");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) throw new IOException("فایل خیلی بزرگه");
            }
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            body.setText(s.replace("\r\n", "\n"));
        } catch (IOException | RuntimeException e) {
            toast("خواندن فایل نشد: " + e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
