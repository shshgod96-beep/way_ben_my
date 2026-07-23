#include <stdio.h>
#include <stdlib.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <dirent.h>
#include <unistd.h>
#include <string.h>
#include <sys/user.h>
#include <elf.h>
#include <android/log.h>

#define LOG_TAG "TimeJumpInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__i386__)
#define pt_regs user_regs_struct
#define ptrace_getregs PTRACE_GETREGS
#define ptrace_setregs PTRACE_SETREGS
#define ptrace_cont PTRACE_CONT
#elif defined(__arm__) || defined(__aarch64__)
#define pt_regs user_regs
#define ptrace_getregs PTRACE_GETREGS
#define ptrace_setregs PTRACE_SETREGS
#define ptrace_cont PTRACE_CONT
#else
#error "Unsupported architecture"
#endif

long get_module_base(pid_t pid, const char* module_name) {
    FILE *fp;
    long addr = 0;
    char filename[32], line[1024];

    if (pid < 0) {
        snprintf(filename, sizeof(filename), "/proc/self/maps");
    } else {
        snprintf(filename, sizeof(filename), "/proc/%d/maps", pid);
    }

    fp = fopen(filename, "r");
    if (fp != NULL) {
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, module_name)) {
                sscanf(line, "%lx-%*lx %*s %*x %*s %*d", &addr);
                break;
            }
        }
        fclose(fp);
    }
    return addr;
}

long get_remote_addr(pid_t target_pid, const char* module_name, void* local_addr) {
    long local_handle, remote_handle;

    local_handle = get_module_base(-1, module_name);
    remote_handle = get_module_base(target_pid, module_name);

    LOGI("[+] get_remote_addr: local[%lx], remote[%lx]\n", local_handle, remote_handle);
    long ret_addr = (long)((uint32_t)local_addr - (uint32_t)local_handle + (uint32_t)remote_handle);
    return ret_addr;
}

int ptrace_attach(pid_t pid) {
    if (ptrace(PTRACE_ATTACH, pid, NULL, 0) < 0) {
        perror("ptrace_attach");
        return -1;
    }
    int status = 0;
    waitpid(pid, &status, WUNTRACED);
    return 0;
}

int ptrace_detach(pid_t pid) {
    if (ptrace(PTRACE_DETACH, pid, NULL, 0) < 0) {
        perror("ptrace_detach");
        return -1;
    }
    return 0;
}

void ptrace_readdata(pid_t pid, uint8_t *src, uint8_t *buf, size_t size) {
    uint32_t i, j, remain;
    uint8_t *laddr;
    const size_t bytes = 4;
    union u { long val; char chars[bytes]; } d;

    j = size / bytes;
    remain = size % bytes;
    laddr = buf;

    for (i = 0; i < j; i++) {
        d.val = ptrace(PTRACE_PEEKTEXT, pid, src, 0);
        memcpy(laddr, d.chars, bytes);
        src += bytes;
        laddr += bytes;
    }
    if (remain > 0) {
        d.val = ptrace(PTRACE_PEEKTEXT, pid, src, 0);
        memcpy(laddr, d.chars, remain);
    }
}

void ptrace_writedata(pid_t pid, uint8_t *dest, uint8_t *data, size_t size) {
    uint32_t i, j, remain;
    uint8_t *laddr;
    const size_t bytes = 4;
    union u { long val; char chars[bytes]; } d;

    j = size / bytes;
    remain = size % bytes;
    laddr = data;

    for (i = 0; i < j; i++) {
        memcpy(d.chars, laddr, bytes);
        ptrace(PTRACE_POKETEXT, pid, dest, d.val);
        dest += bytes;
        laddr += bytes;
    }
    if (remain > 0) {
        d.val = ptrace(PTRACE_PEEKTEXT, pid, dest, 0);
        for (i = 0; i < remain; i++) d.chars[i] = *laddr++;
        ptrace(PTRACE_POKETEXT, pid, dest, d.val);
    }
}

