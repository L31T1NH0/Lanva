package io.oxide.mobile;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServerProcessTest {
    static ServerProcess create(File world, String mode, AtomicInteger ready) {
        return new ServerProcess(Arrays.asList(System.getProperty("java.home") + "/bin/java", "-cp",
            new File(System.getProperty("java.class.path")).getAbsolutePath(), "io.oxide.mobile.FakeServer", mode),
            world, (state, logs) -> { if (state == ServerProcess.State.RUNNING && logs.endsWith("For help, type \"help\"")) ready.incrementAndGet(); });
    }

    static void await(ServerProcess process, ServerProcess.State state) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (process.state() != state && System.nanoTime() < deadline) Thread.sleep(10);
        assert process.state() == state : process.state() + "\n" + process.logs();
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("oxide-process-test").toFile();
        AtomicInteger ready = new AtomicInteger();
        ServerProcess process = create(root, "normal", ready);
        try {
            process.start();
            process.start();
            await(process, ServerProcess.State.RUNNING);
            assert ready.get() == 1 : "início duplicado";
            process.stop();
            process.stop();
            await(process, ServerProcess.State.STOPPED);
            assert Files.readString(root.toPath().resolve("world/saved.txt")).equals("salvo");
            process.start();
            await(process, ServerProcess.State.RUNNING);
            process.stop();
            await(process, ServerProcess.State.STOPPED);
            assert ready.get() == 2 : "reinício não funcionou";
        } finally { process.close(); }

        ServerProcess earlyStop = create(new File(root, "early"), "normal", new AtomicInteger());
        try {
            earlyStop.start();
            earlyStop.stop();
            await(earlyStop, ServerProcess.State.STOPPED);
            assert earlyStop.logs().contains("Servidor parado.") : earlyStop.logs();
        } finally { earlyStop.close(); }

        java.util.concurrent.CountDownLatch preparing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ServerProcess cancelled = new ServerProcess(Arrays.asList("/must-not-be-launched"), new File(root, "prepare"),
            java.util.Collections.emptyMap(), () -> { preparing.countDown(); release.await(); }, (state, logs) -> {});
        try {
            cancelled.start();
            assert preparing.await(5, java.util.concurrent.TimeUnit.SECONDS);
            cancelled.stop();
            release.countDown();
            await(cancelled, ServerProcess.State.STOPPED);
            assert cancelled.logs().contains("Preparação cancelada.");
        } finally { release.countDown(); cancelled.close(); }

        ServerProcess badPreparation = new ServerProcess(Arrays.asList("/must-not-be-launched"), new File(root, "bad-prepare"),
            java.util.Collections.emptyMap(), () -> { throw new java.io.IOException("runtime incompleto"); }, (state, logs) -> {});
        try {
            badPreparation.start();
            await(badPreparation, ServerProcess.State.ERROR);
            assert badPreparation.logs().contains("runtime incompleto");
        } finally { badPreparation.close(); }

        ServerProcess crash = create(new File(root, "crash"), "crash", new AtomicInteger());
        ServerProcess terrain = create(new File(root, "slow"), "slow", new AtomicInteger());
        try {
            terrain.start();
            await(terrain, ServerProcess.State.RUNNING);
            terrain.stop();
            await(terrain, ServerProcess.State.STOPPED);
        } finally { terrain.close(); }
        try {
            crash.start();
            await(crash, ServerProcess.State.ERROR);
            assert crash.logs().contains("código 7");
        } finally { crash.close(); }

        ServerProcess missing = new ServerProcess(Arrays.asList("/nonexistent/oxide"), root, (state, logs) -> {});
        try {
            missing.start();
            await(missing, ServerProcess.State.ERROR);
            assert missing.logs().contains("Falha:");
        } finally { missing.close(); }

        ServerProcess unsaved = create(new File(root, "unsaved"), "unsaved", new AtomicInteger());
        try {
            unsaved.start();
            await(unsaved, ServerProcess.State.RUNNING);
            unsaved.stop();
            await(unsaved, ServerProcess.State.ERROR);
            assert !unsaved.logs().contains("Mundo salvo.");
        } finally { unsaved.close(); }

        ServerProcess spam = create(new File(root, "spam"), "spam", new AtomicInteger());
        try {
            spam.start();
            await(spam, ServerProcess.State.RUNNING);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!spam.logs().contains("fim dos registros") && System.nanoTime() < deadline) Thread.sleep(10);
            assert spam.logs().contains("fim dos registros");
            assert spam.logs().length() <= 24_000;
            assert !spam.logs().contains("registro 0x");
            spam.stop();
            await(spam, ServerProcess.State.STOPPED);
        } finally { spam.close(); }
        System.out.println("OK: início único, reinício, parada durante início, salvamento confirmado, crash, binário ausente e limite de logs.");
    }
}

