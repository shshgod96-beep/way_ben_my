#include <jni.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <android/log.h>
#include "xhook.h"

// Lua headers (C library, needs extern "C")
extern "C" {
#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"
}

#define LOG_TAG "TimeJumpInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== Shared State ====================

static JavaVM *g_jvm = NULL;
static volatile long time_offset_seconds = 0;
static volatile long time_offset_ms = 0;

// ==================== JNI Callback to Java ====================
// This updates the Java-level Xposed hooks with the new time offset

static void update_java_offset() {
    if (!g_jvm) {
        LOGE("g_jvm is NULL, cannot update Java offset");
        return;
    }
    
    JNIEnv *env = NULL;
    int needsDetach = 0;
    
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, NULL) != JNI_OK) {
            LOGE("Failed to attach thread to JVM for Java callback");
            return;
        }
        needsDetach = 1;
    }
    
    jclass clazz = env->FindClass("com/timejump/lsposed/MainHook");
    if (clazz) {
        jmethodID method = env->GetStaticMethodID(clazz, "updateTimeOffset", "(J)V");
        if (method) {
            env->CallStaticVoidMethod(clazz, method, (jlong)time_offset_ms);
            LOGI("Updated Java time offset to %ld ms", time_offset_ms);
        } else {
            LOGE("Could not find updateTimeOffset method");
        }
    } else {
        LOGE("Could not find MainHook class for JNI callback");
    }
    
    if (needsDetach) {
        g_jvm->DetachCurrentThread();
    }
}

// ==================== xHook Native Time Hooks ====================

static int (*real_clock_gettime)(clockid_t, struct timespec *) = NULL;
static int hook_clock_gettime(clockid_t clk_id, struct timespec *tp) {
    int ret = real_clock_gettime(clk_id, tp);
    if (ret == 0 && tp && time_offset_seconds != 0) {
        tp->tv_sec += time_offset_seconds;
    }
    return ret;
}

static int (*real_gettimeofday)(struct timeval *, void *) = NULL;
static int hook_gettimeofday(struct timeval *tv, void *tz) {
    int ret = real_gettimeofday(tv, tz);
    if (ret == 0 && tv && time_offset_seconds != 0) {
        tv->tv_sec += time_offset_seconds;
    }
    return ret;
}

static int hooks_applied = 0;

static void apply_time_hooks() {
    if (!hooks_applied) {
        xhook_register(".*\\.so$", "clock_gettime", (void*)hook_clock_gettime, (void**)&real_clock_gettime);
        xhook_register(".*\\.so$", "gettimeofday", (void*)hook_gettimeofday, (void**)&real_gettimeofday);
        hooks_applied = 1;
    }
    
    if (xhook_refresh(0) == 0) {
        LOGI("xHook: Native time hooks refreshed successfully!");
    } else {
        LOGE("xHook: Failed to refresh native time hooks.");
    }
}

// ==================== Lua API Functions ====================

// Lua function: timeJump(seconds)
// Accumulates the time offset and updates BOTH native AND Java hooks
static int lua_timeJump(lua_State *L) {
    int seconds = (int)luaL_checkinteger(L, 1);
    time_offset_seconds += seconds;
    time_offset_ms += (long)seconds * 1000L;
    LOGI("Lua: timeJump(%d). Total offset: %ld seconds (%ld ms)", seconds, time_offset_seconds, time_offset_ms);
    
    // Update native hooks
    apply_time_hooks();
    
    // Update Java-level Xposed hooks (THIS IS THE KEY!)
    update_java_offset();
    
    return 0;
}

// Lua function: log(message)
static int lua_log(lua_State *L) {
    const char *msg = luaL_checkstring(L, 1);
    LOGI("Lua Script: %s", msg);
    return 0;
}

// Lua function: setTimeOffset(seconds)
static int lua_setTimeOffset(lua_State *L) {
    int seconds = (int)luaL_checkinteger(L, 1);
    time_offset_seconds = seconds;
    time_offset_ms = (long)seconds * 1000L;
    LOGI("Lua: setTimeOffset(%d)", seconds);
    apply_time_hooks();
    update_java_offset();
    return 0;
}

// Lua function: sleep(milliseconds)
static int lua_sleep(lua_State *L) {
    int milliseconds = (int)luaL_checkinteger(L, 1);
    usleep(milliseconds * 1000);
    return 0;
}

