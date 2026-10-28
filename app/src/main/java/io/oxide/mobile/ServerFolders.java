package io.oxide.mobile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Storage rules and copy-before-switch migration, independent of Android. */
final class ServerFolders {
    static File publicFolder(File volume, String path) throws IOException {
        if (path == null || path.trim().isEmpty()) throw new IOException("Escolha uma pasta no armazenamento interno.");
        String name = path.trim();
        for (String part : name.split("/")) {
            if (part.equals("..") || part.equals(".")) throw new IOException("A pasta não pode conter . ou ..");
        }
        File base = volume.getCanonicalFile();
        File folder = (new File(name).isAbsolute() ? new File(name) : new File(base, name)).getCanonicalFile();
        String prefix = base.getPath() + File.separator;
        if (!folder.getPath().startsWith(prefix)) throw new IOException("Escolha uma subpasta de " + volume.getPath());
        String relative = folder.getPath().substring(prefix.length());
        if (relative.split("/")[0].equalsIgnoreCase("Android")) throw new IOException("Use uma pasta fora de Android/data e Android/obb.");
        return folder;
    }

    static List<File> profiles(File base) throws IOException {
        List<File> result = new ArrayList<>();
        if (!base.exists()) return result;
        File[] entries = base.listFiles();
        if (entries == null) throw new IOException("Não foi possível ler " + base);
        for (File entry : entries) {
            try { ServerProfile.javaMajor(entry.getName()); }
            catch (IllegalArgumentException ignored) { continue; }
            if (Files.isSymbolicLink(entry.toPath())) throw new IOException("Pasta de versão com link simbólico: " + entry.getName());
            if (entry.isDirectory()) result.add(entry);
        }
        result.sort((a, b) -> ServerProfile.newestFirst(a.getName(), b.getName()));
        return result;
    }

    static int copyProfiles(File source, File destination) throws IOException {
        File from = source.getCanonicalFile();
        File to = destination.getCanonicalFile();
        if (from.equals(to)) return 0;
        if (to.getPath().startsWith(from + File.separator) || from.getPath().startsWith(to + File.separator)) {
            throw new IOException("A pasta nova deve ficar fora da pasta atual.");
        }
        List<File> profiles = profiles(from);
        ensureDirectory(to);
        for (File profile : profiles) {
            if (new File(to, profile.getName()).exists()) {
                throw new IOException("Já existe " + profile.getName() + " no destino. Escolha outra pasta ou use os arquivos existentes, sem copiar.");
            }
        }
        File stage = Files.createTempDirectory(to.toPath(), ".fabric-mobile-copy-").toFile();
        try {
            for (File profile : profiles) copyTree(profile, new File(stage, profile.getName()), true);
            for (File profile : profiles) {
                File target = new File(to, profile.getName());
                if (target.exists() || !new File(stage, profile.getName()).renameTo(target)) {
                    throw new IOException("Cópia não concluída. A pasta atual e os arquivos originais foram mantidos. Confira o destino antes de tentar novamente.");
                }
            }
            return profiles.size();
        } finally { removeStage(stage); }
    }

    private static void copyTree(File source, File destination, boolean profileRoot) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) throw new IOException("Não é possível copiar o link simbólico " + source.getName());
        if (source.isDirectory()) {
            ensureDirectory(destination);
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Não foi possível ler " + source);
            for (File child : children) {
                // Older APKs kept this native link in the server folder; it is rebuilt privately now.
                if (profileRoot && child.getName().equals(".mobile-jna")) continue;
                copyTree(child, new File(destination, child.getName()), false);
            }
        } else if (source.isFile()) {
            long size = source.length(), modified = source.lastModified(), copied = 0;
            try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); copied += count; }
                output.getFD().sync();
            }
            if (copied != size || source.length() != size || source.lastModified() != modified) {
                throw new IOException("O arquivo mudou durante a cópia: " + source.getName());
            }
        } else throw new IOException("Arquivo indisponível: " + source);
    }

    static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Não foi possível criar " + directory);
    }

    private static void removeStage(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) removeStage(child);
        }
        file.delete();
    }
}
