package com.timejump.lsposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.DataOutputStream;

public class FloatingCoordService extends Service {

    private WindowManager windowManager;
    private View floatingView;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("tj_float", "TimeJump Float", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, "tj_float")
                    .setContentTitle("Coordinate Picker Active")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .build();
            startForeground(7778, notification);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Build the floating layout programmatically
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackgroundColor(Color.TRANSPARENT);
        layout.setGravity(Gravity.CENTER);

        // Crosshair Text
        TextView tvCrosshair = new TextView(this);
        tvCrosshair.setText("🎯");
        tvCrosshair.setTextSize(24); // Smaller crosshair
        tvCrosshair.setPadding(8, 8, 8, 8);
        tvCrosshair.setGravity(Gravity.CENTER);
        layout.addView(tvCrosshair);

        // Save Button (Just a checkmark)
        TextView btnSave = new TextView(this);
        btnSave.setText("✅");
        btnSave.setTextSize(20);
        btnSave.setPadding(16, 12, 16, 12);
        btnSave.setBackgroundColor(Color.parseColor("#AA000000")); // Small dark background for the save button
        layout.addView(btnSave);

        floatingView = layout;

        // WindowManager Parameters
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : 
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 100;

        windowManager.addView(floatingView, params);

        // Make it draggable
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        // Save Button Logic
        btnSave.setOnClickListener(v -> {
            try {
                // Calculate center point of the crosshair on screen
                int[] location = new int[2];
                tvCrosshair.getLocationOnScreen(location);
                int targetX = location[0] + (tvCrosshair.getWidth() / 2);
                int targetY = location[1] + (tvCrosshair.getHeight() / 2);

                // Save to SharedPreferences and create .coord file
                String TARGETS_DIR = "/data/local/tmp/targets";
                String name = "target_" + System.currentTimeMillis() + ".coord";
                
                // Use root to create empty file
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                os.writeBytes("touch " + TARGETS_DIR + "/" + name + "\n");
                os.writeBytes("chmod 666 " + TARGETS_DIR + "/" + name + "\n");
                os.writeBytes("exit\n");
                os.flush();
                p.waitFor();

                SharedPreferences globalPrefs = getSharedPreferences("TimeJumpPrefs", Context.MODE_PRIVATE);
                String configJson = globalPrefs.getString("target_configs", "{}");
                JSONObject configs = new JSONObject(configJson);
                
                JSONObject newCfg = new JSONObject();
                newCfg.put("x", targetX);
                newCfg.put("y", targetY);
                configs.put(name, newCfg);
                
                globalPrefs.edit().putString("target_configs", configs.toString()).apply();

                // Notify MainActivity to refresh list
                Intent refreshIntent = new Intent("com.timejump.TARGET_ADDED");
                sendBroadcast(refreshIntent);

                Toast.makeText(FloatingCoordService.this, "✅ Saved: " + targetX + ", " + targetY, Toast.LENGTH_SHORT).show();
                stopSelf(); // Close the floating window

            } catch (Exception e) {
                Log.e("TimeJump", "Failed to save coord", e);
                Toast.makeText(FloatingCoordService.this, "❌ Error saving", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
