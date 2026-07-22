package com.timejump.lsposed;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ImageMatcher {
    private static final String TAG = "TimeJumpInjector";
    private static final String TARGETS_DIR = "/data/local/tmp/targets";
    private static volatile boolean autoClickerRunning = false;

    private static final int COLOR_TOLERANCE = 45;
    private static final double MIN_CONFIDENCE = 0.65;

    /**
     * Stealth Screen Capture using PixelCopy (NO ROOT REQUIRED BY THE GAME)
     * Works on OpenGL/SurfaceView without triggering Magisk prompts.
     */
    private static Bitmap captureScreenStealthy(Activity activity) {
        if (activity == null || activity.getWindow() == null) return null;

        final Window window = activity.getWindow();
        final View decorView = window.getDecorView();
        if (decorView.getWidth() == 0 || decorView.getHeight() == 0) return null;

        final Bitmap bitmap = Bitmap.createBitmap(decorView.getWidth(), decorView.getHeight(), Bitmap.Config.ARGB_8888);
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        Handler handler = new Handler(Looper.getMainLooper());
        try {
            PixelCopy.request(window, bitmap, copyResult -> {
                if (copyResult == PixelCopy.SUCCESS) {
                    success[0] = true;
                }
                latch.countDown();
            }, handler);
            
            // Wait up to 1 second for capture
            latch.await(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "PixelCopy failed", e);
            bitmap.recycle();
            return null;
        }

        if (success[0]) {
            return bitmap;
        } else {
            bitmap.recycle();
            return null;
        }
    }

    /**
     * Stealth Click using MotionEvent injection directly to the game's window.
     * NO ROOT REQUIRED BY THE GAME.
     */
    private static void performStealthClick(Activity activity, int x, int y) {
        if (activity == null) return;
        
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        MotionEvent eventDown = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent eventUp = MotionEvent.obtain(downTime, eventTime + 50, MotionEvent.ACTION_UP, x, y, 0);

        activity.runOnUiThread(() -> {
            try {
                View decorView = activity.getWindow().getDecorView();
                decorView.dispatchTouchEvent(eventDown);
                decorView.dispatchTouchEvent(eventUp);
                Log.d(TAG, "Stealth tap executed at: (" + x + ", " + y + ")");
            } catch (Exception e) {
                Log.e(TAG, "Stealth tap failed", e);
            } finally {
                eventDown.recycle();
                eventUp.recycle();
            }
        });
    }

    private static int[] findMatch(Bitmap screen, Bitmap template) {
        float scale = 0.4f;
        if (screen.getWidth() <= 1080) scale = 0.5f;

        int sW = (int) (screen.getWidth() * scale);
        int sH = (int) (screen.getHeight() * scale);
        int tW = (int) (template.getWidth() * scale);
        int tH = (int) (template.getHeight() * scale);

        if (tW < 3 || tH < 3 || sW <= tW || sH <= tH) return null;

        Bitmap scaledScreen = Bitmap.createScaledBitmap(screen, sW, sH, true);
        Bitmap scaledTemplate = Bitmap.createScaledBitmap(template, tW, tH, true);

        int[] screenPixels = new int[sW * sH];
        int[] templatePixels = new int[tW * tH];
        scaledScreen.getPixels(screenPixels, 0, sW, 0, 0, sW, sH);
        scaledTemplate.getPixels(templatePixels, 0, tW, 0, 0, tW, tH);

        scaledScreen.recycle();
        scaledTemplate.recycle();

        int stride = 2;
        int totalSamples = 0;
        for (int ty = 0; ty < tH; ty += stride) {
            for (int tx = 0; tx < tW; tx += stride) totalSamples++;
        }
        int earlyCheckAt = Math.max(totalSamples / 4, 10);

        double bestScore = 0;
        int bestX = -1, bestY = -1;

        int searchStep = 2;
        for (int sy = 0; sy <= sH - tH; sy += searchStep) {
            for (int sx = 0; sx <= sW - tW; sx += searchStep) {
                int matches = 0;
                int checked = 0;
                boolean earlyExit = false;

                for (int ty = 0; ty < tH && !earlyExit; ty += stride) {
                    for (int tx = 0; tx < tW && !earlyExit; tx += stride) {
                        int sp = screenPixels[(sy + ty) * sW + (sx + tx)];
                        int tp = templatePixels[ty * tW + tx];

                        int dr = Math.abs(Color.red(sp) - Color.red(tp));
                        int dg = Math.abs(Color.green(sp) - Color.green(tp));
                        int db = Math.abs(Color.blue(sp) - Color.blue(tp));

                        if (dr <= COLOR_TOLERANCE && dg <= COLOR_TOLERANCE && db <= COLOR_TOLERANCE) {
                            matches++;
                        }
                        checked++;

                        if (checked == earlyCheckAt) {
                            if (((double) matches / checked) < 0.40) earlyExit = true;
                        }
                    }
                }

                if (!earlyExit && checked > 0) {
                    double score = (double) matches / checked;
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = sx;
                        bestY = sy;
                    }
                }
            }
        }

        if (bestScore >= MIN_CONFIDENCE && bestX >= 0) {
            return new int[]{(int) (bestX / scale), (int) (bestY / scale)};
        }
        return null;
    }

    public static boolean findAndClickImage(String imageName) {
        // Handled by background auto-clicker now. Lua bridge just returns false.
        // If we want Lua to do it synchronously, we need currentActivity reference here.
        return false;
    }

    /**
     * Background Auto-Clicker: continuously scans ALL target images
     */
    public static void startAutoClicker() {
        if (autoClickerRunning) return;
        autoClickerRunning = true;

        new Thread(() -> {
            Log.i(TAG, "=== Auto-Clicker STARTED (Stealth Mode) ===");
            while (autoClickerRunning) {
                try {
                    Activity activity = MainHook.currentActivity;
                    if (activity == null) {
                        Thread.sleep(2000);
                        continue;
                    }

                    File targetsDir = new File(TARGETS_DIR);
                    if (!targetsDir.exists() || !targetsDir.isDirectory()) {
                        Thread.sleep(3000);
                        continue;
                    }

                    File[] targets = targetsDir.listFiles((dir, name) ->
                            name.endsWith(".png") || name.endsWith(".jpg"));

                    if (targets == null || targets.length == 0) {
                        Thread.sleep(3000);
                        continue;
                    }

                    Bitmap screen = captureScreenStealthy(activity);
                    if (screen == null) {
                        Thread.sleep(1000);
                        continue;
                    }

                    for (File targetFile : targets) {
                        if (!autoClickerRunning) break;

                        Bitmap template = BitmapFactory.decodeFile(targetFile.getAbsolutePath());
                        if (template == null) continue;

                        int[] match = findMatch(screen, template);
                        if (match != null) {
                            int clickX = match[0] + template.getWidth() / 2;
                            int clickY = match[1] + template.getHeight() / 2;
                            performStealthClick(activity, clickX, clickY);
                            // Sleep a bit after a click to let UI react
                            Thread.sleep(1000); 
                        }
                        template.recycle();
                    }

                    screen.recycle();
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Auto-Clicker cycle error", e);
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
        }, "TimeJump-AutoClicker").start();
    }

    public static void stopAutoClicker() {
        autoClickerRunning = false;
    }
}
