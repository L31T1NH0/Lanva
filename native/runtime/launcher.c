#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <sys/prctl.h>
#include <unistd.h>

int mobile_jvm(const char *, const char *, const char *, const char *, int, int);

int main(int argc, char **argv) {
    pid_t parent = getppid();
    // Vanilla does not stop on stdin EOF; keep the child tied to the app.
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent) return 1;
    if (argc != 6 && argc != 7) {
        fprintf(stderr, "Uso: launcher JAVA_HOME NATIVE_LIB_DIR BRIDGE_JAR SERVER_JAR HEAP_MB [--help]\n");
        return 2;
    }
    char *end;
    long heap = strtol(argv[5], &end, 10);
    if (*end || heap < 512 || heap > 4096 || access(argv[3], R_OK) || access(argv[4], R_OK)) {
        fprintf(stderr, "Runtime ou servidor ausente, ou memória inválida\n");
        return 2;
    }
    return mobile_jvm(argv[1], argv[2], argv[3], argv[4], (int)heap, argc == 7);
}
