package io.oxide.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ServerFoldersTest {
    public static void main(String[] args) throws Exception {
        List<String> versions = new ArrayList<>(Arrays.asList("1.21.9", "1.18", "26.1.2", "1.21.10", "26.3", "1.20.5", "1.21.11"));
        versions.sort(ServerProfile::newestFirst);
        assert versions.equals(Arrays.asList("26.3", "26.1.2", "1.21.11", "1.21.10", "1.21.9", "1.20.5", "1.18")) : versions;
        Path temporary = Files.createTempDirectory("server-folders-");
        try {
            File volume = temporary.resolve("public").toFile();
            volume.mkdir();
            File folder = ServerFolders.publicFolder(volume, "Documents/Meu Minecraft");
            assert folder.equals(new File(volume, "Documents/Meu Minecraft"));
            assert folder.equals(ServerFolders.publicFolder(volume, folder.getPath()));
            for (String invalid : new String[]{"", volume.getPath(), "../escape", "a/../escape", "Android/data/app", "Android/obb", temporary.toString()}) {
                try { ServerFolders.publicFolder(volume, invalid); throw new AssertionError(invalid); }
                catch (IOException expected) { }
            }
            Files.createSymbolicLink(volume.toPath().resolve("escape"), temporary);
            try { ServerFolders.publicFolder(volume, "escape/outside"); throw new AssertionError("symlink escape"); }
            catch (IOException expected) { }
            File files = temporary.resolve("private").toFile();
            File source = new File(files, "servers");
            ServerProfile internal = new ServerProfile(files, "26.3");
            ServerProfile external = new ServerProfile(files, folder, "26.3");
            assert external.directory.equals(new File(folder, "26.3"));
            assert internal.javaHome.equals(external.javaHome);
            assert internal.nativeHome.equals(external.nativeHome);
            assert internal.bootstrap.equals(external.bootstrap);
            assert !external.bootstrap.getPath().startsWith(folder.getPath());
            File world = new File(source, "26.3/world/region/r.0.0.mca");
            ServerFolders.ensureDirectory(world.getParentFile());
            byte[] contents = new byte[70000]; Arrays.fill(contents, (byte) 42);
            Files.write(world.toPath(), contents);
            File config = new File(source, "26.3/server.properties");
            FileAccess.writeText(config, "motd=Meu mundo\n");
            File oldJna = new File(source, "26.3/.mobile-jna"); oldJna.mkdir();
            Files.createSymbolicLink(new File(oldJna, "libjnidispatch.so").toPath(), temporary.resolve("native.so"));
            File older = new File(source, "1.21.10/world"); older.mkdirs();
            Files.write(new File(older, "level.dat").toPath(), new byte[]{1, 2, 3});
            assert ServerFolders.copyProfiles(source, folder) == 2;
            assert Arrays.equals(contents, Files.readAllBytes(new File(folder, "26.3/world/region/r.0.0.mca").toPath()));
            assert FileAccess.readText(new File(folder, "26.3/server.properties")).equals("motd=Meu mundo\n");
            assert world.isFile() && config.isFile();
            assert !new File(folder, "26.3/.mobile-jna").exists();
            File sentinel = new File(folder, "26.3/world/do-not-overwrite.txt");
            FileAccess.writeText(sentinel, "preservar");
            try { ServerFolders.copyProfiles(source, folder); throw new AssertionError("destination conflict"); }
            catch (IOException expected) { }
            assert FileAccess.readText(sentinel).equals("preservar");
            try { ServerFolders.copyProfiles(source, new File(source, "nested")); throw new AssertionError("recursive copy"); }
            catch (IOException expected) { }
            Files.createSymbolicLink(new File(older, "unsafe-link").toPath(), temporary);
            File failed = new File(volume, "failed-copy");
            try { ServerFolders.copyProfiles(source, failed); throw new AssertionError("unsafe symlink"); }
            catch (IOException expected) { }
            assert world.exists();
            assert failed.listFiles().length == 0 : "partial copy must not become a server";
            assert !new File(failed, "26.3").exists();
            System.out.println("PASS: newest-first versions, public folder bounds, private JVM/native files, safe copy, conflicts and cleanup");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException e) { throw new RuntimeException(e); } });
            }
        }
    }
}
