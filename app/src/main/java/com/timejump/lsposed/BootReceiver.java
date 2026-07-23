package com.timejump.lsposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "TimeJumpInjector";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            
            Log.i(TAG, "Boot Completed received!");
            SharedPreferences globalPrefs = context.getSharedPreferences("TimeJumpPrefs", Context.MODE_PRIVATE);

            // 1. Start AutoClickerService if enabled
            if (globalPrefs.getBoolean("auto_start_boot", true)) {
                try {
                    Intent serviceIntent = new Intent(context, AutoClickerService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Log.i(TAG, "AutoClickerService started on boot.");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start AutoClickerService on boot", e);
                }
            }

            // 2. Launch specified app if set
            String bootApp = globalPrefs.getString("boot_app_package", "");
            if (!bootApp.isEmpty()) {
                try {
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(bootApp);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        Log.i(TAG, "Launched app on boot: " + bootApp);
                    } else {
                        Log.e(TAG, "Launch intent null for package: " + bootApp);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch app on boot", e);
                }
            }
        }
    }
}

