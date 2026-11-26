package com.example.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class MainModule extends XposedModule {

    private static final String TAG = "IG-Emoji-Score";
    private static final String MODAL_ACTIVITY = "com.instagram.modal.ModalActivity";
    private static final String TOGGLE_TAG = "ig_emoji_auto_toggle";
    private static final int MAX_LOG_LINES = 150;
    private static volatile int targetScore = 99;
    private static volatile boolean enabled = true;
    private static volatile boolean dialogOpen = false;
    private static volatile boolean dispatchingBotTouch = false;
    private static final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static volatile Activity activeActivity = null;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static boolean loopRunning = false;
    private static boolean touchDown = false;
    private static long downTime = 0L;
    private static float prevBallCenterX = -1f;
    private static int stuckFrames = 0;
    private static boolean wasLeaderboardOpen = false;
    private static long resumeAfterMs = 0L;
    private static ViewGroup cachedArena = null;
    private static TextView cachedScoreView = null;
    private static TextView cachedBallView = null;
    private static View cachedPaddleView = null;
    private static View cachedHighScoreContainer = null;
    private static TextView cachedToggleBtn = null;

    private void addLog(String msg) {
        logBuffer.addLast(msg);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.pollFirst();
        }
        try {
            log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"com.instagram.android".equals(param.getPackageName())) return;
        addLog("onPackageReady: hooking com.instagram.android");

        installRuntimeGuard();

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Activity act = (Activity) chain.getThisObject();
                        activeActivity = act;
                        if (isModalActivity(act)) {
                            startBotLoop();
                        } else {
                            stopBotLoop();
                        }
                        return result;
                    });

            Method onPause = Activity.class.getDeclaredMethod("onPause");
            hook(onPause)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        releaseTouch(activeActivity);
                        setKeepScreenOn(false);
                        return chain.proceed();
                    });

            Method dispatchTouch = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            hook(dispatchTouch)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!dispatchingBotTouch && enabled && !dialogOpen && isModalActivity(activeActivity) && isArenaValid()) {
                            Activity act = activeActivity;
                            MotionEvent ev = (MotionEvent) chain.getArg(0);
                            if (act != null && ev != null && !isLeaderboardOpen(act) && SystemClock.uptimeMillis() >= resumeAfterMs) {
                                int[] arenaLoc = new int[2];
                                cachedArena.getLocationInWindow(arenaLoc);
                                float topBarBottom = arenaLoc[1] + 280f;
                                if (ev.getRawY() < topBarBottom) {
                                    // Allow top-bar taps (Leaderboard, Auto-Play toggle, Close)
                                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                        releaseTouch(act);
                                        resumeAfterMs = SystemClock.uptimeMillis() + 250;
                                    }
                                    return chain.proceed();
                                }
                                int score = parseScore(cachedScoreView);
                                if (score >= 0 && score < targetScore) {
                                    return true;
                                }
                            }
                        }
                        return chain.proceed();
                    });

            Method dispatchKey = Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class);
            hook(dispatchKey)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        KeyEvent ev = (KeyEvent) chain.getArg(0);
                        if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN
                                && ev.getRepeatCount() == 0
                                && ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                                && isModalActivity(activeActivity) && !dialogOpen) {
                            if (!isArenaValid()) {
                                findPongViews(activeActivity.getWindow().getDecorView());
                            }
                            if (isArenaValid()) {
                                promptScore(activeActivity);
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            addLog("Hook setup error: " + t.getMessage());
        }
    }

    private void installRuntimeGuard() {
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        } catch (Throwable ignored) {}

        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                if (thread != Looper.getMainLooper().getThread()) {
                    addLog("BG guard caught: " + throwable.getClass().getSimpleName());
                    return;
                }
                if (prev != null) {
                    prev.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean isModalActivity(Activity act) {
        return act != null && MODAL_ACTIVITY.equals(act.getClass().getName());
    }

    private static void stopBotLoop() {
        loopRunning = false;
        prevBallCenterX = -1f;
        releaseTouch(activeActivity);
        setKeepScreenOn(false);
        hideToggleButton();
        cachedArena = null;
        cachedScoreView = null;
        cachedBallView = null;
        cachedPaddleView = null;
        cachedHighScoreContainer = null;
        cachedToggleBtn = null;
    }

    private static void startBotLoop() {
        if (loopRunning) return;
        loopRunning = true;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            private long lastScanMs = 0L;

            @Override
            public void doFrame(long frameTimeNanos) {
                if (!loopRunning) return;
                try {
                    Activity act = activeActivity;
                    if (isModalActivity(act) && !act.isFinishing()) {
                        long nowMs = SystemClock.uptimeMillis();
                        if (!isArenaValid() && nowMs - lastScanMs >= 200) {
                            lastScanMs = nowMs;
                            findPongViews(act.getWindow().getDecorView());
                        }
                        if (isArenaValid()) {
                            ensureToggleButton(act);
                            if (enabled && !dialogOpen) {
                                setKeepScreenOn(true);
                                if (isLeaderboardOpen(act)) {
                                    wasLeaderboardOpen = true;
                                    prevBallCenterX = -1f;
                                    releaseTouch(act);
                                } else {
                                    if (wasLeaderboardOpen) {
                                        wasLeaderboardOpen = false;
                                        resumeAfterMs = nowMs + 300;
                                        prevBallCenterX = -1f;
                                        releaseTouch(act);
                                    }
                                    if (nowMs >= resumeAfterMs) {
                                        stepBot(act);
                                    }
                                }
                            } else {
                                prevBallCenterX = -1f;
                                releaseTouch(act);
                                setKeepScreenOn(false);
                            }
                        } else {
                            hideToggleButton();
                            prevBallCenterX = -1f;
                            releaseTouch(act);
                            setKeepScreenOn(false);
                        }
                    } else {
                        hideToggleButton();
                        prevBallCenterX = -1f;
                        releaseTouch(act);
                        setKeepScreenOn(false);
                    }
                } catch (Throwable ignored) {}
                if (loopRunning) {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        });
    }

    private static void ensureToggleButton(Activity act) {
        if (cachedArena == null || cachedHighScoreContainer == null) return;
        if (!(cachedArena.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) cachedArena.getParent();

        TextView btn = cachedToggleBtn;
        if (btn == null || !btn.isAttachedToWindow()) {
            View existing = parent.findViewWithTag(TOGGLE_TAG);
            if (existing instanceof TextView) {
                btn = (TextView) existing;
            } else {
                btn = new TextView(act);
                btn.setTag(TOGGLE_TAG);
                btn.setTextSize(12f);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setTextColor(Color.WHITE);
                btn.setPadding(24, 12, 24, 12);
                btn.setElevation(12f);
                btn.setOnClickListener(v -> {
                    enabled = !enabled;
                    updateToggleStyle(cachedToggleBtn);
                    if (!enabled) {
                        releaseTouch(act);
                        setKeepScreenOn(false);
                    } else {
                        prevBallCenterX = -1f;
                    }
                    Toast.makeText(act, enabled ? "Auto-Play ON (" + targetScore + ")" : "Auto-Play OFF", Toast.LENGTH_SHORT).show();
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                parent.addView(btn, lp);
            }
            cachedToggleBtn = btn;
        }

        updateToggleStyle(btn);
        btn.setVisibility(View.VISIBLE);

        if (btn.getWidth() == 0) {
            btn.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        }
        int btnW = btn.getWidth() > 0 ? btn.getWidth() : btn.getMeasuredWidth();
        int btnH = btn.getHeight() > 0 ? btn.getHeight() : btn.getMeasuredHeight();

        int[] hiLoc = new int[2];
        int[] parentLoc = new int[2];
        cachedHighScoreContainer.getLocationInWindow(hiLoc);
        parent.getLocationInWindow(parentLoc);

        float hiLeftInParent = hiLoc[0] - parentLoc[0];
        float hiTopInParent = hiLoc[1] - parentLoc[1];
        int hiH = cachedHighScoreContainer.getHeight();

        btn.setX(Math.max(16f, hiLeftInParent - btnW - 20f));
        btn.setY(Math.max(16f, hiTopInParent + (hiH - btnH) / 2f));
    }

    private static void updateToggleStyle(TextView btn) {
        if (btn == null) return;
        String label = enabled ? "Auto: ON" : "Auto: OFF";
        if (!label.contentEquals(btn.getText())) {
            btn.setText(label);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(40f);
        bg.setColor(enabled ? Color.parseColor("#CC16A34A") : Color.parseColor("#CCDC2626"));
        btn.setBackground(bg);
    }

    private static void hideToggleButton() {
        try {
            if (cachedToggleBtn != null) {
                cachedToggleBtn.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private static void setKeepScreenOn(boolean keepOn) {
        try {
            if (cachedArena != null && cachedArena.getKeepScreenOn() != keepOn) {
                cachedArena.setKeepScreenOn(keepOn);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isArenaValid() {
        return cachedArena != null
                && cachedScoreView != null
                && cachedBallView != null
                && cachedPaddleView != null
                && cachedArena.isAttachedToWindow()
                && cachedArena.isShown()
                && cachedBallView.isShown()
                && cachedPaddleView.isShown();
    }

    private static void findPongViews(View root) {
        cachedArena = null;
        cachedScoreView = null;
        cachedBallView = null;
        cachedPaddleView = null;
        cachedHighScoreContainer = null;
        if (root instanceof ViewGroup) {
            scanGroup((ViewGroup) root);
        }
    }

    private static boolean scanGroup(ViewGroup vg) {
        if (vg == null || vg.getVisibility() != View.VISIBLE) return false;

        TextView scoreCandidate = null;
        TextView ballCandidate = null;
        View paddleCandidate = null;
        View highScoreContainer = null;

        int childCount = vg.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = vg.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;

            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                CharSequence cs = tv.getText();
                String text = cs != null ? cs.toString().trim() : "";
                int w = tv.getWidth();
                int h = tv.getHeight();

                if (text.startsWith("HI ") || text.startsWith("HI\n")) {
                    highScoreContainer = tv;
                } else if (text.matches("\\d+") && h >= 220) {
                    scoreCandidate = tv;
                } else if (!text.isEmpty() && w > 0 && w <= 250 && h > 0 && h <= 250) {
                    ballCandidate = tv;
                }
            } else if (child instanceof ViewGroup) {
                if (containsHighScoreText((ViewGroup) child)) {
                    highScoreContainer = child;
                }
            } else if ("com.instagram.common.ui.base.IgView".equals(child.getClass().getName())) {
                int w = child.getWidth();
                int h = child.getHeight();
                if (w >= 120 && w <= 600 && h >= 40 && h <= 250) {
                    paddleCandidate = child;
                }
            }
        }

        if (highScoreContainer != null && scoreCandidate != null && ballCandidate != null && paddleCandidate != null) {
            cachedArena = vg;
            cachedScoreView = scoreCandidate;
            cachedBallView = ballCandidate;
            cachedPaddleView = paddleCandidate;
            cachedHighScoreContainer = highScoreContainer;
            return true;
        }

        for (int i = 0; i < childCount; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ViewGroup && scanGroup((ViewGroup) child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHighScoreText(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                if (cs != null && cs.toString().trim().startsWith("HI ")) {
                    return true;
                }
            } else if (c instanceof ViewGroup && containsHighScoreText((ViewGroup) c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLeaderboardOpen(Activity act) {
        if (act == null) return false;
        View root = act.getWindow().getDecorView();
        return hasVisibleText(root, "Leaderboard");
    }

    private static boolean hasVisibleText(View v, String target) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && target.equals(cs.toString().trim())) {
                return true;
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (hasVisibleText(vg.getChildAt(i), target)) return true;
            }
        }
        return false;
    }

    private static void stepBot(Activity act) {
        int currentScore = parseScore(cachedScoreView);
        if (currentScore < 0) return;

        int[] arenaLoc = new int[2];
        cachedArena.getLocationInWindow(arenaLoc);
        float arenaLeft = arenaLoc[0];
        float arenaWidth = cachedArena.getWidth();
        if (arenaWidth <= 0) return;

        int[] ballLoc = new int[2];
        cachedBallView.getLocationInWindow(ballLoc);
        float ballCenterX = ballLoc[0] + cachedBallView.getWidth() / 2f;

        float dx = 0f;
        if (prevBallCenterX >= 0f) {
            dx = ballCenterX - prevBallCenterX;
            if (Math.abs(dx) > 150f) dx = 0f;
        }
        prevBallCenterX = ballCenterX;

        int[] padLoc = new int[2];
        cachedPaddleView.getLocationInWindow(padLoc);
        float padHalfW = cachedPaddleView.getWidth() / 2f;
        float padCenterX = padLoc[0] + padHalfW;
        float padY = padLoc[1] + cachedPaddleView.getHeight() / 2f;

        float targetX;
        if (currentScore < targetScore) {
            float predictedX = ballCenterX + dx * 0.85f;
            targetX = Math.max(arenaLeft + padHalfW, Math.min(arenaLeft + arenaWidth - padHalfW, predictedX));
        } else {
            float arenaCenterX = arenaLeft + arenaWidth / 2f;
            if (ballCenterX < arenaCenterX) {
                targetX = arenaLeft + arenaWidth - padHalfW;
            } else {
                targetX = arenaLeft + padHalfW;
            }
        }

        if (touchDown) {
            if (Math.abs(padCenterX - targetX) > 60f) {
                stuckFrames++;
                if (stuckFrames >= 6) {
                    stuckFrames = 0;
                    releaseTouch(act);
                    return;
                }
            } else {
                stuckFrames = 0;
            }
        } else {
            stuckFrames = 0;
        }

        long now = SystemClock.uptimeMillis();
        dispatchingBotTouch = true;
        try {
            if (!touchDown) {
                downTime = now;
                MotionEvent down = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_DOWN, targetX, padY, 0);
                act.dispatchTouchEvent(down);
                down.recycle();
                touchDown = true;
            } else {
                MotionEvent move = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, targetX, padY, 0);
                act.dispatchTouchEvent(move);
                move.recycle();
            }
        } finally {
            dispatchingBotTouch = false;
        }
    }

    private static void dispatchTap(Activity act, float x, float y) {
        if (act == null || act.isFinishing()) return;
        long now = SystemClock.uptimeMillis();
        dispatchingBotTouch = true;
        try {
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            act.dispatchTouchEvent(down);
            down.recycle();
            MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0);
            act.dispatchTouchEvent(up);
            up.recycle();
        } finally {
            dispatchingBotTouch = false;
        }
    }

    private static void releaseTouch(Activity act) {
        if (!touchDown) return;
        touchDown = false;
        stuckFrames = 0;
        if (act != null && !act.isFinishing()) {
            long now = SystemClock.uptimeMillis();
            dispatchingBotTouch = true;
            try {
                MotionEvent up = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_UP, 0f, 0f, 0);
                act.dispatchTouchEvent(up);
                up.recycle();
            } finally {
                dispatchingBotTouch = false;
            }
        }
    }

    private static int parseScore(TextView tv) {
        try {
            CharSequence cs = tv.getText();
            if (cs == null) return -1;
            return Integer.parseInt(cs.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void showLogsDialog(Activity act) {
        StringBuilder sb = new StringBuilder();
        for (String line : logBuffer) {
            sb.append(line).append("\n");
        }
        String text = sb.length() > 0 ? sb.toString() : "(No logs captured yet)";

        ScrollView scroll = new ScrollView(act);
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(10f);
        tv.setPadding(28, 28, 28, 28);
        scroll.addView(tv);

        new AlertDialog.Builder(act)
                .setTitle("IG-Emoji-Score Logs")
                .setView(scroll)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("IG-Emoji-Score Logs", text));
                        Toast.makeText(act, "Logs copied!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Clear", (d, w) -> logBuffer.clear())
                .setNegativeButton("Close", null)
                .show();
    }

    private static void promptScore(Activity act) {
        dialogOpen = true;
        releaseTouch(act);

        final boolean openedLeaderboard;
        if (isArenaValid() && !isLeaderboardOpen(act) && cachedHighScoreContainer != null) {
            cachedHighScoreContainer.performClick();
            openedLeaderboard = true;
        } else {
            openedLeaderboard = false;
        }

        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(targetScore));
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Auto-Play Target Score")
                .setView(et)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        targetScore = Integer.parseInt(et.getText().toString().trim());
                        enabled = true;
                        updateToggleStyle(cachedToggleBtn);
                        Toast.makeText(act, "Auto-Play Target: " + targetScore, Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException ignored) {}
                })
                .setNeutralButton("Logs", (d, w) -> showLogsDialog(act))
                .setNegativeButton("Off", (d, w) -> {
                    enabled = false;
                    updateToggleStyle(cachedToggleBtn);
                    releaseTouch(act);
                    setKeepScreenOn(false);
                    Toast.makeText(act, "Auto-Play Off", Toast.LENGTH_SHORT).show();
                })
                .create();

        dlg.setOnDismissListener(d -> {
            dialogOpen = false;
            prevBallCenterX = -1f;
            releaseTouch(act);
            resumeAfterMs = SystemClock.uptimeMillis() + 350;
            if (openedLeaderboard && isLeaderboardOpen(act) && cachedArena != null) {
                mainHandler.postDelayed(() -> {
                    if (isLeaderboardOpen(act) && cachedArena != null) {
                        dispatchTap(act, cachedArena.getWidth() / 2f, 250f);
                    }
                }, 80);
            }
        });
        dlg.show();
    }
}
