package com.voiceprompter.fa;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.voiceprompter.fa.core.MistakeDetector;
import com.voiceprompter.fa.core.ScriptIndex;
import com.voiceprompter.fa.core.Tracker;

/** The prompter screen: scrolls by voice (follows the spoken words) or at a fixed speed. */
public class PrompterActivity extends Activity implements Choreographer.FrameCallback, VoiceEngine.Listener {

    public static final String EXTRA_ID = "id";

    private static final int REQ_MIC = 7;
    /** How quickly the text glides to the spoken word (seconds). */
    private static final double TAU = 0.35;

    private NoFlingScrollView scroll;
    private TextView text, heard, status, speedLabel;
    private View marker, level, controls;
    private Button btnPlay, btnMode, btnRotate;

    private String body = "";
    private ScriptIndex index;
    private Tracker tracker;
    private Spannable span;
    private final ForegroundColorSpan readSpan = new ForegroundColorSpan(0xFF6E6E6E);
    private VoiceEngine engine;

    private boolean voiceMode, playing, touching, layoutReady, engineReady, resumed;
    private boolean askedPermission, waitingForPermission;
    /** Auto "go back" fires once, then waits until the speaker is following the text again. */
    private boolean retakeArmed = true;
    private String notice = "";
    private boolean pendingRestore = true;
    private int anchorWord;
    private double posY, targetY;
    private long lastFrame;
    private int downScrollY;
    private int fontSp, speed;
    private String lastHeard = "";
    private String engineInfo = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyOrientation();
        setContentView(R.layout.activity_prompter);

        scroll = findViewById(R.id.scroll);
        text = findViewById(R.id.text);
        heard = findViewById(R.id.heard);
        status = findViewById(R.id.status);
        speedLabel = findViewById(R.id.speedLabel);
        marker = findViewById(R.id.marker);
        level = findViewById(R.id.level);
        controls = findViewById(R.id.controls);
        btnPlay = findViewById(R.id.btnPlay);
        btnMode = findViewById(R.id.btnMode);
        btnRotate = findViewById(R.id.btnRotate);

        String id = getIntent().getStringExtra(EXTRA_ID);
        String[] script = id == null ? null : ScriptStore.load(this, id);
        if (script == null) {
            finish();
            return;
        }
        body = script[1];

        index = new ScriptIndex(body);
        tracker = new Tracker(index.keys());
        text.setText(body, TextView.BufferType.SPANNABLE);
        span = (Spannable) text.getText();
        engine = new VoiceEngine(this, this);

        if (Prefs.sp(this).getBoolean(Prefs.GRAMMAR_GUARD, false)) {
            // The previous run died while building the restricted vocabulary.
            Prefs.sp(this).edit()
                    .putBoolean(Prefs.GRAMMAR_GUARD, false)
                    .putBoolean(Prefs.RESTRICT, false)
                    .apply();
            toast("«محدود کردن لغات» با این مدل سازگار نبود و خاموش شد.");
        }

        fontSp = Prefs.i(this, Prefs.FONT_SP);
        speed = Prefs.i(this, Prefs.SPEED);
        voiceMode = Prefs.b(this, Prefs.VOICE_MODE);
        if (state != null) anchorWord = state.getInt("word", 0);
        tracker.setPosition(anchorWord);

        level.setPivotX(0f);
        applyStyle();

        View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> onLayoutChanged();
        scroll.addOnLayoutChangeListener(relayout);
        text.addOnLayoutChangeListener(relayout);