int ptrace_call_wrapper(pid_t pid, const char* func_name, void* func_addr, long* parameters, int param_num, struct pt_regs* regs) {
    LOGI("[+] Calling %s in target process.\n", func_name);
#if defined(__i386__)
    regs->esp -= (param_num) * sizeof(long);
    ptrace_writedata(pid, (uint8_t*)regs->esp, (uint8_t*)parameters, (param_num) * sizeof(long));
    long tmp_addr = 0x00000000;
    regs->esp -= sizeof(long);
    ptrace_writedata(pid, (uint8_t*)regs->esp, (uint8_t*)&tmp_addr, sizeof(tmp_addr)); 
    regs->eip = (long)func_addr;
    if (ptrace(PTRACE_SETREGS, pid, NULL, regs) == -1 || ptrace(PTRACE_CONT, pid, NULL, 0) == -1) return -1;
    int status = 0; waitpid(pid, &status, WUNTRACED);
    while (status != 0xb7f) {
        if (ptrace(PTRACE_CONT, pid, NULL, 0) == -1) return -1;
        waitpid(pid, &status, WUNTRACED);
    }
#elif defined(__arm__)
    int i = 0;
    for (i = 0; i < param_num && i < 4; i++) regs->uregs[i] = parameters[i];
    if (i < param_num) {
        regs->ARM_sp -= (param_num - i) * sizeof(long);
        ptrace_writedata(pid, (uint8_t *)regs->ARM_sp, (uint8_t *)&parameters[i], (param_num - i) * sizeof(long));
    }
    regs->ARM_pc = (long)func_addr;
    if (regs->ARM_pc & 1) { regs->ARM_pc &= (~1u); regs->ARM_cpsr |= 0x20; }
    else { regs->ARM_cpsr &= ~0x20; }
    regs->ARM_lr = 0;
    if (ptrace(PTRACE_SETREGS, pid, NULL, regs) == -1 || ptrace(PTRACE_CONT, pid, NULL, 0) == -1) return -1;
    int status = 0; waitpid(pid, &status, WUNTRACED);
    while (status != 0xb7f) {
        if (ptrace(PTRACE_CONT, pid, NULL, 0) == -1) return -1;
        waitpid(pid, &status, WUNTRACED);
    }
#endif
    return 0;
}

int main(int argc, char** argv) {
    if (argc != 3) {
        printf("Usage: %s <PID> <Path to .so>\n", argv[0]);
        return -1;
    }

    pid_t target_pid = atoi(argv[1]);
    char* so_path = argv[2];

    LOGI("[+] Injecting into PID %d with %s\n", target_pid, so_path);

    if (ptrace_attach(target_pid) < 0) {
        LOGE("[-] Cannot attach to process\n");
        return -1;
    }
    
    struct pt_regs regs, original_regs;
    if (ptrace(PTRACE_GETREGS, target_pid, NULL, &regs) < 0) return -1;
    memcpy(&original_regs, &regs, sizeof(regs));

    void* mmap_addr = (void*)get_remote_addr(target_pid, "/system/lib/libc.so", (void*)mmap);
    void* dlopen_addr = (void*)get_remote_addr(target_pid, "/system/lib/libdl.so", (void*)dlopen);
    
    if (dlopen_addr == 0) dlopen_addr = (void*)get_remote_addr(target_pid, "/system/bin/linker", (void*)dlopen);

    LOGI("[+] Remote mmap address: %p\n", mmap_addr);
    LOGI("[+] Remote dlopen address: %p\n", dlopen_addr);

    long parameters[6];
    parameters[0] = 0;
    parameters[1] = 0x4000;
    parameters[2] = PROT_READ | PROT_WRITE | PROT_EXEC;
    parameters[3] = MAP_ANONYMOUS | MAP_PRIVATE;
    parameters[4] = 0;
    parameters[5] = 0;

    if (ptrace_call_wrapper(target_pid, "mmap", mmap_addr, parameters, 6, &regs) == -1) return -1;
    if (ptrace(PTRACE_GETREGS, target_pid, NULL, &regs) == -1) return -1;
    
#if defined(__i386__)
    long map_base = regs.eax;
#elif defined(__arm__)
    long map_base = regs.ARM_r0;
#endif

    LOGI("[+] Allocated memory at: %lx\n", map_base);
    ptrace_writedata(target_pid, (uint8_t*)map_base, (uint8_t*)so_path, strlen(so_path) + 1);

    parameters[0] = map_base;
    parameters[1] = RTLD_NOW | RTLD_GLOBAL;

    if (ptrace_call_wrapper(target_pid, "dlopen", dlopen_addr, parameters, 2, &regs) == -1) return -1;
    if (ptrace(PTRACE_GETREGS, target_pid, NULL, &regs) == -1) return -1;
    
#if defined(__i386__)
    long handle = regs.eax;
#elif defined(__arm__)
    long handle = regs.ARM_r0;
#endif

    LOGI("[+] dlopen returned: %lx\n", handle);
    ptrace(PTRACE_SETREGS, target_pid, NULL, &original_regs);
    ptrace_detach(target_pid);
    
    LOGI("[+] Injection completed successfully!\n");
    return 0;
}
