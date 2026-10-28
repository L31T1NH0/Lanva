package io.oxide.mobile;

import android.content.Context;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ServerRuntime {
    static void prepare(Context context, ServerProfile profile) throws Exception {
        StorageSettings.requireAccess(context);
        if (!context.getSharedPreferences("server", Context.MODE_PRIVATE).getBoolean("minecraftEulaAccepted", false)) {
            throw new IOException("Aceite a EULA do Minecraft no aplicativo antes de iniciar.");
        }
        File directory = profile.directory;
        File javaHome = profile.javaHome;
        mkdir(directory);
        mkdir(javaHome);
        File marker = new File(javaHome, "runtime-v2.ready");
        if (!marker.isFile() || !new File(javaHome, "lib/modules").isFile()) {
            unzip(context.getAssets().open("java" + profile.javaMajor + ".zip"), javaHome);
            writeIfAbsent(marker, "runtime-v2\n");
        }
        copy(context, "fabric-bootstrap.jar", profile.bootstrap);
        mkdir(new File(directory, "mods"));
        writeIfAbsent(new File(directory, "server.properties"),
            "server-port=25565\nserver-ip=\nlevel-name=world\nlevel-type=minecraft:normal\n"
            + "generate-structures=true\ngamemode=survival\ndifficulty=normal\nview-distance=4\n"
            + "simulation-distance=4\nmax-players=8\nonline-mode=true\n"
            + "motd=Lanva\n");
        // Repoint links after APK updates: nativeLibraryDir changes with each installation.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("java" + profile.javaMajor + "-native-links.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] pair = line.split("\t", -1);
                if (pair.length != 2 || pair[0].contains("..") || pair[0].startsWith("/")
                    || pair[1].contains("/")) throw new IOException("Mapa nativo inválido.");
                File link = new File(javaHome, pair[0]);
                mkdir(link.getParentFile());
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    if (!link.delete()) throw new IOException("Não foi possível atualizar " + link.getName());
                }
                Os.symlink(new File(context.getApplicationInfo().nativeLibraryDir, pair[1]).getAbsolutePath(), link.getAbsolutePath());
            }
        }
        String jna = FabricCatalog.install(profile);
        if (!jna.matches("[0-9.]+")) throw new IOException("Versão JNA inválida.");
        File nativeJna = new File(context.getApplicationInfo().nativeLibraryDir, "libjna" + jna.replace('.', '_') + ".so");
        if (!nativeJna.isFile()) throw new IOException("JNA " + jna + " não está incluído. Atualize o aplicativo para usar esta versão.");
        File jnaLink = new File(profile.nativeHome, "jna/libjnidispatch.so");
        mkdir(jnaLink.getParentFile());
        if (jnaLink.exists() || java.nio.file.Files.isSymbolicLink(jnaLink.toPath())) {
            if (!jnaLink.delete()) throw new IOException("Não foi possível atualizar a biblioteca JNA.");
        }
        Os.symlink(nativeJna.getAbsolutePath(), jnaLink.getAbsolutePath());
        // This write follows the explicit consent check above, including after interrupted starts.
        File eula = new File(directory, "eula.txt");
        File temporary = new File(directory, "eula.txt.part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write("eula=true\n".getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(eula)) throw new IOException("Não foi possível registrar a EULA.");
    }

    private static void unzip(InputStream input, File root) throws IOException {
        mkdir(root);
        String prefix = root.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(root, entry.getName());
                if (!file.getCanonicalPath().startsWith(prefix)) throw new IOException("Entrada de runtime inválida.");
                if (entry.isDirectory()) mkdir(file);
                else {
                    mkdir(file.getParentFile());
                    try (FileOutputStream output = new FileOutputStream(file)) { transfer(zip, output); }
                }
            }
        }
    }

    private static void copy(Context context, String asset, File file) throws IOException {
        mkdir(file.getParentFile());
        File temporary = new File(file.getPath() + ".part");
        try (InputStream input = context.getAssets().open(asset); FileOutputStream output = new FileOutputStream(temporary)) {
            transfer(input, output);
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) throw new IOException("Não foi possível instalar " + file.getName());
    }

    private static void transfer(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static void writeIfAbsent(File file, String text) throws IOException {
        if (file.exists()) return;
        mkdir(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Não foi possível criar " + directory.getName());
    }
}
