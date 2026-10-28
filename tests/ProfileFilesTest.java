package io.oxide.mobile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProfileFilesTest {
    public static void main(String[] args) throws Exception {
        assert ServerProfile.javaMajor("1.18") == 17;
        assert ServerProfile.javaMajor("1.20.4") == 17;
        assert ServerProfile.javaMajor("1.20.5") == 21;
        assert ServerProfile.javaMajor("1.21.10") == 21;
        assert ServerProfile.javaMajor("26.1.2") == 25;
        assert ServerProfile.javaMajor("26.3") == 25;
        for (String invalid : new String[]{"../26.3", "1.17", "27.1", "26.3-snapshot-1", "", "1.21/../26.3"}) {
            try { ServerProfile.javaMajor(invalid); throw new AssertionError(invalid); }
            catch (IllegalArgumentException expected) { }
        }
        Path temporary = Files.createTempDirectory("fabric-profile-");
        try {
            File root = temporary.toFile();
            ServerProfile older = new ServerProfile(root, "1.21.10");
            ServerProfile newer = new ServerProfile(root, "26.3");
            assert !older.directory.equals(newer.directory);
            assert !older.javaHome.equals(newer.javaHome);
            File config = FileAccess.resolve(root, "server.properties");
            FileAccess.writeText(config, "motd=Olá\n");
            assert FileAccess.readText(config).equals("motd=Olá\n");
            FileAccess.writeText(config, "motd=Atualizado\n");
            assert FileAccess.readText(config).equals("motd=Atualizado\n");
            assert !new File(config + ".part").exists();
            try { FileAccess.resolve(root, "../outside"); throw new AssertionError("traversal"); }
            catch (java.io.IOException expected) { }
            Files.createSymbolicLink(temporary.resolve("escape"), temporary.getParent());
            try { FileAccess.resolve(root, "escape/outside"); throw new AssertionError("symlink"); }
            catch (java.io.IOException expected) { }
            Files.delete(temporary.resolve("escape"));
            Files.write(config.toPath(), new byte[1024 * 1024 + 1]);
            try { FileAccess.readText(config); throw new AssertionError("read limit"); }
            catch (java.io.IOException expected) { }
            config.delete();
        } finally { Files.delete(temporary); }
        System.out.println("PASS: version runtimes, isolated worlds, atomic edits, traversal/symlink and editor limits");
    }
}