// Lua function: clickButton(text)
// Searches for a UI element with the given text and clicks it.
static int lua_clickButton(lua_State *L) {
    const char *text = luaL_checkstring(L, 1);
    int success = 0;
    
    if (g_jvm) {
        JNIEnv *env = NULL;
        int needsDetach = 0;
        
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, NULL) == JNI_OK) {
                needsDetach = 1;
            }
        }
        
        if (env) {
            jclass clazz = env->FindClass("com/timejump/lsposed/MainHook");
            if (clazz) {
                jmethodID method = env->GetStaticMethodID(clazz, "clickButtonByText", "(Ljava/lang/String;)Z");
                if (method) {
                    jstring jText = env->NewStringUTF(text);
                    success = env->CallStaticBooleanMethod(clazz, method, jText);
                    env->DeleteLocalRef(jText);
                    LOGI("Lua clickButton('%s') returned %d", text, success);
                }
            }
            if (needsDetach) {
                g_jvm->DetachCurrentThread();
            }
        }
    }
    
    // Return boolean result to Lua
    lua_pushboolean(L, success);
    return 1;
}

// Lua function: clickImage(imageName)
// Captures screen and clicks if image is found.
static int lua_clickImage(lua_State *L) {
    const char *imageName = luaL_checkstring(L, 1);
    int success = 0;
    
    if (g_jvm) {
        JNIEnv *env = NULL;
        int needsDetach = 0;
        
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, NULL) == JNI_OK) {
                needsDetach = 1;
            }
        }
        
        if (env) {
            jclass clazz = env->FindClass("com/timejump/lsposed/MainHook");
            if (clazz) {
                jmethodID method = env->GetStaticMethodID(clazz, "clickImage", "(Ljava/lang/String;)Z");
                if (method) {
                    jstring jImage = env->NewStringUTF(imageName);
                    success = env->CallStaticBooleanMethod(clazz, method, jImage);
                    env->DeleteLocalRef(jImage);
                    LOGI("Lua clickImage('%s') returned %d", imageName, success);
                }
            }
            if (needsDetach) {
                g_jvm->DetachCurrentThread();
            }
        }
    }
    
    lua_pushboolean(L, success);
    return 1;
}

// ==================== Lua Engine ====================

static lua_State *g_lua = NULL;

static void init_lua_engine(const char *script) {
    LOGI("Initializing Lua 5.4 engine...");
    
    g_lua = luaL_newstate();
    if (!g_lua) {
        LOGE("Failed to create Lua state!");
        return;
    }
    
    luaL_openlibs(g_lua);
    
    // Register custom functions
    lua_pushcfunction(g_lua, lua_timeJump);
    lua_setglobal(g_lua, "timeJump");
    
    lua_pushcfunction(g_lua, lua_log);
    lua_setglobal(g_lua, "log");
    
    lua_pushcfunction(g_lua, lua_setTimeOffset);
    lua_setglobal(g_lua, "setTimeOffset");

    lua_pushcfunction(g_lua, lua_sleep);
    lua_setglobal(g_lua, "sleep");
    
    lua_pushcfunction(g_lua, lua_clickButton);
    lua_setglobal(g_lua, "clickButton");

    lua_pushcfunction(g_lua, lua_clickImage);
    lua_setglobal(g_lua, "clickImage");
    
    LOGI("Lua engine ready. Executing script...");
    
    if (script && strlen(script) > 0) {
        int result = luaL_dostring(g_lua, script);
        if (result != LUA_OK) {
            const char *error = lua_tostring(g_lua, -1);
            LOGE("Lua Error: %s", error ? error : "unknown error");
            lua_pop(g_lua, 1);
        } else {
            LOGI("Lua script executed successfully!");
        }
    } else {
        LOGI("No script provided. Hooks not applied.");
    }
}

// ==================== JNI Entry Point ====================

extern "C" {

JNIEXPORT void JNICALL
Java_com_timejump_lsposed_MainHook_initLua(JNIEnv *env, jobject thiz, jstring script) {
    const char *scriptStr = env->GetStringUTFChars(script, NULL);
    LOGI("JNI: Received script from Java (%d bytes)", (int)strlen(scriptStr));
    
    init_lua_engine(scriptStr);
    
    env->ReleaseStringUTFChars(script, scriptStr);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm; // Save JVM reference for JNI callbacks from Lua thread
    LOGI("TimeJump library loaded! JVM reference saved.");
    return JNI_VERSION_1_6;
}

} // extern "C"
