#include <errno.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <dirent.h>
#include <fcntl.h>
#include <limits.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <stdint.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <signal.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <stdio.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <stdlib.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <string.h> // NOLINT(modernize-deprecated-headers): This target uses ANDROID_STL=none.
#include <sys/types.h>
#include <unistd.h>

namespace {

    constexpr size_t MAX_CLASSPATH_LENGTH = 8192;
    constexpr size_t MAX_SPLIT_APK_COUNT = 128;
    constexpr uid_t ROOT_UID = 0;
    constexpr uid_t SYSTEM_UID = 1000;
    constexpr uid_t SHELL_UID = 2000;
    constexpr int UNSUPPORTED_UID_EXIT_CODE = 8;
    constexpr int STOP_EXISTING_SERVER_FAILED_EXIT_CODE = 9;
    constexpr int STOP_WAIT_ATTEMPTS = 20;
    constexpr useconds_t STOP_WAIT_INTERVAL_MICROS = 100000;
    constexpr const char *DEFAULT_MAIN_CLASS = "priv.kit.core.internal.server.PrivilegeServerMain";
    constexpr const char *DEFAULT_PROCESS_SUFFIX = ":priv-server";
    constexpr const char *SCOPED_PROCESS_USER_SUFFIX = "-u";
    constexpr const char *SCOPED_PROCESS_TOKEN_FORMAT = "pk-%012llx";
    constexpr const char *ENV_OWNER_USER_ID = "PRIV_KIT_OWNER_USER_ID";
    constexpr const char *STOP_EXISTING_SERVER_FAILED_MARKER =
            "PRIV_KIT_STARTER_STOP_EXISTING_SERVER_FAILED";

    constexpr bool is_supported_starter_uid(uid_t uid) {
        return uid == ROOT_UID || uid == SYSTEM_UID || uid == SHELL_UID;
    }

    static_assert(is_supported_starter_uid(ROOT_UID));
    static_assert(is_supported_starter_uid(SYSTEM_UID));
    static_assert(is_supported_starter_uid(SHELL_UID));
    static_assert(!is_supported_starter_uid(1001));

    struct StarterConfig {
        char classpath[MAX_CLASSPATH_LENGTH] = {};
        char package_name[256] = {};
        char process_name[384] = {};
        char process_comm[16] = {};
        int owner_user_id = -1;
    };

    enum ProcessMatch {
        PROCESS_NO_MATCH,
        PROCESS_MATCH,
        PROCESS_INSPECTION_FAILED,
    };

    struct ProcessSnapshot {
        pid_t *pids = nullptr;
        size_t count = 0;
        size_t capacity = 0;
    };

    bool string_copy(char *output, size_t output_size, const char *value) {
        if (value == nullptr || strlen(value) >= output_size) {
            return false;
        }
        strcpy(output, value);
        return true;
    }

    bool string_append(char *output, size_t output_size, const char *value) {
        if (value == nullptr || strlen(output) + strlen(value) >= output_size) {
            return false;
        }
        strcat(output, value);
        return true;
    }

    bool string_append_path(char *output, size_t output_size, const char *dir, const char *file) {
        return string_copy(output, output_size, dir) &&
               string_append(output, output_size, "/") &&
               string_append(output, output_size, file);
    }

    bool ends_with(const char *value, const char *suffix) {
        const size_t value_length = strlen(value);
        const size_t suffix_length = strlen(suffix);
        return value_length >= suffix_length &&
               strcmp(value + value_length - suffix_length, suffix) == 0;
    }

    char *concat_arg(const char *prefix, const char *value) {
        const size_t prefix_length = strlen(prefix);
        const size_t value_length = strlen(value);
        char *result = static_cast<char *>(malloc(prefix_length + value_length + 1));
        if (result == nullptr) {
            return nullptr;
        }
        memcpy(result, prefix, prefix_length);
        memcpy(result + prefix_length, value, value_length);
        result[prefix_length + value_length] = '\0';
        return result;
    }

    int compare_path_string(const void *left, const void *right) {
        return strcmp(static_cast<const char *>(left), static_cast<const char *>(right));
    }