        final GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                setControlsVisible(controls.getVisibility() != View.VISIBLE);
                return true;
            }
        });
        scroll.setOnTouchListener((v, e) -> {
            taps.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                touching = true;
                downScrollY = scroll.getScrollY();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                touching = false;
                onUserScrolled();
            }
            return false;
        });

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        btnMode.setOnClickListener(v -> setVoiceMode(!voiceMode));
        btnPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btnSlower).setOnClickListener(v -> perform(Keys.SLOWER));
        findViewById(R.id.btnFaster).setOnClickListener(v -> perform(Keys.FASTER));
        findViewById(R.id.btnFontDown).setOnClickListener(v -> changeFont(-4));
        findViewById(R.id.btnFontUp).setOnClickListener(v -> changeFont(4));
        btnRotate.setOnClickListener(v -> {
            // follow the device -> locked landscape -> locked portrait -> ...
            int o = Prefs.i(this, Prefs.ORIENT);
            Prefs.put(this, Prefs.ORIENT, o == 0 ? 1 : o == 1 ? 2 : 0);
            applyOrientation();
            updateUi();
        });
        findViewById(R.id.btnMirror).setOnClickListener(v -> {
            Prefs.put(this, Prefs.MIRROR_H, !Prefs.b(this, Prefs.MIRROR_H));
            applyStyle();
        });

        updateUi();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
        if (voiceMode) startVoice();
        updateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        playing = false;
        engine.stop();
        engineReady = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("word", currentWord());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (layoutReady) anchorWord = currentWord();
        pendingRestore = true;
        text.requestLayout();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------------------------------------------------------- look

    /** Locks the screen rotation, or lets it follow the device. */
    private void applyOrientation() {
        int req;
        switch (Prefs.i(this, Prefs.ORIENT)) {
            case 1: req = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case 2: req = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case 3: req = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            case 4: req = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            default: req = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; break;
        }
        setRequestedOrientation(req);
    }

    private void applyStyle() {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        text.setLineSpacing(0f, Prefs.i(this, Prefs.LINE_SPACING) / 10f);
        text.setTypeface(Fonts.get(this, Prefs.s(this, Prefs.FONT), Prefs.b(this, Prefs.BOLD)));
        int align = Prefs.i(this, Prefs.ALIGN);
        text.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        text.setGravity(align == 1 ? Gravity.CENTER_HORIZONTAL : align == 2 ? Gravity.LEFT : Gravity.RIGHT);
        float sx = Prefs.b(this, Prefs.MIRROR_H) ? -1f : 1f;
        scroll.setScaleX(sx);
        scroll.setScaleY(Prefs.b(this, Prefs.MIRROR_V) ? -1f : 1f);
        // the small notes are read through the same glass, so mirror them too
        heard.setScaleX(sx);
        status.setScaleX(sx);
        if (layoutReady) anchorWord = currentWord();
        pendingRestore = true;
        text.requestLayout();
    }

    private void changeFont(int delta) {
        fontSp = Math.max(16, Math.min(200, fontSp + delta));
        Prefs.put(this, Prefs.FONT_SP, fontSp);
        applyStyle();
    }

    /** Keeps the paddings right for the screen size, then restores the reading position. */
    private void onLayoutChanged() {
        final int vh = scroll.getHeight(), vw = scroll.getWidth();
        if (vh == 0 || vw == 0) return;
        final int readOff = vh * Prefs.i(this, Prefs.READ_POS) / 100;
        final int side = vw * Prefs.i(this, Prefs.MARGIN) / 100;
        final int top = readOff, bottom = vh - readOff;
        if (text.getPaddingTop() != top || text.getPaddingBottom() != bottom
                || text.getPaddingLeft() != side || text.getPaddingRight() != side) {
            if (layoutReady && !pendingRestore) anchorWord = currentWord();
            layoutReady = false;
            pendingRestore = true;
            text.post(() -> text.setPadding(side, top, side, bottom));
            return;
        }
        if (text.getLayout() == null) return;
        layoutReady = true;

        int lineH = text.getLineHeight();
        float my = readOff + lineH / 2f - marker.getHeight() / 2f;
        if (Prefs.b(this, Prefs.MIRROR_V)) my = vh - my - marker.getHeight();
        marker.setTranslationY(my);

        if (pendingRestore) {
            pendingRestore = false;
            posY = targetY = yFor(anchorWord);
            scroll.scrollTo(0, (int) Math.round(posY));
            updateHighlight();
        }
    }

    // ---------------------------------------------------------------- geometry

    /** Scroll offset that puts the given word on the reading line. */
    private double yFor(int word) {
        Layout l = text.getLayout();
        if (l == null || index.size() == 0) return 0;
        if (word >= index.size()) return l.getHeight();
        int off = Math.min(index.start(word), Math.max(0, text.length() - 1));
        int line = l.getLineForOffset(off);
        int top = l.getLineTop(line), bottom = l.getLineBottom(line);
        int ls = l.getLineStart(line), le = l.getLineEnd(line);
        double frac = le > ls ? (double) (off - ls) / (le - ls) : 0;
        return top + frac * (bottom - top);
    }

    private int wordAtY(double y) {
        Layout l = text.getLayout();
        if (l == null) return anchorWord;
        if (y >= l.getHeight()) return index.size();
        int line = l.getLineForVertical((int) y);
        return index.wordAtOrAfter(l.getLineStart(line));
    }

    private int currentWord() { return voiceMode ? tracker.position() : wordAtY(posY); }

    private int maxScroll() { return Math.max(0, text.getHeight() - scroll.getHeight()); }

    private void updateHighlight() {
        boolean on = voiceMode && Prefs.b(this, Prefs.HIGHLIGHT);
        int off = on ? Math.min(index.start(tracker.position()), span.length()) : 0;
        if (off > 0) span.setSpan(readSpan, 0, off, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else span.removeSpan(readSpan);
    }

    // ---------------------------------------------------------------- animation

    @Override
    public void doFrame(long nanos) {
        double dt = lastFrame == 0 ? 0 : (nanos - lastFrame) / 1e9;
        lastFrame = nanos;
        if (dt > 0.1) dt = 0.1;
        if (layoutReady && !touching && !pendingRestore) {
            double max = maxScroll();
            if (voiceMode) {
                posY += (targetY - posY) * (1 - Math.exp(-dt / TAU));
            } else if (playing) {
                posY += speed * text.getLineHeight() / 40.0 * dt;
                if (posY >= max) {
                    posY = max;
                    playing = false;
                    updateUi();
                    setControlsVisible(true);
                }
            }
            if (posY < 0) posY = 0;
            if (posY > max) posY = max;
            int y = (int) Math.round(posY);
            if (y != scroll.getScrollY()) scroll.scrollTo(0, y);
        }
        if (resumed) Choreographer.getInstance().postFrameCallback(this);
    }

    /** The user dragged the text: carry on from where they left it. */
    private void onUserScrolled() {
        int y = scroll.getScrollY();
        if (Math.abs(y - downScrollY) < 12) return; // a tap, not a drag
        posY = y;
        if (voiceMode) {
            int w = wordAtY(posY);
            tracker.setPosition(w);
            targetY = yFor(w);
            updateHighlight();
        } else {
            targetY = posY;
        }
    }

    // ---------------------------------------------------------------- actions

    private void perform(int action) {
        switch (action) {
            case Keys.PLAY:
                togglePlay();
                break;
            case Keys.FASTER:
                if (voiceMode) nudge(1); else changeSpeed(1);
                break;
            case Keys.SLOWER:
                if (voiceMode) nudge(-1); else changeSpeed(-1);
                break;
            case Keys.BACK:
                nudge(-1);
                break;
            case Keys.FORWARD:
                nudge(1);
                break;
            case Keys.RETAKE:
                retake();
                break;
            default:
                break;
        }
    }

    private void togglePlay() {
        if (!playing) {
            if (voiceMode) {
                if (!engine.isRunning() && !startVoice()) return;
                if (tracker.position() >= index.size()) jumpTo(0);
            } else if (layoutReady && posY >= maxScroll() - 1) {
                jumpTo(0);
            }
        }
        playing = !playing;
        engine.setPaused(!(playing && voiceMode));
        if (playing) setControlsVisible(false);
        updateUi();
    }

    private void changeSpeed(int delta) {
        speed = Math.max(1, Math.min(100, speed + delta));
        Prefs.put(this, Prefs.SPEED, speed);
        updateUi();
    }

    /** Moves one line back or forward. */
    private void nudge(int dir) {
        Layout l = text.getLayout();
        if (l == null || index.size() == 0) return;
        int w = currentWord();
        int last = l.getLineCount() - 1;
        int line = w >= index.size() ? last + 1 : l.getLineForOffset(index.start(w));
        int target = line + dir;
        int nw;
        if (target < 0) nw = 0;
        else if (target > last) nw = index.size();
        else nw = index.wordAtOrAfter(l.getLineStart(target));
        jumpTo(nw);
    }

    /** "I slipped": stop, and put the text two lines back so the passage can be read again. */
    private void retake() {
        Layout l = text.getLayout();
        if (l == null || index.size() == 0) return;
        int w = currentWord();
        int last = l.getLineCount() - 1;
        int line = w >= index.size() ? last : l.getLineForOffset(index.start(w));
        int target = Math.max(0, line - 2);
        jumpTo(index.wordAtOrAfter(l.getLineStart(target)));
        retakeArmed = false;
        if (voiceMode) {
            engine.resetUtterance();
            lastHeard = "";
            heard.setText("");
            flash("↩ دو خط عقب — از همین‌جا دوباره بخون");
        } else if (playing) {
            playing = false;
            flash("↩ دو خط عقب — برای ادامه پخش رو بزن");
        }
    }

    /** Shows a short note for a few seconds. */
    private void flash(String msg) {
        notice = msg;
        updateUi();
        status.postDelayed(() -> {
            if (msg.equals(notice)) {
                notice = "";
                updateUi();
            }
        }, 3000);
    }

    private void jumpTo(int word) {
        tracker.setPosition(word);
        targetY = yFor(word);
        if (!voiceMode) posY = targetY;
        updateHighlight();
    }

    private void setVoiceMode(boolean on) {
        int w = layoutReady ? currentWord() : anchorWord;
        playing = false;
        if (on) {
            voiceMode = true;
            tracker.setPosition(w);
            targetY = yFor(w);
            if (!startVoice() && !waitingForPermission) voiceMode = false;
        } else {
            voiceMode = false;
            engine.stop();
            engineReady = false;
            targetY = posY;
        }
        Prefs.put(this, Prefs.VOICE_MODE, voiceMode);
        engine.setPaused(true);
        updateHighlight();
        updateUi();
    }

    /** Starts the recogniser (paused). Returns false when it could not be started right now. */
    private boolean startVoice() {
        if (engine.isRunning()) return true;
        if (!ModelStore.isInstalled(this)) {
            voiceMode = false;
            toast("مدل گفتار فارسی نصب نیست. از صفحه‌ی اول نصبش کن.");
            updateUi();
            return false;
        }
        boolean wantBt = Prefs.i(this, Prefs.MIC) == VoiceEngine.MIC_BLUETOOTH && Build.VERSION.SDK_INT >= 31;
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!micOk) {
            if (askedPermission) {
                voiceMode = false;
                toast("بدون اجازه‌ی میکروفون، حالت صدا کار نمی‌کنه.");
                updateUi();
                return false;
            }
            askedPermission = true;
            waitingForPermission = true;
            requestPermissions(wantBt
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return false;
        }
        engineReady = false;
        engineInfo = "";
        engine.start(body, Prefs.b(this, Prefs.RESTRICT), Prefs.i(this, Prefs.MIC));
        engine.setPaused(!playing);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        waitingForPermission = false;
        boolean ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (ok && voiceMode) {
            startVoice();
        } else if (!ok) {
            voiceMode = false;
            Prefs.put(this, Prefs.VOICE_MODE, false);
            toast("بدون اجازه‌ی میکروفون، حالت صدا کار نمی‌کنه.");
        }
        updateUi();
    }

    // ---------------------------------------------------------------- recogniser callbacks

    @Override
    public void onReady(String info) {
        engineReady = true;
        engineInfo = info == null ? "" : info;
        if (!engineInfo.isEmpty()) toast(engineInfo);
        updateUi();
    }

    @Override
    public void onHypothesis(String hyp, boolean isFinal) {
        if (!voiceMode || !playing) return;
        if (!hyp.isEmpty() && !hyp.equals(lastHeard)) {
            lastHeard = hyp;
            if (heard.getVisibility() == View.VISIBLE) heard.setText(hyp);
        }
        if (tracker.onHypothesis(hyp, isFinal)) {
            targetY = yFor(tracker.position());
            updateHighlight();
            retakeArmed = true; // following the text again
        } else if (retakeArmed && Prefs.b(this, Prefs.AUTO_RETAKE)
                && MistakeDetector.isMistake(tracker, isFinal, Prefs.i(this, Prefs.RETAKE_SENS))) {
            retake();
        }
    }

    @Override
    public void onLevel(float v) {
        level.setScaleX(Math.max(0.03f, v));
    }

    @Override
    public void onError(String message) {
        engine.stop();
        engineReady = false;
        playing = false;
        voiceMode = false;
        toast("حالت صدا متوقف شد: " + message);
        updateHighlight();
        updateUi();
        setControlsVisible(true);
    }

    // ---------------------------------------------------------------- keys (remote / pedal)

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = Keys.actionFor(this, event.getKeyCode());
        if (action == Keys.NONE) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean repeatable = action != Keys.PLAY;
            if (event.getRepeatCount() == 0 || repeatable) perform(action);
        }
        return true;
    }

    // ---------------------------------------------------------------- ui

    private void setControlsVisible(boolean show) {
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateUi() {
        btnPlay.setText(playing ? "⏸" : "▶");
        btnMode.setText(voiceMode ? "🎤 صدا" : "دستی");
        int orient = Prefs.i(this, Prefs.ORIENT);
        btnRotate.setText(orient == 0 ? "چرخش آزاد" : orient == 1 || orient == 3 ? "قفل افقی" : "قفل عمودی");
        speedLabel.setText(voiceMode ? "خط" : "سرعت " + speed);
        level.setVisibility(voiceMode ? View.VISIBLE : View.GONE);
        boolean showHeard = voiceMode && Prefs.b(this, Prefs.SHOW_HEARD);
        heard.setVisibility(showHeard ? View.VISIBLE : View.GONE);
        String s;
        if (!notice.isEmpty()) s = notice;
        else if (!voiceMode) s = playing ? "" : "دستی — سرعت " + speed;
        else if (waitingForPermission) s = "منتظر اجازه‌ی میکروفون";
        else if (!engineReady) s = "در حال بارگذاری مدل فارسی…";
        else s = playing ? "" : "آماده — پخش رو بزن و شروع کن به خوندن";
        status.setText(s);
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
