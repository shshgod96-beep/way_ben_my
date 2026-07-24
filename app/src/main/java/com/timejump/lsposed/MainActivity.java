package com.timejump.lsposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.FileInputStream;

public class MainActivity extends Activity {

    public static final String PREFS_NAME = "com.timejump.lsposed_preferences";
    public static final String KEY_SCRIPT = "lua_script";
    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int PICK_ZIP_REQUEST = 2;
    private static final String TARGETS_DIR = "/data/local/tmp/targets";

    private View pageScript, pageMacro, pageBoot, pageLog;
    private TextView tabScript, tabMacro, tabBoot, tabLog;
    private TextView tvTargetList, tvServiceStatus, tvBootApp, tvLogs;
    private Button btnToggleService, btnRefreshLogs;
    private EditText etStepDelay, etClickDelay;
    private boolean serviceRunning = false;
    private SharedPreferences globalPrefs;
    
    private android.content.BroadcastReceiver targetAddedReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        globalPrefs = getSharedPreferences("TimeJumpPrefs", Context.MODE_PRIVATE);

        // Register Receiver for Float Picker updates
        targetAddedReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.timejump.TARGET_ADDED".equals(intent.getAction())) {
                    refreshTargetList();
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter("com.timejump.TARGET_ADDED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(targetAddedReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(targetAddedReceiver, filter);
        }

        // Tab views
        tabScript = findViewById(R.id.tabScript);
        tabMacro = findViewById(R.id.tabMacro);
        tabBoot = findViewById(R.id.tabBoot);
        tabLog = findViewById(R.id.tabLog);

        // Page views
        pageScript = findViewById(R.id.pageScript);
        pageMacro = findViewById(R.id.pageMacro);
        pageBoot = findViewById(R.id.pageBoot);
        pageLog = findViewById(R.id.pageLog);

        // Script tab views
        EditText scriptInput = findViewById(R.id.scriptInput);
        Button btnSave = findViewById(R.id.btnSave);
        CheckBox cbAutoMacro = findViewById(R.id.cbAutoMacro);
        CheckBox cbAutoBoot = findViewById(R.id.cbAutoBoot);

        // Macro tab views
        Button btnAddTarget = findViewById(R.id.btnAddTarget);
        Button btnClearTargets = findViewById(R.id.btnClearTargets);
        Button btnConfigTarget = findViewById(R.id.btnConfigTarget);
        Button btnExportMacro = findViewById(R.id.btnExportMacro);
        Button btnImportMacro = findViewById(R.id.btnImportMacro);
        Button btnAddCoordTarget = findViewById(R.id.btnAddCoordTarget);
        Button btnTogglePointer = findViewById(R.id.btnTogglePointer);
        tvTargetList = findViewById(R.id.tvTargetList);
        btnToggleService = findViewById(R.id.btnToggleService);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        etStepDelay = findViewById(R.id.etStepDelay);
        etClickDelay = findViewById(R.id.etClickDelay);

        // Boot tab views
        tvBootApp = findViewById(R.id.tvBootApp);
        Button btnSelectBootApp = findViewById(R.id.btnSelectBootApp);
        
        // Log tab views
        tvLogs = findViewById(R.id.tvLogs);
        btnRefreshLogs = findViewById(R.id.btnRefreshLogs);

        // Load saved script and checkboxes
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedScript = prefs.getString(KEY_SCRIPT, "-- Enter your script here\ntimeJump(60)");
        scriptInput.setText(savedScript);
        
        cbAutoMacro.setChecked(globalPrefs.getBoolean("auto_start_macro", true));
        cbAutoBoot.setChecked(globalPrefs.getBoolean("auto_start_boot", true));

        // Load global delay settings
        etStepDelay.setText(String.valueOf(globalPrefs.getInt("global_step_delay", 100)));
        etClickDelay.setText(String.valueOf(globalPrefs.getInt("global_click_delay", 100)));

        // Load boot app
        String currentBootApp = globalPrefs.getString("boot_app_package", "");
        tvBootApp.setText("Selected: " + (currentBootApp.isEmpty() ? "None" : currentBootApp));

