package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PvP grace period + admin PvP switch.
 *  - global grace period: nobody can hurt anybody for a set time (saved across restarts)
 *  - personal grace: protect one player (they cannot hit or be hit)
 *  - /wincors pvp off: PvP disabled until an admin turns it back on
 */
public final class GraceManager implements Listener {
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhd]?)");

    private final WincorsSMP plugin;
    private final Map<UUID, Long> warned = new HashMap<>();

    public GraceManager(WincorsSMP plugin) { this.plugin = plugin; }

    public void begin() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long until = plugin.data().getGraceUntil();
        if (until != 0 && until <= now) {
            plugin.data().setGraceUntil(0);
            Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("grace-ended")));
        }
        plugin.data().cleanupPersonalGrace();
    }

    // ---------------- state ----------------

    public boolean globalActive() { return plugin.data().getGraceUntil() > System.currentTimeMillis(); }

    public long globalRemaining() { return Math.max(0, plugin.data().getGraceUntil() - System.currentTimeMillis()); }

    public long personalRemaining(Player p) {
        Long u = plugin.data().personalGrace().get(p.getUniqueId());
        return u == null ? 0 : Math.max(0, u - System.currentTimeMillis());
    }

    public boolean pvpOff() { return !plugin.data().isPvpEnabled(); }

    /** True if attacker may NOT hurt victim right now. */
    public boolean blocksPvp(Player attacker, Player victim) {
        if (attacker.equals(victim)) return false;
        return pvpOff() || globalActive() || personalRemaining(attacker) > 0 || personalRemaining(victim) > 0;
    }

    // ---------------- admin actions ----------------

    public void start(long ms, CommandSender by) {
        plugin.data().setGraceUntil(System.currentTimeMillis() + ms);
        Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("grace-started", "time", fmt(ms))));
        Text.msg(by, "Grace period started for &f" + fmt(ms) + "&r.");
    }

    public void extend(long ms, CommandSender by) {
        long base = Math.max(System.currentTimeMillis(), plugin.data().getGraceUntil());
        plugin.data().setGraceUntil(base + ms);
        Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("grace-extended", "time", fmt(globalRemaining()))));
        Text.msg(by, "Grace period extended. Time left: &f" + fmt(globalRemaining()) + "&r.");
    }

    public void stop(CommandSender by) {
        if (!globalActive()) { Text.msg(by, "&cNo grace period is running."); return; }
        plugin.data().setGraceUntil(0);
        Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("grace-ended")));
    }

    public void setPersonal(Player p, long ms) {
        plugin.data().setPersonalGrace(p.getUniqueId(), ms <= 0 ? 0 : System.currentTimeMillis() + ms);
    }

    public void setPvpEnabled(boolean on) {
        plugin.data().setPvpEnabled(on);
        Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t(on ? "pvp-toggle-on" : "pvp-toggle-off")));
    }

    // ---------------- duration helpers ----------------

    /** "30m", "2h", "1d", "1h30m", "45s"; a bare number means minutes. @return milliseconds or -1 if invalid */
    public static long parseDuration(String s) {
        if (s == null || s.isBlank()) return -1;
        Matcher m = PART.matcher(s.toLowerCase(Locale.ROOT));
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end && !s.substring(end, m.start()).isBlank()) return -1;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s" -> total += n * 1000L;
                case "h" -> total += n * 3_600_000L;
                case "d" -> total += n * 86_400_000L;
                default -> total += n * 60_000L;
            }
            end = m.end();
        }
        return end == 0 || total <= 0 ? -1 : total;
    }

    public static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0 || (d == 0 && h == 0 && m < 5 && sec > 0)) sb.append(sec).append("s");
        return sb.toString().trim();
    }

    // ---------------- protection ----------------

    private static Player shooter(Entity d) {
        if (d instanceof Player direct) return direct;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player shot) return shot;
        return null;
    }

    private void notifyBlocked(Player attacker, Player victim) {
        long now = System.currentTimeMillis();
        Long last = warned.get(attacker.getUniqueId());
        if (last != null && now - last < 2000) return;
        warned.put(attacker.getUniqueId(), now);
        String text;
        if (pvpOff()) text = Msg.t("pvp-disabled");
        else if (globalActive()) text = Msg.t("grace-pvp-blocked", "time", fmt(globalRemaining()));
        else text = Msg.t("grace-personal-blocked");
        plugin.powers().notice(attacker, text);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = shooter(e.getDamager());
        if (attacker == null || !blocksPvp(attacker, victim)) return;
        e.setCancelled(true);
        notifyBlocked(attacker, victim);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = shooter(e.getCombuster());
        if (attacker != null && blocksPvp(attacker, victim)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player attacker)) return;
        for (LivingEntity le : e.getAffectedEntities()) {
            if (le instanceof Player victim && blocksPvp(attacker, victim)) e.setIntensity(le, 0);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (pvpOff()) Text.msg(p, Msg.t("pvp-disabled"));
            else if (globalActive()) Text.msg(p, Msg.t("grace-active", "time", fmt(globalRemaining())));
        }, 60L);
    }
}
