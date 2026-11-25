package com.example.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

public class MainModule extends XposedModule {

    private static final String TAG = "IG-Emoji-Score";
    private static volatile int targetScore = 99;
    private static volatile boolean enabled = true;
    private static final Set<String> checkedClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> seenActivities = ConcurrentHashMap.newKeySet();
    private static volatile Activity activeActivity = null;

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"com.instagram.android".equals(param.getPackageName())) return;

        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "onPackageReady: hooking com.instagram.android");

        try {
            // 1. Track active Activity & bind Volume Up to open custom score prompt
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Activity act = (Activity) chain.getThisObject();
                activeActivity = act;
                if (act != null && seenActivities.add(act.getClass().getName())) {
                    log(Log.INFO, TAG, "Activity resumed: " + act.getClass().getName());
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

            // 2. Catch & log Emoji Pong View when attached to window
            Method onAttached = View.class.getDeclaredMethod("onAttachedToWindow");
            hook(onAttached).intercept(chain -> {
                Object result = chain.proceed();
                Object view = chain.getThisObject();
                if (view != null) {
                    Class<?> c = view.getClass();
                    if (checkedClasses.add(c.getName())) {
                        String low = c.getName().toLowerCase();
                        boolean nameMatch = low.contains("pong") || low.contains("emoji_game") || low.contains("emojipong");
                        if (nameMatch || looksLikePongView(c)) {
                            dumpClassInfo(c);
                            hookPongMethods(c);
                        }
                    }
                }
                return result;
            });

            // 3. Log & override local SharedPreferences high score writes
            Class<?> editor = cl.loadClass("android.app.SharedPreferencesImpl$EditorImpl");
            Method putInt = editor.getDeclaredMethod("putInt", String.class, int.class);
            hook(putInt).intercept(chain -> {
                String key = (String) chain.getArg(0);
                int val = (int) chain.getArg(1);
                if (key != null) {
                    String low = key.toLowerCase();
                    if (low.contains("pong") || low.contains("emoji") || low.contains("score")) {
                        log(Log.INFO, TAG, "Prefs.putInt: key=" + key + " val=" + val);
                    }
                    if (enabled && (low.contains("emoji_pong") || low.contains("pong_high_score")
                            || (low.contains("pong") && low.contains("score")))) {
                        log(Log.INFO, TAG, "Prefs.putInt OVERRIDE: " + key + " -> " + targetScore);
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
                    if (low.contains("pong") || low.contains("emoji") || low.contains("score")) {
                        log(Log.INFO, TAG, "Prefs.putLong: key=" + key + " val=" + val);
                    }
                    if (enabled && (low.contains("emoji_pong") || low.contains("pong_high_score")
                            || (low.contains("pong") && low.contains("score")))) {
                        log(Log.INFO, TAG, "Prefs.putLong OVERRIDE: " + key + " -> " + targetScore);
                        return chain.proceed(new Object[]{key, (long) targetScore});
                    }
                }
                return chain.proceed();
            });

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook setup error", t);
        }
    }

    private static boolean looksLikePongView(Class<?> c) {
        String low = c.getName().toLowerCase();
        if (low.contains("emojipong") || low.contains("emoji_pong")) return true;
        if (!c.getName().startsWith("X.")) return false;
        int floats = 0, ints = 0;
        boolean gfx = false;
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == float.class) floats++;
            else if (t == int.class) ints++;
            else {
                String tn = t.getName();
                if (tn.contains("Paint") || tn.contains("RectF") || tn.contains("Animator")
                        || tn.contains("Canvas") || tn.contains("Path") || tn.contains("Drawable")
                        || tn.contains("Bitmap")) {
                    gfx = true;
                }
            }
        }
        return gfx && floats >= 2 && ints >= 1 && ints <= 15;
    }

    private void dumpClassInfo(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        sb.append("PongCandidate class=").append(c.getName())
                .append(" super=").append(c.getSuperclass() != null ? c.getSuperclass().getName() : "null")
                .append("\nFields:");
        for (Field f : c.getDeclaredFields()) {
            sb.append(" ").append(f.getType().getSimpleName()).append(":").append(f.getName());
        }
        sb.append("\nMethods:");
        for (Method m : c.getDeclaredMethods()) {
            sb.append(" ").append(m.getReturnType().getSimpleName()).append(" ")
                    .append(m.getName()).append(Arrays.toString(m.getParameterTypes()));
        }
        log(Log.INFO, TAG, sb.toString());
    }

    private void hookPongMethods(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 1 && p[0] == int.class) {
                    hook(m).intercept(chain -> {
                        int arg = (int) chain.getArg(0);
                        log(Log.INFO, TAG, c.getName() + "." + m.getName() + "(int=" + arg + ")");
                        if (enabled && arg > 0) {
                            return chain.proceed(new Object[]{targetScore});
                        }
                        return chain.proceed();
                    });
                } else if (p.length == 2 && p[0] == String.class && p[1] == int.class) {
                    hook(m).intercept(chain -> {
                        int arg = (int) chain.getArg(1);
                        log(Log.INFO, TAG, c.getName() + "." + m.getName() + "(str=" + chain.getArg(0) + ", int=" + arg + ")");
                        if (enabled && arg > 0) {
                            return chain.proceed(new Object[]{chain.getArg(0), targetScore});
                        }
                        return chain.proceed();
                    });
                } else if (p.length == 0 && m.getReturnType() == void.class) {
                    hook(m).intercept(chain -> {
                        Object obj = chain.getThisObject();
                        if (enabled && obj != null) {
                            for (Field f : obj.getClass().getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                                try {
                                    f.setAccessible(true);
                                    int cur = f.getInt(obj);
                                    if (cur > 0 && cur < 400) {
                                        log(Log.INFO, TAG, c.getName() + "." + m.getName() + "() field " + f.getName() + "=" + cur + " -> " + targetScore);
                                        f.setInt(obj, targetScore);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                        return chain.proceed();
                    });
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void promptScore(Context ctx) {
        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(targetScore));
        new AlertDialog.Builder(ctx)
                .setTitle("Custom Emoji Score")
                .setView(et)
                .setPositiveButton("Set", (d, w) -> {
                    try {
                        targetScore = Integer.parseInt(et.getText().toString().trim());
                        enabled = true;
                        Toast.makeText(ctx, "Target: " + targetScore, Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException ignored) {}
                })
                .setNegativeButton("Off", (d, w) -> enabled = false)
                .show();
    }
}