        // Ensure targets directory
        runAsRoot(new String[]{"mkdir -p " + TARGETS_DIR, "chmod 777 " + TARGETS_DIR});

        refreshTargetList();
        updateServiceUI();

        // ===== TAB SWITCHING =====
        tabScript.setOnClickListener(v -> switchTab(0));
        tabMacro.setOnClickListener(v -> switchTab(1));
        tabBoot.setOnClickListener(v -> switchTab(2));
        tabLog.setOnClickListener(v -> switchTab(3));
        
        // ===== LOG TAB =====
        btnRefreshLogs.setOnClickListener(v -> fetchLogs());
        
        // ===== CHECKBOX LISTENERS =====
        cbAutoMacro.setOnCheckedChangeListener((buttonView, isChecked) -> {
            globalPrefs.edit().putBoolean("auto_start_macro", isChecked).apply();
            new Thread(() -> {
                if (isChecked) {
                    runAsRoot(new String[]{"touch /data/local/tmp/tj_auto_macro", "chmod 666 /data/local/tmp/tj_auto_macro"});
                } else {
                    runAsRoot(new String[]{"rm -f /data/local/tmp/tj_auto_macro"});
                }
            }).start();
        });
        
        cbAutoBoot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            globalPrefs.edit().putBoolean("auto_start_boot", isChecked).apply();
        });

        // ===== SAVE SCRIPT & SETTINGS =====
        btnSave.setOnClickListener(v -> {
            String newScript = scriptInput.getText().toString();
            prefs.edit().putString(KEY_SCRIPT, newScript).apply();

            File tempScript = new File(getFilesDir(), "script.lua");
            try {
                FileOutputStream fos = new FileOutputStream(tempScript);
                fos.write(newScript.getBytes());
                fos.close();

                runAsRoot(new String[]{
                        "cp " + tempScript.getAbsolutePath() + " /data/local/tmp/timejump_script.lua",
                        "chmod 666 /data/local/tmp/timejump_script.lua"
                });
                Toast.makeText(this, "✅ Script saved!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "❌ Failed to save!", Toast.LENGTH_SHORT).show();
            }
        });

        // ===== ADD TARGET =====
        btnAddTarget.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        });

        btnAddCoordTarget.setOnClickListener(v -> {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(48, 32, 48, 16);

            EditText etX = new EditText(this);
            etX.setHint("X Coordinate");
            etX.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            layout.addView(etX);

            EditText etY = new EditText(this);
            etY.setHint("Y Coordinate");
            etY.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            layout.addView(etY);

            new AlertDialog.Builder(this)
                    .setTitle("Add Coordinate Target")
                    .setView(layout)
                    .setPositiveButton("Add", (dialog, which) -> {
                        try {
                            int x = Integer.parseInt(etX.getText().toString());
                            int y = Integer.parseInt(etY.getText().toString());
                            String name = "target_" + System.currentTimeMillis() + ".coord";
                            runAsRoot(new String[]{"touch " + TARGETS_DIR + "/" + name, "chmod 666 " + TARGETS_DIR + "/" + name});
                            
                            String configJson = globalPrefs.getString("target_configs", "{}");
                            JSONObject configs = new JSONObject(configJson);
                            JSONObject newCfg = new JSONObject();
                            newCfg.put("x", x);
                            newCfg.put("y", y);
                            configs.put(name, newCfg);
                            globalPrefs.edit().putString("target_configs", configs.toString()).apply();
                            
                            refreshTargetList();
                            Toast.makeText(this, "✅ Coord Target Added!", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "❌ Invalid input!", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnTogglePointer.setOnClickListener(v -> {
            new Thread(() -> {
                runAsRoot(new String[]{"appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"});
                runOnUiThread(() -> {
                    Intent intent = new Intent(MainActivity.this, FloatingCoordService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    Toast.makeText(this, "🎈 Floating Picker Started!", Toast.LENGTH_SHORT).show();
                });
            }).start();
        });

        // ===== CLEAR TARGETS =====
        btnClearTargets.setOnClickListener(v -> {
            new Thread(() -> {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls " + TARGETS_DIR + " 2>/dev/null"});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    List<String> files = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.endsWith(".png") || line.endsWith(".jpg") || line.endsWith(".jpeg") || line.endsWith(".coord")) {
                            files.add(line);
                        }
                    }
                    p.waitFor();

                    if (files.isEmpty()) {
                        runOnUiThread(() -> Toast.makeText(this, "No targets to clear.", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    String[] fileArray = files.toArray(new String[0]);
                    boolean[] checkedItems = new boolean[fileArray.length];

                    runOnUiThread(() -> {
                        new AlertDialog.Builder(this)
                                .setTitle("Select Targets to Delete")
                                .setMultiChoiceItems(fileArray, checkedItems, (dialog, which, isChecked) -> {
                                    checkedItems[which] = isChecked;
                                })
                                .setPositiveButton("Delete Selected", (dialog, which) -> {
                                    new Thread(() -> {
                                        StringBuilder rmCmd = new StringBuilder("rm -f");
                                        boolean anySelected = false;
                                        String configJson = globalPrefs.getString("target_configs", "{}");
                                        JSONObject configs;
                                        try { configs = new JSONObject(configJson); }
                                        catch (Exception e) { configs = new JSONObject(); }

                                        for (int i = 0; i < fileArray.length; i++) {
                                            if (checkedItems[i]) {
                                                rmCmd.append(" ").append(TARGETS_DIR).append("/").append(fileArray[i]);
                                                configs.remove(fileArray[i]);
                                                anySelected = true;
                                            }
                                        }

                                        if (anySelected) {
                                            runAsRoot(new String[]{rmCmd.toString()});
                                            globalPrefs.edit().putString("target_configs", configs.toString()).apply();
                                            refreshTargetList();
                                            runOnUiThread(() -> Toast.makeText(this, "🗑️ Selected targets deleted!", Toast.LENGTH_SHORT).show());
                                        }
                                    }).start();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        });

        // ===== CONFIGURE TARGET =====
        btnConfigTarget.setOnClickListener(v -> showTargetConfigPicker());

        // ===== EXPORT / IMPORT MACRO =====
        btnExportMacro.setOnClickListener(v -> exportMacro());
        btnImportMacro.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/zip");
            startActivityForResult(intent, PICK_ZIP_REQUEST);
        });

        // ===== TOGGLE SERVICE =====
        btnToggleService.setOnClickListener(v -> {
            saveGlobalDelays();
            if (serviceRunning) {
                stopService(new Intent(this, AutoClickerService.class));
                serviceRunning = false;
            } else {
                Intent serviceIntent = new Intent(this, AutoClickerService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                serviceRunning = true;
            }
            updateServiceUI();
        });

        // ===== BOOT APP SELECTOR =====
        btnSelectBootApp.setOnClickListener(v -> showAppSelectionDialog());
    }

    // ==================== TAB SWITCHING ====================

    private void switchTab(int tab) {
        pageScript.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        pageMacro.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        pageBoot.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);

        tabScript.setTextColor(tab == 0 ? 0xFFFFFFFF : 0xFFAAAAAA);
        tabMacro.setTextColor(tab == 1 ? 0xFFFFFFFF : 0xFFAAAAAA);
        tabBoot.setTextColor(tab == 2 ? 0xFFFFFFFF : 0xFFAAAAAA);
        tabLog.setTextColor(tab == 3 ? 0xFFFFFFFF : 0xFFAAAAAA);

        tabScript.setBackgroundColor(tab == 0 ? 0xFF3F51B5 : 0x00000000);
        tabMacro.setBackgroundColor(tab == 1 ? 0xFF3F51B5 : 0x00000000);
        tabBoot.setBackgroundColor(tab == 2 ? 0xFF3F51B5 : 0x00000000);
        tabLog.setBackgroundColor(tab == 3 ? 0xFF3F51B5 : 0x00000000);

        if (tab == 1) {
            saveGlobalDelays();
            refreshTargetList();
        }
        if (tab == 3) {
            fetchLogs();
        }
    }
    
    // ==================== FETCH LOGS ====================
    private void fetchLogs() {
        tvLogs.setText("Fetching logs...");
        new Thread(() -> {
            try {
                // -d dumps and exits. -s filters to our tags
                Process p = Runtime.getRuntime().exec("logcat -d -s TimeJumpInjector TJ-Injector TJ-Lua");
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                p.waitFor();
                
                String logs = sb.toString();
                if (logs.isEmpty()) {
                    logs = "No logs found yet. Start the service and try again.";
                }
                
                // Get the last 2000 chars to avoid memory issues
                if (logs.length() > 3000) {
                    logs = "... " + logs.substring(logs.length() - 3000);
                }
                
                final String finalLogs = logs;
                runOnUiThread(() -> tvLogs.setText(finalLogs));
            } catch (Exception e) {
                runOnUiThread(() -> tvLogs.setText("Error reading logs: " + e.getMessage()));
            }
        }).start();
    }

    // ==================== SAVE GLOBAL DELAYS ====================

    private void saveGlobalDelays() {
        try {
            int stepDelay = Integer.parseInt(etStepDelay.getText().toString());
            int clickDelay = Integer.parseInt(etClickDelay.getText().toString());
            globalPrefs.edit()
                    .putInt("global_step_delay", stepDelay)
                    .putInt("global_click_delay", clickDelay)
                    .apply();
        } catch (NumberFormatException e) {
            // Keep defaults
        }
    }

    // ==================== TARGET LIST ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            new Thread(() -> {
                try {
                    Uri imageUri = data.getData();
                    if (imageUri == null) return;

                    String fileName = "target_" + System.currentTimeMillis() + ".png";
                    File tempFile = new File(getFilesDir(), fileName);
                    InputStream is = getContentResolver().openInputStream(imageUri);
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                    fos.close();
                    is.close();

                    runAsRoot(new String[]{
                            "cp " + tempFile.getAbsolutePath() + " " + TARGETS_DIR + "/" + fileName,
                            "chmod 666 " + TARGETS_DIR + "/" + fileName
                    });

                    runOnUiThread(() -> {
                        refreshTargetList();
                        Toast.makeText(this, "✅ Target added: " + fileName, Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(this, "❌ Failed to add target!", Toast.LENGTH_SHORT).show());
                }
            }).start();
        } else if (requestCode == PICK_ZIP_REQUEST && resultCode == RESULT_OK && data != null) {
            new Thread(() -> {
                try {
                    Uri zipUri = data.getData();
                    if (zipUri == null) return;
                    InputStream is = getContentResolver().openInputStream(zipUri);
                    ZipInputStream zis = new ZipInputStream(is);
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (name.equals("config.json")) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(zis));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            globalPrefs.edit().putString("target_configs", sb.toString()).apply();
                        } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                            File tempFile = new File(getFilesDir(), name);
                            FileOutputStream fos = new FileOutputStream(tempFile);
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                            fos.close();
                            runAsRoot(new String[]{
                                    "cp " + tempFile.getAbsolutePath() + " " + TARGETS_DIR + "/" + name,
                                    "chmod 666 " + TARGETS_DIR + "/" + name
                            });
                        }
                        zis.closeEntry();
                    }
                    zis.close();
                    runOnUiThread(() -> {
                        refreshTargetList();
                        Toast.makeText(this, "📥 Macro imported successfully!", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(this, "❌ Failed to import macro!", Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    }

    private void exportMacro() {
        new Thread(() -> {
            try {
                String zipName = "TimeJump_Macro_" + System.currentTimeMillis() + ".zip";
                File cacheZip = new File(getCacheDir(), zipName);
                FileOutputStream fos = new FileOutputStream(cacheZip);
                ZipOutputStream zos = new ZipOutputStream(fos);

                // Add config.json
                String configJson = globalPrefs.getString("target_configs", "{}");
                ZipEntry configEntry = new ZipEntry("config.json");
                zos.putNextEntry(configEntry);
                zos.write(configJson.getBytes());
                zos.closeEntry();

                // Get target files
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls " + TARGETS_DIR + " 2>/dev/null"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                List<String> files = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.endsWith(".png") || line.endsWith(".jpg") || line.endsWith(".jpeg") || line.endsWith(".coord")) files.add(line);
                }
                p.waitFor();

                // We must copy files from root dir to cache dir to read them via Java
                for (String f : files) {
                    File tempCopy = new File(getCacheDir(), f);
                    runAsRoot(new String[]{"cp " + TARGETS_DIR + "/" + f + " " + tempCopy.getAbsolutePath(), "chmod 666 " + tempCopy.getAbsolutePath()});
                    if (tempCopy.exists()) {
                        ZipEntry imgEntry = new ZipEntry(f);
                        zos.putNextEntry(imgEntry);
                        FileInputStream fis = new FileInputStream(tempCopy);
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                        fis.close();
                        zos.closeEntry();
                        tempCopy.delete();
                    }
                }
                zos.close();

                // Move zip to SD card
                String finalPath = "/sdcard/Download/" + zipName;
                runAsRoot(new String[]{"cp " + cacheZip.getAbsolutePath() + " " + finalPath, "chmod 666 " + finalPath});
                cacheZip.delete();

                runOnUiThread(() -> Toast.makeText(this, "📤 Macro exported to Downloads folder!", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "❌ Export failed!", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void refreshTargetList() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls " + TARGETS_DIR + " 2>/dev/null"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                List<String> files = new ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.endsWith(".png") || line.endsWith(".jpg") || line.endsWith(".jpeg") || line.endsWith(".coord")) {
                        files.add(line);
                    }
                }
                p.waitFor();

                // Load configs
                String configJson = globalPrefs.getString("target_configs", "{}");
                JSONObject configs;
                try { configs = new JSONObject(configJson); }
                catch (Exception e) { configs = new JSONObject(); }

                if (files.isEmpty()) {
                    sb.append("No targets added yet.");
                } else {
                    for (String f : files) {
                        JSONObject cfg = configs.optJSONObject(f);
                        int clickCount = (cfg != null) ? cfg.optInt("clickCount", 1) : 1;
                        boolean smartMode = (cfg != null) ? cfg.optBoolean("smartMode", false) : false;
                        
                        sb.append(f.endsWith(".coord") ? "📍 " : "📁 ").append(f);
                        sb.append("  [").append(clickCount == 0 ? "♾️∞" : clickCount + "x").append("]");
                        if (smartMode && !f.endsWith(".coord")) sb.append(" [📍Smart]");
                        sb.append("\n");
                    }
                }

                String result = sb.toString().trim();
                runOnUiThread(() -> tvTargetList.setText(result));
            } catch (Exception e) {
                runOnUiThread(() -> tvTargetList.setText("Error reading targets."));
            }
        }).start();
    }

    // ==================== TARGET CONFIG ====================

    private void showTargetConfigPicker() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls " + TARGETS_DIR + " 2>/dev/null"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                List<String> files = new ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.endsWith(".png") || line.endsWith(".jpg") || line.endsWith(".jpeg") || line.endsWith(".coord")) {
                        files.add(line);
                    }
                }
                p.waitFor();

                if (files.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "No targets to configure!", Toast.LENGTH_SHORT).show());
                    return;
                }

                String[] fileArray = files.toArray(new String[0]);
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Select Target to Configure")
                            .setItems(fileArray, (dialog, which) -> showTargetConfigDialog(fileArray, which))
                            .show();
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void showTargetConfigDialog(String[] fileArray, int index) {
        String targetName = fileArray[index];
        // Load existing config
        String configJson = globalPrefs.getString("target_configs", "{}");
        JSONObject configs;
        try { configs = new JSONObject(configJson); }
        catch (Exception e) { configs = new JSONObject(); }

        JSONObject targetCfg = configs.optJSONObject(targetName);
        int currentClicks = (targetCfg != null) ? targetCfg.optInt("clickCount", 1) : 1;
        boolean currentSmart = (targetCfg != null) ? targetCfg.optBoolean("smartMode", false) : false;
        String currentPasteText = (targetCfg != null) ? targetCfg.optString("pasteText", "") : "";
        int currentPasteDelay = (targetCfg != null) ? targetCfg.optInt("pasteDelay", 100) : 100;
        int currentNextDelay = (targetCfg != null) ? targetCfg.optInt("nextDelay", 0) : 0;

        // Build dialog UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        // Order Buttons
        LinearLayout orderLayout = new LinearLayout(this);
        orderLayout.setOrientation(LinearLayout.HORIZONTAL);
        orderLayout.setPadding(0, 0, 0, 16);
        Button btnUp = new Button(this);
        btnUp.setText("⬆️ Move Up");
        btnUp.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button btnDown = new Button(this);
        btnDown.setText("⬇️ Move Down");
        btnDown.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        
        btnUp.setEnabled(index > 0);
        btnDown.setEnabled(index < fileArray.length - 1);
        
        orderLayout.addView(btnUp);
        orderLayout.addView(btnDown);
        layout.addView(orderLayout);

        // Click count
        TextView tvClicks = new TextView(this);
        tvClicks.setText("Clicks before moving to next target (0 = forever):");
        tvClicks.setTextSize(14);
        layout.addView(tvClicks);

        EditText etClicks = new EditText(this);
        etClicks.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etClicks.setText(String.valueOf(currentClicks));
        layout.addView(etClicks);

        // Next Target Delay
        TextView tvNextDelay = new TextView(this);
        tvNextDelay.setText("Delay before next target in sequence (ms):");
        tvNextDelay.setTextSize(14);
        tvNextDelay.setPadding(0, 16, 0, 0);
        layout.addView(tvNextDelay);

        EditText etNextDelay = new EditText(this);
        etNextDelay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etNextDelay.setText(String.valueOf(currentNextDelay));
        layout.addView(etNextDelay);

        // Paste Text
        TextView tvPaste = new TextView(this);
        tvPaste.setText("Text to Paste (or RAW URL from Pastebin/Gist):");
        tvPaste.setTextSize(14);
        tvPaste.setPadding(0, 16, 0, 0);
        layout.addView(tvPaste);

        EditText etPaste = new EditText(this);
        etPaste.setText(currentPasteText);
        layout.addView(etPaste);

        // Paste Delay
        TextView tvPasteDelay = new TextView(this);
        tvPasteDelay.setText("Delay before pasting (ms):");
        tvPasteDelay.setTextSize(14);
        layout.addView(tvPasteDelay);

        EditText etPasteDelay = new EditText(this);
        etPasteDelay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPasteDelay.setText(String.valueOf(currentPasteDelay));
        layout.addView(etPasteDelay);

        // Smart mode
        CheckBox cbSmart = new CheckBox(this);
        cbSmart.setText("📍 Smart Mode (cache coordinates)");
        cbSmart.setChecked(currentSmart);
        cbSmart.setPadding(0, 16, 0, 8);
        layout.addView(cbSmart);

        TextView tvSmartHint = new TextView(this);
        tvSmartHint.setText("When enabled, after finding the target once, it saves the position and clicks there directly without re-scanning.");
        tvSmartHint.setTextSize(11);
        tvSmartHint.setTextColor(0xFF888888);
        layout.addView(tvSmartHint);

        final JSONObject finalConfigs = configs;
        AlertDialog dialogInstance = new AlertDialog.Builder(this)
                .setTitle("⚙️ Configure Target")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        int clicks = Integer.parseInt(etClicks.getText().toString());
                        boolean smart = cbSmart.isChecked();
                        String paste = etPaste.getText().toString();
                        int pDelay = Integer.parseInt(etPasteDelay.getText().toString());
                        int nDelay = Integer.parseInt(etNextDelay.getText().toString());

                        JSONObject newCfg = (targetCfg != null) ? targetCfg : new JSONObject();
                        newCfg.put("clickCount", clicks);
                        newCfg.put("smartMode", smart);
                        newCfg.put("pasteText", paste);
                        newCfg.put("pasteDelay", pDelay);
                        newCfg.put("nextDelay", nDelay);
                        finalConfigs.put(targetName, newCfg);

                        globalPrefs.edit().putString("target_configs", finalConfigs.toString()).apply();
                        refreshTargetList();
                        Toast.makeText(this, "✅ Config saved", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "❌ Invalid input!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("🗑️ Delete", (dialog, which) -> {
                    runAsRoot(new String[]{"rm -f " + TARGETS_DIR + "/" + targetName});
                    try { finalConfigs.remove(targetName); } catch (Exception ignored) {}
                    globalPrefs.edit().putString("target_configs", finalConfigs.toString()).apply();
                    refreshTargetList();
                    Toast.makeText(this, "🗑️ Deleted " + targetName, Toast.LENGTH_SHORT).show();
                })
                .create();

        btnUp.setOnClickListener(v -> {
            swapTargets(fileArray[index], fileArray[index - 1]);
            dialogInstance.dismiss();
        });
        
        btnDown.setOnClickListener(v -> {
            swapTargets(fileArray[index], fileArray[index + 1]);
            dialogInstance.dismiss();
        });

        dialogInstance.show();
    }

    private void swapTargets(String targetA, String targetB) {
        new Thread(() -> {
            try {
                // To safely swap, rename A to TEMP, B to A, TEMP to B
                String tempName = "temp_" + System.currentTimeMillis();
                runAsRoot(new String[]{
                        "mv " + TARGETS_DIR + "/" + targetA + " " + TARGETS_DIR + "/" + tempName,
                        "mv " + TARGETS_DIR + "/" + targetB + " " + TARGETS_DIR + "/" + targetA,
                        "mv " + TARGETS_DIR + "/" + tempName + " " + TARGETS_DIR + "/" + targetB
                });
                
                // Swap configs
                String configJson = globalPrefs.getString("target_configs", "{}");
                JSONObject configs = new JSONObject(configJson);
                JSONObject cfgA = configs.optJSONObject(targetA);
                JSONObject cfgB = configs.optJSONObject(targetB);
                configs.remove(targetA);
                configs.remove(targetB);
                if (cfgB != null) configs.put(targetA, cfgB);
                if (cfgA != null) configs.put(targetB, cfgA);
                globalPrefs.edit().putString("target_configs", configs.toString()).apply();
                
                runOnUiThread(() -> {
                    refreshTargetList();
                    Toast.makeText(this, "✅ Sequence Re-ordered!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==================== SERVICE UI ====================

    private void updateServiceUI() {
        if (serviceRunning) {
            btnToggleService.setText("⏹️ STOP Auto Clicker");
            btnToggleService.setBackgroundColor(0xFFF44336);
            tvServiceStatus.setText("Status: Running 🟢");
            tvServiceStatus.setTextColor(0xFF4CAF50);
        } else {
            btnToggleService.setText("▶️ START Auto Clicker");
            btnToggleService.setBackgroundColor(0xFF4CAF50);
            tvServiceStatus.setText("Status: Stopped 🔴");
            tvServiceStatus.setTextColor(0xFFF44336);
        }
    }

    // ==================== BOOT APP SELECTOR ====================

    private void showAppSelectionDialog() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
        List<String> appNames = new ArrayList<>();
        List<String> appPackages = new ArrayList<>();

        appNames.add("None (Disable Auto-Start)");
        appPackages.add("");

        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo != null) {
                appNames.add(info.activityInfo.loadLabel(pm).toString());
                appPackages.add(info.activityInfo.packageName);
            }
        }

        String[] namesArray = appNames.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("Select Game to Auto-Start on Boot")
                .setItems(namesArray, (dialog, which) -> {
                    String selectedPackage = appPackages.get(which);
                    String selectedName = appNames.get(which);
                    globalPrefs.edit().putString("boot_app_package", selectedPackage).apply();
                    tvBootApp.setText("Selected: " + (selectedPackage.isEmpty() ? "None" : selectedPackage));
                    if (!selectedPackage.isEmpty()) {
                        Toast.makeText(this, "✅ " + selectedName + " will launch on boot!", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ==================== ROOT HELPER ====================

    private void runAsRoot(String[] cmds) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String tmpCmd : cmds) {
                os.writeBytes(tmpCmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (targetAddedReceiver != null) {
            unregisterReceiver(targetAddedReceiver);
        }
    }
}