    bool infer_starter_path(char **argv, char *output, size_t output_size) {
        if (argv[0] != nullptr && argv[0][0] == '/') {
            return string_copy(output, output_size, argv[0]);
        }

        ssize_t length = readlink("/proc/self/exe", output, output_size - 1);
        if (length <= 0) {
            fprintf(stderr, "fatal: failed to infer starter path\n");
            return false;
        }
        output[length] = '\0';
        return true;
    }

    bool infer_install_dir(const char *starter_path, char *output, size_t output_size) {
        const char *apk_separator = strstr(starter_path, "!/");
        if (apk_separator != nullptr) {
            const char *archive_slash = nullptr;
            for (const char *cursor = starter_path; cursor < apk_separator; ++cursor) {
                if (*cursor == '/') {
                    archive_slash = cursor;
                }
            }
            if (archive_slash == nullptr || archive_slash == starter_path) {
                fprintf(stderr, "fatal: failed to infer APK install dir from %s\n", starter_path);
                return false;
            }
            const auto length = static_cast<size_t>(archive_slash - starter_path);
            if (length >= output_size) {
                fprintf(stderr, "fatal: install dir is too long\n");
                return false;
            }
            memcpy(output, starter_path, length);
            output[length] = '\0';
            return true;
        }

        const char *lib_segment = nullptr;
        const char *search = starter_path;
        const char *match = nullptr;
        while ((match = strstr(search, "/lib/")) != nullptr) {
            lib_segment = match;
            search = match + 1;
        }
        if (lib_segment == nullptr) {
            fprintf(stderr, "fatal: failed to infer install dir from %s\n", starter_path);
            return false;
        }
        const auto length = static_cast<size_t>(lib_segment - starter_path);
        if (length == 0 || length >= output_size) {
            fprintf(stderr, "fatal: install dir is too long\n");
            return false;
        }
        memcpy(output, starter_path, length);
        output[length] = '\0';
        return true;
    }

    bool infer_package_name(const char *install_dir, char *output, size_t output_size) {
        const char *basename = strrchr(install_dir, '/');
        basename = basename == nullptr ? install_dir : basename + 1;
        const char *suffix = strchr(basename, '-');
        const size_t length =
                suffix == nullptr ? strlen(basename) : static_cast<size_t>(suffix - basename);
        if (length == 0 || length >= output_size) {
            fprintf(stderr, "fatal: failed to infer package name from %s\n", install_dir);
            return false;
        }
        memcpy(output, basename, length);
        output[length] = '\0';
        return true;
    }

    bool build_classpath(const char *install_dir, char *output, size_t output_size) {
        char base_apk[PATH_MAX] = {};
        if (!string_append_path(base_apk, sizeof(base_apk), install_dir, "base.apk")) {
            fprintf(stderr, "fatal: base APK path is too long\n");
            return false;
        }
        if (access(base_apk, R_OK) != 0) {
            fprintf(stderr, "fatal: can't access base APK %s\n", base_apk);
            return false;
        }
        if (!string_copy(output, output_size, base_apk)) {
            fprintf(stderr, "fatal: classpath is too long\n");
            return false;
        }

        DIR *dir = opendir(install_dir);
        if (dir == nullptr) {
            return true;
        }
        char split_apks[MAX_SPLIT_APK_COUNT][PATH_MAX] = {};
        size_t split_apk_count = 0;
        dirent *entry = nullptr;
        while ((entry = readdir(dir)) != nullptr) {
            if (!ends_with(entry->d_name, ".apk") || strcmp(entry->d_name, "base.apk") == 0) {
                continue;
            }
            if (split_apk_count >= MAX_SPLIT_APK_COUNT) {
                closedir(dir);
                fprintf(stderr, "fatal: too many split APKs\n");
                return false;
            }
            char apk_path[PATH_MAX] = {};
            if (!string_append_path(apk_path, sizeof(apk_path), install_dir, entry->d_name)) {
                closedir(dir);
                fprintf(stderr, "fatal: split APK path is too long\n");
                return false;
            }
            if (!string_copy(split_apks[split_apk_count], sizeof(split_apks[split_apk_count]),
                             apk_path)) {
                closedir(dir);
                fprintf(stderr, "fatal: split APK path is too long\n");
                return false;
            }
            split_apk_count += 1;
        }
        closedir(dir);

        qsort(split_apks, split_apk_count, sizeof(split_apks[0]), compare_path_string);
        for (size_t i = 0; i < split_apk_count; ++i) {
            if (!string_append(output, output_size, ":") ||
                !string_append(output, output_size, split_apks[i])) {
                fprintf(stderr, "fatal: classpath is too long\n");
                return false;
            }
        }
        return true;
    }

