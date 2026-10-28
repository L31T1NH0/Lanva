#!/usr/bin/env python3
"""Exercise Android's direct dladdr path and a real relocated host JVM, without a world/EULA."""
from pathlib import Path
import os
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build/fabric/runtime-check-clean"
JAVA = Path(os.environ.get("FABRIC_BUILD_JAVA_HOME", Path.home() / ".local/share/PrismLauncher/java/java-runtime-epsilon"))
HEADERS = JAVA if (JAVA / "include/jni.h").exists() else ROOT.parent / "Rowd/.toolchain/jdk"
COMPILER = JAVA if (JAVA / "bin/javac").exists() else HEADERS
BUILD.mkdir(parents=True, exist_ok=True)


def run(*args, **kwargs):
    kwargs.setdefault("check", True)
    return subprocess.run([str(arg) for arg in args], **kwargs)


launcher = BUILD / "launcher"
run("cc", "-O2", "-Wall", "-Wextra", "-I" + str(HEADERS / "include"), "-I" + str(HEADERS / "include/linux"),
    ROOT / "native/runtime/launcher.c", ROOT / "native/runtime/jvm.c", "-ldl",
    "-Wl,--export-dynamic-symbol=realpath", "-Wl,--export-dynamic-symbol=dladdr", "-o", launcher)
# The Android runtime copies dladdr's filename directly. Preloading ensures that
# dladdr returns the physical APK-style location, even when dlopen uses a symlink.
source = BUILD / "android-jvm.c"
source.write_text(r'''
#define _GNU_SOURCE
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
JNIEXPORT jint JNICALL JNI_CreateJavaVM(JavaVM **vm, void **env, void *args) {
    (void)vm; (void)env; (void)args;
    Dl_info info;
    char expected[4096];
    snprintf(expected, sizeof(expected), "%s/lib/server/libjvm.so", getenv("JAVA_HOME"));
    if (!dladdr((void *)JNI_CreateJavaVM, &info) || strcmp(info.dli_fname, expected)) {
        fprintf(stderr, "Wrong Android JVM layout: %s\n", info.dli_fname); exit(1);
    }
    puts("PASS: Android-style dladdr points to runtime/lib/server/libjvm.so");
    exit(0);
}
''')
fake_native = BUILD / "android-native"
fake_native.mkdir(exist_ok=True)
fake_vm = fake_native / "libjava25_lib_server_libjvm_so.so"
run("cc", "-shared", "-fPIC", "-I" + str(HEADERS / "include"), "-I" + str(HEADERS / "include/linux"), source, "-ldl", "-o", fake_vm)
fake_home = BUILD / "android-java"
(fake_home / "lib/server").mkdir(parents=True, exist_ok=True)
(fake_home / "lib/modules").write_text("probe")
(fake_home / "lib/libawt.so").touch()
link = fake_home / "lib/server/libjvm.so"
link.unlink(missing_ok=True)
link.symlink_to(fake_vm)
probe_jar = BUILD / "probe.jar"
# The Android-path probe exits inside the stub before touching the jar.
probe_jar.touch()
environment = dict(os.environ, JAVA_HOME=str(fake_home), LD_PRELOAD=str(fake_vm))
run(launcher, fake_home, fake_native, probe_jar, probe_jar, "512", env=environment)
# Prove that the previous realpath-only workaround fails this same path.
old_source = BUILD / "jvm-realpath-only.c"
old_source.write_text((ROOT / "native/runtime/jvm.c").read_text().replace("int dladdr(", "int unused_dladdr("))
old_launcher = BUILD / "launcher-realpath-only"
run("cc", "-O2", "-I" + str(HEADERS / "include"), "-I" + str(HEADERS / "include/linux"),
    ROOT / "native/runtime/launcher.c", old_source, "-ldl", "-Wl,--export-dynamic-symbol=realpath", "-o", old_launcher)
old = run(old_launcher, fake_home, fake_native, probe_jar, probe_jar, "512", env=environment,
          capture_output=True, text=True, check=False)
assert old.returncode != 0 and "Wrong Android JVM layout" in old.stderr, old.stdout + old.stderr
print("PASS: regression reproduced without dladdr correction")


# Load a real copied libjvm through the same layout used on Android.
real_native = BUILD / "host-native"
real_native.mkdir(exist_ok=True)
real_vm = real_native / "libjava25_lib_server_libjvm_so.so"
shutil.copy2(JAVA / "lib/server/libjvm.so", real_vm)
real_home = BUILD / "host-java"
if real_home.exists(): shutil.rmtree(real_home)
(real_home / "lib/server").mkdir(parents=True, exist_ok=True)
for path in (JAVA / "lib").iterdir():
    if path.name == "server":
        continue
    target = real_home / "lib" / path.name
    if path.name in ("libawt.so", "libawt_headless.so"):
        packaged = real_native / ("libjava25_lib_" + path.name.replace(".", "_") + ".so")
        shutil.copy2(path, packaged)
        target.symlink_to(packaged)
    elif not target.exists(): target.symlink_to(path)
