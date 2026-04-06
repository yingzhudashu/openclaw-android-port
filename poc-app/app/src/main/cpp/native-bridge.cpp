#include <jni.h>
#include <string>
#include <cstdlib>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>

#include "node.h"

static const char *ADBTAG = "NODEJS-MOBILE";

// Prevent calling node::Start more than once per process
static bool g_node_started = false;

// stdout/stderr → logcat redirection
static int pipe_stdout[2];
static int pipe_stderr[2];
static pthread_t thread_stdout;
static pthread_t thread_stderr;

static void *thread_stderr_func(void *) {
    ssize_t n;
    char buf[2048];
    while ((n = read(pipe_stderr[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return nullptr;
}

static void *thread_stdout_func(void *) {
    ssize_t n;
    char buf[2048];
    while ((n = read(pipe_stdout[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, buf);
    }
    return nullptr;
}

static int start_redirecting_stdout_stderr() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);

    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);

    if (pthread_create(&thread_stdout, nullptr, thread_stdout_func, nullptr) == -1)
        return -1;
    pthread_detach(thread_stdout);

    if (pthread_create(&thread_stderr, nullptr, thread_stderr_func, nullptr) == -1)
        return -1;
    pthread_detach(thread_stderr);

    return 0;
}

// JNI entry point
extern "C" JNIEXPORT jint JNICALL
Java_ai_openclaw_poc_NodeRunner_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments,
        jstring modulesPath,
        jboolean redirectToLogcat) {

    // node::Start can only be called once per process
    if (g_node_started) {
        __android_log_write(ANDROID_LOG_WARN, ADBTAG,
                            "node::Start already called, returning -2 (use health check instead)");
        return -2;
    }
    g_node_started = true;

    // Set NODE_PATH
    const char *path_str = env->GetStringUTFChars(modulesPath, nullptr);
    if (path_str && path_str[0] != '\0') {
        setenv("NODE_PATH", path_str, 1);
    }
    env->ReleaseStringUTFChars(modulesPath, path_str);

    // Build contiguous argv (required by libuv)
    jsize argc = env->GetArrayLength(arguments);
    int args_total = 0;
    for (int i = 0; i < argc; i++) {
        auto jarg = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *s = env->GetStringUTFChars(jarg, nullptr);
        args_total += (int) strlen(s) + 1;
        env->ReleaseStringUTFChars(jarg, s);
        env->DeleteLocalRef(jarg);
    }

    char *args_buf = (char *) calloc(args_total, sizeof(char));
    char **argv = new char *[argc];

    char *pos = args_buf;
    for (int i = 0; i < argc; i++) {
        auto jarg = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *s = env->GetStringUTFChars(jarg, nullptr);
        size_t len = strlen(s);
        memcpy(pos, s, len);
        pos[len] = '\0';
        argv[i] = pos;
        pos += len + 1;
        env->ReleaseStringUTFChars(jarg, s);
        env->DeleteLocalRef(jarg);
    }

    // Redirect stdout/stderr to logcat
    if (redirectToLogcat) {
        if (start_redirecting_stdout_stderr() == -1) {
            __android_log_write(ANDROID_LOG_ERROR, ADBTAG,
                                "Failed to redirect stdout/stderr to logcat");
        }
    }

    __android_log_print(ANDROID_LOG_INFO, ADBTAG,
                        "Starting node::Start with %d arguments", argc);
    for (int i = 0; i < argc; i++) {
        __android_log_print(ANDROID_LOG_INFO, ADBTAG, "  argv[%d] = %s", i, argv[i]);
    }

    // Call node::Start (blocks until Node.js exits)
    int exit_code = node::Start(argc, argv);

    __android_log_print(ANDROID_LOG_INFO, ADBTAG,
                        "node::Start returned exit code %d", exit_code);

    delete[] argv;
    free(args_buf);

    return (jint) exit_code;
}
