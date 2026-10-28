package io.oxide.bootstrap;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;

/** Starts the unmodified Fabric server launcher with its own application class loader. */
public final class FabricBootstrap {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        String main;
        try (JarFile file = new JarFile(jar.toFile())) {
            main = file.getManifest().getMainAttributes().getValue("Main-Class");
        }
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
            ClassLoader.getSystemClassLoader().getParent());
        System.setProperty("java.class.path", jar.toAbsolutePath().toString());
        Thread.currentThread().setContextClassLoader(loader);
        String[] serverArgs = args.length > 1 ? new String[]{args[1]} : new String[]{"nogui"};
        Class.forName(main, true, loader).getMethod("main", String[].class).invoke(null, (Object) serverArgs);
        // The Fabric/Minecraft threads retain this loader until process exit.
    }
}