for name in ("conf", "release"):
    target = real_home / name
    if not target.exists(): target.symlink_to(JAVA / name)
link = real_home / "lib/server/libjvm.so"
link.unlink(missing_ok=True)
link.symlink_to(real_vm)
java_source = BUILD / "Probe.java"
java_source.write_text('''public final class Probe {
    public static void main(String[] args) throws Exception {
        if (!"nogui".equals(args[0])) throw new AssertionError("server args");
        if (System.getProperty("mcopt.native.path") != null)
            throw new AssertionError("mcopt must not be configured automatically");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 2,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff123456);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image, "png", png)) throw new AssertionError("PNG writer");
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(png.toByteArray()));
        if (decoded.getRGB(0, 0) != 0xff123456) throw new AssertionError("PNG pixel round trip");
        System.out.println("PASS: relocated real JVM + Fabric bootstrap, Java " + System.getProperty("java.version"));
    }
}
''')
classes = BUILD / "classes"
classes.mkdir(exist_ok=True)
run(COMPILER / "bin/javac", "--release", "17", "-d", classes, java_source)
run(COMPILER / "bin/jar", "--create", "--file", probe_jar, "--main-class", "Probe", "-C", classes, ".")
bootstrap = ROOT / "app/src/main/assets/fabric-bootstrap.jar"
environment = dict(os.environ, JAVA_HOME=str(real_home), LD_LIBRARY_PATH=str(JAVA / "lib") + ":" + str(JAVA / "lib/server"))
result = run(launcher, real_home, real_native, bootstrap, probe_jar, "512", env=environment, capture_output=True, text=True, timeout=60)
assert "PASS: relocated real JVM" in result.stdout, result.stdout + result.stderr
print(result.stdout.strip())
print("PASS: relocated AWT + BufferedImage + PNG encode/decode")
# The old JVM-only mapping must reproduce the Geyser startup failure.
old_awt_source = BUILD / "jvm-without-awt.c"
old_awt_source.write_text((ROOT / "native/runtime/jvm.c").read_text().replace("installed_awt[0]", "0"))
old_awt_launcher = BUILD / "launcher-without-awt"
run("cc", "-O2", "-I" + str(HEADERS / "include"), "-I" + str(HEADERS / "include/linux"),
    ROOT / "native/runtime/launcher.c", old_awt_source, "-ldl",
    "-Wl,--export-dynamic-symbol=realpath", "-Wl,--export-dynamic-symbol=dladdr", "-o", old_awt_launcher)
old_awt = run(old_awt_launcher, real_home, real_native, bootstrap, probe_jar, "512",
              env=environment, capture_output=True, text=True, timeout=60, check=False)
assert old_awt.returncode != 0 and "Can't load library:" in old_awt.stderr and "libawt_headless.so" in old_awt.stderr, old_awt.stdout + old_awt.stderr
print("PASS: original AWT loading failure reproduced without path correction")

# If the official Fabric launcher and its dependencies were cached, also run
# the real server entrypoint in help mode. No mods, EULA or world are installed.
cache = ROOT / "build/fabric/host-help"
java_major = int(re.search(r'JAVA_VERSION="(\d+)', (JAVA / "release").read_text()).group(1))
if java_major >= 25 and (cache / "fabric-server.jar").exists() and (cache / "libraries").exists():
    server = BUILD / "fabric-server"
    server.mkdir(exist_ok=True)
    shutil.copy2(cache / "fabric-server.jar", server / "fabric-server.jar")
    for name in ("libraries", "versions", ".fabric"):
        target = server / name
        if not target.exists(): target.symlink_to(cache / name)
    result = run(launcher, real_home, real_native, bootstrap, server / "fabric-server.jar", "512", "--help",
                 cwd=server, env=environment, capture_output=True, text=True, timeout=60, check=False)
    (BUILD / "fabric-help.log").write_text(result.stdout + result.stderr)
    assert result.returncode == 0 and "nogui" in result.stdout + result.stderr, result.stdout + result.stderr
    assert not (server / "world").exists()
    assert not (server / "eula.txt").exists()
    print("PASS: real Fabric/Minecraft help, no mods/world/EULA")
else:
    print("SKIP: Fabric 26.3 help requires Java 25 and cached launcher/libraries (JVM and bootstrap tested)")
