package io.oxide.mobile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Protocol fixture for the process supervisor, not a Minecraft server. */
public final class FakeServer {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("crash")) {
            System.out.println("falha simulada");
            System.exit(7);
        }
        if (args[0].equals("slow")) Thread.sleep(250);
        Path world = Path.of("world");
        Files.createDirectories(world);
        System.out.println("[00:00:00] [Server thread/INFO]: Done (0.1s)! For help, type \"help\"");
        if (args[0].equals("spam")) {
            for (int i = 0; i < 1000; i++) System.out.println("registro " + i + "x".repeat(100));
            System.out.println("fim dos registros");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        if ("stop".equals(reader.readLine())) {
            if (args[0].equals("unsaved")) return;
            Files.writeString(world.resolve("saved.txt"), "salvo");
            System.out.println("[00:00:00] [Server thread/INFO]: ThreadedAnvilChunkStorage: All dimensions are saved");
        } else System.exit(10);
    }
}

