#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

namespace {

constexpr size_t MAX_CLASSPATH_LENGTH = 8192;
constexpr size_t MAX_SPLIT_APK_COUNT = 128;
constexpr const char* DEFAULT_MAIN_CLASS = "priv.kit.internal.server.PrivilegeServerMain";
constexpr const char* DEFAULT_PROCESS_SUFFIX = ":priv-kit-server";

struct StarterConfig {
    const char* classpath = nullptr;
    const char* main_class = DEFAULT_MAIN_CLASS;
    const char* process_name = nullptr;
    const char* package_name = nullptr;

    char classpath_buffer[MAX_CLASSPATH_LENGTH] = {};
    char package_name_buffer[256] = {};
    char process_name_buffer[320] = {};
};

bool string_copy(char* output, size_t output_size, const char* value) {
    if (value == nullptr || strlen(value) >= output_size) {
        return false;
    }
    strcpy(output, value);
    return true;
}

bool string_append(char* output, size_t output_size, const char* value) {
    if (value == nullptr || strlen(output) + strlen(value) >= output_size) {
        return false;
    }
    strcat(output, value);
    return true;
}

bool string_append_path(char* output, size_t output_size, const char* dir, const char* file) {
    return string_copy(output, output_size, dir) &&
        string_append(output, output_size, "/") &&
        string_append(output, output_size, file);
}

bool ends_with(const char* value, const char* suffix) {
    const size_t value_length = strlen(value);
    const size_t suffix_length = strlen(suffix);
    return value_length >= suffix_length &&
        strcmp(value + value_length - suffix_length, suffix) == 0;
}

char* concat_arg(const char* prefix, const char* value) {
    const size_t prefix_length = strlen(prefix);
    const size_t value_length = strlen(value);
    char* result = static_cast<char*>(malloc(prefix_length + value_length + 1));
    if (result == nullptr) {
        return nullptr;
    }
    memcpy(result, prefix, prefix_length);
    memcpy(result + prefix_length, value, value_length);
    result[prefix_length + value_length] = '\0';
    return result;
}

int compare_path_string(const void* left, const void* right) {
    return strcmp(static_cast<const char*>(left), static_cast<const char*>(right));
}

bool infer_starter_path(char** argv, char* output, size_t output_size) {
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

bool infer_install_dir(const char* starter_path, char* output, size_t output_size) {
    const char* lib_segment = nullptr;
    const char* search = starter_path;
    const char* match = nullptr;
    while ((match = strstr(search, "/lib/")) != nullptr) {
        lib_segment = match;
        search = match + 1;
    }
    if (lib_segment == nullptr) {
        fprintf(stderr, "fatal: failed to infer install dir from %s\n", starter_path);
        return false;
    }
    const size_t length = static_cast<size_t>(lib_segment - starter_path);
    if (length == 0 || length >= output_size) {
        fprintf(stderr, "fatal: install dir is too long\n");
        return false;
    }
    memcpy(output, starter_path, length);
    output[length] = '\0';
    return true;
}

bool infer_package_name(const char* install_dir, char* output, size_t output_size) {
    const char* basename = strrchr(install_dir, '/');
    basename = basename == nullptr ? install_dir : basename + 1;
    const char* suffix = strchr(basename, '-');
    const size_t length = suffix == nullptr ? strlen(basename) : static_cast<size_t>(suffix - basename);
    if (length == 0 || length >= output_size) {
        fprintf(stderr, "fatal: failed to infer package name from %s\n", install_dir);
        return false;
    }
    memcpy(output, basename, length);
    output[length] = '\0';
    return true;
}

bool build_classpath(const char* install_dir, char* output, size_t output_size) {
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

    DIR* dir = opendir(install_dir);
    if (dir == nullptr) {
        return true;
    }
    char split_apks[MAX_SPLIT_APK_COUNT][PATH_MAX] = {};
    size_t split_apk_count = 0;
    dirent* entry = nullptr;
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
        if (!string_copy(split_apks[split_apk_count], sizeof(split_apks[split_apk_count]), apk_path)) {
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

bool infer_defaults(char** argv, StarterConfig* config) {
    char starter_path[PATH_MAX] = {};
    char install_dir[PATH_MAX] = {};
    if (!infer_starter_path(argv, starter_path, sizeof(starter_path)) ||
        !infer_install_dir(starter_path, install_dir, sizeof(install_dir))) {
        return false;
    }

    if (config->package_name == nullptr) {
        if (!infer_package_name(install_dir, config->package_name_buffer, sizeof(config->package_name_buffer))) {
            return false;
        }
        config->package_name = config->package_name_buffer;
    }
    if (config->classpath == nullptr) {
        if (!build_classpath(install_dir, config->classpath_buffer, sizeof(config->classpath_buffer))) {
            return false;
        }
        config->classpath = config->classpath_buffer;
    }
    if (config->process_name == nullptr) {
        if (!string_copy(config->process_name_buffer, sizeof(config->process_name_buffer), config->package_name) ||
            !string_append(config->process_name_buffer, sizeof(config->process_name_buffer), DEFAULT_PROCESS_SUFFIX)) {
            fprintf(stderr, "fatal: process name is too long\n");
            return false;
        }
        config->process_name = config->process_name_buffer;
    }
    return true;
}

bool parse_pid_name(const char* name, pid_t* pid) {
    char* end = nullptr;
    long value = strtol(name, &end, 10);
    if (end == name || *end != '\0' || value <= 0) {
        return false;
    }
    *pid = static_cast<pid_t>(value);
    return true;
}

bool read_process_name(pid_t pid, char* output, size_t output_size) {
    char path[PATH_MAX] = {};
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        return false;
    }
    ssize_t length = read(fd, output, output_size - 1);
    close(fd);
    if (length <= 0) {
        return false;
    }
    output[length] = '\0';
    return output[0] != '\0';
}

int kill_existing_server(const char* process_name) {
    int killed_count = 0;
    DIR* proc = opendir("/proc");
    if (proc == nullptr) {
        return killed_count;
    }
    dirent* entry = nullptr;
    while ((entry = readdir(proc)) != nullptr) {
        pid_t pid = 0;
        if (!parse_pid_name(entry->d_name, &pid) || pid == getpid()) {
            continue;
        }
        char name[PATH_MAX] = {};
        if (!read_process_name(pid, name, sizeof(name)) || strcmp(name, process_name) != 0) {
            continue;
        }
        if (kill(pid, SIGKILL) == 0) {
            killed_count++;
            printf("info: killed existing server pid=%d\n", pid);
        } else {
            fprintf(stderr, "warn: failed to kill existing server pid=%d: %s\n", pid, strerror(errno));
        }
    }
    closedir(proc);
    return killed_count;
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

void exec_app_process(const StarterConfig& config) {
    char* classpath_arg = concat_arg("-Djava.class.path=", config.classpath);
    char* nice_name_arg = concat_arg("--nice-name=", config.process_name);
    if (classpath_arg == nullptr || nice_name_arg == nullptr) {
        fprintf(stderr, "fatal: failed to allocate app_process args\n");
        _exit(4);
    }

    const int app_arg_count = 5;
    char** app_argv = static_cast<char**>(calloc(app_arg_count + 1, sizeof(char*)));
    if (app_argv == nullptr) {
        fprintf(stderr, "fatal: failed to allocate app_process argv\n");
        _exit(4);
    }

    int index = 0;
    app_argv[index++] = const_cast<char*>("/system/bin/app_process");
    app_argv[index++] = classpath_arg;
    app_argv[index++] = const_cast<char*>("/system/bin");
    app_argv[index++] = nice_name_arg;
    app_argv[index++] = const_cast<char*>(config.main_class);
    app_argv[index] = nullptr;

    setenv("CLASSPATH", config.classpath, 1);
    execv(app_argv[0], app_argv);
    fprintf(stderr, "fatal: exec app_process failed: %s\n", strerror(errno));
    _exit(5);
}

int start_server(const StarterConfig& config) {
    if (kill_existing_server(config.process_name) > 0) {
        fflush(stdout);
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

int main(int argc, char** argv) {
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
