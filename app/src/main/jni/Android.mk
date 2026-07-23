LOCAL_PATH := $(call my-dir)

# ==================== Lua 5.4.6 Library ====================
# Still needed for the Java-side Lua engine (libLuaBridge.so)
include $(CLEAR_VARS)
LOCAL_MODULE := lua
LOCAL_SRC_FILES := \
    lua/lapi.c lua/lauxlib.c lua/lbaselib.c lua/lcode.c \
    lua/lcorolib.c lua/lctype.c lua/ldblib.c lua/ldebug.c \
    lua/ldo.c lua/ldump.c lua/lfunc.c lua/lgc.c \
    lua/linit.c lua/liolib.c lua/llex.c lua/lmathlib.c \
    lua/lmem.c lua/loadlib.c lua/lobject.c lua/lopcodes.c \
    lua/loslib.c lua/lparser.c lua/lstate.c lua/lstring.c \
    lua/lstrlib.c lua/ltable.c lua/ltablib.c lua/ltm.c \
    lua/lundump.c lua/lutf8lib.c lua/lvm.c lua/lzio.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/lua
LOCAL_CFLAGS := -O2 -DLUA_USE_POSIX -DLUA_USE_DLOPEN
include $(BUILD_STATIC_LIBRARY)

# ==================== xHook Library ====================
include $(CLEAR_VARS)
LOCAL_MODULE := xhook
LOCAL_SRC_FILES := xhook.c xh_core.c xh_elf.c xh_log.c xh_util.c xh_version.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CFLAGS := -O3 -fvisibility=hidden -Werror -Wno-unused-parameter
LOCAL_LDLIBS := -llog
include $(BUILD_STATIC_LIBRARY)

# ==================== TimeJump Injected Library ====================
# This is the .so that gets injected into the game process via ptrace.
# It has NO Lua, NO JNI. Just xhook time hooks + file-based IPC.
include $(CLEAR_VARS)
LOCAL_MODULE := TimeJump
LOCAL_SRC_FILES := timejump.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_LDLIBS := -llog -ldl
LOCAL_STATIC_LIBRARIES := xhook
include $(BUILD_SHARED_LIBRARY)

# ==================== ptrace Injector Executable ====================
# Root binary that injects libTimeJump.so into the game process.
include $(CLEAR_VARS)
LOCAL_MODULE := tj_injector
LOCAL_SRC_FILES := injector.c
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_EXECUTABLE)

# ==================== Lua Bridge Library ====================
# JNI library that runs Lua scripts inside OUR app process.
# Lua scripts call timeJump() which writes to /data/local/tmp/tj_offset,
# and clickButton()/clickImage() which call back to Java for root-based input.
include $(CLEAR_VARS)
LOCAL_MODULE := LuaBridge
LOCAL_SRC_FILES := lua_bridge.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/lua
LOCAL_LDLIBS := -llog
LOCAL_STATIC_LIBRARIES := lua
include $(BUILD_SHARED_LIBRARY)
