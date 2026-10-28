package io.oxide.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StorageActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ServerService service;
    private boolean bound, busy, waitingForPermission;
    private String pendingAction = "";
    private EditText folder;
    private TextView current, message;
    private Button choose, usePublic, usePrivate, permission;
    private final ServerProcess.Listener listener = (state, logs) -> refresh();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ServerService.LocalBinder) binder).service(); service.addListener(listener); resumePending();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        screen.setPadding(padding, padding, padding, padding); scroll.addView(screen); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        scroll.requestApplyInsets();
        TextView title = new TextView(this); title.setText("Pasta dos servidores"); title.setTextSize(24); screen.addView(title);
        current = new TextView(this); current.setTextIsSelectable(true); screen.addView(current);
        TextView explanation = new TextView(this);
        explanation.setText("Escolha uma pasta dentro de /storage/emulated/0/. Cada versão terá sua própria subpasta com mundo, mods e configurações. Pare o servidor antes de trocar a pasta.");
        explanation.setPadding(0, padding, 0, padding); screen.addView(explanation);
        TextView label = new TextView(this); label.setText("Pasta pública"); screen.addView(label);
        folder = new EditText(this); folder.setSingleLine(true); folder.setHint("Lanva ou Documents/Minecraft");
        String selected = getSharedPreferences("server", MODE_PRIVATE).getString("serverFolder", "");
        folder.setText(state == null ? (selected.isEmpty() ? "Lanva" : selected) : state.getString("folder", "Lanva"));
        screen.addView(folder);
        choose = button(screen, "Selecionar pasta existente", () -> withPermission("picker"));
        permission = button(screen, "Permitir acesso ao armazenamento", () -> withPermission("permission"));
        usePublic = button(screen, "Usar pasta pública", () -> withPermission("public"));
        usePrivate = button(screen, "Usar pasta privada do app", () -> confirmSwitch(new File(getFilesDir(), "servers"), false));
        message = new TextView(this); screen.addView(message);
        button(screen, "Voltar", this::onBackPressed);
        if (state != null) {
            pendingAction = state.getString("pending", ""); waitingForPermission = state.getBoolean("waiting", false);
        }
        refresh();
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setMinHeight((int)(48 * getResources().getDisplayMetrics().density));
        parent.addView(button); button.setOnClickListener(view -> action.run()); return button;
    }
    @Override protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, ServerService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (service != null) service.removeListener(listener);
        if (bound) unbindService(connection);
        bound = false; service = null; super.onStop();
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("folder", folder.getText().toString()); state.putString("pending", pendingAction);
        state.putBoolean("waiting", waitingForPermission); super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (busy) { error("Aguarde a cópia terminar."); return; }
        super.onBackPressed();
    }
    private void refresh() {
        boolean available = !busy && service != null && service.canStart();
        choose.setEnabled(available); usePublic.setEnabled(available); usePrivate.setEnabled(available); folder.setEnabled(available);
        permission.setEnabled(available && !StorageSettings.hasPublicAccess(this));
        permission.setText(StorageSettings.hasPublicAccess(this) ? "Acesso ao armazenamento permitido" : "Permitir acesso ao armazenamento");
        current.setText("Pasta atual\n" + StorageSettings.servers(this).getAbsolutePath());
        message.setText(busy ? "Copiando servidores. Os arquivos originais serão mantidos…" : service != null && !service.idle()
            ? "Use Salvar e parar antes de alterar a pasta." : "A pasta pública fica acessível no gerenciador de arquivos do celular. Java e bibliotecas nativas continuam na área privada do app.");
    }
    private void withPermission(String action) {
        if (service == null || !service.canStart() || busy) { error("Pare o servidor e aguarde as operações nos arquivos."); return; }
        pendingAction = action;
        if (StorageSettings.hasPublicAccess(this)) { resumePending(); return; }
        new AlertDialog.Builder(this).setTitle("Acesso à pasta pública")
            .setMessage("O Minecraft precisa ler e gravar diretamente os arquivos do servidor. Na próxima tela do Android, permita ao Lanva gerenciar arquivos. O app usará a pasta que você escolher.")
            .setNegativeButton("Cancelar", (dialog, which) -> pendingAction = "")
            .setPositiveButton("Continuar", (dialog, which) -> {
                waitingForPermission = true;
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())), 10);
                        } catch (android.content.ActivityNotFoundException e) {
                            startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 10);
                        }
                    } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10);
                } catch (RuntimeException e) {
                    waitingForPermission = false; pendingAction = ""; error("Abra as configurações do Android e permita acesso aos arquivos para este app.");
                }
            }).show();
    }
    private void resumePending() {
        if (pendingAction.isEmpty() || waitingForPermission || service == null || !StorageSettings.hasPublicAccess(this)) return;
        String action = pendingAction; pendingAction = "";
        if (!service.canStart()) { error("Pare o servidor antes de escolher a pasta."); return; }
        if (action.equals("picker")) {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 11); }
            catch (RuntimeException e) { error("Seletor de pastas indisponível. Digite o caminho no campo Pasta pública."); }
        } else if (action.equals("public")) {
            try { confirmSwitch(ServerFolders.publicFolder(StorageSettings.PUBLIC_VOLUME, folder.getText().toString()), true); }
            catch (Exception e) { error(e.getMessage()); }
        } else refresh();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 10) permissionReturned();
    }
    private void permissionReturned() {
        waitingForPermission = false;
        if (!StorageSettings.hasPublicAccess(this)) { pendingAction = ""; error("Permissão não concedida. A pasta atual foi mantida."); }
        refresh(); resumePending();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10) { permissionReturned(); return; }
        if (request != 11 || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) throw new java.io.IOException("Escolha uma pasta no armazenamento interno do celular.");
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (!id.startsWith("primary:")) throw new java.io.IOException("Escolha uma pasta dentro de /storage/emulated/0/.");
            File selected = ServerFolders.publicFolder(StorageSettings.PUBLIC_VOLUME, id.substring("primary:".length()));
            folder.setText(selected.getAbsolutePath());
        } catch (Exception e) { error(e.getMessage()); }
    }
    private void confirmSwitch(File destination, boolean publicFolder) {
        if (busy || service == null || !service.canStart()) { error("Pare o servidor antes de alterar a pasta."); return; }
        try {
            if (StorageSettings.servers(this).getCanonicalFile().equals(destination.getCanonicalFile())) {
                error("Esta pasta já está em uso."); return;
            }
        } catch (Exception e) { error(e.getMessage()); return; }
        new AlertDialog.Builder(this).setTitle("Usar esta pasta?")
            .setMessage(destination.getAbsolutePath() + "\n\nCopiar servidores atuais mantém uma cópia dos mundos na pasta original. Usar sem copiar abre os servidores que já estão no destino ou cria novos mundos.")
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Usar sem copiar", (dialog, which) -> switchFolder(destination, publicFolder, false))
            .setPositiveButton("Copiar servidores atuais", (dialog, which) -> switchFolder(destination, publicFolder, true)).show();
    }
    private void switchFolder(File destination, boolean publicFolder, boolean copy) {
        ServerService owner = service;
        if (busy || owner == null || !owner.beginFileEdit()) { error("Pare o servidor e aguarde as operações nos arquivos."); return; }
        File source = StorageSettings.servers(this);
        busy = true; refresh();
        io.execute(() -> {
            try {
                if (publicFolder) {
                    if (!StorageSettings.hasPublicAccess(this)) throw new java.io.IOException("Permissão de armazenamento revogada. A pasta atual foi mantida.");
                    ServerFolders.publicFolder(StorageSettings.PUBLIC_VOLUME, destination.getPath());
                }
                if (copy) {
                    StorageSettings.requireAccess(this);
                    ServerFolders.copyProfiles(source, destination);
                } else ServerFolders.ensureDirectory(destination);
                // Prove that this volume accepts ordinary server writes before changing the setting.
                File probe = File.createTempFile(".fabric-mobile-write-", ".tmp", destination);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(probe)) { output.write(1); output.getFD().sync(); }
                finally { probe.delete(); }
                if (!getSharedPreferences("server", MODE_PRIVATE).edit().putString("serverFolder", publicFolder ? destination.getCanonicalPath() : "").commit()) {
                    throw new java.io.IOException("Não foi possível salvar a pasta escolhida. Os arquivos originais foram mantidos.");
                }
                runOnUiThread(() -> { busy = false; if (!isDestroyed()) { refresh(); error("Pasta dos servidores atualizada."); } });
            } catch (Exception e) {
                runOnUiThread(() -> { busy = false; if (!isDestroyed()) { refresh(); error(e.getMessage()); } });
            } finally { owner.endFileEdit(); }
        });
    }
    private void error(String text) { Toast.makeText(this, text == null ? "Falha ao alterar a pasta." : text, Toast.LENGTH_LONG).show(); }
}
