package com.timejump.lsposed;

import android.app.Activity;
import android.util.Log;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.io.File;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "TimeJumpInjector";
    private static final String TARGET_PACKAGE = "com.dts.freefireth";
    
    // Shared time offset in MILLISECONDS
    public static volatile long timeOffsetMs = 0;
    
    // Track current foreground activity
    public static volatile Activity currentActivity = null;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        Log.d(TAG, "Target game loaded: " + lpparam.packageName);
        
        if (!lpparam.packageName.equals(lpparam.processName)) {
            return;
        }

        Log.d(TAG, "=== TimeJump Injection Starting ===");
        
        // Track Current Activity
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    currentActivity = (Activity) param.thisObject;
                    Log.d(TAG, "Current Activity updated: " + currentActivity.getClass().getName());
                    
                    // Only auto-start if the user enabled it (flag file created by MainActivity)
                    File autoMacroFlag = new File("/data/local/tmp/tj_auto_macro");
                    if (autoMacroFlag.exists()) {
                        // Automatically start the TimeJump AutoClickerService in the background
                        try {
                            android.content.Intent intent = new android.content.Intent();
                            intent.setComponent(new android.content.ComponentName("com.timejump.lsposed", "com.timejump.lsposed.AutoClickerService"));
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                currentActivity.startForegroundService(intent);
                            } else {
                                currentActivity.startService(intent);
                            }
                            Log.d(TAG, "Sent intent to start AutoClickerService automatically");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to auto-start AutoClickerService", e);
                        }
                    } else {
                        Log.d(TAG, "Auto-macro disabled (flag file not found), skipping auto-start");
                    }
                }
            });
            Log.d(TAG, "✓ Hooked Activity.onResume for UI tracking");
        } catch (Throwable t) {
            Log.e(TAG, "✗ Failed to hook Activity.onResume", t);
        }

        // ============================================================
        // JAVA-LEVEL TIME HOOKS
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(System.class, "currentTimeMillis", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (timeOffsetMs != 0) param.setResult((Long) param.getResult() + timeOffsetMs);
                }
            });
            XposedHelpers.findAndHookMethod(System.class, "nanoTime", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (timeOffsetMs != 0) param.setResult((Long) param.getResult() + (timeOffsetMs * 1000000L));
                }
            });
            XposedHelpers.findAndHookMethod(SystemClock.class, "elapsedRealtime", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (timeOffsetMs != 0) param.setResult((Long) param.getResult() + timeOffsetMs);
                }
            });
            XposedHelpers.findAndHookMethod(SystemClock.class, "uptimeMillis", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (timeOffsetMs != 0) param.setResult((Long) param.getResult() + timeOffsetMs);
                }
            });
            XposedHelpers.findAndHookMethod(SystemClock.class, "elapsedRealtimeNanos", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (timeOffsetMs != 0) param.setResult((Long) param.getResult() + (timeOffsetMs * 1000000L));
                }
            });
            Log.d(TAG, "✓ Java Time Hooks Applied");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply some Java time hooks", t);
        }
        
        // ============================================================
        // NATIVE HOOKS + LUA ENGINE
        // ============================================================
        try {
            File scriptFile = new File("/data/local/tmp/timejump_script.lua");
            String script = "";
            if (scriptFile.exists() && scriptFile.canRead()) {
                StringBuilder sb = new StringBuilder();
                java.util.Scanner scanner = new java.util.Scanner(scriptFile);
                while (scanner.hasNextLine()) sb.append(scanner.nextLine()).append("\n");
                scanner.close();
                script = sb.toString();
            }

            File soFile = new File("/data/local/tmp/libTimeJump.so");
            if (soFile.exists() && soFile.canRead()) {
                System.load(soFile.getAbsolutePath());
            } else {
                System.loadLibrary("TimeJump");
            }

            if (script != null && !script.isEmpty()) {
                final String finalScript = script;
                new Thread(() -> initLua(finalScript)).start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize Lua/Native/OpenCV", t);
        }
    }
    
    // Called FROM native code to update time
    public static void updateTimeOffset(long offsetMs) {
        timeOffsetMs = offsetMs;
    }
    
    // ============================================================
    // UI AUTOMATION (AUTO CLICKER)
    // ============================================================
    
    // Called FROM native code (Lua) - Image Matcher (Root-based)
    public static boolean clickImage(String imageName) {
        try {
            return ImageMatcher.findAndClickImage(imageName);
        } catch (Exception e) {
            Log.e(TAG, "clickImage error", e);
            return false;
        }
    }
    
    // Called FROM native code (Lua) - Text Search
    public static boolean clickButtonByText(String targetText) {
        final Activity activity = currentActivity;
        if (activity == null) {
            Log.e(TAG, "clickButtonByText failed: No current activity tracked.");
            return false;
        }

        try {
            final View rootView = activity.getWindow().getDecorView();
            return findAndClickText(rootView, targetText.toLowerCase(), activity);
        } catch (Exception e) {
            Log.e(TAG, "Error traversing view tree", e);
            return false;
        }
    }

    private static boolean findAndClickText(View view, String targetText, Activity activity) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString().toLowerCase();
            if (text.contains(targetText)) {
                return performClickOnViewOrParent(view, activity);
            }
        }
        
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findAndClickText(group.getChildAt(i), targetText, activity)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean performClickOnViewOrParent(View view, Activity activity) {
        View current = view;
        while (current != null) {
            if (current.isClickable()) {
                final View target = current;
                activity.runOnUiThread(() -> {
                    Log.d(TAG, "Performing click on view: " + target);
                    target.performClick();
                });
                return true;
            }
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        // Fallback: click the textview itself even if it doesn't report as clickable
        final View targetFallback = view;
        activity.runOnUiThread(() -> {
            Log.d(TAG, "Performing fallback click on view: " + targetFallback);
            targetFallback.performClick();
        });
        return true;
    }
    
    // Native method - passes script to C++ Lua engine
    public native void initLua(String script);
}
