#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <android/log.h>

// Lua headers
extern "C" {
#include "lua/lua.h"
#include "lua/lauxlib.h"
#include "lua/lualib.h"
}

#define LOG_TAG "TimeJumpInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define OFFSET_FILE "/data/local/tmp/tj_offset"

static JavaVM *g_jvm = NULL;

// ==================== Lua API Functions ====================

// timeJump(seconds) - Writes time offset to file for the injected library to read
static int lua_timeJump(lua_State *L) {
    int seconds = (int)luaL_checkinteger(L, 1);
    
    // Read current offset, add to it
    long current_offset = 0;
    FILE *fp = fopen(OFFSET_FILE, "r");
    if (fp) {
        fscanf(fp, "%ld", &current_offset);
        fclose(fp);
    }
    
    current_offset += seconds;
    
    fp = fopen(OFFSET_FILE, "w");
    if (fp) {
        fprintf(fp, "%ld", current_offset);
        fclose(fp);
        LOGI("Lua: timeJump(%d). Total offset written: %ld seconds", seconds, current_offset);
    } else {
        LOGE("Lua: Failed to write offset file!");
    }
    
    return 0;
}

// setTimeOffset(seconds) - Sets absolute time offset
static int lua_setTimeOffset(lua_State *L) {
    int seconds = (int)luaL_checkinteger(L, 1);
    
    FILE *fp = fopen(OFFSET_FILE, "w");
    if (fp) {
        fprintf(fp, "%d", seconds);
        fclose(fp);
        LOGI("Lua: setTimeOffset(%d)", seconds);
    } else {
        LOGE("Lua: Failed to write offset file!");
    }
    
    return 0;
}

// log(message)
static int lua_log(lua_State *L) {
    const char *msg = luaL_checkstring(L, 1);
    LOGI("Lua Script: %s", msg);
    return 0;
}

// sleep(milliseconds)
static int lua_sleep(lua_State *L) {
    int milliseconds = (int)luaL_checkinteger(L, 1);
    usleep(milliseconds * 1000);
    return 0;
}

// clickButton(text) - Calls Java AutoClickerService to perform root tap by text search
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
            jclass clazz = env->FindClass("com/timejump/lsposed/LuaEngine");
            if (clazz) {
                jmethodID method = env->GetStaticMethodID(clazz, "onClickButton", "(Ljava/lang/String;)Z");
                if (method) {
                    jstring jText = env->NewStringUTF(text);
                    success = env->CallStaticBooleanMethod(clazz, method, jText);
                    env->DeleteLocalRef(jText);
                    LOGI("Lua clickButton('%s') returned %d", text, success);
                }
            }
            if (needsDetach) g_jvm->DetachCurrentThread();
        }
    }

    lua_pushboolean(L, success);
    return 1;
}

// clickImage(imageName) - Calls Java to take screenshot and find image
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
            jclass clazz = env->FindClass("com/timejump/lsposed/LuaEngine");
            if (clazz) {
                jmethodID method = env->GetStaticMethodID(clazz, "onClickImage", "(Ljava/lang/String;)Z");
                if (method) {
                    jstring jImage = env->NewStringUTF(imageName);
                    success = env->CallStaticBooleanMethod(clazz, method, jImage);
                    env->DeleteLocalRef(jImage);
                    LOGI("Lua clickImage('%s') returned %d", imageName, success);
                }
            }
            if (needsDetach) g_jvm->DetachCurrentThread();
        }
    }

    lua_pushboolean(L, success);
    return 1;
}

// ==================== JNI Entry Points ====================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("LuaBridge library loaded! JVM reference saved.");
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_timejump_lsposed_LuaEngine_runScript(JNIEnv *env, jclass clazz, jstring script) {
    const char *scriptStr = env->GetStringUTFChars(script, NULL);
    LOGI("LuaBridge: Executing Lua script (%d bytes)", (int)strlen(scriptStr));

    lua_State *L = luaL_newstate();
    if (!L) {
        env->ReleaseStringUTFChars(script, scriptStr);
        return env->NewStringUTF("ERROR: Failed to create Lua state");
    }

    luaL_openlibs(L);

    // Register custom functions
    lua_pushcfunction(L, lua_timeJump);
    lua_setglobal(L, "timeJump");

    lua_pushcfunction(L, lua_log);
    lua_setglobal(L, "log");

    lua_pushcfunction(L, lua_setTimeOffset);
    lua_setglobal(L, "setTimeOffset");

    lua_pushcfunction(L, lua_sleep);
    lua_setglobal(L, "sleep");

    lua_pushcfunction(L, lua_clickButton);
    lua_setglobal(L, "clickButton");

    lua_pushcfunction(L, lua_clickImage);
    lua_setglobal(L, "clickImage");

    LOGI("Lua engine ready. Executing script...");

    int result = luaL_dostring(L, scriptStr);
    jstring jResult;
    if (result != LUA_OK) {
        const char *error = lua_tostring(L, -1);
        LOGE("Lua Error: %s", error ? error : "unknown error");
        jResult = env->NewStringUTF(error ? error : "unknown error");
        lua_pop(L, 1);
    } else {
        LOGI("Lua script executed successfully!");
        jResult = env->NewStringUTF("OK");
    }

    lua_close(L);
    env->ReleaseStringUTFChars(script, scriptStr);
    return jResult;
}

} // extern "C"
