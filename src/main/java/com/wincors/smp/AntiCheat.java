package com.wincors.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Behaviour-based anti-hack: fly, speed, reach, auto-click, nuker and block-reach checks with violation
 * levels (VL), staff alerts, optional setback / kick / command punishments and a log file.
 * Powers (dashes, launches, teleports, knockback) are exempted automatically.
 * This is a heuristic layer - it is not a replacement for a dedicated packet-level anti-cheat.
 */
public final class AntiCheat implements Listener {
    private static final Set<Material> SPECIAL_BLOCKS = Set.of(
        Material.COBWEB, Material.SCAFFOLDING, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.HONEY_BLOCK,
        Material.SLIME_BLOCK, Material.LADDER, Material.VINE, Material.TWISTING_VINES, Material.WEEPING_VINES,
        Material.CAVE_VINES, Material.CAVE_VINES_PLANT);
    private static final Set<String> SETBACK_CHECKS = Set.of("fly", "speed");

    static final class State {
        final Map<String, Double> vl = new HashMap<>();
        long exemptUntil, lastTeleport, lastHurt, lastVelocity, lastCommand;
        Location lastSafe;
        int hover, noFall, airTicks;
        double airStartY;
        long winStart;
        double winDist;
        long cpsWin;
        int cps;
        long breakWin;
        int breaks;
    }

    private final WincorsSMP plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final File logFile;

