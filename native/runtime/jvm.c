#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>

static char installed_jvm[PATH_MAX];
static char runtime_jvm[PATH_MAX];
static char installed_awt[PATH_MAX];
static char runtime_awt[PATH_MAX];

// The Android runtime obtains its home directly from dladdr, without realpath.
int dladdr(const void *address, Dl_info *info) {
    typedef int (*DlAddr)(const void *, Dl_info *);
    DlAddr original = (DlAddr)dlsym(RTLD_NEXT, "dladdr");
    if (!original) return 0;
    int result = original(address, info);
    if (result && installed_jvm[0] && info->dli_fname
        && strcmp(info->dli_fname, installed_jvm) == 0) info->dli_fname = runtime_jvm;
    else if (result && installed_awt[0] && info->dli_fname
        && strcmp(info->dli_fname, installed_awt) == 0) info->dli_fname = runtime_awt;
    return result;
}

// HotSpot derives its home before reading -Djava.home. Android keeps executable
// libraries in the APK directory, so preserve the runtime layout for this lookup.
char *realpath(const char *path, char *resolved) {
    typedef char *(*RealPath)(const char *, char *);
    RealPath original = (RealPath)dlsym(RTLD_NEXT, "realpath");
    if (!original) return NULL;
    char *result = original(path, resolved);
    if (result && installed_jvm[0] && runtime_jvm[0] && strcmp(result, installed_jvm) == 0) {
        if (resolved) strcpy(result, runtime_jvm);
        else { free(result); result = strdup(runtime_jvm); }
    }
    // AWT locates its headless library beside libawt.so, using dladdr + realpath.
    if (result && installed_awt[0] && strcmp(result, installed_awt) == 0) {
        if (resolved) strcpy(result, runtime_awt);
        else { free(result); result = strdup(runtime_awt); }
    }
    return result;
}

// HotSpot runs in its own child process, separate from Android's ART VM.
int mobile_jvm(const char *home, const char *libraries, const char *bridge,
               const char *server, int heap, int help) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/lib/modules", home);
    if (access(path, R_OK) != 0) {
        fprintf(stderr, "Módulos do Java ausentes: %s\n", path);
        return 1;
    }
    snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", home);
    if (!realpath(path, installed_jvm)) {
        fprintf(stderr, "Biblioteca da JVM ausente: %s\n", path);
        return 1;
    }
    int length = snprintf(runtime_jvm, sizeof(runtime_jvm), "%s/lib/server/libjvm.so", home);
    if (length < 0 || (size_t)length >= sizeof(runtime_jvm)) {
        fprintf(stderr, "Caminho do Java muito longo\n");
        return 1;
    }
    length = snprintf(runtime_awt, sizeof(runtime_awt), "%s/lib/libawt.so", home);
    if (length < 0 || (size_t)length >= sizeof(runtime_awt)) {
        fprintf(stderr, "Caminho da biblioteca AWT muito longo\n");
        return 1;
    }
    if (!realpath(runtime_awt, path)) {
        fprintf(stderr, "Biblioteca AWT ausente: %s\n", runtime_awt);
        return 1;
    }
    strcpy(installed_awt, path);
    snprintf(path, sizeof(path), "%s/libc++_shared.so", libraries);
    if (access(path, R_OK) == 0 && !dlopen(path, RTLD_NOW | RTLD_GLOBAL)) {
        fprintf(stderr, "C++ runtime: %s\n", dlerror());
        return 1;
    }
    snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", home);
    fprintf(stderr, "Java home: %s\nJVM: %s\n", home, installed_jvm);
    void *library = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!library) { fprintf(stderr, "Java runtime: %s\n", dlerror()); return 1; }
    typedef jint (*CreateVM)(JavaVM **, void **, void *);
    CreateVM create = (CreateVM)dlsym(library, "JNI_CreateJavaVM");
    if (!create) { fprintf(stderr, "JNI_CreateJavaVM ausente\n"); return 1; }
    char java_home[4096], native_path[4096], class_path[4096], heap_size[64], jna_path[4096], temporary[4096];
    snprintf(java_home, sizeof(java_home), "-Djava.home=%s", home);
    snprintf(native_path, sizeof(native_path), "-Djava.library.path=%s", libraries);
    snprintf(class_path, sizeof(class_path), "-Djava.class.path=%s", bridge);
    snprintf(heap_size, sizeof(heap_size), "-Xmx%dm", heap);
    const char *jna_directory = getenv("JNA_LIBRARY_PATH");
    snprintf(jna_path, sizeof(jna_path), "-Djna.boot.library.path=%s", jna_directory ? jna_directory : libraries);
    const char *tempdir = getenv("TMPDIR");
    snprintf(temporary, sizeof(temporary), "-Djava.io.tmpdir=%s", tempdir ? tempdir : "/tmp");
    JavaVMOption options[] = {
        {java_home, NULL}, {native_path, NULL}, {class_path, NULL}, {heap_size, NULL},
        {"-Xms256m", NULL}, {"-Djava.awt.headless=true", NULL},
        {"--enable-native-access=ALL-UNNAMED", NULL},
        {"-Djna.nosys=true", NULL}, {jna_path, NULL}, {temporary, NULL},
        {"-Djava.net.preferIPv4Stack=true", NULL}, {"-Dfile.encoding=UTF-8", NULL}
    };
    JavaVMInitArgs config = {JNI_VERSION_1_8, sizeof(options) / sizeof(options[0]), options, JNI_FALSE};
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    if (create(&vm, (void **)&env, &config) != JNI_OK) {
        fprintf(stderr, "Não foi possível iniciar a JVM. Consulte os caminhos e o erro acima.\n"); return 1;
    }
    jclass main = (*env)->FindClass(env, "io/oxide/bootstrap/FabricBootstrap");
    if (!main) goto failed;
    jmethodID method = (*env)->GetStaticMethodID(env, main, "main", "([Ljava/lang/String;)V");
    if (!method) goto failed;
    jclass string = (*env)->FindClass(env, "java/lang/String");
    if (!string) goto failed;
    jobjectArray args = (*env)->NewObjectArray(env, help ? 2 : 1, string, NULL);
    if (!args) goto failed;
    (*env)->SetObjectArrayElement(env, args, 0, (*env)->NewStringUTF(env, server));
    if (help) (*env)->SetObjectArrayElement(env, args, 1, (*env)->NewStringUTF(env, "--help"));
    (*env)->CallStaticVoidMethod(env, main, method, args);
    if ((*env)->ExceptionCheck(env)) goto failed;
    return (*vm)->DestroyJavaVM(vm) == JNI_OK ? 0 : 1;
failed:
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    (*vm)->DestroyJavaVM(vm);
    return 1;
}
