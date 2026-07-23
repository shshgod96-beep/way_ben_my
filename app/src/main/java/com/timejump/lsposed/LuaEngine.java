package com.timejump.lsposed;

import android.util.Log;

/**
 * LuaEngine - Runs Lua scripts natively inside OUR app process.
 * Lua functions like timeJump() write to /data/local/tmp/tj_offset (file IPC),
 * and clickButton()/clickImage() call back to Java for root-based UI actions.
 */
public class LuaEngine {
    private static final String TAG = "TimeJumpInjector";

    static {
        System.loadLibrary("LuaBridge");
        Log.i(TAG, "LuaBridge native library loaded.");
    }

    /**
     * Run a Lua script. Returns "OK" on success, or an error string on failure.
     * This is a BLOCKING call - run it on a background thread!
     */
    public static native String runScript(String script);

    // ==================== Callbacks from native Lua ====================

    /**
     * Called from native Lua clickButton() function.
     * Uses root 'input tap' after finding element via accessibility or coordinates.
     * For now, we use a simple root-based approach.
     */
    public static boolean onClickButton(String text) {
        // Obsolete in pure root architecture. Handled by AutoClickerService.
        Log.i(TAG, "LuaEngine: clickButton('" + text + "') is not supported in this version. Use image macros.");
        return false;
    }

    /**
     * Called from native Lua clickImage() function.
     * Takes screenshot via root, finds image match, and taps.
     */
    public static boolean onClickImage(String imageName) {
        // Obsolete in pure root architecture. Handled by AutoClickerService.
        Log.i(TAG, "LuaEngine: clickImage('" + imageName + "') is not supported in this version. Use image macros.");
        return false;
    }
}
