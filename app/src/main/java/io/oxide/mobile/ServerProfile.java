package io.oxide.mobile;

import java.io.File;

/** Each Minecraft release owns its world, libraries, mods and settings. */
final class ServerProfile {
    final String version;
    final int javaMajor;
    final File directory;
    final File javaHome;
    final File nativeHome;
    final File bootstrap;

    ServerProfile(File files, String version) {
        this(files, new File(files, "servers"), version);
    }

    ServerProfile(File files, File servers, String version) {
        this.version = version;
        javaMajor = javaMajor(version);
        directory = new File(servers, version);
        javaHome = new File(files, "runtimes/java" + javaMajor + "-zalith2.7-v2");
        nativeHome = new File(files, "server-native/" + version);
        bootstrap = new File(nativeHome, "fabric-bootstrap.jar");
    }

    static int newestFirst(String left, String right) {
        javaMajor(left);
        javaMajor(right);
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int comparison = Integer.compare(i < b.length ? Integer.parseInt(b[i]) : 0,
                i < a.length ? Integer.parseInt(a[i]) : 0);
            if (comparison != 0) return comparison;
        }
        return left.compareTo(right);
    }

    static int javaMajor(String version) {
        if (version == null || !version.matches("[0-9]{1,2}\\.[0-9]{1,2}(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Versão do Minecraft inválida.");
        }
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        if (major == 26) return 25;
        if (major != 1 || minor < 18 || minor > 21) {
            throw new IllegalArgumentException("Escolha uma versão estável entre 1.18 e 26.x.");
        }
        return minor > 20 || (minor == 20 && patch >= 5) ? 21 : 17;
    }
}