    public AntiCheat(WincorsSMP plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "anticheat.log");
    }

    // ------------------------------------------------------------------ setup

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double dec = num("decay-per-20s", 1.0);
            for (State s : states.values()) {
                s.vl.replaceAll((k, v) -> Math.max(0, v - dec));
                s.vl.values().removeIf(v -> v <= 0);
            }
        }, 400L, 400L);
        String x = paperXrayStatus();
        plugin.getLogger().info("Anti-cheat enabled. " + x);
    }

    // ------------------------------------------------------------------ config helpers

    boolean on() { return plugin.getConfig().getBoolean("anticheat.enabled", true); }

    boolean check(String name) { return plugin.getConfig().getBoolean("anticheat.checks." + name + ".enabled", true); }

    double num(String path, double def) { return plugin.getConfig().getDouble("anticheat." + path, def); }

    private boolean bypassed(Player p) {
        return plugin.getConfig().getBoolean("anticheat.bypass-permission", true) && p.hasPermission("wincors.bypass");
    }

    State st(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }

    /** Checks apply to this player right now (survival/adventure, not bypassed). */
    private boolean active(Player p) {
        if (!on() || bypassed(p) || (plugin.calamity() != null && plugin.calamity().isCasting(p))) return false;
        GameMode g = p.getGameMode();
        return g == GameMode.SURVIVAL || g == GameMode.ADVENTURE;
    }

    /** Exempt a player from movement/combat checks for a while (used by powers). */
    public void exempt(Player p, long ms) {
        State s = st(p);
        s.exemptUntil = Math.max(s.exemptUntil, System.currentTimeMillis() + ms);
    }

    private boolean isExempt(Player p) { return st(p).exemptUntil > System.currentTimeMillis(); }

    // ------------------------------------------------------------------ violations

    void flag(Player p, String check, double weight, String detail) {
        State s = st(p);
        double v = s.vl.merge(check, weight, Double::sum);
        log(p.getName() + " failed " + check + " (VL " + String.format("%.1f", v) + ") " + detail);
        if (v >= num("checks." + check + ".alert-vl", 3)) alert(p, check, v, detail);
        punish(p, s, check, v);
    }

    private void alert(Player p, String check, double vl, String detail) {
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + check;
        Long last = lastAlert.get(key);
        if (last != null && now - last < (long) (num("alert-cooldown-seconds", 3) * 1000)) return;
        lastAlert.put(key, now);
        Component msg = Text.c("&c[AC] &f" + p.getName() + " &7failed &e" + check + " &8(VL &f" + String.format("%.1f", vl) + "&8) &7" + detail)
            .clickEvent(ClickEvent.runCommand("/wincors tp " + p.getName()))
            .hoverEvent(HoverEvent.showText(Text.c("&eClick to teleport to " + p.getName())));
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("wincors.alerts") && !alertsOff.contains(a.getUniqueId())) a.sendMessage(msg);
        }
        plugin.getLogger().warning("[AC] " + p.getName() + " failed " + check + " (VL " + String.format("%.1f", vl) + ") " + detail);
    }

    private void punish(Player p, State s, String check, double v) {
        if (SETBACK_CHECKS.contains(check) && plugin.getConfig().getBoolean("anticheat.punish.setback", true)
                && v >= num("checks." + check + ".setback-vl", 4) && s.lastSafe != null) {
            Location back = s.lastSafe.clone();
            s.lastTeleport = System.currentTimeMillis();
            p.teleport(back);
            p.setFallDistance(0);
        }
        double total = s.vl.values().stream().mapToDouble(Double::doubleValue).sum();
        double kick = num("punish.kick-vl", 0);
        if (kick > 0 && total >= kick) {
            p.kick(Text.c("&cKicked by the anti-cheat. (" + check + ")"));
            return;
        }
        String cmd = plugin.getConfig().getString("anticheat.punish.command", "");
        double cvl = num("punish.command-vl", 0);
        long now = System.currentTimeMillis();
        if (cmd != null && !cmd.isBlank() && cvl > 0 && total >= cvl && now - s.lastCommand > 60_000) {
            s.lastCommand = now;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("{check}", check));
        }
    }

    private void log(String line) {
        String text = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + line + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDataFolder().mkdirs();
                Files.writeString(logFile.toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        });
    }

    // ------------------------------------------------------------------ movement

    private boolean skipMovement(Player p, State s, long now) {
        if (!active(p)) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding() || p.isInsideVehicle() || p.isDead()) return true;
        if (s.exemptUntil > now || now - s.lastTeleport < 2000 || now - s.lastHurt < 1500 || now - s.lastVelocity < 1500) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        return p.isInWater() || p.isInLava() || p.isClimbing();
    }

    private static boolean supported(Location l) {
        World w = l.getWorld();
        for (double dx : new double[]{-0.3, 0, 0.3}) {
            for (double dz : new double[]{-0.3, 0, 0.3}) {
                Block b = w.getBlockAt(Location.locToBlock(l.getX() + dx), Location.locToBlock(l.getY() - 0.35), Location.locToBlock(l.getZ() + dz));
                if (!b.isPassable() || b.isLiquid() || SPECIAL_BLOCKS.contains(b.getType())) return true;
            }
        }
        return SPECIAL_BLOCKS.contains(w.getBlockAt(l).getType());
    }

    private double speedLimit(Player p, Location at) {
        double limit = num("checks.speed.max-bps", 9.5) * (p.getWalkSpeed() / 0.2);
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) limit *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        Material below = at.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.BLUE_ICE || below == Material.FROSTED_ICE) limit *= 1.6;
        return limit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        State s = st(p);
        long now = System.currentTimeMillis();
        if (skipMovement(p, s, now)) {
            s.winStart = 0;
            s.winDist = 0;
            s.hover = 0;
            s.noFall = 0;
            s.airTicks = 0;
            s.airStartY = to.getY();
            if (supported(to)) s.lastSafe = to.clone();
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();

        if (check("speed")) {
            if (s.winStart == 0) s.winStart = now;
            s.winDist += Math.sqrt(dx * dx + dz * dz);
            long span = now - s.winStart;
            if (span >= 1000) {
                double bps = s.winDist / (span / 1000.0);
                double limit = speedLimit(p, to);
                s.winStart = now;
                s.winDist = 0;
                if (bps > limit) flag(p, "speed", 1.0, String.format("%.1f b/s (limit %.1f)", bps, limit));
            }
        }

        if (check("fly")) {
            if (supported(to)) {
                Material below = to.clone().subtract(0, 0.5, 0).getBlock().getType();
                if (below == Material.SLIME_BLOCK || below.name().endsWith("_BED")) s.lastVelocity = now;   // bouncy blocks
                s.airTicks = 0;
                s.hover = 0;
                s.noFall = 0;
                s.airStartY = to.getY();
                s.lastSafe = to.clone();
            } else {
                s.airTicks++;
                if (Math.abs(dy) < 0.005) s.hover++; else s.hover = 0;
                if (dy < -0.2) s.noFall = 0; else if (s.airTicks > 20 && dy > -0.1) s.noFall++;
                PotionEffect jump = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
                double maxRise = 1.5 + 0.6 * (jump == null ? 0 : jump.getAmplifier() + 1);
                if (dy > 0 && to.getY() - s.airStartY > maxRise) {
                    flag(p, "fly", 2.0, String.format("rose %.1f blocks in the air", to.getY() - s.airStartY));
                    s.airStartY = to.getY();
                } else if (s.hover >= 12) {
                    if (p.getNearbyEntities(0.8, 1.5, 0.8).isEmpty()) flag(p, "fly", 2.0, "hovering in mid-air");
                    s.hover = 0;
                } else if (s.noFall >= 12) {
                    flag(p, "fly", 1.5, "not falling");
                    s.noFall = 0;
                }
            }
        }
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!active(a) || !check("reach") || isExempt(a)) return;
        BoundingBox box = e.getEntity().getBoundingBox();
        Vector eye = a.getEyeLocation().toVector();
        double cx = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double cy = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double cz = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double d = eye.distance(new Vector(cx, cy, cz));
        double limit = num("checks.reach.limit", 3.9) + Math.min(1.0, a.getPing() / 1000.0);
        if (a.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")) limit += 2.0;
        if (d > limit) {
            flag(a, "reach", 1.5, String.format("%.2f blocks (limit %.2f)", d, limit));
            if (plugin.getConfig().getBoolean("anticheat.checks.reach.cancel-hit", true)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (!active(p) || !check("cps")) return;
        State s = st(p);
        long now = System.currentTimeMillis();
        if (now - s.cpsWin >= 1000) {
            if (s.cps > num("checks.cps.max-cps", 24)) flag(p, "cps", 1.0, s.cps + " clicks/second");
            s.cpsWin = now;
            s.cps = 0;
        }
        s.cps++;
    }

    // ------------------------------------------------------------------ blocks

    private boolean tooFar(Player p, Block b) {
        double d = p.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5));
        return d > num("checks.blockreach.limit", 6.5);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!active(p)) return;
        State s = st(p);
        long now = System.currentTimeMillis();
        if (check("blockreach") && tooFar(p, e.getBlock())) {
            e.setCancelled(true);
            flag(p, "blockreach", 1.5, "broke a block from too far away");
            return;
        }
        if (check("nuker")) {
            if (now - s.breakWin >= 1000) { s.breakWin = now; s.breaks = 0; }
            s.breaks++;
            if (s.breaks > num("checks.nuker.max-breaks-per-second", 12)) {
                e.setCancelled(true);
                flag(p, "nuker", 1.5, s.breaks + " blocks/second");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!active(p) || !check("blockreach")) return;
        if (tooFar(p, e.getBlockPlaced())) {
            e.setCancelled(true);
            flag(p, "blockreach", 1.5, "placed a block from too far away");
        }
    }

    // ------------------------------------------------------------------ exemption bookkeeping

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) st(p).lastHurt = System.currentTimeMillis();
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) { st(e.getPlayer()).lastVelocity = System.currentTimeMillis(); }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) { st(e.getPlayer()).lastTeleport = System.currentTimeMillis(); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { st(e.getPlayer()).lastTeleport = System.currentTimeMillis(); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { st(e.getPlayer()).lastTeleport = System.currentTimeMillis(); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        st(p).lastTeleport = System.currentTimeMillis();
        if (plugin.getConfig().getBoolean("anticheat.paper-antixray-hint", true) && p.hasPermission("wincors.alerts")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && !paperXrayEnabled()) {
                    p.sendMessage(Text.c("&e[AC] &7Paper's built-in anti-xray is &cOFF&7. Run &e/wincors ac info &7to see how to enable it."));
                }
            }, 100L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
        alertsOff.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ admin API

    public boolean toggleAlerts(Player p) {
        if (alertsOff.remove(p.getUniqueId())) return true;
        alertsOff.add(p.getUniqueId());
        return false;
    }

    public boolean alertsOn(Player p) { return !alertsOff.contains(p.getUniqueId()); }

    /** Exempt for exactly this long (0 clears the exemption). */
    public void setExemptFor(Player p, long ms) { st(p).exemptUntil = ms <= 0 ? 0 : System.currentTimeMillis() + ms; }

    public String summary(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null || s.vl.isEmpty()) return "no violations";
        StringBuilder sb = new StringBuilder();
        s.vl.forEach((k, v) -> sb.append(k).append(" ").append(String.format("%.1f", v)).append("  "));
        return sb.toString().trim();
    }

    public void reset(Player p) { State s = states.get(p.getUniqueId()); if (s != null) s.vl.clear(); }

    public void resetAll() { for (State s : states.values()) s.vl.clear(); }

    public List<String> tail(int n) {
        try {
            if (!logFile.exists()) return List.of();
            List<String> all = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        } catch (IOException ex) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ Paper anti-xray status

    private YamlConfiguration paperDefaults() {
        File f = new File(Bukkit.getWorldContainer(), "config/paper-world-defaults.yml");
        return f.exists() ? YamlConfiguration.loadConfiguration(f) : null;
    }

    public boolean paperXrayEnabled() {
        YamlConfiguration y = paperDefaults();
        return y != null && y.getBoolean("anticheat.anti-xray.enabled", false);
    }

    public String paperXrayStatus() {
        YamlConfiguration y = paperDefaults();
        if (y == null) return "Paper anti-xray: config/paper-world-defaults.yml not found (is this Paper?).";
        boolean en = y.getBoolean("anticheat.anti-xray.enabled", false);
        int mode = y.getInt("anticheat.anti-xray.engine-mode", 1);
        return en ? "Paper anti-xray: ON (engine-mode " + mode + ")." : "Paper anti-xray: OFF - enable it for real ore hiding (see /wincors ac info).";
    }
}
