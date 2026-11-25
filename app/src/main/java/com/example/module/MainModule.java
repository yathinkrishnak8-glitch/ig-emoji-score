package com.example.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

public class MainModule extends XposedModule {

    private static MainModule instance;
    private static volatile int targetScore = 999;
    private static volatile boolean enabled = true;
    private static final Set<String> hookedClasses = new HashSet<>();
    private static Activity activeActivity = null;

    public MainModule(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
        instance = this;
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);
        if (!"com.instagram.android".equals(param.getPackageName())) return;

        ClassLoader cl = param.getClassLoader();

        try {
            // 1. Track active Activity & bind Volume Up to open custom score prompt
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume, ActivityResumeHooker.class);

            Method dispatchKey = Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class);
            hook(dispatchKey, KeyEventHooker.class);

            // 2. Catch Emoji Pong View when attached to window
            Method onAttached = View.class.getDeclaredMethod("onAttachedToWindow");
            hook(onAttached, ViewAttachHooker.class);

            // 3. Override local SharedPreferences high score writes
            Class<?> editor = cl.loadClass("android.app.SharedPreferencesImpl$EditorImpl");
            Method putInt = editor.getDeclaredMethod("putInt", String.class, int.class);
            hook(putInt, PrefsPutIntHooker.class);

        } catch (Throwable t) {
            log("Hook setup error: " + t.getMessage());
        }
    }

    @XposedHooker
    public static class ActivityResumeHooker implements XposedInterface.Hooker {
        @AfterInvocation
        public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
            activeActivity = (Activity) callback.getThisObject();
        }
    }

    @XposedHooker
    public static class KeyEventHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            KeyEvent ev = (KeyEvent) callback.getArgs()[0];
            if (ev.getAction() == KeyEvent.ACTION_DOWN
                    && ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                    && activeActivity != null) {
                promptScore(activeActivity);
                callback.returnAndSkip(true);
            }
        }
    }

    @XposedHooker
    public static class PrefsPutIntHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            String key = (String) callback.getArgs()[0];
            if (enabled && key != null && (key.contains("emoji_pong") || key.contains("pong_high_score"))) {
                callback.getArgs()[1] = targetScore;
            }
        }
    }

    @XposedHooker
    public static class ViewAttachHooker implements XposedInterface.Hooker {
        @AfterInvocation
        public static void after(@NonNull XposedInterface.AfterHookCallback callback) {
            Object view = callback.getThisObject();
            if (view == null) return;
            Class<?> c = view.getClass();
            String name = c.getName();
            if (!hookedClasses.contains(name) && looksLikePongView(c)) {
                hookedClasses.add(name);
                hookPongMethods(c);
            }
        }
    }

    @XposedHooker
    public static class SingleIntArgHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            if (enabled && (int) callback.getArgs()[0] > 0) {
                callback.getArgs()[0] = targetScore;
            }
        }
    }

    @XposedHooker
    public static class ThreadScoreHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            if (enabled && (int) callback.getArgs()[1] > 0) {
                callback.getArgs()[1] = targetScore;
            }
        }
    }

    @XposedHooker
    public static class VoidGameOverHooker implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void before(@NonNull XposedInterface.BeforeHookCallback callback) {
            Object obj = callback.getThisObject();
            if (!enabled || obj == null) return;
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    int cur = f.getInt(obj);
                    if (cur > 0 && cur < 400) {
                        f.setInt(obj, targetScore);
                    }
                } catch (Throwable ignored) {}
            }
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
            else if (t.getName().contains("Paint") || t.getName().contains("RectF") || t.getName().contains("Animator")) {
                gfx = true;
            }
        }
        return gfx && floats >= 4 && ints >= 1 && ints <= 6;
    }

    private static void hookPongMethods(Class<?> c) {
        if (instance == null) return;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 1 && p[0] == int.class) {
                    instance.hook(m, SingleIntArgHooker.class);
                } else if (p.length == 2 && p[0] == String.class && p[1] == int.class) {
                    instance.hook(m, ThreadScoreHooker.class);
                } else if (p.length == 0 && m.getReturnType() == void.class) {
                    instance.hook(m, VoidGameOverHooker.class);
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