    bool parse_owner_user_id(int *owner_user_id) {
        const char *value = getenv(ENV_OWNER_USER_ID);
        if (value == nullptr) {
            *owner_user_id = 0;
            return true;
        }
        char *end = nullptr;
        errno = 0;
        const long parsed = strtol(value, &end, 10);
        if (errno != 0 || end == value || *end != '\0' || parsed < 0 || parsed > INT_MAX) {
            fprintf(stderr, "fatal: %s is invalid: %s\n", ENV_OWNER_USER_ID, value);
            return false;
        }
        *owner_user_id = static_cast<int>(parsed);
        return true;
    }

    uint64_t process_scope_hash(const char *package_name, const char *user_id) {
        uint64_t hash = UINT64_C(14695981039346656037);
        const char *parts[] = {package_name, "#", user_id};
        for (const char *part: parts) {
            for (auto cursor = reinterpret_cast<const unsigned char *>(part);
                 *cursor != '\0'; ++cursor) {
                hash ^= *cursor;
                hash *= UINT64_C(1099511628211);
            }
        }
        return hash;
    }

    bool infer_defaults(char **argv, StarterConfig *config) {
        char starter_path[PATH_MAX] = {};
        char install_dir[PATH_MAX] = {};
        if (!infer_starter_path(argv, starter_path, sizeof(starter_path)) ||
            !infer_install_dir(starter_path, install_dir, sizeof(install_dir)) ||
            !parse_owner_user_id(&config->owner_user_id)) {
            return false;
        }

        if (!infer_package_name(install_dir, config->package_name, sizeof(config->package_name))) {
            return false;
        }
        if (!build_classpath(install_dir, config->classpath, sizeof(config->classpath))) {
            return false;
        }
        char user_id[16] = {};
        const int user_id_length = snprintf(user_id, sizeof(user_id), "%d", config->owner_user_id);
        if (user_id_length <= 0 || static_cast<size_t>(user_id_length) >= sizeof(user_id)) {
            fprintf(stderr, "fatal: owner userId is too long\n");
            return false;
        }
        const auto scope_hash = static_cast<unsigned long long>(
                process_scope_hash(config->package_name, user_id) & UINT64_C(0xffffffffffff));
        const int comm_length = snprintf(
                config->process_comm,
                sizeof(config->process_comm),
                SCOPED_PROCESS_TOKEN_FORMAT,
                scope_hash);
        if (comm_length != 15) {
            fprintf(stderr, "fatal: failed to build scoped process token\n");
            return false;
        }
        if (!string_copy(config->process_name, sizeof(config->process_name),
                         config->process_comm) ||
            !string_append(config->process_name, sizeof(config->process_name), "-") ||
            !string_append(config->process_name, sizeof(config->process_name),
                           config->package_name) ||
            !string_append(config->process_name, sizeof(config->process_name),
                           DEFAULT_PROCESS_SUFFIX)) {
            fprintf(stderr, "fatal: process name is too long\n");
            return false;
        }
        if (config->owner_user_id != 0) {
            if (!string_append(config->process_name, sizeof(config->process_name),
                               SCOPED_PROCESS_USER_SUFFIX) ||
                !string_append(config->process_name, sizeof(config->process_name), user_id)) {
                fprintf(stderr, "fatal: scoped process name is too long\n");
                return false;
            }
        }
        return true;
    }

    bool parse_pid_name(const char *name, pid_t *pid) {
        char *end = nullptr;
        long value = strtol(name, &end, 10);
        if (end == name || *end != '\0' || value <= 0) {
            return false;
        }
        *pid = static_cast<pid_t>(value);
        return true;
    }

