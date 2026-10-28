package io.oxide.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MainActivity extends Activity {
    private ServerService service;
    private boolean bound;
    private Button start;
    private Button stop;
    private TextView status;
    private TextView logs;
    private Button version;
    private TextView versionHint;
    private Button storage;
    private boolean loadingVersions;
    private boolean watchingNetwork;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            refreshAddresses();
        }
        @Override public void onLost(Network network) { refreshAddresses(); }
        private void refreshAddresses() {
            runOnUiThread(() -> {
                if (watchingNetwork) ((TextView) findViewById(R.id.address)).setText(addresses());
            });
        }
    };
    private final ServerProcess.Listener listener = this::render;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ServerService.LocalBinder) binder).service();
            service.addListener(listener);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            start.setEnabled(false);
            stop.setEnabled(false);
            status.setText("Conexão com o serviço perdida. Reabra o aplicativo.");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View screen = findViewById(R.id.screen);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        screen.requestApplyInsets();
        start = findViewById(R.id.start);
        stop = findViewById(R.id.stop);
        status = findViewById(R.id.status);
        logs = findViewById(R.id.logs);
        version = findViewById(R.id.version);
        versionHint = findViewById(R.id.version_hint);
        storage = findViewById(R.id.storage);
        storage.setOnClickListener(view -> startActivity(new Intent(this, StorageActivity.class)));
        android.content.SharedPreferences preferences = getSharedPreferences("server", MODE_PRIVATE);
        // Existing saved versions came from an explicit selection in the previous APK.
        if (preferences.contains("minecraftVersion") && !preferences.contains("versionChosen")) {
            preferences.edit().putBoolean("versionChosen", true).apply();
        }
        updateVersion();
        version.setOnClickListener(view -> chooseVersion());
        findViewById(R.id.files).setOnClickListener(view -> startActivity(new Intent(this, FilesActivity.class)
            .putExtra("version", selectedVersion())));
        start.setOnClickListener(view -> {
            if (getSharedPreferences("server", MODE_PRIVATE).getBoolean("minecraftEulaAccepted", false)) {
                beginServer();
            } else {
                android.text.SpannableString message = new android.text.SpannableString(
                    "Para usar o servidor Minecraft, leia e aceite a EULA:\nhttps://www.minecraft.net/eula\n\n"
                    + "Na primeira execução, o Fabric baixa o servidor e suas bibliotecas. É preciso internet.");
                android.text.util.Linkify.addLinks(message, android.text.util.Linkify.WEB_URLS);
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle("EULA do Minecraft")
                    .setMessage(message).setNegativeButton("Cancelar", null)
                    .setPositiveButton("Li e aceito", (choice, which) -> {
                        getSharedPreferences("server", MODE_PRIVATE).edit().putBoolean("minecraftEulaAccepted", true).apply();
                        beginServer();
                    }).show();
                ((TextView) dialog.findViewById(android.R.id.message)).setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            }
        });
        stop.setOnClickListener(view -> {
            startService(new Intent(this, ServerService.class).setAction(ServerService.STOP));
            stop.setEnabled(false);
        });
        loadVersions(false);
    }

    private void beginServer() {
        if (waitingForFirstCatalog()) {
            Toast.makeText(this, "Aguarde a busca da versão mais recente.", Toast.LENGTH_LONG).show();
            return;
        }
        if (StorageSettings.publicSelected(this) && !StorageSettings.hasPublicAccess(this)) {
            Toast.makeText(this, "Permita acesso à pasta pública antes de iniciar.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, StorageActivity.class));
            return;
        }
        if (service == null || !service.canStart()) {
            Toast.makeText(this, "Aguarde o servidor ou a operação nos arquivos terminar.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        try {
            startForegroundService(new Intent(this, ServerService.class).setAction(ServerService.START));
            start.setEnabled(false);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Não foi possível iniciar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        updateStorage();
        ((TextView) findViewById(R.id.address)).setText(addresses());
        try {
            getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), networkCallback);
            watchingNetwork = true;
        } catch (RuntimeException e) {
            android.util.Log.w("OxideMobile", "Não foi possível acompanhar a rede", e);
        }
        bound = bindService(new Intent(this, ServerService.class), connection, BIND_AUTO_CREATE);
        if (!bound) status.setText("Não foi possível conectar ao serviço. Reabra o aplicativo.");
    }

    @Override protected void onStop() {
        if (watchingNetwork) {
            watchingNetwork = false;
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        }
        if (service != null) service.removeListener(listener);
        if (bound) unbindService(connection);
        service = null;
        bound = false;
        start.setEnabled(false);
        stop.setEnabled(false);
        super.onStop();
    }

    private void render(ServerProcess.State state, String output) {
        applyLatestDefault();
        updateStorage();
        status.setText(ServerService.statusText(state));
        start.setEnabled((state == ServerProcess.State.STOPPED || state == ServerProcess.State.ERROR)
            && service != null && service.canStart() && !waitingForFirstCatalog());
        stop.setEnabled(state == ServerProcess.State.STARTING || state == ServerProcess.State.RUNNING);
        version.setEnabled((state == ServerProcess.State.STOPPED || state == ServerProcess.State.ERROR) && !loadingVersions);
        storage.setEnabled(service != null && service.canStart());
        logs.setText(output.isEmpty() ? "Toque em Iniciar servidor para começar." : output);
    }

    private String selectedVersion() {
        return getSharedPreferences("server", MODE_PRIVATE).getString("minecraftVersion", "26.3");
    }

    private boolean waitingForFirstCatalog() {
        android.content.SharedPreferences preferences = getSharedPreferences("server", MODE_PRIVATE);
        return loadingVersions && !preferences.getBoolean("versionChosen", false)
            && preferences.getString("fabricVersions", "").isEmpty();
    }

    private void applyLatestDefault() {
        android.content.SharedPreferences preferences = getSharedPreferences("server", MODE_PRIVATE);
        if (service == null || !service.canStart() || preferences.getBoolean("versionChosen", false)) return;
        String cached = preferences.getString("fabricVersions", "");
        if (cached.isEmpty()) return;
        String latest = cached.split("\n")[0];
        try { ServerProfile.javaMajor(latest); }
        catch (IllegalArgumentException ignored) { return; }
        if (!latest.equals(selectedVersion())) {
            preferences.edit().putString("minecraftVersion", latest).apply();
            updateVersion();
        }
    }

    private void updateStorage() {
        ((TextView)findViewById(R.id.storage_path)).setText(StorageSettings.servers(this).getAbsolutePath());
    }

    private void updateVersion() {
        version.setText("Minecraft " + selectedVersion() + " · Escolher versão");
        String mode = getSharedPreferences("server", MODE_PRIVATE).getBoolean("versionChosen", false) ? "" : "Última estável automática · ";
        versionHint.setText(mode + "Fabric · Java " + ServerProfile.javaMajor(selectedVersion())
            + " automático. Cada versão tem seu próprio mundo e arquivos.");
    }

    private void chooseVersion() {
        if (service == null || !service.idle()) return;
        Set<String> choices = new LinkedHashSet<>();
        choices.add(selectedVersion());
        java.io.File[] installed = StorageSettings.servers(this).listFiles();
        if (installed != null) for (java.io.File directory : installed) {
            if (!directory.isDirectory()) continue;
            try { ServerProfile.javaMajor(directory.getName()); choices.add(directory.getName()); }
            catch (IllegalArgumentException ignored) { }
        }
        String cached = getSharedPreferences("server", MODE_PRIVATE).getString("fabricVersions", "");
        for (String entry : cached.split("\\n")) {
            try { ServerProfile.javaMajor(entry); choices.add(entry); }
            catch (IllegalArgumentException ignored) { }
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(choices);
        sorted.sort(ServerProfile::newestFirst);
        String[] options = sorted.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Versão do Minecraft")
            .setSingleChoiceItems(options, sorted.indexOf(selectedVersion()), (dialog, index) -> {
                if (service == null || !service.idle()) return;
                getSharedPreferences("server", MODE_PRIVATE).edit().putString("minecraftVersion", options[index]).putBoolean("versionChosen", true).apply();
                updateVersion();
                dialog.dismiss();
            })
            .setPositiveButton("Buscar versões", (dialog, which) -> loadVersions(true))
            .setNeutralButton("Usar última estável", (dialog, which) -> {
                if (service == null || !service.canStart()) return;
                getSharedPreferences("server", MODE_PRIVATE).edit().putBoolean("versionChosen", false).apply();
                applyLatestDefault();
                updateVersion();
                loadVersions(false);
            })
            .setNegativeButton("Cancelar", null).show();
    }

    private void loadVersions(boolean openPicker) {
        if (loadingVersions) return;
        loadingVersions = true;
        version.setEnabled(false);
        versionHint.setText("Buscando versões estáveis no Fabric…");
        new Thread(() -> {
            try {
                java.util.List<String> available = FabricCatalog.versions();
                getSharedPreferences("server", MODE_PRIVATE).edit().putString("fabricVersions", String.join("\n", available)).apply();
                runOnUiThread(() -> {
                    loadingVersions = false;
                    if (isFinishing() || isDestroyed()) return;
                    updateVersion();
                    applyLatestDefault();
                    version.setEnabled(service != null && service.idle());
                    if (service != null) render(service.state(), service.logs());
                    if (openPicker) chooseVersion();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingVersions = false;
                    if (isFinishing() || isDestroyed()) return;
                    version.setEnabled(service != null && service.idle());
                    versionHint.setText("Não foi possível buscar versões. Verifique a internet e tente novamente.");
                    if (service != null) render(service.state(), service.logs());
                    if (openPicker) Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "fabric-catalog").start();
    }

    private String addresses() {
        Set<String> addresses = new LinkedHashSet<>();
        try {
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties properties = connectivity.getLinkProperties(network);
                if (properties == null) continue;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    java.net.InetAddress address = link.getAddress();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                        && !address.isAnyLocalAddress()) addresses.add(address.getHostAddress());
                }
            }
        } catch (RuntimeException e) {
            android.util.Log.w("OxideMobile", "Não foi possível consultar os endereços Android", e);
        }
        if (addresses.isEmpty()) try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) addresses.add(address.getHostAddress());
                }
            }
        } catch (Exception e) {
            android.util.Log.w("OxideMobile", "Não foi possível consultar as interfaces", e);
        }
        StringBuilder text = new StringBuilder(addresses.isEmpty()
            ? "Nenhum IPv4 da rede local encontrado. Consulte o endereço IP nos detalhes do Wi-Fi.\n" : "Endereços na rede local\n");
        for (String address : addresses) text.append("Java: ").append(address).append(":25565\n");
        return text.append("Neste aparelho: 127.0.0.1:25565").toString();
    }
}
