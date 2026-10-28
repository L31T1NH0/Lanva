package io.oxide.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class FileAccess {
    static File resolve(File root, String relative) throws IOException {
        File target = new File(root, relative).getCanonicalFile();
        File base = root.getCanonicalFile();
        if (!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("O arquivo está fora da pasta do servidor.");
        }
        return target;
    }

    static String readText(File file) throws IOException {
        if (file.length() > 1024 * 1024) throw new IOException("O editor aceita arquivos de até 1 MB. Use Exportar para arquivos maiores.");
        try (java.io.InputStream input = Files.newInputStream(file.toPath());
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1024 * 1024) throw new IOException("Arquivo maior que 1 MB.");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    static void writeText(File file, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (data.length > 1024 * 1024) throw new IOException("O editor aceita arquivos de até 1 MB.");
        File temporary = File.createTempFile(".mobile-write-", ".part", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.getFD().sync();
            }
            if (!temporary.renameTo(file)) throw new IOException("Não foi possível salvar " + file.getName());
        } finally {
            temporary.delete();
        }
    }
}
