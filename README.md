# TimeJump & AutoClicker 🚀

**TimeJump** is a powerful, fully-featured automation framework for Android games:
1. **LSPosed Module (TimeJump):** Injects a full Lua 5.4 engine directly into the target game's process to manipulate time and game logic.
2. **Foreground Service (Smart AutoClicker):** A root-powered, independent background service that scans the screen and clicks on target images with smart coordinate caching.
3. **Auto-Boot Launcher:** Automatically forces your target game to launch when the device boots, creating a completely hands-free automation loop.

---

## 🔥 Features
- **Modern Tabbed UI:** Clean, intuitive interface divided into Script, Macro, and Boot configuration.
- **Smart Macro (Strict Sequence State Machine):** 
  - Adds target images and clicks them automatically in a **strict sequence** (e.g., waits for Target 1, clicks it, then waits for Target 2, etc.).
  - **Re-order Sequence:** Easily swap targets Up/Down in your sequence without deleting them.
  - **Per-Target Next Delay:** Define a custom wait time (ms) *after* a specific target finishes, before moving to the next one.
  - **Fast Coordinate Targets (📍):** Add targets using precise X/Y screen coordinates instead of image matching for instant clicks!
  - **Floating Coordinate Picker (🎈):** Use a draggable overlay bubble to instantly grab and save coordinates straight from your game!
  - **Smart Mode:** Caches coordinates after the first successful find, massively improving speed and reducing battery usage.
  - **Dynamic URL Paste:** Paste static text OR a **GitHub Gist / Pastebin RAW URL**. The app fetches the latest text from the URL dynamically on every click!
  - **Click Limits:** Define exactly how many times a target should be clicked before moving to the next target in the sequence. Default is 1. Set to 0 to stay on this target forever and never advance.
- **Macro Export & Import:** Share your entire macro sequence (images, coordinates, timings, settings) as a single `.zip` file with one click!
- **Auto-Start on Boot:** Select any installed app to automatically launch via Root when the device boots up.
- **In-Process Scripting:** Lua scripts run natively inside the game process.
- **Universal Time Hooking:** Manipulates both Native and Java time functions simultaneously to bypass robust game timers.
- **Stealth Operation:** The Auto-Clicker and Boot launcher use pure Android Root commands externally. The game itself *never* requests root or detects the clicks!

---

## ⚙️ Requirements
- Rooted Android device (Magisk / KernelSU / APatch / SuperUser).
- Works perfectly on Android 7 to Android 14.
- [LSPosed](https://github.com/LSPosed/LSPosed) (or original Xposed on older emulators) installed and active.

---

## 🛠️ Installation & Setup
1. Download the latest `TimeJump-Module.apk` from the [Releases/Actions](https://github.com/ghjfyu9t8fg/injection-LSPosed/actions).
2. Install the APK on your device.
3. Open **LSPosed Manager**, go to **Modules**, and enable **TimeJump**.
4. **Crucial:** Select the target game(s) in the LSPosed module settings (Note: Not required if using original Xposed).
5. Open the **TimeJump app** and grant Root permissions.
6. **Configure the App:** 
   - **📝 Script Tab:** Write your Lua script and tap **Save**.
   - **🎯 Macro Tab:** Tap **"➕ Add"** to add cropped target images. Use **"⚙️ Configure"** to enable *Smart Mode* or set click limits. Tap **"▶️ START"** to run the clicker.
   - **🚀 Boot Tab:** Select your game to enable full hands-free automation when the device boots.
7. **Launch your game** and watch the magic happen!

---

## 📚 Lua API Reference (In-Game Script)

The Lua script is injected into the game to handle time manipulation. The Auto-Clicker runs independently of this script.

### 1. `timeJump(seconds)`
Adds the specified number of seconds to the game's internal clock immediately. The effect is cumulative.
- **Example:** `timeJump(60) -- Skips 60 seconds of waiting time`

### 2. `setTimeOffset(seconds)`
Sets the time offset to an exact number of seconds. Not cumulative.
- **Example:** `setTimeOffset(10) -- Sets the offset to exactly 10 seconds`

### 3. `sleep(milliseconds)`
Pauses the execution of the Lua script for the specified number of milliseconds.
- **Example:** `sleep(3000) -- Pauses the script for 3 seconds`

### 4. `log(message)`
Prints a message to the Android Logcat for debugging.
- **Example:** `log("Script started!")`

---

## 📝 Example Lua Script: Continuous Time Skipper

```lua
log("TimeJump script starting in 20 seconds...")

-- Wait 20 seconds for the game to fully launch
sleep(20000)

log("Starting Time Jumper! 🚀")

while true do
    -- Add 60 seconds to the clock
    timeJump(60)
    
    -- Rest for 100 milliseconds before the next cycle
    sleep(100)
end
```

---

## 🛡️ Architecture
1. **LSPosed Hooking:** When the target game starts, `MainHook.java` intercepts the initialization and injects the Lua Engine (`libTimeJump.so`).
2. **Auto-Start Hook:** `MainHook.java` automatically signals the TimeJump app to start the AutoClicker service the moment the game UI appears on screen.
3. **Stealth Auto-Clicker:** Uses `su -c screencap` and an optimized Java Bitmap matcher to find targets, then `su -c input tap` to click them. Smart Mode caches these coordinates for blazing fast subsequent clicks.
4. **Boot Receiver:** A system broadcast receiver that force-launches your selected game via `su -c monkey` when the device finishes booting.
