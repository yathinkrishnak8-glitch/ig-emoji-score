package com.example.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.libxposed.api.XposedModule;

public class MainModule extends XposedModule {

    private static final String TAG = "IG-Emoji-Score";
    private static final int MAX_LOG_LINES = 150;
    private static volatile int targetScore = 99;
    private static volatile boolean enabled = true;
    private static final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static volatile Activity activeActivity = null;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static boolean loopRunning = false;
    private static boolean touchDown = false;
    private static long downTime = 0L;
    private static ViewGroup cachedArena = null;
    private static TextView cachedScoreView = null;
    private static TextView cachedBallView = null;
    private static View cachedPaddleView = null;

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

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                activeActivity = (Activity) chain.getThisObject();
                startBotLoop();
                return result;
            });

            Method onPause = Activity.class.getDeclaredMethod("onPause");
            hook(onPause).intercept(chain -> {
                releaseTouch(activeActivity);
                return chain.proceed();
            });

            Method dispatchKey = Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class);
            hook(dispatchKey).intercept(chain -> {
                KeyEvent ev = (KeyEvent) chain.getArg(0);
                if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN
                        && ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                        && activeActivity != null) {
                    promptScore(activeActivity);
                    return true;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            addLog("Hook setup error: " + t.getMessage());
        }
    }

    private static void startBotLoop() {
        if (loopRunning) return;
        loopRunning = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                long delayMs = 200;
                try {
                    Activity act = activeActivity;
                    if (enabled && act != null && !act.isFinishing()) {
                        if (!isArenaValid()) {
                            findPongViews(act.getWindow().getDecorView());
                        }
                        if (isArenaValid()) {
                            stepBot(act);
                            delayMs = 16; // ~60fps while Emoji Pong is active
                        } else {
                            releaseTouch(act);
                        }
                    } else {
                        releaseTouch(act);
                    }
                } catch (Throwable ignored) {}
                mainHandler.postDelayed(this, delayMs);
            }
        });
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
        if (root instanceof ViewGroup) {
            scanGroup((ViewGroup) root);
        }
    }

    private static boolean scanGroup(ViewGroup vg) {
        if (vg == null || vg.getVisibility() != View.VISIBLE) return false;

        TextView scoreCandidate = null;
        TextView ballCandidate = null;
        View paddleCandidate = null;
        boolean hasHighScoreLabel = false;

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
                    hasHighScoreLabel = true;
                } else if (text.matches("\\d+") && h >= 220) {
                    scoreCandidate = tv;
                } else if (!text.isEmpty() && w > 0 && w <= 250 && h > 0 && h <= 250) {
                    ballCandidate = tv;
                }
            } else if (child instanceof ViewGroup) {
                if (containsHighScoreText((ViewGroup) child)) {
                    hasHighScoreLabel = true;
                }
            } else if ("com.instagram.common.ui.base.IgView".equals(child.getClass().getName())) {
                int w = child.getWidth();
                int h = child.getHeight();
                if (w >= 120 && w <= 600 && h >= 40 && h <= 250) {
                    paddleCandidate = child;
                }
            }
        }

        if (hasHighScoreLabel && scoreCandidate != null && ballCandidate != null && paddleCandidate != null) {
            cachedArena = vg;
            cachedScoreView = scoreCandidate;
            cachedBallView = ballCandidate;
            cachedPaddleView = paddleCandidate;
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

        int[] padLoc = new int[2];
        cachedPaddleView.getLocationInWindow(padLoc);
        float padY = padLoc[1] + cachedPaddleView.getHeight() / 2f;
        float padHalfW = cachedPaddleView.getWidth() / 2f;

        float targetX;
        if (currentScore < targetScore) {
            // Track ball X
            targetX = Math.max(arenaLeft + padHalfW, Math.min(arenaLeft + arenaWidth - padHalfW, ballCenterX));
        } else {
            // Auto-lose: dodge to the opposite side from the ball
            float arenaCenterX = arenaLeft + arenaWidth / 2f;
            if (ballCenterX < arenaCenterX) {
                targetX = arenaLeft + arenaWidth - padHalfW;
            } else {
                targetX = arenaLeft + padHalfW;
            }
        }

        long now = SystemClock.uptimeMillis();
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
    }

    private static void releaseTouch(Activity act) {
        if (!touchDown) return;
        touchDown = false;
        if (act != null && !act.isFinishing()) {
            long now = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_UP, 0f, 0f, 0);
            act.dispatchTouchEvent(up);
            up.recycle();
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
        releaseTouch(act);
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(targetScore));
        new AlertDialog.Builder(act)
                .setTitle("Auto-Play Target Score")
                .setView(et)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        targetScore = Integer.parseInt(et.getText().toString().trim());
                        enabled = true;
                        Toast.makeText(act, "Auto-Play Target: " + targetScore, Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException ignored) {}
                })
                .setNeutralButton("Logs", (d, w) -> showLogsDialog(act))
                .setNegativeButton("Off", (d, w) -> {
                    enabled = false;
                    releaseTouch(act);
                    Toast.makeText(act, "Auto-Play Off", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