    bool read_process_name(pid_t pid, char *output, size_t output_size) {
        char path[PATH_MAX] = {};
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            return false;
        }
        ssize_t length = read(fd, output, output_size - 1);
        const int read_errno = errno;
        close(fd);
        if (length <= 0) {
            errno = length == 0 ? ENODATA : read_errno;
            return false;
        }
        output[length] = '\0';
        return output[0] != '\0';
    }

    bool read_process_comm(pid_t pid, char *output, size_t output_size) {
        char path[PATH_MAX] = {};
        snprintf(path, sizeof(path), "/proc/%d/comm", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            return false;
        }
        ssize_t length = read(fd, output, output_size - 1);
        const int read_errno = errno;
        close(fd);
        if (length <= 0) {
            errno = length == 0 ? ENODATA : read_errno;
            return false;
        }
        while (length > 0 && (output[length - 1] == '\n' || output[length - 1] == '\r')) {
            length--;
        }
        output[length] = '\0';
        return output[0] != '\0';
    }

    bool read_process_uid(pid_t pid, uid_t *uid) {
        char path[PATH_MAX] = {};
        snprintf(path, sizeof(path), "/proc/%d/status", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            return false;
        }
        char status[4096] = {};
        ssize_t length = read(fd, status, sizeof(status) - 1);
        const int read_errno = errno;
        close(fd);
        if (length <= 0) {
            errno = length == 0 ? ENODATA : read_errno;
            return false;
        }
        status[length] = '\0';

        char *uid_line = strstr(status, "\nUid:");
        if (uid_line != nullptr) {
            uid_line++;
        } else if (strncmp(status, "Uid:", 4) == 0) {
            uid_line = status;
        } else {
            errno = ENODATA;
            return false;
        }
        char *value = uid_line + 4;
        while (*value == ' ' || *value == '\t') {
            value++;
        }
        char *end = nullptr;
        errno = 0;
        const unsigned long parsed = strtoul(value, &end, 10);
        if (errno != 0 || end == value || parsed > UINT_MAX) {
            errno = EINVAL;
            return false;
        }
        *uid = static_cast<uid_t>(parsed);
        return true;
    }

    bool process_name_matches(const StarterConfig &config, const char *process_name) {
        return strcmp(process_name, config.process_name) == 0;
    }

    ProcessMatch validate_process_uid(pid_t pid) {
        uid_t uid = 0;
        if (read_process_uid(pid, &uid)) {
            return is_supported_starter_uid(uid) ? PROCESS_MATCH : PROCESS_NO_MATCH;
        }
        return errno == ENOENT || errno == ESRCH
               ? PROCESS_NO_MATCH
               : PROCESS_INSPECTION_FAILED;
    }

    ProcessMatch inspect_process(const StarterConfig &config, pid_t pid) {
        char name[PATH_MAX] = {};
        if (read_process_name(pid, name, sizeof(name))) {
            if (!process_name_matches(config, name)) {
                return PROCESS_NO_MATCH;
            }
            return validate_process_uid(pid);
        }
        const int cmdline_errno = errno;

        char comm[sizeof(config.process_comm)] = {};
        if (read_process_comm(pid, comm, sizeof(comm))) {
            if (strcmp(comm, config.process_comm) != 0) {
                return PROCESS_NO_MATCH;
            }
            return validate_process_uid(pid);
        }
        const int comm_errno = errno;
        if (cmdline_errno == ENOENT || cmdline_errno == ESRCH ||
            comm_errno == ENOENT || comm_errno == ESRCH) {
            return PROCESS_NO_MATCH;
        }
        return PROCESS_INSPECTION_FAILED;
    }

    bool append_process(ProcessSnapshot *snapshot, pid_t pid) {
        if (snapshot->count == snapshot->capacity) {
            const size_t next_capacity = snapshot->capacity == 0 ? 4 : snapshot->capacity * 2;
            if (next_capacity < snapshot->capacity ||
                next_capacity > SIZE_MAX / sizeof(pid_t)) {
                errno = ENOMEM;
                return false;
            }
            void *resized = realloc(snapshot->pids, next_capacity * sizeof(pid_t));
            if (resized == nullptr) {
                errno = ENOMEM;
                return false;
            }
            snapshot->pids = static_cast<pid_t *>(resized);
            snapshot->capacity = next_capacity;
        }
        snapshot->pids[snapshot->count++] = pid;
        return true;
    }

    bool collect_existing_servers(
            const StarterConfig &config,
            ProcessSnapshot *snapshot) {
        DIR *proc = opendir("/proc");
        if (proc == nullptr) {
            return false;
        }
        dirent *entry = nullptr;
        while ((entry = readdir(proc)) != nullptr) {
            pid_t pid = 0;
            if (!parse_pid_name(entry->d_name, &pid) || pid == getpid()) {
                continue;
            }
            const ProcessMatch match = inspect_process(config, pid);
            if (match == PROCESS_INSPECTION_FAILED) {
                closedir(proc);
                errno = EACCES;
                return false;
            }
            if (match == PROCESS_MATCH && !append_process(snapshot, pid)) {
                closedir(proc);
                return false;
            }
        }
        closedir(proc);
        return true;
    }

    bool preflight_process_snapshot(
            const StarterConfig &config,
            ProcessSnapshot *snapshot) {
        for (size_t index = 0; index < snapshot->count; ++index) {
            const pid_t pid = snapshot->pids[index];
            const ProcessMatch match = inspect_process(config, pid);
            if (match == PROCESS_INSPECTION_FAILED) {
                errno = EACCES;
                return false;
            }
            if (match != PROCESS_MATCH) {
                snapshot->pids[index] = 0;
                continue;
            }
            if (kill(pid, 0) != 0) {
                if (errno == ESRCH) {
                    snapshot->pids[index] = 0;
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    int scan_existing_server(const StarterConfig &config) {
        DIR *proc = opendir("/proc");
        if (proc == nullptr) {
            return -1;
        }
        int found = 0;
        dirent *entry = nullptr;
        while ((entry = readdir(proc)) != nullptr) {
            pid_t pid = 0;
            if (!parse_pid_name(entry->d_name, &pid) || pid == getpid()) {
                continue;
            }
            const ProcessMatch match = inspect_process(config, pid);
            if (match == PROCESS_INSPECTION_FAILED) {
                closedir(proc);
                errno = EACCES;
                return -1;
            }
            if (match == PROCESS_MATCH) {
                found = 1;
                break;
            }
        }
        closedir(proc);
        return found;
    }

    bool kill_existing_server(const StarterConfig &config) {
        ProcessSnapshot snapshot;
        if (!collect_existing_servers(config, &snapshot)) {
            fprintf(stderr, "fatal: %s: failed to inspect /proc before kill: %s\n",
                    STOP_EXISTING_SERVER_FAILED_MARKER, strerror(errno));
            free(snapshot.pids);
            return false;
        }
        if (!preflight_process_snapshot(config, &snapshot)) {
            fprintf(stderr, "fatal: %s: failed to validate old servers before kill: %s\n",
                    STOP_EXISTING_SERVER_FAILED_MARKER, strerror(errno));
            free(snapshot.pids);
            return false;
        }

        bool failed = false;
        for (size_t index = 0; index < snapshot.count; ++index) {
            const pid_t pid = snapshot.pids[index];
            if (pid == 0) {
                continue;
            }
            if (kill(pid, SIGKILL) == 0) {
                printf("info: killed existing server pid=%d\n", pid);
            } else if (errno != ESRCH) {
                failed = true;
                fprintf(stderr, "fatal: %s: failed to kill existing server pid=%d: %s\n",
                        STOP_EXISTING_SERVER_FAILED_MARKER, pid, strerror(errno));
                break;
            } else {
                printf("info: existing server pid=%d already exited\n", pid);
            }
        }
        free(snapshot.pids);
        if (failed) {
            return false;
        }
        fflush(stdout);
        for (int attempt = 0; attempt < STOP_WAIT_ATTEMPTS; ++attempt) {
            const int existing = scan_existing_server(config);
            if (existing == 0) {
                return true;
            }
            if (existing < 0) {
                fprintf(stderr, "fatal: %s: failed to verify old server exit: %s\n",
                        STOP_EXISTING_SERVER_FAILED_MARKER, strerror(errno));
                return false;
            }
            usleep(STOP_WAIT_INTERVAL_MICROS);
        }
        fprintf(stderr, "fatal: %s: timed out waiting for old server exit\n",
                STOP_EXISTING_SERVER_FAILED_MARKER);
        return false;
    }

    void redirect_child_io() {
        int fd = open("/dev/null", O_RDWR);
        if (fd >= 0) {
            dup2(fd, STDIN_FILENO);
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > STDERR_FILENO) {
                close(fd);
            }
        }
    }

    void exec_app_process(const StarterConfig &config) {
        char *classpath_arg = concat_arg("-Djava.class.path=", config.classpath);
        char *nice_name_arg = concat_arg("--nice-name=", config.process_name);
        if (classpath_arg == nullptr || nice_name_arg == nullptr) {
            fprintf(stderr, "fatal: failed to allocate app_process args\n");
            _exit(4);
        }

        const int app_arg_count = 5;
        char **app_argv = static_cast<char **>(calloc(app_arg_count + 1, sizeof(char *)));
        if (app_argv == nullptr) {
            fprintf(stderr, "fatal: failed to allocate app_process argv\n");
            _exit(4);
        }

        int index = 0;
        app_argv[index++] = const_cast<char *>("/system/bin/app_process");
        app_argv[index++] = classpath_arg;
        app_argv[index++] = const_cast<char *>("/system/bin");
        app_argv[index++] = nice_name_arg;
        app_argv[index++] = const_cast<char *>(DEFAULT_MAIN_CLASS);
        app_argv[index] = nullptr;

        setenv("CLASSPATH", config.classpath, 1);
        execv(app_argv[0], app_argv);
        fprintf(stderr, "fatal: exec app_process failed: %s\n", strerror(errno));
        _exit(5);
    }

    int start_server(const StarterConfig &config) {
        if (!kill_existing_server(config)) {
            return STOP_EXISTING_SERVER_FAILED_EXIT_CODE;
        }

        printf("info: starting server...\n");
        fflush(stdout);
        int ready_pipe[2];
        if (pipe(ready_pipe) != 0) {
            fprintf(stderr, "fatal: pipe failed: %s\n", strerror(errno));
            return 3;
        }

        const pid_t pid = fork();
        if (pid < 0) {
            fprintf(stderr, "fatal: fork failed: %s\n", strerror(errno));
            close(ready_pipe[0]);
            close(ready_pipe[1]);
            return 3;
        }

        if (pid == 0) {
            close(ready_pipe[0]);
            if (setsid() < 0) {
                fprintf(stderr, "fatal: setsid failed: %s\n", strerror(errno));
                _exit(6);
            }
            chdir("/");
            redirect_child_io();

            const char ready = '1';
            write(ready_pipe[1], &ready, 1);
            close(ready_pipe[1]);

            exec_app_process(config);
        }

        close(ready_pipe[1]);
        char ready = '\0';
        const ssize_t read_count = read(ready_pipe[0], &ready, 1);
        close(ready_pipe[0]);
        if (read_count != 1) {
            fprintf(stderr, "fatal: child did not report ready\n");
            return 7;
        }

        printf("info: starter exit with 0\n");
        fflush(stdout);
        return 0;
    }

} // namespace

int main(int argc, char **argv) {
    const uid_t uid = getuid();
    if (!is_supported_starter_uid(uid)) {
        fprintf(
                stderr,
                "fatal: priv-kit starter requires root (uid 0), system (uid 1000), "
                "or shell (uid 2000); current uid=%u\n",
                static_cast<unsigned int>(uid));
        return UNSUPPORTED_UID_EXIT_CODE;
    }
    if (argc != 1) {
        fprintf(stderr, "fatal: priv-kit starter does not accept arguments\n");
        return 2;
    }
    StarterConfig config;
    if (!infer_defaults(argv, &config)) {
        return 2;
    }
    printf("info: starter begin\n");
    fflush(stdout);
    return start_server(config);
}
