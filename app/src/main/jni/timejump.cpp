#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include "xhook.h"

#define LOG_TAG "TimeJumpInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== Shared State ====================

static volatile long time_offset_seconds = 0;

// File used for IPC between our app and the injected library
#define OFFSET_FILE "/data/local/tmp/tj_offset"

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

// ==================== File Watcher Thread ====================
// Reads time offset from /data/local/tmp/tj_offset every 100ms.
// The file contains a single integer: the time offset in seconds.

static void *offset_reader_thread(void *arg) {
    LOGI("Offset reader thread started. Watching: %s", OFFSET_FILE);

    while (1) {
        FILE *fp = fopen(OFFSET_FILE, "r");
        if (fp) {
            long new_offset = 0;
            if (fscanf(fp, "%ld", &new_offset) == 1) {
                if (new_offset != time_offset_seconds) {
                    time_offset_seconds = new_offset;
                    LOGI("Time offset updated to: %ld seconds", time_offset_seconds);
                    // Re-apply hooks to catch newly loaded libraries
                    apply_time_hooks();
                }
            }
            fclose(fp);
        }
        usleep(100000); // 100ms
    }

    return NULL;
}

// ==================== Constructor (Auto-Init on dlopen) ====================

__attribute__((constructor))
static void on_load() {
    LOGI("=== TimeJump Library Injected via Root! ===");

    // Apply time hooks immediately
    apply_time_hooks();

    // Start background thread to read offset file
    pthread_t tid;
    if (pthread_create(&tid, NULL, offset_reader_thread, NULL) == 0) {
        pthread_detach(tid);
        LOGI("Offset reader thread launched successfully.");
    } else {
        LOGE("Failed to create offset reader thread!");
    }

    LOGI("=== TimeJump Injection Complete ===");
}
