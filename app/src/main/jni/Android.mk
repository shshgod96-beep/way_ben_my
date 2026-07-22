LOCAL_PATH := $(call my-dir)

# ==================== Lua 5.4.6 Library ====================
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

# ==================== TimeJump Main Library ====================
include $(CLEAR_VARS)
LOCAL_MODULE := TimeJump
LOCAL_SRC_FILES := timejump.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/lua
LOCAL_LDLIBS := -llog -ldl
LOCAL_STATIC_LIBRARIES := xhook lua
include $(BUILD_SHARED_LIBRARY)
