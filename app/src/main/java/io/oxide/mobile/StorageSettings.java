package io.oxide.mobile;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.IOException;

final class StorageSettings {
    static final File PUBLIC_VOLUME = new File("/storage/emulated/0");
    static File servers(Context context) {
        String path = context.getSharedPreferences("server", Context.MODE_PRIVATE).getString("serverFolder", "");
        return path.isEmpty() ? new File(context.getFilesDir(), "servers") : new File(path);
    }
    static ServerProfile profile(Context context, String version) {
        return new ServerProfile(context.getFilesDir(), servers(context), version);
    }
    static boolean publicSelected(Context context) {
        return !context.getSharedPreferences("server", Context.MODE_PRIVATE).getString("serverFolder", "").isEmpty();
    }
    static boolean hasPublicAccess(Context context) {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
            : context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    static void requireAccess(Context context) throws IOException {
        if (!publicSelected(context)) return;
        ServerFolders.publicFolder(PUBLIC_VOLUME, servers(context).getPath());
        if (!hasPublicAccess(context)) throw new IOException("Permita acesso ao armazenamento em Pasta dos servidores antes de iniciar.");
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(PUBLIC_VOLUME))) {
            throw new IOException("O armazenamento interno está indisponível. A pasta do servidor não foi alterada.");
        }
    }
}
