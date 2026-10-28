package io.oxide.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

final class FabricCatalog {
    private static final String META = "https://meta.fabricmc.net/v2/versions/";

    static List<String> versions() throws Exception {
        JSONArray entries = json("game");
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String version = entry.getString("version");
            if (!entry.optBoolean("stable")) continue;
            try { ServerProfile.javaMajor(version); versions.add(version); }
            catch (IllegalArgumentException ignored) { /* Not supported by the packaged runtimes. */ }
        }
        if (versions.isEmpty()) throw new IOException("O Fabric não retornou versões compatíveis.");
        versions.sort(ServerProfile::newestFirst);
        return versions;
    }

    static String install(ServerProfile profile) throws Exception {
        File jar = new File(profile.directory, "fabric-server.jar");
        File manifest = new File(profile.directory, "installation.json");
        if (jar.isFile() && manifest.isFile()) {
            JSONObject installed = new JSONObject(FileAccess.readText(manifest));
            if (!profile.version.equals(installed.getString("minecraft"))) throw new IOException("Instalação de outra versão nesta pasta.");
            validateJar(jar);
            return installed.getString("jna");
        }
        JSONObject minecraft = minecraftMetadata(profile.version);
        if (minecraft.getJSONObject("javaVersion").getInt("majorVersion") != profile.javaMajor) {
            throw new IOException("Esta versão exige outro runtime Java. Atualize o aplicativo.");
        }
        String jna = null;
        JSONArray libraries = minecraft.getJSONArray("libraries");
        for (int i = 0; i < libraries.length(); i++) {
            String name = libraries.getJSONObject(i).getString("name");
            if (name.startsWith("net.java.dev.jna:jna:")) jna = name.substring("net.java.dev.jna:jna:".length());
        }
        if (jna == null || !jna.matches("[0-9.]+")) throw new IOException("Não foi possível identificar a biblioteca JNA do Minecraft.");
        String loader = null;
        JSONArray loaders = json("loader/" + profile.version);
        for (int i = 0; i < loaders.length(); i++) {
            JSONObject candidate = loaders.getJSONObject(i).getJSONObject("loader");
            if (candidate.optBoolean("stable")) { loader = candidate.getString("version"); break; }
        }
        if (loader == null) throw new IOException("Nenhum Fabric Loader estável disponível para " + profile.version);
        String installer = null;
        JSONArray installers = json("installer");
        for (int i = 0; i < installers.length(); i++) {
            JSONObject candidate = installers.getJSONObject(i);
            if (candidate.optBoolean("stable")) { installer = candidate.getString("version"); break; }
        }
        if (installer == null || !loader.matches("[0-9.]+") || !installer.matches("[0-9.]+")) throw new IOException("Versões inválidas retornadas pelo Fabric.");
        File temporary = new File(profile.directory, "fabric-server.jar.part");
        download(META + "loader/" + profile.version + "/" + loader + "/" + installer + "/server/jar", temporary, 64 * 1024 * 1024);
        validateJar(temporary);
        if (!temporary.renameTo(jar)) throw new IOException("Não foi possível instalar o launcher Fabric.");
        JSONObject info = new JSONObject().put("minecraft", profile.version).put("loader", loader)
            .put("installer", installer).put("java", profile.javaMajor).put("jna", jna);
        FileAccess.writeText(manifest, info.toString(2));
        return jna;
    }

    private static JSONObject minecraftMetadata(String version) throws Exception {
        JSONObject manifest = new JSONObject(readJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        JSONArray versions = manifest.getJSONArray("versions");
        for (int i = 0; i < versions.length(); i++) {
            JSONObject entry = versions.getJSONObject(i);
            if (!version.equals(entry.getString("id"))) continue;
            URL url = new URL(entry.getString("url"));
            if (!"https".equals(url.getProtocol()) || !("piston-meta.mojang.com".equals(url.getHost())
                || "launchermeta.mojang.com".equals(url.getHost()))) throw new IOException("Endereço de versão Mojang inválido.");
            return new JSONObject(readJson(url.toString()));
        }
        throw new IOException("A versão não foi encontrada no catálogo Mojang.");
    }

    private static JSONArray json(String path) throws Exception {
        return new JSONArray(readJson(META + path));
    }

    private static String readJson(String url) throws IOException {
        HttpURLConnection connection = connect(url);
        try (InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            transfer(input, output, 2 * 1024 * 1024);
            return output.toString("UTF-8");
        } finally { connection.disconnect(); }
    }

    private static void download(String url, File destination, int limit) throws IOException {
        HttpURLConnection connection = connect(url);
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
            transfer(input, output, limit);
            output.getFD().sync();
        } catch (IOException e) {
            destination.delete();
            throw e;
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "Lanva/0.3");
        return connection;
    }

    static void transfer(InputStream input, java.io.OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Arquivo maior que o limite permitido.");
            output.write(buffer, 0, count);
        }
    }

    private static void validateJar(File file) throws IOException {
        try (JarFile jar = new JarFile(file)) {
            if (jar.getManifest() == null || jar.getManifest().getMainAttributes().getValue("Main-Class") == null) {
                throw new IOException("Launcher Fabric incompleto. Tente iniciar novamente com internet.");
            }
        }
    }
}
