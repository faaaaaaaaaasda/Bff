package com.wincors.smp;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The built-in texture pack: builds WincorsSMP-pack.zip from the files inside the plugin jar, can serve it
 * with a tiny built-in web server, and sends it to players on join. Items carry a custom_model_data tag, so
 * players without the pack just see the normal vanilla items.
 */
public final class ResourcePackManager implements Listener {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("wincors-smp-pack".getBytes(StandardCharsets.UTF_8));
    private static final String FILE_NAME = "WincorsSMP-pack.zip";

    private final WincorsSMP plugin;
    private volatile byte[] bytes;
    private byte[] sha1;
    private String sha1Hex = "";
    private HttpServer server;
    private ExecutorService executor;
    private File packFile;

    public ResourcePackManager(WincorsSMP plugin) { this.plugin = plugin; }

    // ---------------- items ----------------

    /** Tags an item with the custom model key (e.g. "emberfang"); the pack maps it to a texture. */
    public static void applyModel(WincorsSMP plugin, ItemMeta meta, String key) {
        if (key == null || key.isBlank() || !plugin.getConfig().getBoolean("resourcepack.custom-models", true)) return;
        CustomModelDataComponent c = meta.getCustomModelDataComponent();
        c.setStrings(List.of(key));
        meta.setCustomModelDataComponent(c);
    }

    // ---------------- lifecycle ----------------

    public boolean enabled() { return plugin.getConfig().getBoolean("resourcepack.enabled", true); }

    public void start() {
        if (!enabled()) return;
        try {
            rebuild();
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not build the resource pack: " + ex.getMessage());
            return;
        }
        String ext = plugin.getConfig().getString("resourcepack.url", "");
        if ((ext == null || ext.isBlank()) && plugin.getConfig().getBoolean("resourcepack.self-host.enabled", true)) startHost();
        String u = url();
        if (u == null) {
            plugin.getLogger().warning("Resource pack built (" + packFile.getName() + ") but no download URL is set: set resourcepack.self-host.public-host "
                + "(your server's public IP/domain) or resourcepack.url. Players will see normal textures until then.");
        } else {
            plugin.getLogger().info("Resource pack ready: " + u + "  (sha1 " + sha1Hex + ")");
        }
    }

    public void shutdown() {
        try {
            if (server != null) server.stop(0);
            if (executor != null) executor.shutdownNow();
        } catch (Throwable ignored) { }
        server = null;
        executor = null;
    }

    /** (Re)creates the zip from the pack/ folder inside the jar. */
    public void rebuild() throws IOException {
        List<String> files = new ArrayList<>();
        try (InputStream in = plugin.getResource("pack/index.txt")) {
            if (in == null) throw new IOException("pack/index.txt is missing from the plugin jar");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) files.add(line.trim());
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String f : files) {
                try (InputStream in = plugin.getResource("pack/" + f)) {
                    if (in == null) { plugin.getLogger().warning("Missing pack file: " + f); continue; }
                    ZipEntry e = new ZipEntry(f);
                    e.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));   // fixed time -> the same hash every start
                    zos.putNextEntry(e);
                    zos.write(in.readAllBytes());
                    zos.closeEntry();
                }
            }
        }
        byte[] data = bos.toByteArray();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            sha1 = md.digest(data);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : sha1) sb.append(String.format("%02x", b));
        sha1Hex = sb.toString();
        plugin.getDataFolder().mkdirs();
        packFile = new File(plugin.getDataFolder(), FILE_NAME);
        Files.write(packFile.toPath(), data);
        bytes = data;
    }

    private void startHost() {
        int port = plugin.getConfig().getInt("resourcepack.self-host.port", 8165);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/" + FILE_NAME, ex -> {
                byte[] data = bytes;
                if (data == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, data.length);
                try (var out = ex.getResponseBody()) { out.write(data); }
            });
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "WincorsSMP-packhost");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not start the built-in pack web server on port " + port + ": " + ex
                + ". Host " + FILE_NAME + " yourself and set resourcepack.url.");
            server = null;
        }
    }

    // ---------------- sending ----------------

    /** The URL players download from, or null if none is configured. */
    public String url() {
        String ext = plugin.getConfig().getString("resourcepack.url", "");
        if (ext != null && !ext.isBlank()) return ext.trim();
        if (server == null) return null;
        String host = plugin.getConfig().getString("resourcepack.self-host.public-host", "");
        if (host == null || host.isBlank()) host = Bukkit.getIp();
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) return null;
        return "http://" + host.trim() + ":" + plugin.getConfig().getInt("resourcepack.self-host.port", 8165) + "/" + FILE_NAME;
    }

    public void send(Player p) {
        String u = url();
        if (!enabled() || u == null || sha1 == null) return;
        p.setResourcePack(PACK_ID, u, sha1,
            Text.c(plugin.getConfig().getString("resourcepack.prompt", "&6Wincors SMP &7textures")),
            plugin.getConfig().getBoolean("resourcepack.required", false));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("resourcepack.send-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) send(p); }, 40L);
    }

    public List<String> info() {
        List<String> l = new ArrayList<>();
        l.add("&6Resource pack &7(" + (enabled() ? "&aON" : "&cOFF") + "&7)");
        l.add("&7File: &f" + (packFile == null ? "not built" : packFile.getPath() + " (" + (bytes == null ? 0 : bytes.length / 1024) + " KB)"));
        l.add("&7SHA-1: &f" + (sha1Hex.isEmpty() ? "-" : sha1Hex));
        String u = url();
        l.add("&7URL: " + (u == null ? "&cnot available &7- set resourcepack.self-host.public-host (your public IP/domain) or resourcepack.url" : "&f" + u));
        l.add("&7Built-in web server: " + (server != null ? "&arunning on port " + plugin.getConfig().getInt("resourcepack.self-host.port", 8165) : "&7off"));
        return l;
    }
}
