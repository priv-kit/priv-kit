#include <dirent.h>
#include <errno.h>
#include <grp.h>
#include <limits.h>
#include <linux/capability.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
    // adbd installs supplementary groups before spawning its shell children.
    // Read the device's actual list rather than maintaining an AOSP/OEM approximation.
    bool read_adbd_groups(gid_t **groups, size_t *count) {
        DIR *proc = opendir("/proc");
        if (proc == nullptr) return false;
        bool found = false;
        while (dirent *entry = readdir(proc)) {
            char *end = nullptr;
            const long pid = strtol(entry->d_name, &end, 10);
            if (pid <= 0 || *end != '\0') continue;
            char path[PATH_MAX];
            snprintf(path, sizeof(path), "/proc/%ld/exe", pid);
            char executable[PATH_MAX];
            const ssize_t length = readlink(path, executable, sizeof(executable) - 1);
            if (length <= 0) continue;
            executable[length] = '\0';
            const char *basename = strrchr(executable, '/');
            if (basename == nullptr || strcmp(basename + 1, "adbd") != 0) continue;
            snprintf(path, sizeof(path), "/proc/%ld/status", pid);
            FILE *status = fopen(path, "re");
            if (status == nullptr) continue;
            char *line = nullptr;
            size_t capacity = 0;
            bool trusted_uid = false;
            bool read_groups = false;
            while (getline(&line, &capacity, status) >= 0) {
                unsigned int uid = UINT_MAX;
                if (sscanf(line, "Uid:\t%u", &uid) == 1) {
                    trusted_uid = uid == 0 || uid == 2000;
                }
                if (strncmp(line, "Groups:", 7) != 0) continue;
                const long limit = sysconf(_SC_NGROUPS_MAX);
                if (limit <= 0) break;
                auto *values = static_cast<gid_t *>(calloc(static_cast<size_t>(limit), sizeof(gid_t)));
                if (values == nullptr) break;
                size_t size = 0;
                char *cursor = line + 7;
                bool valid = true;
                while (true) {
                    while (*cursor == ' ' || *cursor == '\t') ++cursor;
                    if (*cursor == '\n' || *cursor == '\0') break;
                    errno = 0;
                    const unsigned long value = strtoul(cursor, &end, 10);
                    if (end == cursor || errno != 0 || value > UINT_MAX || size >= static_cast<size_t>(limit)) {
                        valid = false;
                        break;
                    }
                    values[size++] = static_cast<gid_t>(value);
                    cursor = end;
                }
                if (valid) {
                    *groups = values;
                    *count = size;
                    read_groups = true;
                } else {
                    free(values);
                }
                break;
            }
            free(line);
            fclose(status);
            if (trusted_uid && read_groups) {
                found = true;
                break;
            }
            free(*groups);
            *groups = nullptr;
            *count = 0;
        }
        closedir(proc);
        return found;
    }

    int fail(const char *operation) {
        fprintf(stderr, "UserService %s failed: %s\n", operation, strerror(errno));
        return 1;
    }
}

int start_user_service(int argc, char **argv) {
    if (argc < 3 || strcmp(argv[2], "/system/bin/app_process") != 0) {
        fprintf(stderr, "Usage: UserService launcher <uid> /system/bin/app_process ...\n");
        return 2;
    }
    char *end = nullptr;
    const long requested = strtol(argv[1], &end, 10);
    if (*end != '\0' || end == argv[1] || (requested != 0 && requested != 1000 && requested != 2000)) {
        fprintf(stderr, "Unsupported UserService UID: %s\n", argv[1]);
        return 2;
    }
    const auto uid = static_cast<uid_t>(requested);
    if (getuid() != uid) {
        if (getuid() != 0) {
            fprintf(stderr, "Changing UserService UID requires root\n");
            return 2;
        }
        gid_t *groups = nullptr;
        size_t count = 0;
        if (uid == 2000 && !read_adbd_groups(&groups, &count)) {
            fprintf(stderr, "Cannot obtain shell supplementary groups from device adbd\n");
            return 3;
        }
        // system has no universal supplementary group profile. Never inherit root groups.
        const int group_result = setgroups(count, groups);
        free(groups);
        if (group_result != 0) return fail("setgroups");
        if (setresgid(uid, uid, uid) != 0) return fail("setresgid");
        if (setresuid(uid, uid, uid) != 0) return fail("setresuid");
        __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
        __user_cap_data_struct caps[2] = {};
        if (syscall(__NR_capset, &header, caps) != 0) return fail("capset");
    }
    execv(argv[2], argv + 2);
    return fail("exec app_process");
}
