#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

namespace {

constexpr size_t MAX_CLASSPATH_LENGTH = 8192;
constexpr size_t MAX_CLASSPATH_IDENTITY_LENGTH = 16384;
constexpr const char* DEFAULT_MAIN_CLASS = "priv.kit.server.PrivilegeServerMain";
constexpr const char* DEFAULT_MODE = "2";
constexpr const char* DEFAULT_PROTOCOL_VERSION = "7";
constexpr const char* DEFAULT_SERVER_VERSION = "0.1.0-SNAPSHOT";
constexpr const char* DEFAULT_FOLLOW_DEATH_DELAY_MILLIS = "600000";
constexpr const char* DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH = "false";
constexpr const char* DEFAULT_PROCESS_SUFFIX = ":priv-kit-server";
constexpr const char* DEFAULT_PROVIDER_SUFFIX = ".privilege.handshake";
constexpr const char* DEFAULT_LOG_PREFIX = "/data/local/tmp/priv-kit-server-manual-";

struct StarterConfig {
    const char* classpath = nullptr;
    const char* main_class = DEFAULT_MAIN_CLASS;
    const char* process_name = nullptr;
    const char* log_path = nullptr;
    const char* token = nullptr;
    const char* classpath_identity = nullptr;
    const char* package_name = nullptr;
    const char* provider_authority = nullptr;
    const char* mode = DEFAULT_MODE;
    const char* protocol_version = DEFAULT_PROTOCOL_VERSION;
    const char* server_version = DEFAULT_SERVER_VERSION;
    const char* follow_death_delay_millis = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS;
    const char* active_reconnect_on_owner_death = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH;
    int server_arg_start = -1;

