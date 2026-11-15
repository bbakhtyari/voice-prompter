package com.voiceprompter.fa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings screen, built in code to keep it in one place. */
public class SettingsActivity extends Activity {

    private static final int REQ_FONT = 31;

    private LinearLayout box;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = dp(16);
        box.setPadding(p, p, p, p);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        build();
    }

    private void build() {
        box.removeAllViews();

        header("نمایش");
        slider("اندازه‌ی فونت", Prefs.FONT_SP, 16, 200, "");
        slider("فاصله‌ی خط‌ها", Prefs.LINE_SPACING, 10, 25, " (÷۱۰)");
        slider("حاشیه‌ی دو طرف متن", Prefs.MARGIN, 0, 40, "٪");
        slider("جای خط خوندن از بالای صفحه", Prefs.READ_POS, 10, 70, "٪");
        choice("چرخش صفحه‌ی تله‌پرامپتر", Prefs.ORIENT, new String[]{
                "آزاد (با چرخوندن تبلت می‌چرخه)", "قفل روی افقی", "قفل روی عمودی",
                "قفل روی افقی (برعکس)", "قفل روی عمودی (برعکس)"});
        toggle("آینه‌ی افقی (برای شیشه‌ی تله‌پرامپتر)", Prefs.MIRROR_H);
        toggle("آینه‌ی عمودی", Prefs.MIRROR_V);
        choice("چینش متن", Prefs.ALIGN, new String[]{"راست‌چین", "وسط‌چین", "چپ‌چین"});
        fontRow();
        toggle("فونت ضخیم", Prefs.BOLD);
        note("ظاهر برنامه همیشه تیره است: زمینه‌ی مشکی و متن سفید.");

        header("حالت صدا");
        engineRow();
        choice("میکروفون", Prefs.MIC, new String[]{
                "خودکار (پیش‌فرض دستگاه)", "میکروفون خود تبلت", "هدست بلوتوث"});
        toggle("محدود کردن لغات به کلمه‌های متن (دقت بیشتر)", Prefs.RESTRICT);
        toggle("کم‌رنگ کردن قسمت خونده‌شده", Prefs.HIGHLIGHT);
        toggle("نمایش چیزی که برنامه می‌شنوه", Prefs.SHOW_HEARD);
        note("اگه برنامه کلمه‌ها رو اشتباه می‌شنوه، یک بار «محدود کردن لغات» رو خاموش و روشن امتحان کن "
                + "و ببین کدوم برای صدای تو بهتره.");

        header("وقتی اشتباه خوندم");
        toggle("خودکار: توقف و برگشت دو خط به عقب", Prefs.AUTO_RETAKE);
        choice("حساسیت تشخیص اشتباه", Prefs.RETAKE_SENS, new String[]{
                "کم (فقط اشتباه‌های واضح)", "متوسط", "زیاد (ممکنه بی‌دلیل هم برگرده)"});
        note("برنامه وقتی چند کلمه پشت سر هم بشنوه که به متن نمی‌خوره، فرض می‌کنه اشتباه شده. "
                + "اگه مدل کلمه‌ای رو اشتباه بشنوه هم همین اتفاق می‌افته، پس ممکنه گاهی بی‌دلیل برگرده. "
                + "راه مطمئن: یکی از پدال‌ها رو در پایین همین صفحه به «اشتباه شد» وصل کن.");

        header("حالت دستی");
        slider("سرعت", Prefs.SPEED, 1, 100, "");

        header("کلیدهای ریموت / پدال");
        note("روی هر ردیف بزن و بعد کلید یا پدال مورد نظر رو فشار بده. کلیدهای پیش‌فرض هم همیشه کار می‌کنن.");
        for (int action : Keys.ACTIONS) keyRow(action);
    }

    // ---------------------------------------------------------------- rows

    private void header(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTextColor(getColor(R.color.accent));
        t.setPadding(0, dp(22), 0, dp(8));
        box.addView(t);
    }

    private void note(String msg) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(13);
        t.setTextColor(0xFF9E9E9E);
        t.setPadding(0, dp(4), 0, dp(8));
        box.addView(t);
    }

    private void toggle(String label, final String key) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(16);
        s.setChecked(Prefs.b(this, key));
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener((v, on) -> Prefs.put(this, key, on));
        box.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void slider(final String label, final String key, final int min, int max, final String suffix) {
        final TextView t = new TextView(this);
        t.setTextSize(16);
        t.setPadding(0, dp(10), 0, 0);
        int value = Math.max(min, Math.min(max, Prefs.i(this, key)));
        t.setText(label + ": " + value + suffix);
        box.addView(t);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                t.setText(label + ": " + v + suffix);
                if (fromUser) Prefs.put(SettingsActivity.this, key, v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void choice(final String label, final String key, final String[] options) {
        final Button b = rowButton();
        int cur = Math.max(0, Math.min(options.length - 1, Prefs.i(this, key)));
        b.setText(label + ": " + options[cur]);
        b.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(options, (d, which) -> {
                    Prefs.put(this, key, which);
                    b.setText(label + ": " + options[which]);
                })
                .show());
        box.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Lets the user pick a speech engine among those this build contains. */
    private void engineRow() {
        final List<Integer> have = new ArrayList<>();
        for (int e : Engines.ALL) {
            if (Engines.available(this, e)) have.add(e);
        }
        final Button b = rowButton();
        b.setText("موتور تشخیص گفتار: " + Engines.label(Engines.effective(this)));
        b.setOnClickListener(v -> {
            String[] labels = new String[have.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = Engines.label(have.get(i));
            new AlertDialog.Builder(this)
                    .setTitle("موتور تشخیص گفتار")
                    .setItems(labels, (d, which) -> {
                        Prefs.put(this, Prefs.ENGINE, have.get(which));
                        build();
                    })
                    .show();
        });
        box.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (have.size() == 1) {
            note("این نسخه فقط Vosk داره. برای انتخاب Shenava نسخه‌ی shenava برنامه رو نصب کن.");
        } else {
            note("Shenava مدل بزرگ‌تریه ولی هر چند لحظه یک‌بار صدا رو از نو تشخیص می‌ده، پس متن ممکنه "
                    + "با تأخیر بیشتری جلو بیاد. عدد داخل [ ] در خط پایین صفحه زمان هر تشخیص رو نشون می‌ده. "
                    + "«محدود کردن لغات» فقط روی Vosk اثر داره.");
        }
    }

    private void fontRow() {
        final Button b = rowButton();
        b.setText("فونت: " + Fonts.displayName(Prefs.s(this, Prefs.FONT)));
        b.setOnClickListener(v -> {
            final List<String> names = new ArrayList<>();
            names.add(Fonts.VAZIR);
            names.add(Fonts.SYSTEM);
            names.addAll(Fonts.imported(this));
            String[] labels = new String[names.size() + 1];
            for (int i = 0; i < names.size(); i++) labels[i] = Fonts.displayName(names.get(i));
            labels[names.size()] = "＋ افزودن فونت از فایل (ttf یا otf)…";
            new AlertDialog.Builder(this)
                    .setTitle("فونت")
                    .setItems(labels, (d, which) -> {
                        if (which == names.size()) {
                            pickFont();
                        } else {
                            Prefs.put(this, Prefs.FONT, names.get(which));
                            build();
                        }
                    })
                    .show();
        });
        box.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_FONT);
        } catch (RuntimeException e) {
            toast("برنامه‌ای برای انتخاب فایل پیدا نشد");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FONT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String name = null;
        try (Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException e) {
            // no name available
        }
        if (name == null || name.trim().isEmpty()) name = "font-" + System.currentTimeMillis() + ".ttf";
        name = name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            toast("فقط فایل فونت ttf یا otf");
            return;
        }

        File out = new File(Fonts.dir(this), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("فایل باز نشد");
            byte[] buf = new byte[32 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                if (total > 30L * 1024 * 1024) throw new IOException("فایل خیلی بزرگه");
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            toast("فونت اضافه نشد: " + e.getMessage());
            return;
        }
        if (!Fonts.isUsable(out)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            toast("این فایل فونت معتبری نیست");
            return;
        }
        Prefs.put(this, Prefs.FONT, name);
        toast("فونت اضافه شد: " + Fonts.displayName(name));
        build();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void keyRow(final int action) {
        final Button b = rowButton();
        b.setText(keyRowText(action));
        b.setOnClickListener(v -> {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(Keys.label(action))
                    .setMessage("حالا کلید یا پدال مورد نظر رو فشار بده…")
                    .setNegativeButton("انصراف", null)
                    .setNeutralButton("برگشت به پیش‌فرض", (d, w) -> {
                        Keys.setCustom(this, action, 0);
                        build();
                    })
                    .create();
            dlg.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    Keys.setCustom(this, action, keyCode);
                    d.dismiss();
                    build();
                }
                return true;
            });
            dlg.show();
        });
        box.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String keyRowText(int action) {
        int custom = Keys.custom(this, action);
        String k = custom != 0 ? Keys.name(custom) : "پیش‌فرض: " + Keys.defaultsFor(action);
        return Keys.label(action) + "\n" + k;
    }

    private Button rowButton() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
