#include <jni.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>

struct PtySession {
    int master;
    pid_t pid;
};

static PtySession* fromHandle(jlong handle) {
    return reinterpret_cast<PtySession*>(static_cast<uintptr_t>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_carson_androidtuxterminal_NativePty_nativeStart(
        JNIEnv* env, jobject, jstring cwd, jint columns, jint rows) {
    const char* cwdChars = env->GetStringUTFChars(cwd, nullptr);
    if (cwdChars == nullptr) return 0;

    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);
    if (pid < 0) {
        env->ReleaseStringUTFChars(cwd, cwdChars);
        return 0;
    }

    if (pid == 0) {
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("PS1", "", 1);
        setenv("PS2", "", 1);
        setenv("ANDROID_TUX_TERMINAL", "1", 1);
        chdir(cwdChars);
        execl("/system/bin/sh", "sh", nullptr);
        _exit(127);
    }

    env->ReleaseStringUTFChars(cwd, cwdChars);

    auto* session = new PtySession{master, pid};
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(session));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_carson_androidtuxterminal_NativePty_nativeRead(
        JNIEnv* env, jobject, jlong handle) {
    PtySession* session = fromHandle(handle);
    if (session == nullptr || session->master < 0) return nullptr;

    unsigned char buffer[8192];
    ssize_t count;
    do {
        count = read(session->master, buffer, sizeof(buffer));
    } while (count < 0 && errno == EINTR);

    if (count <= 0) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(count));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(count),
                            reinterpret_cast<const jbyte*>(buffer));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_carson_androidtuxterminal_NativePty_nativeWrite(
        JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    PtySession* session = fromHandle(handle);
    if (session == nullptr || session->master < 0 || data == nullptr) return -1;

    jsize length = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1;

    ssize_t written = 0;
    while (written < length) {
        ssize_t n = write(session->master, bytes + written, length - written);
        if (n > 0) {
            written += n;
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            written = -1;
            break;
        }
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_carson_androidtuxterminal_NativePty_nativeResize(
        JNIEnv*, jobject, jlong handle, jint columns, jint rows) {
    PtySession* session = fromHandle(handle);
    if (session == nullptr || session->master < 0) return -1;

    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    return ioctl(session->master, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT void JNICALL
Java_com_carson_androidtuxterminal_NativePty_nativeClose(
        JNIEnv*, jobject, jlong handle) {
    PtySession* session = fromHandle(handle);
    if (session == nullptr) return;

    if (session->pid > 0) {
        kill(session->pid, SIGHUP);
        kill(session->pid, SIGTERM);
        waitpid(session->pid, nullptr, 0);
    }
    if (session->master >= 0) close(session->master);
    delete session;
}
