package com.wincors.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ore-mining monitor (heuristic x-ray detection).
 * A rare ore counts as "suspect" when it was NOT naturally exposed to air/liquid - i.e. the player had to
 * tunnel to it blindly. We alert staff when a player mines suspiciously many such ores for the amount of stone
 * they dug (ratio) or finds several in a short burst. It never punishes on its own.
 * For real ore hiding, enable Paper's built-in anti-xray (see /wincors ac info).
 */
public final class XrayMonitor implements Listener {
    private static final Set<Material> STONE = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.GRANITE,
        Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE);
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    static final class Data {
        long windowStart;
        int blocks;
        int hits;
        int rareTotal;
        final Deque<Long> hitTimes = new ArrayDeque<>();
        final LinkedHashMap<Long, Long> broken = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) { return size() > 400; }
        };
    }

    private final WincorsSMP plugin;
    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private Set<Material> rare = EnumSet.noneOf(Material.class);

    public XrayMonitor(WincorsSMP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String n : plugin.getConfig().getStringList("anticheat.xray.rare-ores")) {
            Material m = Material.matchMaterial(n);
            if (m == null) plugin.getLogger().warning("Unknown block in anticheat.xray.rare-ores: " + n);
            else set.add(m);
        }
        rare = set;
    }

    private double num(String path, double def) { return plugin.getConfig().getDouble("anticheat.xray." + path, def); }

    /** True if some face of the ore touches open space that the player did NOT dig out themselves. */
    private boolean naturallyExposed(Block ore, Data d) {
        for (BlockFace f : FACES) {
            Block nb = ore.getRelative(f);
            boolean open = nb.getType().isAir() || nb.isLiquid() || !nb.getType().isOccluding();
            if (open && !d.broken.containsKey(nb.getBlockKey())) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true) || !plugin.anticheat().check("xray")) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        if (plugin.getConfig().getBoolean("anticheat.bypass-permission", true) && p.hasPermission("wincors.bypass")) return;

        Block b = e.getBlock();
        Material m = b.getType();
        Data d = data.computeIfAbsent(p.getUniqueId(), k -> new Data());
        long now = System.currentTimeMillis();
        long windowMs = (long) (num("window-minutes", 15) * 60_000);
        if (now - d.windowStart > windowMs) {
            d.windowStart = now;
            d.blocks = 0;
            d.hits = 0;
            d.hitTimes.clear();
        }

        if (STONE.contains(m)) d.blocks++;

        if (rare.contains(m)) {
            d.rareTotal++;
            boolean natural = naturallyExposed(b, d);
            if (plugin.getConfig().getBoolean("anticheat.xray.notify-all", false)) {
                notifyStaff(p, "&7found &b" + pretty(m) + (natural ? " &8(natural/visible)" : " &c(hidden)") + " &8at " + coords(b));
            }
            if (!natural) {
                d.hits++;
                d.hitTimes.addLast(now);
                long burstMs = (long) (num("burst-minutes", 4) * 60_000);
                while (!d.hitTimes.isEmpty() && now - d.hitTimes.peekFirst() > burstMs) d.hitTimes.removeFirst();

                boolean burst = d.hitTimes.size() >= (int) num("burst-hits", 4);
                boolean ratio = d.blocks >= (int) num("min-blocks", 120) && d.hits >= (int) num("min-hits", 5)
                    && (double) d.hits / Math.max(1, d.blocks) >= num("ratio-limit", 0.05);
                if (burst || ratio) {
                    String why = burst
                        ? d.hitTimes.size() + " hidden rare ores in " + String.format("%.0f", num("burst-minutes", 4)) + " min"
                        : d.hits + " hidden rare ores / " + d.blocks + " stone (" + String.format("%.1f", 100.0 * d.hits / Math.max(1, d.blocks)) + "%)";
                    Long last = lastAlert.get(p.getUniqueId());
                    if (last == null || now - last > 60_000) {
                        lastAlert.put(p.getUniqueId(), now);
                        plugin.anticheat().flag(p, "xray", 2.0, why + " at " + coords(b));
                    }
                }
            }
        }
        d.broken.put(b.getBlockKey(), now);
    }

    private static String coords(Block b) {
        return b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ();
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    private void notifyStaff(Player p, String text) {
        Component msg = Text.c("&b[Ore] &f" + p.getName() + " " + text)
            .clickEvent(ClickEvent.runCommand("/wincors tp " + p.getName()))
            .hoverEvent(HoverEvent.showText(Text.c("&eClick to teleport")));
        for (Player a : Bukkit.getOnlinePlayers()) if (a.hasPermission("wincors.alerts")) a.sendMessage(msg);
    }

    public String stats(Player p) {
        Data d = data.get(p.getUniqueId());
        if (d == null) return "no mining data yet";
        return "stone mined: " + d.blocks + " | hidden rare ores: " + d.hits + " | total rare ores: " + d.rareTotal
            + " | ratio: " + String.format("%.1f", 100.0 * d.hits / Math.max(1, d.blocks)) + "%";
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
        lastAlert.remove(e.getPlayer().getUniqueId());
    }
}