    char classpath_buffer[MAX_CLASSPATH_LENGTH] = {};
    char classpath_identity_buffer[MAX_CLASSPATH_IDENTITY_LENGTH] = {};
    char package_name_buffer[256] = {};
    char provider_authority_buffer[320] = {};
    char process_name_buffer[320] = {};
    char log_path_buffer[PATH_MAX] = {};
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

const char* require_value(int argc, char** argv, int* index, const char* option) {
    if (*index + 1 >= argc) {
        fprintf(stderr, "fatal: missing value for %s\n", option);
        return nullptr;
    }
    *index += 1;
    return argv[*index];
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
    dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (!ends_with(entry->d_name, ".apk") || strcmp(entry->d_name, "base.apk") == 0) {
            continue;
        }
        char apk_path[PATH_MAX] = {};
        if (!string_append_path(apk_path, sizeof(apk_path), install_dir, entry->d_name)) {
            closedir(dir);
            fprintf(stderr, "fatal: split APK path is too long\n");
            return false;
        }
        if (!string_append(output, output_size, ":") ||
            !string_append(output, output_size, apk_path)) {
            closedir(dir);
            fprintf(stderr, "fatal: classpath is too long\n");
            return false;
        }
    }
    closedir(dir);
    return true;
}

bool append_path_identity(char* output, size_t output_size, const char* path) {
    struct stat stat_buffer = {};
    if (stat(path, &stat_buffer) != 0) {
        fprintf(stderr, "fatal: can't stat classpath entry %s\n", path);
        return false;
    }
    char metadata[64] = {};
    snprintf(
        metadata,
        sizeof(metadata),
        "@%lld@%lld",
        static_cast<long long>(stat_buffer.st_size),
        static_cast<long long>(stat_buffer.st_mtime));
    return string_append(output, output_size, path) &&
        string_append(output, output_size, metadata);
}

bool build_classpath_identity(const char* classpath, char* output, size_t output_size) {
    output[0] = '\0';
    const char* cursor = classpath;
    while (cursor != nullptr && *cursor != '\0') {
        const char* separator = strchr(cursor, ':');
        const size_t length = separator == nullptr
            ? strlen(cursor)
            : static_cast<size_t>(separator - cursor);
        if (length > 0) {
            char path[PATH_MAX] = {};
            if (length >= sizeof(path)) {
                fprintf(stderr, "fatal: classpath entry is too long\n");
                return false;
            }
            memcpy(path, cursor, length);
            path[length] = '\0';
            if (output[0] != '\0' && !string_append(output, output_size, ":")) {
                fprintf(stderr, "fatal: classpath identity is too long\n");
                return false;
            }
            if (!append_path_identity(output, output_size, path)) {
                return false;
            }
        }
        if (separator == nullptr) {
            break;
        }
        cursor = separator + 1;
    }
    if (output[0] == '\0') {
        fprintf(stderr, "fatal: classpath identity is empty\n");
        return false;
    }
    return true;
}

bool infer_defaults(int argc, char** argv, StarterConfig* config) {
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
    if (config->classpath_identity == nullptr) {
        if (!build_classpath_identity(
                config->classpath,
                config->classpath_identity_buffer,
                sizeof(config->classpath_identity_buffer))) {
            return false;
        }
        config->classpath_identity = config->classpath_identity_buffer;
    }
    if (config->provider_authority == nullptr) {
        if (!string_copy(config->provider_authority_buffer, sizeof(config->provider_authority_buffer), config->package_name) ||
            !string_append(config->provider_authority_buffer, sizeof(config->provider_authority_buffer), DEFAULT_PROVIDER_SUFFIX)) {
            fprintf(stderr, "fatal: provider authority is too long\n");
            return false;
        }
        config->provider_authority = config->provider_authority_buffer;
    }
    if (config->process_name == nullptr) {
        if (!string_copy(config->process_name_buffer, sizeof(config->process_name_buffer), config->package_name) ||
            !string_append(config->process_name_buffer, sizeof(config->process_name_buffer), DEFAULT_PROCESS_SUFFIX)) {
            fprintf(stderr, "fatal: process name is too long\n");
            return false;
        }
        config->process_name = config->process_name_buffer;
    }
    if (config->log_path == nullptr) {
        snprintf(
            config->log_path_buffer,
            sizeof(config->log_path_buffer),
            "%s%ld-%d.log",
            DEFAULT_LOG_PREFIX,
            static_cast<long>(time(nullptr)),
            getpid());
        config->log_path = config->log_path_buffer;
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

void kill_existing_server(const char* process_name) {
    DIR* proc = opendir("/proc");
    if (proc == nullptr) {
        return;
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
            printf("priv-kit-killed-existing-server-pid=%d\n", pid);
        } else {
            fprintf(stderr, "warn: failed to kill existing server pid=%d: %s\n", pid, strerror(errno));
        }
    }
    closedir(proc);
}

bool parse_args(int argc, char** argv, StarterConfig* config) {
    for (int i = 1; i < argc; ++i) {
        const char* arg = argv[i];
        if (strcmp(arg, "--") == 0) {
            config->server_arg_start = i + 1;
            break;
        }
        if (strcmp(arg, "--classpath") == 0) {
            config->classpath = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--main-class") == 0) {
            config->main_class = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--process-name") == 0) {
            config->process_name = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--log-path") == 0) {
            config->log_path = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--token") == 0) {
            config->token = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--classpath-identity") == 0) {
            config->classpath_identity = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--package-name") == 0) {
            config->package_name = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--provider-authority") == 0) {
            config->provider_authority = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--mode") == 0 || strcmp(arg, "--launch-mode") == 0) {
            config->mode = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--protocol-version") == 0) {
            config->protocol_version = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--server-version") == 0) {
            config->server_version = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--follow-death-delay-millis") == 0) {
            config->follow_death_delay_millis = require_value(argc, argv, &i, arg);
        } else if (strcmp(arg, "--active-reconnect-on-owner-death") == 0) {
            config->active_reconnect_on_owner_death = require_value(argc, argv, &i, arg);
        } else {
            fprintf(stderr, "fatal: unknown option %s\n", arg);
            return false;
        }
        if ((strcmp(arg, "--classpath") == 0 && config->classpath == nullptr) ||
            (strcmp(arg, "--main-class") == 0 && config->main_class == nullptr) ||
            (strcmp(arg, "--process-name") == 0 && config->process_name == nullptr) ||
            (strcmp(arg, "--log-path") == 0 && config->log_path == nullptr) ||
            (strcmp(arg, "--token") == 0 && config->token == nullptr) ||
            (strcmp(arg, "--classpath-identity") == 0 && config->classpath_identity == nullptr) ||
            (strcmp(arg, "--package-name") == 0 && config->package_name == nullptr) ||
            (strcmp(arg, "--provider-authority") == 0 && config->provider_authority == nullptr) ||
            ((strcmp(arg, "--mode") == 0 || strcmp(arg, "--launch-mode") == 0) && config->mode == nullptr) ||
            (strcmp(arg, "--protocol-version") == 0 && config->protocol_version == nullptr) ||
            (strcmp(arg, "--server-version") == 0 && config->server_version == nullptr) ||
            (strcmp(arg, "--follow-death-delay-millis") == 0 &&
                config->follow_death_delay_millis == nullptr) ||
            (strcmp(arg, "--active-reconnect-on-owner-death") == 0 &&
                config->active_reconnect_on_owner_death == nullptr)) {
            return false;
        }
    }

    if (!infer_defaults(argc, argv, config)) {
        return false;
    }

    if (config->server_arg_start < 0 && config->token == nullptr) {
        fprintf(
            stderr,
            "fatal: usage: %s --token <token> [starter options]\n"
            "       or: %s --classpath <path> --main-class <class> "
            "--process-name <name> --log-path <path> -- <server args>\n",
            argv[0],
            argv[0]);
        return false;
    }
    return true;
}

void redirect_child_io(const char* log_path) {
    int input_fd = open("/dev/null", O_RDONLY);
    if (input_fd >= 0) {
        dup2(input_fd, STDIN_FILENO);
        if (input_fd > STDERR_FILENO) {
            close(input_fd);
        }
    }

    int output_fd = open(log_path, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (output_fd < 0) {
        output_fd = open("/dev/null", O_WRONLY);
    }
    if (output_fd >= 0) {
        dup2(output_fd, STDOUT_FILENO);
        dup2(output_fd, STDERR_FILENO);
        if (output_fd > STDERR_FILENO) {
            close(output_fd);
        }
    }
}

void exec_app_process(const StarterConfig& config, int argc, char** argv) {
    char* classpath_arg = concat_arg("-Djava.class.path=", config.classpath);
    char* nice_name_arg = concat_arg("--nice-name=", config.process_name);
    if (classpath_arg == nullptr || nice_name_arg == nullptr) {
        fprintf(stderr, "fatal: failed to allocate app_process args\n");
        _exit(4);
    }

    const bool use_argv_server_args = config.server_arg_start >= 0;
    const int server_arg_count = use_argv_server_args ? argc - config.server_arg_start : 18;
    const int app_arg_count = 5 + server_arg_count;
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
    if (use_argv_server_args) {
        for (int i = config.server_arg_start; i < argc; ++i) {
            app_argv[index++] = argv[i];
        }
    } else {
        app_argv[index++] = const_cast<char*>("--token");
        app_argv[index++] = const_cast<char*>(config.token);
        app_argv[index++] = const_cast<char*>("--provider-authority");
        app_argv[index++] = const_cast<char*>(config.provider_authority);
        app_argv[index++] = const_cast<char*>("--package-name");
        app_argv[index++] = const_cast<char*>(config.package_name);
        app_argv[index++] = const_cast<char*>("--launch-mode");
        app_argv[index++] = const_cast<char*>(config.mode);
        app_argv[index++] = const_cast<char*>("--protocol-version");
        app_argv[index++] = const_cast<char*>(config.protocol_version);
        app_argv[index++] = const_cast<char*>("--server-version");
        app_argv[index++] = const_cast<char*>(config.server_version);
        app_argv[index++] = const_cast<char*>("--classpath-identity");
        app_argv[index++] = const_cast<char*>(config.classpath_identity);
        app_argv[index++] = const_cast<char*>("--follow-death-delay-millis");
        app_argv[index++] = const_cast<char*>(config.follow_death_delay_millis);
        app_argv[index++] = const_cast<char*>("--active-reconnect-on-owner-death");
        app_argv[index++] = const_cast<char*>(config.active_reconnect_on_owner_death);
    }
    app_argv[index] = nullptr;

    setenv("CLASSPATH", config.classpath, 1);
    execv(app_argv[0], app_argv);
    fprintf(stderr, "fatal: exec app_process failed: %s\n", strerror(errno));
    _exit(5);
}

int start_server(const StarterConfig& config, int argc, char** argv) {
    kill_existing_server(config.process_name);

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
        redirect_child_io(config.log_path);
        printf("priv-kit-starter child pid=%d\n", getpid());
        fflush(stdout);

        const char ready = '1';
        write(ready_pipe[1], &ready, 1);
        close(ready_pipe[1]);

        exec_app_process(config, argc, argv);
    }

    close(ready_pipe[1]);
    char ready = '\0';
    const ssize_t read_count = read(ready_pipe[0], &ready, 1);
    close(ready_pipe[0]);
    if (read_count != 1) {
        fprintf(stderr, "fatal: child did not report ready\n");
        return 7;
    }

    printf("priv-kit-starter-pid=%d\n", pid);
    printf("priv-kit-server-log=%s\n", config.log_path);
    fflush(stdout);
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    StarterConfig config;
    if (!parse_args(argc, argv, &config)) {
        return 2;
    }
    return start_server(config, argc, argv);
}
