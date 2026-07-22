package com.timejump.lsposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.DataOutputStream;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "TimeJumpInjector";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot Completed! Checking for Auto-Boot App...");
            
            SharedPreferences prefs = context.getSharedPreferences("TimeJumpPrefs", Context.MODE_PRIVATE);
            
            // Check if auto-boot is enabled
            boolean autoBootEnabled = prefs.getBoolean("auto_start_boot", true);
            if (!autoBootEnabled) {
                Log.i(TAG, "Auto-Boot is disabled by user. Skipping.");
                return;
            }
            
            String targetApp = prefs.getString("boot_app_package", "");

            if (!targetApp.isEmpty()) {
                Log.i(TAG, "Auto-Boot target found: " + targetApp);
                
                // Run in a background thread so we don't block the receiver
                new Thread(() -> {
                    try {
                        // Wait 10 seconds to allow the system to fully stabilize after boot
                        Thread.sleep(10000);
                        
                        Log.i(TAG, "Launching " + targetApp + " via ROOT...");
                        Process p = Runtime.getRuntime().exec("su");
                        DataOutputStream os = new DataOutputStream(p.getOutputStream());
                        // Use monkey to launch the main launcher activity of the package
                        os.writeBytes("monkey -p " + targetApp + " -c android.intent.category.LAUNCHER 1\n");
                        os.writeBytes("exit\n");
                        os.flush();
                        p.waitFor();
                        
                        Log.i(TAG, "Launch command executed.");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch app on boot", e);
                    }
                }).start();
            } else {
                Log.i(TAG, "No Auto-Boot app configured.");
            }
        }
    }
}
