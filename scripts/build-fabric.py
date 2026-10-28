#!/usr/bin/env python3
"""Package Android Java runtimes and a small C launcher. Servers are installed on demand."""
from pathlib import Path
import hashlib
import io
import os
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build/fabric"
ASSETS = ROOT / "app/src/main/assets"
NATIVE = ROOT / "app/src/main/jniLibs/arm64-v8a"
JAVA = Path(os.environ.get("FABRIC_BUILD_JAVA_HOME", Path.home() / ".local/share/PrismLauncher/java/java-runtime-epsilon"))
SDK = Path(os.environ.get("ANDROID_HOME", ROOT.parent / "Rowd/.toolchain/android-sdk"))
NDK = SDK / "ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
FILES = {
    "zalith.apk": ("https://github.com/ZalithLauncher/ZalithLauncher2/releases/download/2.7/ZalithLauncher-2.7-arm64-v8a.apk", "102f5caa938de900da6d12820ebc1db1045358d4852d9dcc5cea0a49c29eb67e"),
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(name, url, expected):
    path = BUILD / name
    if path.is_file() and digest(path) == expected:
        return path
    temporary = path.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as output:
        shutil.copyfileobj(source, output)
    if digest(temporary) != expected:
        temporary.unlink()
        raise RuntimeError(f"Checksum mismatch: {name}")
    temporary.replace(path)
    return path


def package_runtime(apk, major):
    runtime = BUILD / f"java{major}"
    if runtime.exists():
        shutil.rmtree(runtime)
    runtime.mkdir()
    for name in ("bin-arm64.tar.xz", "universal.tar.xz"):
        with tarfile.open(fileobj=io.BytesIO(apk.read(f"assets/runtimes/jre-{major}/{name}")), mode="r:xz") as archive:
            archive.extractall(runtime, filter="data")
    links = []
    for path in sorted(runtime.rglob("*")):
        if not path.is_file():
            continue
        relative = str(path.relative_to(runtime))
        if path.suffix == ".so" or relative in ("lib/jspawnhelper", "lib/jexec", "bin/java"):
            # APK names avoid collisions; symlinks retain each JDK's original layout.
            name = f"libjava{major}_{relative.replace('/', '_').replace('.', '_')}.so"
            shutil.copy2(path, NATIVE / name)
            links.append(f"{relative}\t{name}")
    (ASSETS / f"java{major}-native-links.txt").write_text("\n".join(links) + "\n")
    excluded = {line.split("\t")[0] for line in links}
    with zipfile.ZipFile(ASSETS / f"java{major}.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(runtime.rglob("*")):
            relative = str(path.relative_to(runtime))
            if path.is_file() and relative not in excluded and (relative == "release" or relative.split('/')[0] in ("lib", "conf", "legal")):
                archive.write(path, relative)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    if not (JAVA / "bin/javac").is_file():
        raise RuntimeError("Defina FABRIC_BUILD_JAVA_HOME para um JDK 17 ou mais recente.")
    for name, (url, expected) in FILES.items():
        download(name, url, expected)
    # This directory contains generated build output only.
    if NATIVE.exists():
        shutil.rmtree(NATIVE)
    NATIVE.mkdir(parents=True)
    with zipfile.ZipFile(BUILD / "zalith.apk") as apk:
        for major in (17, 21, 25):
            package_runtime(apk, major)
        (NATIVE / "libc++_shared.so").write_bytes(apk.read("lib/arm64-v8a/libc++_shared.so"))
        with zipfile.ZipFile(io.BytesIO(apk.read("assets/runtimes/jna/jna-arm64.zip"))) as jna:
            for name in jna.namelist():
                if name.endswith("/libjnidispatch.so"):
                    version = name.split("/")[1]
                    (NATIVE / ("libjna" + version.replace(".", "_") + ".so")).write_bytes(jna.read(name))
    classes = BUILD / "bootstrap-classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    subprocess.run([str(JAVA / "bin/javac"), "--release", "17", "-d", str(classes), str(ROOT / "native/runtime/FabricBootstrap.java")], check=True)
    subprocess.run([str(JAVA / "bin/jar"), "--create", "--file", str(ASSETS / "fabric-bootstrap.jar"), "-C", str(classes), "."], check=True)
    headers = JAVA if (JAVA / "include/jni.h").exists() else SDK.parent / "jdk"
    subprocess.run([str(NDK / "aarch64-linux-android26-clang"), "-O2", "-fPIE", "-pie", "-Wall", "-Wextra",
                    "-I" + str(headers / "include"), "-I" + str(headers / "include/linux"),
                    str(ROOT / "native/runtime/launcher.c"), str(ROOT / "native/runtime/jvm.c"), "-ldl",
                    "-Wl,-z,max-page-size=16384", "-Wl,--export-dynamic-symbol=realpath",
                    "-Wl,--export-dynamic-symbol=dladdr", "-o", str(NATIVE / "libfabric_launcher.so")], check=True)
    (ASSETS / "fabric-server.jar").unlink(missing_ok=True)
    if (ASSETS / "server-mods").exists():
        shutil.rmtree(ASSETS / "server-mods")
    (ASSETS / "mcopt-server-26.3.jar").unlink(missing_ok=True)
    print("Java Android 17/21/25 e launcher Fabric preparados, sem mods incluídos.")


if __name__ == "__main__":
    main()
