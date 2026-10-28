package io.oxide.mobile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns exactly one server process; Android lifecycle and widgets live elsewhere. */
final class ServerProcess {
    interface Preparation { void run() throws Exception; }
    enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
    interface Listener { void changed(State state, String logs); }
    private static final int MAX_LOG_CHARS = 24_000;
    private final List<String> command;
    private final File directory;
    private final Listener listener;
    private final Map<String, String> environment;
    private final Preparation preparation;
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private State state = State.STOPPED;
    private Process process;
    private boolean stopRequested;
    private boolean failed;
    private boolean saved;
    private boolean launched;
    private int logChars;
    private ScheduledFuture<?> startupTimer;
    private ScheduledFuture<?> stopTimer;

    ServerProcess(List<String> command, File directory, Listener listener) {
        this(command, directory, Collections.emptyMap(), () -> {}, listener);
    }

    ServerProcess(List<String> command, File directory, Map<String, String> environment, Preparation preparation, Listener listener) {
        this.command = Collections.unmodifiableList(new ArrayList<>(command));
        this.directory = directory;
        this.listener = listener;
        this.environment = environment;
        this.preparation = preparation;
    }

    synchronized State state() { return state; }
    synchronized String logs() { return String.join("\n", logs); }

    synchronized void start() {
        if (state == State.STARTING || state == State.RUNNING || state == State.STOPPING) return;
        if (timers.isShutdown()) return;
        stopRequested = false;
        failed = false;
        saved = false;
        launched = false;
        logs.clear();
        logChars = 0;
        state = State.STARTING;
        log("Preparando Java e a instalação Fabric…");
        new Thread(this::run, "oxide-process").start();
    }

    synchronized void stop() {
        if (state != State.STARTING && state != State.RUNNING) return;
        stopRequested = true;
        state = State.STOPPING;
        log("Salvando o mundo antes de parar…");
        if (process != null) sendStop();
    }

    synchronized void close() {
        stop();
        // Keep the stop timeout alive until the process has actually exited.
        timers.shutdown();
    }

    private void run() {
        int exit = -1;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new java.io.IOException("Não foi possível criar a pasta do servidor.");
            }
            preparation.run();
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
            builder.environment().putAll(environment);
            synchronized (this) {
                if (stopRequested) { exit = 0; return; }
                log("Iniciando Minecraft Java com Fabric…");
                process = builder.start();
                launched = true;
                if (stopRequested) sendStop();
                else startupTimer = timers.schedule(this::startupExpired, 300, TimeUnit.SECONDS);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (this) {
                        if (line.contains("[Server thread/INFO]: Done (") && line.contains("For help, type") && state == State.STARTING) {
                            state = State.RUNNING;
                            cancel(startupTimer);
                        }
                        if (stopRequested && line.contains("All dimensions are saved")) saved = true;
                        log(line);
                    }
                }
            }
            exit = process.waitFor();
        } catch (Exception e) {
            synchronized (this) {
                failed = true;
                log("Falha: " + e.getMessage());
            }
        } finally {
            synchronized (this) {
                // A pipe/read failure must not allow starting a second writer against the world.
                if (process != null && process.isAlive()) {
                    failed = true;
                    stopRequested = true;
                    state = State.STOPPING;
                    sendStop();
                    new Thread(this::awaitFailedProcess, "oxide-exit").start();
                } else {
                    finish(exit);
                }
            }
        }
    }

    private void awaitFailedProcess() {
        try { finishAfterWait(process.waitFor()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private synchronized void finishAfterWait(int exit) { finish(exit); }

    private void finish(int exit) {
        cancel(startupTimer);
        cancel(stopTimer);
        startupTimer = null;
        stopTimer = null;
        process = null;
        state = stopRequested && exit == 0 && (saved || !launched) && !failed ? State.STOPPED : State.ERROR;
        log(state == State.STOPPED ? (saved ? "Mundo salvo. Servidor parado." : "Preparação cancelada. Servidor parado.")
            : "Servidor encerrou com código " + exit + ". Consulte o registro acima.");
    }

    private synchronized void startupExpired() {
        if (state != State.STARTING) return;
        failed = true;
        log("O Minecraft não ficou pronto em 5 minutos. Solicitando encerramento.");
        stop();
    }

    private void sendStop() {
        Process target = process;
        new Thread(() -> {
            try {
                OutputStream input = target.getOutputStream();
                input.write("stop\n".getBytes(StandardCharsets.UTF_8));
                input.flush();
            } catch (Exception e) {
                synchronized (this) { log("Não foi possível solicitar a parada: " + e.getMessage()); }
            }
        }, "oxide-stop").start();
        if (stopTimer == null && !timers.isShutdown()) {
            stopTimer = timers.schedule(() -> {
                synchronized (this) {
                    if (state == State.STOPPING) log("A parada está demorando. O processo será mantido para não interromper o salvamento.");
                }
            }, 60, TimeUnit.SECONDS);
        }
    }

    private void log(String line) {
        if (line.length() > 2000) line = line.substring(0, 2000) + "…";
        logs.addLast(line);
        logChars += line.length() + 1;
        while (logChars > MAX_LOG_CHARS) logChars -= logs.removeFirst().length() + 1;
        listener.changed(state, String.join("\n", logs));
    }

    private static void cancel(ScheduledFuture<?> timer) { if (timer != null) timer.cancel(false); }
}
