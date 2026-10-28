package io.oxide.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native file browser; edits use the same service gate as server startup. */
public final class FilesActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private File root, directory, editing, importDirectory, exportFile;
    private File activeRoot, privateRoot;
    private ServerService service;
    private boolean bound, busy;
    private TextView path, hint;
    private EditText editor;
    private ListView list;
    private Button save, folders, importButton, newFolder, newFile;
    private String originalText = "";
    private Runnable pendingResult;
    private static final class Draft {
        File file;
        String original, text;
    }
    private final List<File> entries = new ArrayList<>();
    private final ServerProcess.Listener listener = (state, logs) -> refreshControls();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ServerService.LocalBinder) binder).service();
            service.addListener(listener);
            if (pendingResult != null) { Runnable pending = pendingResult; pendingResult = null; pending.run(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; refreshControls(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        privateRoot = getFilesDir();
        activeRoot = StorageSettings.servers(this);
        try { privateRoot = privateRoot.getCanonicalFile(); activeRoot = activeRoot.getCanonicalFile(); }
        catch (Exception ignored) { }
        root = activeRoot;
        directory = new File(root, getIntent().getStringExtra("version"));
        try {
            if (state != null) {
                File savedRoot = new File(state.getString("root", activeRoot.getPath())).getCanonicalFile();
                if (savedRoot.equals(activeRoot) || savedRoot.equals(privateRoot)) root = savedRoot;
                directory = FileAccess.resolve(root, state.getString("directory", ""));
                String pendingImport = state.getString("import");
                String pendingExport = state.getString("export");
                if (pendingImport != null) importDirectory = FileAccess.resolve(root, pendingImport);
                if (pendingExport != null) exportFile = FileAccess.resolve(root, pendingExport);
            }
        } catch (Exception e) { directory = root; }
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * getResources().getDisplayMetrics().density);
        screen.setPadding(padding, padding, padding, padding);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(screen);
        screen.requestApplyInsets();
        path = new TextView(this); path.setTextSize(20); path.setTextIsSelectable(true); screen.addView(path);
        hint = new TextView(this); screen.addView(hint);
        LinearLayout navigation = new LinearLayout(this); screen.addView(navigation);
        button(navigation, "Voltar", () -> leaveEditor(() -> {
            if (directory.equals(root)) finish();
            else { directory = directory.getParentFile(); refresh(); }
        }));
        folders = button(navigation, "Outras pastas", () -> leaveEditor(() -> {
            new AlertDialog.Builder(this).setTitle("Arquivos dos servidores")
                .setItems(new String[]{"Servidores atuais", "Pastas antigas do app"}, (dialog, which) -> {
                    root = which == 0 ? activeRoot : privateRoot; directory = root; refresh();
                }).show();
        }));
        LinearLayout actions = new LinearLayout(this); screen.addView(actions);
        importButton = button(actions, "Importar", this::pickImport);
        newFolder = button(actions, "Nova pasta", () -> create(true));
        newFile = button(actions, "Novo arquivo", () -> create(false));
        list = new ListView(this); screen.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = new TextView(this); empty.setText("Pasta vazia. Inicie o servidor para preparar seus arquivos ou importe um arquivo.");
        screen.addView(empty); list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            File file = entries.get(position);
            if (file.isDirectory() || directory.equals(root) && file.getName().equals("servers")) { directory = file; refresh(); }
            else if (isText(file)) openEditor(file);
            else export(file);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            File file = entries.get(position);
            if (file.isDirectory()) return false;
            new AlertDialog.Builder(this).setTitle(file.getName()).setItems(new String[]{"Exportar", "Excluir"},
                (dialog, which) -> { if (which == 0) export(file); else delete(file); }).show();
            return true;
        });
        editor = new EditText(this);
        editor.setGravity(android.view.Gravity.TOP);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setTextSize(14); editor.setVisibility(View.GONE);
        screen.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        save = button(screen, "Salvar arquivo", () -> {
            String text = editor.getText().toString();
            File file = editing;
            mutate(() -> FileAccess.writeText(FileAccess.resolve(root, relative(file)), text), () -> {
                originalText = text; Toast.makeText(this, "Arquivo salvo.", Toast.LENGTH_SHORT).show();
            });
        });
        refresh();
        Draft draft = (Draft) getLastNonConfigurationInstance();
        if (draft != null) {
            directory = draft.file.getParentFile();
            editing = draft.file; originalText = draft.original; editor.setText(draft.text);
            list.setVisibility(View.GONE); list.getEmptyView().setVisibility(View.GONE);
            editor.setVisibility(View.VISIBLE); refresh();
        }
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setMinHeight((int)(48 * getResources().getDisplayMetrics().density));
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        else parent.addView(button);
        button.setOnClickListener(view -> action.run()); return button;
    }

    @Override protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, ServerService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (service != null) service.removeListener(listener);
        if (bound) unbindService(connection);
        service = null; bound = false; super.onStop();
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override public Object onRetainNonConfigurationInstance() {
        if (editing == null) return null;
        Draft draft = new Draft(); draft.file = editing; draft.original = originalText; draft.text = editor.getText().toString();
        return draft;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("root", root.getPath());
        state.putString("directory", relative(directory));
        if (importDirectory != null) state.putString("import", relative(importDirectory));
        if (exportFile != null) state.putString("export", relative(exportFile));
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (busy) { error("Aguarde a operação terminar."); return; }
        leaveEditor(() -> {
            if (directory.equals(root)) super.onBackPressed();
            else { directory = directory.getParentFile(); refresh(); }
        });
    }

    private String relative(File file) { return file.equals(root) ? "" : file.getPath().substring(root.getPath().length() + 1); }
    private boolean writableDirectory() {
        try { return (!root.equals(activeRoot) || !StorageSettings.publicSelected(this) || StorageSettings.hasPublicAccess(this)) && serverPath(directory); }
        catch (Exception e) { return false; }
    }
    private boolean serverPath(File file) throws Exception {
        File canonical = FileAccess.resolve(root, relative(file));
        String relative = relative(canonical);
        if (root.equals(activeRoot)) {
            if (relative.isEmpty()) return false;
            try { ServerProfile.javaMajor(relative.split("/")[0]); return true; }
            catch (IllegalArgumentException ignored) { return false; }
        }
        return relative.startsWith("servers/") || relative.equals("fabric-server") || relative.startsWith("fabric-server/")
            || relative.equals("server") || relative.startsWith("server/");
    }
    private void refresh() {
        if (editing != null) {
            path.setText(relative(editing));
            refreshControls();
            return;
        }
        entries.clear();
        File[] files = directory.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing((File file) -> !file.isDirectory()).thenComparing(File::getName));
            for (File file : files) {
                if (directory.equals(root)) {
                    if (root.equals(privateRoot) && !Arrays.asList("servers", "fabric-server", "server").contains(file.getName())) continue;
                    if (root.equals(activeRoot)) {
                        try { ServerProfile.javaMajor(file.getName()); }
                        catch (IllegalArgumentException ignored) { continue; }
                        if (!file.isDirectory()) continue;
                    }
                }
                try {
                    FileAccess.resolve(root, relative(file));
                    if (file.getName().equals("servers") && directory.equals(root) || serverPath(file)) entries.add(file);
                }
                catch (Exception ignored) { }
            }
        }
        // Show the versions folder even before the first install.
        if (directory.equals(privateRoot) && entries.stream().noneMatch(file -> file.getName().equals("servers"))) entries.add(new File(root, "servers"));
        if (directory.equals(activeRoot) && root.equals(activeRoot)) {
            entries.sort((a, b) -> ServerProfile.newestFirst(a.getName(), b.getName()));
        }
        List<String> labels = new ArrayList<>();
        for (File file : entries) labels.add(file.getName() + (file.isDirectory() || file.getName().equals("servers") && directory.equals(root) ? "/" : ""));
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        path.setText(directory.getAbsolutePath());
        refreshControls();
    }
    private void refreshControls() {
        boolean writable = service != null && service.idle() && !busy && writableDirectory();
        importButton.setEnabled(writable && editing == null);
        newFolder.setEnabled(writable && editing == null); newFile.setEnabled(writable && editing == null);
        save.setVisibility(editing == null ? View.GONE : View.VISIBLE); save.setEnabled(writable);
        editor.setEnabled(writable);
        folders.setEnabled(!busy);
        list.setEnabled(!busy);
        hint.setText(busy ? "Trabalhando nos arquivos…" : root.equals(activeRoot) && StorageSettings.publicSelected(this) && !StorageSettings.hasPublicAccess(this)
            ? "Permita acesso ao armazenamento em Pasta dos servidores para abrir esta pasta." : service != null && !service.idle()
            ? "Servidor ativo. Pare para alterar arquivos." : "Toque para abrir. Segure um arquivo para exportar ou excluir.");
    }
    private static boolean isText(File file) {
        return file.getName().matches("(?i).*\\.(properties|txt|json|yml|yaml|toml|cfg|conf|log|md)");
    }
    private void openEditor(File file) {
        busy = true; refreshControls();
        io.execute(() -> {
            try {
                String text = FileAccess.readText(FileAccess.resolve(root, relative(file)));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false; editing = file; originalText = text; editor.setText(text);
                    list.setVisibility(View.GONE); list.getEmptyView().setVisibility(View.GONE);
                    editor.setVisibility(View.VISIBLE); refresh();
                });
            } catch (Exception e) { failure(e); }
        });
    }
    private void leaveEditor(Runnable next) {
        if (busy) { error("Aguarde a operação terminar."); return; }
        Runnable leave = () -> {
            editing = null; editor.setVisibility(View.GONE); list.setVisibility(View.VISIBLE); next.run(); refresh();
        };
        if (editing != null && !originalText.equals(editor.getText().toString())) {
            new AlertDialog.Builder(this).setTitle("Descartar alterações?").setMessage("O arquivo ainda não foi salvo.")
                .setNegativeButton("Continuar editando", null).setPositiveButton("Descartar", (dialog, which) -> leave.run()).show();
        } else leave.run();
    }
    private void create(boolean folder) {
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint(folder ? "Nome da pasta" : "arquivo.properties");
        new AlertDialog.Builder(this).setTitle(folder ? "Nova pasta" : "Novo arquivo").setView(name)
            .setNegativeButton("Cancelar", null).setPositiveButton("Criar", (dialog, which) -> {
                try {
                    File target = namedFile(directory, name.getText().toString());
                    mutate(() -> {
                        if (target.exists()) throw new java.io.IOException("Já existe um arquivo com esse nome.");
                        ensureDirectory(target.getParentFile());
                        if (folder) ensureDirectory(target); else FileAccess.writeText(target, "");
                    }, this::refresh);
                } catch (Exception e) { error(e.getMessage()); }
            }).show();
    }
    private File namedFile(File parent, String name) throws Exception {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.length() > 120) {
            throw new java.io.IOException("Digite um nome de arquivo sem barras, com até 120 caracteres.");
        }
        File target = FileAccess.resolve(root, relative(parent) + "/" + name);
        if (!serverPath(target)) throw new java.io.IOException("Escolha uma pasta de servidor.");
        return target;
    }
    private static void ensureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Não foi possível criar a pasta.");
    }
    private void pickImport() {
        importDirectory = directory;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1);
    }
    private void export(File file) {
        exportFile = file;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, file.getName()), 2);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (service == null) { pendingResult = () -> onActivityResult(request, result, data); return; }
        Uri uri = data.getData();
        if (request == 1 && importDirectory != null) {
            try {
                String name = null;
                try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                File target = namedFile(importDirectory, name == null ? "arquivo" : name);
                Runnable copy = () -> mutate(() -> {
                    ensureDirectory(target.getParentFile());
                    File temporary = File.createTempFile(".mobile-import-", ".part", target.getParentFile());
                    try {
                        try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(temporary)) {
                            if (input == null) throw new java.io.IOException("Não foi possível ler o arquivo.");
                            FabricCatalog.transfer(input, output, 256L * 1024 * 1024); output.getFD().sync();
                        }
                        if (!temporary.renameTo(target)) throw new java.io.IOException("Não foi possível importar o arquivo.");
                    } finally { temporary.delete(); }
                }, this::refresh);
                if (target.exists()) new AlertDialog.Builder(this).setTitle("Substituir " + target.getName() + "?")
                    .setMessage("O arquivo atual será substituído.").setNegativeButton("Cancelar", null)
                    .setPositiveButton("Substituir", (dialog, which) -> copy.run()).show();
                else copy.run();
            } catch (Exception e) { error(e.getMessage()); }
        } else if (request == 2 && exportFile != null) {
            File source = exportFile;
            busy = true; refreshControls();
            io.execute(() -> {
                try (InputStream input = new FileInputStream(FileAccess.resolve(root, relative(source))); OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new java.io.IOException("Não foi possível exportar o arquivo.");
                    FabricCatalog.transfer(input, output, Long.MAX_VALUE);
                    runOnUiThread(() -> { busy = false; if (!isDestroyed()) { refreshControls(); error("Arquivo exportado."); } });
                } catch (Exception e) { failure(e); }
            });
        }
    }
    private void delete(File file) {
        new AlertDialog.Builder(this).setTitle("Excluir " + file.getName() + "?").setMessage("Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Excluir", (dialog, which) -> mutate(() -> {
                if (!FileAccess.resolve(root, relative(file)).delete()) throw new java.io.IOException("Não foi possível excluir o arquivo.");
            }, this::refresh)).show();
    }
    private interface Operation { void run() throws Exception; }
    private void mutate(Operation operation, Runnable done) {
        ServerService owner = service;
        if (busy || !writableDirectory() || owner == null || !owner.beginFileEdit()) { error("Pare o servidor antes de alterar arquivos."); return; }
        busy = true; refreshControls();
        io.execute(() -> {
            try { operation.run(); runOnUiThread(() -> { busy = false; if (!isDestroyed()) { done.run(); refreshControls(); } }); }
            catch (Exception e) { failure(e); }
            finally { owner.endFileEdit(); }
        });
    }
    private void failure(Exception e) { runOnUiThread(() -> { busy = false; if (!isDestroyed()) { refreshControls(); error(e.getMessage()); } }); }
    private void error(String message) { Toast.makeText(this, message == null ? "Falha na operação." : message, Toast.LENGTH_LONG).show(); }
}
