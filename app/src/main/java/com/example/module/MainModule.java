package com.example.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.libxposed.api.XposedModule;

public class MainModule extends XposedModule {

    private static final String TAG = "IG-Emoji-Score";
    private static final int MAX_LOG_LINES = 250;
    private static volatile int targetScore = 99;
    private static volatile boolean enabled = true;
    private static volatile long captureUntilMs = 0;
    private static final Set<String> seenActivities = ConcurrentHashMap.newKeySet();
    private static final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static volatile Activity activeActivity = null;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void addLog(String msg) {
        logBuffer.addLast(msg);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.pollFirst();
        }
        try {
            log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {}
    }

    private boolean isCapturing() {
        return System.currentTimeMillis() < captureUntilMs;
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"com.instagram.android".equals(param.getPackageName())) return;

        ClassLoader cl = param.getClassLoader();
        addLog("onPackageReady: hooking com.instagram.android");

        try {
            // 1. Track active Activity & bind Volume Up to open menu
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Activity act = (Activity) chain.getThisObject();
                activeActivity = act;
                if (act != null && seenActivities.add(act.getClass().getName())) {
                    addLog("Activity resumed: " + act.getClass().getName());
                }
                return result;
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

            // 2. Capture TextView.setText during 5s window to find score updates
            Method setText = TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class);
            hook(setText).intercept(chain -> {
                if (isCapturing()) {
                    CharSequence cs = (CharSequence) chain.getArg(0);
                    if (cs != null && cs.length() > 0 && cs.length() <= 24) {
                        String s = cs.toString().trim();
                        if (s.matches("\\d+") || s.toLowerCase().contains("score") || s.toLowerCase().contains("best")) {
                            addLog("TextView.setText(\"" + s + "\") on " + chain.getThisObject().getClass().getName()
                                    + " caller=" + getAppCaller());
                        }
                    }
                }
                return chain.proceed();
            });

            // 3. Capture & override SharedPreferences writes
            Class<?> editor = cl.loadClass("android.app.SharedPreferencesImpl$EditorImpl");
            Method putInt = editor.getDeclaredMethod("putInt", String.class, int.class);
            hook(putInt).intercept(chain -> {
                String key = (String) chain.getArg(0);
                int val = (int) chain.getArg(1);
                if (key != null) {
                    String low = key.toLowerCase();
                    if (isCapturing() || low.contains("pong") || low.contains("emoji") || low.contains("score")) {
                        addLog("Prefs.putInt: key=" + key + " val=" + val);
                    }
                    if (enabled && (low.contains("emoji_pong") || low.contains("pong_high_score")
                            || (low.contains("pong") && low.contains("score")))) {
                        addLog("Prefs.putInt OVERRIDE: " + key + " -> " + targetScore);
                        return chain.proceed(new Object[]{key, targetScore});
                    }
                }
                return chain.proceed();
            });

            Method putLong = editor.getDeclaredMethod("putLong", String.class, long.class);
            hook(putLong).intercept(chain -> {
                String key = (String) chain.getArg(0);
                long val = (long) chain.getArg(1);
                if (key != null) {
                    String low = key.toLowerCase();
                    if (isCapturing() || low.contains("pong") || low.contains("emoji") || low.contains("score")) {
                        addLog("Prefs.putLong: key=" + key + " val=" + val);
                    }
                    if (enabled && (low.contains("emoji_pong") || low.contains("pong_high_score")
                            || (low.contains("pong") && low.contains("score")))) {
                        addLog("Prefs.putLong OVERRIDE: " + key + " -> " + targetScore);
                        return chain.proceed(new Object[]{key, (long) targetScore});
                    }
                }
                return chain.proceed();
            });

        } catch (Throwable t) {
            addLog("Hook setup error: " + t.getMessage());
        }
    }

    private static String getAppCaller() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : st) {
            String cn = el.getClassName();
            if (cn.startsWith("X.") || cn.startsWith("com.instagram.")) {
                return cn + "." + el.getMethodName() + ":" + el.getLineNumber();
            }
        }
        return "?";
    }

    private void dumpViewHierarchy(Activity act) {
        try {
            View root = act.getWindow().getDecorView();
            StringBuilder sb = new StringBuilder();
            sb.append("=== ViewTree (").append(act.getClass().getSimpleName()).append(") ===\n");
            walkView(root, 0, sb, new int[]{0});
            addLog(sb.toString());
        } catch (Throwable t) {
            addLog("dumpViewHierarchy err: " + t.getMessage());
        }
    }

    private void walkView(View v, int depth, StringBuilder sb, int[] count) {
        if (v == null || v.getVisibility() != View.VISIBLE || count[0] >= 80) return;
        if (v.getWidth() == 0 && v.getHeight() == 0 && depth > 2) return;
        count[0]++;

        for (int i = 0; i < depth; i++) sb.append(" ");
        sb.append(v.getClass().getName())
                .append(" [").append(v.getWidth()).append("x").append(v.getHeight()).append("]");
        if (v.getTranslationX() != 0f || v.getTranslationY() != 0f) {
            sb.append(" tx=").append((int) v.getTranslationX()).append(" ty=").append((int) v.getTranslationY());
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                String s = t.toString().replace("\n", " ");
                if (s.length() > 20) s = s.substring(0, 20) + "...";
                sb.append(" \"").append(s).append("\"");
            }
        }
        sb.append("\n");

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                walkView(vg.getChildAt(i), depth + 1, sb, count);
            }
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
        int pad = 28;
        tv.setPadding(pad, pad, pad, pad);
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

    private void promptScore(Activity act) {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(targetScore));
        new AlertDialog.Builder(act)
                .setTitle("Custom Emoji Score")
                .setView(et)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        targetScore = Integer.parseInt(et.getText().toString().trim());
                        enabled = true;
                        Toast.makeText(act, "Target: " + targetScore, Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException ignored) {}
                })
                .setNeutralButton("Logs (5s)", (d, w) -> {
                    captureUntilMs = System.currentTimeMillis() + 5000;
                    dumpViewHierarchy(act);
                    Toast.makeText(act, "Capturing 5s...", Toast.LENGTH_SHORT).show();
                    mainHandler.postDelayed(() -> {
                        Activity cur = activeActivity;
                        if (cur != null && !cur.isFinishing()) {
                            showLogsDialog(cur);
                        }
                    }, 5000);
                })
                .setNegativeButton("Off", (d, w) -> enabled = false)
                .show();
    }
}
