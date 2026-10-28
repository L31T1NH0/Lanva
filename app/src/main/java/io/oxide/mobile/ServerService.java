package io.oxide.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class ServerService extends Service {
    static final String START = "io.oxide.mobile.START";
    static final String STOP = "io.oxide.mobile.STOP";
    private static final String CHANNEL = "oxide-server";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<ServerProcess.Listener> listeners = new HashSet<>();
    private final LocalBinder binder = new LocalBinder();
    private ServerProcess server;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;
    private boolean destroyed;
    private boolean updatePending;
    private static final Object FILE_GATE = new Object();
    // An edit can outlive the bound Activity and its Service instance.
    private static int fileEdits;
    private static ServerService live;

    final class LocalBinder extends Binder {
        ServerService service() { return ServerService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Servidor Minecraft", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oxide:server");
        server = createServer();
        synchronized (FILE_GATE) { live = this; }
    }

    private ServerProcess createServer() {
        ServerProfile profile = StorageSettings.profile(this, getSharedPreferences("server", MODE_PRIVATE)
            .getString("minecraftVersion", "26.3"));
        File directory = profile.directory;
        File javaHome = profile.javaHome;
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        Map<String, String> environment = new HashMap<>();
        environment.put("LD_LIBRARY_PATH", javaHome + "/lib:" + javaHome + "/lib/server:" + nativeDir);
        environment.put("JAVA_HOME", javaHome.getAbsolutePath());
        environment.put("TMPDIR", getCacheDir().getAbsolutePath());
        environment.put("JNA_LIBRARY_PATH", new File(profile.nativeHome, "jna").getAbsolutePath());
        return new ServerProcess(Arrays.asList(new File(nativeDir, "libfabric_launcher.so").getAbsolutePath(),
            javaHome.getAbsolutePath(), nativeDir, profile.bootstrap.getAbsolutePath(),
            new File(directory, "fabric-server.jar").getAbsolutePath(), "1024"), directory, environment,
            () -> ServerRuntime.prepare(this, profile), (state, logs) -> queueUpdate());
    }

    private void queueUpdate() {
        // Coalesce bursts of server output so logging cannot flood the UI thread.
        synchronized (this) {
            if (destroyed || updatePending) return;
            updatePending = true;
        }
        main.postDelayed(() -> {
            synchronized (ServerService.this) { updatePending = false; }
            if (destroyed) return;
            ServerProcess.State state = server.state();
            String logs = server.logs();
            for (ServerProcess.Listener listener : new HashSet<>(listeners)) listener.changed(state, logs);
            if (!foreground) return;
            if (state == ServerProcess.State.STOPPED || state == ServerProcess.State.ERROR) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
                if (wakeLock.isHeld()) wakeLock.release();
                stopSelf();
            } else {
                getSystemService(NotificationManager.class).notify(1, notification(state));
            }
        }, 150);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (FILE_GATE) {
            if (intent != null && STOP.equals(intent.getAction())) {
                server.stop();
            } else if (intent != null && START.equals(intent.getAction())) {
                if (fileEdits > 0) {
                    android.widget.Toast.makeText(this, "Aguarde a operação nos arquivos terminar.", android.widget.Toast.LENGTH_LONG).show();
                    stopSelf(startId);
                    return START_NOT_STICKY;
                }
                if (!idle()) return START_NOT_STICKY;
                server.close();
                server = createServer();
                Notification notification = notification(ServerProcess.State.STARTING);
                if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(1, notification);
                foreground = true;
                if (!wakeLock.isHeld()) wakeLock.acquire();
                server.start();
            }
            return START_NOT_STICKY;
        }
    }

    boolean idle() {
        return server.state() == ServerProcess.State.STOPPED || server.state() == ServerProcess.State.ERROR;
    }

    boolean canStart() {
        synchronized (FILE_GATE) { return idle() && fileEdits == 0; }
    }
    ServerProcess.State state() { return server.state(); }
    String logs() { return server.logs(); }

    boolean beginFileEdit() {
        synchronized (FILE_GATE) {
            if (!idle() || fileEdits > 0) return false;
            fileEdits++;
            if (live != null) live.queueUpdate();
            return true;
        }
    }

    void endFileEdit() {
        synchronized (FILE_GATE) {
            fileEdits--;
            if (live != null) live.queueUpdate();
        }
    }

    void addListener(ServerProcess.Listener listener) {
        listeners.add(listener);
        listener.changed(server.state(), server.logs());
    }
    void removeListener(ServerProcess.Listener listener) { listeners.remove(listener); }

    static String statusText(ServerProcess.State state) {
        switch (state) {
            case STARTING: return "Iniciando…";
            case RUNNING: return "Servidor ativo";
            case STOPPING: return "Salvando e parando…";
            case ERROR: return "Falha no servidor";
            default: return "Parado";
        }
    }

    private Notification notification(ServerProcess.State state) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ServerService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server).setContentTitle("Lanva")
            .setContentText(statusText(state)).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true);
        if (state != ServerProcess.State.STOPPING) builder.addAction(new Notification.Action.Builder(null, "Salvar e parar", stop).build());
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        synchronized (this) { destroyed = true; }
        synchronized (FILE_GATE) { if (live == this) live = null; }
        server.close();
        main.removeCallbacksAndMessages(null);
        if (wakeLock.isHeld()) wakeLock.release();
        listeners.clear();
        super.onDestroy();
    }
}

