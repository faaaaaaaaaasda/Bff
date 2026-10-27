package com.wincors.smp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side control of Xaero's Minimap / World Map using Xaero's own official method: the server sends
 * a (blank-looking) system message containing hidden formatting codes, and the Xaero mods obey it.
 *   nominimap            -> the minimap is disabled completely
 *   fairxaero            -> fair-play: cave mode (minimap AND world map) and the entity radar are disabled
 *   xaerowmnetherisfair  -> lets the world map's cave mode work in the Nether despite fair-play
 *   resetxaero           -> clears all of the above on the client
 * Only the official Xaero mods honour these. Modified clients (or other map mods) can ignore them.
 */
public final class XaeroControl implements Listener {
    private final WincorsSMP plugin;

    public XaeroControl(WincorsSMP plugin) { this.plugin = plugin; }

    /** "nominimap" -> "§n§o§m§i§n§i§m§a§p" */
    static String code(String word) {
        StringBuilder sb = new StringBuilder();
        for (char c : word.toCharArray()) sb.append('\u00A7').append(c);
        return sb.toString();
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("xaero.enabled", true); }

    private List<String> words() {
        List<String> w = new ArrayList<>();
        if (plugin.getConfig().getBoolean("xaero.disable-minimap", true)) w.add("nominimap");
        if (plugin.getConfig().getBoolean("xaero.fair-play", true)) w.add("fairxaero");
        if (plugin.getConfig().getBoolean("xaero.nether-cave-mode", false)) w.add("xaerowmnetherisfair");
        return w;
    }

    public void send(Player p) {
        if (!enabled()) return;
        for (String w : words()) p.sendMessage(Component.text(code(w)));
    }

    public void reset(Player p) { p.sendMessage(Component.text(code("resetxaero"))); }

    public void sendAll() { for (Player p : Bukkit.getOnlinePlayers()) send(p); }

    public void resetAll() { for (Player p : Bukkit.getOnlinePlayers()) reset(p); }

    public void start() {
        int secs = plugin.getConfig().getInt("xaero.resend-seconds", 0);
        if (secs > 0) Bukkit.getScheduler().runTaskTimer(plugin, this::sendAll, secs * 20L, secs * 20L);
    }

    public List<String> status() {
        List<String> l = new ArrayList<>();
        l.add("&6Xaero's Minimap / World Map control &7(" + (enabled() ? "&aON" : "&cOFF") + "&7)");
        l.add("&7Minimap disabled: " + (plugin.getConfig().getBoolean("xaero.disable-minimap", true) ? "&ayes" : "&7no"));
        l.add("&7Fair-play (no cave mode on minimap + world map, no entity radar): " + (plugin.getConfig().getBoolean("xaero.fair-play", true) ? "&ayes" : "&7no"));
        l.add("&7Nether cave mode allowed: " + (plugin.getConfig().getBoolean("xaero.nether-cave-mode", false) ? "&ayes" : "&7no"));
        l.add("&8Only the official Xaero mods obey this; modified clients can ignore it.");
        return l;
    }

    private void later(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) send(p); }, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { later(e.getPlayer()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { later(e.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { later(e.getPlayer()); }
}
