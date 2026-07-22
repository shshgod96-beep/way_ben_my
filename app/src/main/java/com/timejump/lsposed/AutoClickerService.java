package com.timejump.lsposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Foreground Service Auto-Clicker with Smart Mode.
 * Runs in the TimeJump app process, NOT the game process.
 * Uses root screencap + input tap externally.
 */
public class AutoClickerService extends Service {
    private static final String TAG = "TimeJumpInjector";
    private static final String CHANNEL_ID = "tj_autoclicker";
    private static final int NOTIFICATION_ID = 7777;
    private static final String TARGETS_DIR = "/data/local/tmp/targets";
    private static final String SCREENSHOT_PATH = "/data/local/tmp/tj_screen.png";

    private volatile boolean running = false;
    private Thread workerThread;

    public static volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);

        if (!running) {
            running = true;
            isRunning = true;
            workerThread = new Thread(this::autoClickLoop, "TJ-AutoClicker");
            workerThread.start();
            Log.i(TAG, "=== AutoClicker Service STARTED ===");
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        isRunning = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        Log.i(TAG, "=== AutoClicker Service STOPPED ===");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== NOTIFICATION ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Auto Clicker", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("TimeJump AI Auto Clicker scanning for targets.");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("TimeJump Auto Clicker")
                .setContentText("Scanning for target images...")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build();
    }

    // ==================== MAIN LOOP ====================

    private void autoClickLoop() {
        Log.i(TAG, "Auto-Click loop started. Watching: " + TARGETS_DIR);

        SharedPreferences prefs = getSharedPreferences("TimeJumpPrefs", Context.MODE_PRIVATE);

        // Track click counts per target (reset each service start)
        Map<String, Integer> clickCounts = new HashMap<>();
        // Cache coordinates for Smart Mode targets
        Map<String, int[]> cachedCoords = new HashMap<>();
        int currentTargetIndex = 0;
        int currentStepClicks = 0; // Tracks clicks for the CURRENT target before moving

        while (running) {
            try {
                // Read config values each cycle (so changes apply live)
                int stepDelay = prefs.getInt("global_step_delay", 500);
                int clickDelay = prefs.getInt("global_click_delay", 200);
                String configJson = prefs.getString("target_configs", "{}");
                JSONObject configs;
                try { configs = new JSONObject(configJson); }
                catch (Exception e) { configs = new JSONObject(); }

                File targetsDir = new File(TARGETS_DIR);
                if (!targetsDir.exists() || !targetsDir.isDirectory()) {
                    Thread.sleep(3000);
                    currentTargetIndex = 0;
                    currentStepClicks = 0;
                    continue;
                }

                File[] targets = targetsDir.listFiles((dir, name) ->
                        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".coord"));

                if (targets == null || targets.length == 0) {
                    Thread.sleep(3000);
                    currentTargetIndex = 0;
                    currentStepClicks = 0;
                    continue;
                }

                // Sort targets chronologically based on their added timestamp
                java.util.Arrays.sort(targets, (f1, f2) -> f1.getName().compareTo(f2.getName()));

                if (currentTargetIndex >= targets.length) {
                    currentTargetIndex = 0; // Sequence completed, start over
                    currentStepClicks = 0;
                    continue; // Re-evaluate targets array
                }

                File targetFile = targets[currentTargetIndex];
                String name = targetFile.getName();

                // Read per-target config
                JSONObject cfg = configs.optJSONObject(name);
                int maxClicks = (cfg != null) ? cfg.optInt("clickCount", 1) : 1;
                boolean smartMode = (cfg != null) ? cfg.optBoolean("smartMode", false) : false;
                int nextDelay = (cfg != null) ? cfg.optInt("nextDelay", 0) : 0;

                int clickX = -1, clickY = -1;

                if (name.endsWith(".coord")) {
                    clickX = (cfg != null) ? cfg.optInt("x", -1) : -1;
                    clickY = (cfg != null) ? cfg.optInt("y", -1) : -1;
                    if (clickX < 0 || clickY < 0) {
                        currentTargetIndex++;
                        currentStepClicks = 0;
                        continue;
                    }
                } else if (smartMode && cachedCoords.containsKey(name)) {
                    int[] cached = cachedCoords.get(name);
                    clickX = cached[0];
                    clickY = cached[1];
                    Log.d(TAG, "Smart Mode: Using cached coords (" + clickX + ", " + clickY + ") for " + name);
                } else {
                    // Take screenshot only when we are actively searching
                    Bitmap screen = captureScreenViaRoot();
                    if (screen == null) {
                        Thread.sleep(2000);
                        continue;
                    }

                    // Full image search
                    Bitmap template = BitmapFactory.decodeFile(targetFile.getAbsolutePath());
                    if (template != null) {
                        int[] match = findMatch(screen, template);
                        if (match != null) {
                            clickX = match[0] + template.getWidth() / 2;
                            clickY = match[1] + template.getHeight() / 2;

                            // Cache coords for smart mode
                            if (smartMode) {
                                cachedCoords.put(name, new int[]{clickX, clickY});
                                Log.i(TAG, "Smart Mode: Cached coords for " + name + " at (" + clickX + ", " + clickY + ")");
                            }
                        }
                        template.recycle();
                    }
                    screen.recycle();
                }

                // Perform click if we have coordinates
                if (clickX >= 0 && clickY >= 0) {
                    Log.i(TAG, "CLICKING '" + name + "' at (" + clickX + ", " + clickY + ") [step count: " + (currentStepClicks + 1) + "/" + (maxClicks == 0 ? "∞" : maxClicks) + "]");
                    performTapViaRoot(clickX, clickY);
                    currentStepClicks++;

                    // Handle Paste Text (Static or from URL)
                    String pasteText = (cfg != null) ? cfg.optString("pasteText", "") : "";
                    int pasteDelay = (cfg != null) ? cfg.optInt("pasteDelay", 500) : 500;

                    if (!pasteText.isEmpty()) {
                        try {
                            Thread.sleep(pasteDelay);
                            String finalPaste = pasteText;
                            if (pasteText.startsWith("http://") || pasteText.startsWith("https://")) {
                                finalPaste = fetchTextFromUrl(pasteText);
                            }
                            if (finalPaste != null && !finalPaste.isEmpty()) {
                                performTextPasteViaRoot(finalPaste);
                            } else {
                                Log.w(TAG, "Paste text was empty or URL fetch failed, skipping paste.");
                            }
                        } catch (Exception pasteEx) {
                            Log.e(TAG, "Paste failed, continuing macro.", pasteEx);
                        }
                    }

                    boolean advanced = false;
                    if (maxClicks > 0 && currentStepClicks >= maxClicks) {
                        // Reached the click limit for this step. Move to next target!
                        currentTargetIndex++;
                        currentStepClicks = 0; // Reset for the next target
                        advanced = true;
                    }

                    // Wait after click
                    Thread.sleep(clickDelay);
                    
                    if (advanced && nextDelay > 0) {
                        Thread.sleep(nextDelay);
                    }
                } else {
                    // Target not found yet, wait scan interval and try again (do NOT advance sequence)
                    Thread.sleep(stepDelay);
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "AutoClicker error", e);
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    // ==================== ROOT SCREEN CAPTURE ====================

    private Bitmap captureScreenViaRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "screencap -p " + SCREENSHOT_PATH + " && chmod 666 " + SCREENSHOT_PATH
            });
            int exit = p.waitFor();
            if (exit != 0) {
                Log.e(TAG, "screencap failed with exit code: " + exit);
                return null;
            }

            File file = new File(SCREENSHOT_PATH);
            if (file.exists() && file.length() > 100) {
                return BitmapFactory.decodeFile(SCREENSHOT_PATH);
            }
        } catch (Exception e) {
            Log.e(TAG, "screencap exception", e);
        }
        return null;
    }

    // ==================== ROOT INPUT TAP ====================

    private void performTapViaRoot(int x, int y) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "input tap " + x + " " + y
            });
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "input tap failed", e);
        }
    }

    // ==================== ROOT INPUT TEXT ====================

    private void performTextPasteViaRoot(String text) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            
            // Consume streams to prevent deadlock if buffer fills up
            new Thread(() -> { try { java.io.InputStream is = p.getInputStream(); while(is.read() != -1); } catch (Exception e){} }).start();
            new Thread(() -> { try { java.io.InputStream es = p.getErrorStream(); while(es.read() != -1); } catch (Exception e){} }).start();

            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            
            String[] lines = text.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!line.isEmpty()) {
                    // Android input text requires spaces to be %s
                    // We wrap the argument in single quotes, so we only need to escape single quotes properly for sh: '\''
                    String escaped = line.replace(" ", "%s").replace("'", "'\\''");
                    os.writeBytes("input text '" + escaped + "'\n");
                    os.flush();
                }
                
                // If it's not the last line, simulate an Enter key press
                if (i < lines.length - 1) {
                    os.writeBytes("input keyevent 66\n");
                    os.flush();
                }
            }
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
            Log.i(TAG, "Pasted text successfully.");
        } catch (Exception e) {
            Log.e(TAG, "input text failed", e);
        }
    }

    // ==================== HTTP TEXT FETCH ====================

    private String fetchTextFromUrl(String urlString) {
        try {
            // Clean the URL by removing all whitespace and newlines
            String cleanUrl = urlString.replaceAll("\\s+", "");
            java.net.URL url = new java.net.URL(cleanUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine).append("\n");
                }
                in.close();
                return content.toString().trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch text from URL: " + urlString, e);
        }
        return null;
    }

    // ==================== TEMPLATE MATCHING ====================

    private int[] findMatch(Bitmap screen, Bitmap template) {
        int localColorTolerance = 30;
        double localMinConfidence = 0.75;

        int sW = screen.getWidth();
        int sH = screen.getHeight();
        int tW = template.getWidth();
        int tH = template.getHeight();

        if (tW < 3 || tH < 3 || sW <= tW || sH <= tH) {
            return null;
        }

        int[] screenPixels = new int[sW * sH];
        int[] templatePixels = new int[tW * tH];
        screen.getPixels(screenPixels, 0, sW, 0, 0, sW, sH);
        template.getPixels(templatePixels, 0, tW, 0, 0, tW, tH);

        int stride = 2;
        double bestScore = 0;
        int bestX = -1, bestY = -1;

        int searchStep = 2;
        for (int sy = 0; sy <= sH - tH; sy += searchStep) {
            for (int sx = 0; sx <= sW - tW; sx += searchStep) {
                int matches = 0;
                int checked = 0;

                for (int ty = 0; ty < tH; ty += stride) {
                    for (int tx = 0; tx < tW; tx += stride) {
                        int sp = screenPixels[(sy + ty) * sW + (sx + tx)];
                        int tp = templatePixels[ty * tW + tx];

                        if (Color.alpha(tp) < 128) continue;

                        int dr = Math.abs(Color.red(sp) - Color.red(tp));
                        int dg = Math.abs(Color.green(sp) - Color.green(tp));
                        int db = Math.abs(Color.blue(sp) - Color.blue(tp));

                        if (dr <= localColorTolerance && dg <= localColorTolerance && db <= localColorTolerance) {
                            matches++;
                        }
                        checked++;
                    }
                }

                if (checked > 0) {
                    double score = (double) matches / checked;
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = sx;
                        bestY = sy;
                    }
                    if (bestScore > 0.95) break;
                }
            }
            if (bestScore > 0.95) break;
        }

        Log.d(TAG, "Best confidence: " + String.format("%.1f%%", bestScore * 100));

        if (bestScore >= localMinConfidence && bestX >= 0) {
            return new int[]{bestX, bestY};
        }
        return null;
    }
}
