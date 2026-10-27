package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Combat tag system + ender pearl / crystal / anchor restrictions during PvP. */
public final class CombatManager implements Listener {
    private final WincorsSMP plugin;
    private final Map<UUID, Long> until = new HashMap<>();
    private final Map<UUID, UUID> opponent = new HashMap<>();
    private final Set<UUID> loggedOut = new HashSet<>();
    private final Map<UUID, Long> pearlUntil = new HashMap<>();

    public CombatManager(WincorsSMP plugin) { this.plugin = plugin; }

    // ---------- tag API ----------

    public boolean isTagged(Player p) {
        Long u = until.get(p.getUniqueId());
        if (u == null) return false;
        if (u < System.currentTimeMillis()) {
            until.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    public int remaining(Player p) {
        Long u = until.get(p.getUniqueId());
        if (u == null) return 0;
        return (int) Math.max(0, Math.ceil((u - System.currentTimeMillis()) / 1000.0));
    }

    public void tag(Player a, Player b) {
        if (!plugin.getConfig().getBoolean("combat.enabled", true)) return;
        long dur = plugin.getConfig().getLong("combat.duration-seconds", 20) * 1000L;
        long end = System.currentTimeMillis() + dur;
        for (Player[] pair : new Player[][]{{a, b}, {b, a}}) {
            Player p = pair[0];
            boolean was = isTagged(p);
            until.put(p.getUniqueId(), end);
            opponent.put(p.getUniqueId(), pair[1].getUniqueId());
            if (!was) Text.msg(p, Msg.t("combat-start"));
        }
    }

    public void clear(Player p) {
        until.remove(p.getUniqueId());
        opponent.remove(p.getUniqueId());
    }

    /** Returns the opponent if this player was killed by a combat-log, else null. */
    public Player combatLogKiller(Player victim) {
        if (!loggedOut.remove(victim.getUniqueId())) return null;
        UUID o = opponent.get(victim.getUniqueId());
        return o == null ? null : Bukkit.getPlayer(o);
    }

    /** True if this player is tagged or another player is nearby (used for crystal/anchor bans). */
    public boolean pvpContext(Player p) {
        if (isTagged(p)) return true;
        double r = plugin.getConfig().getDouble("combat.pvp-radius", 10);
        for (Player o : p.getWorld().getPlayers()) {
            if (o != p && o.getGameMode() != GameMode.SPECTATOR && o.getLocation().distanceSquared(p.getLocation()) <= r * r) return true;
        }
        return false;
    }

    // ---------- tagging ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player pl) attacker = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) attacker = pl;
        if (attacker == null || attacker.equals(victim)) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;
        tag(attacker, victim);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("combat.kill-on-logout", true)) return;
        if (isTagged(p) && !p.isDead() && p.getGameMode() != GameMode.CREATIVE) {
            loggedOut.add(p.getUniqueId());
            if (!plugin.vanish().isVanished(p)) {
                Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("combat-logout", "player", p.getName())));
            }
            p.setHealth(0.0);
        }
    }

    // ---------- blocked items / commands ----------

    private boolean blockedItem(Material m) {
        return plugin.getConfig().getStringList("combat.blocked-items").stream().anyMatch(s -> s.equalsIgnoreCase(m.name()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractItem(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        Player p = e.getPlayer();
        if (it == null || !isTagged(p)) return;
        boolean pearl = it.getType() == Material.ENDER_PEARL && plugin.getConfig().getBoolean("ender-pearl.block-in-pvp", true);
        if (!pearl && !blockedItem(it.getType())) return;
        if (!pearl && it.getType().isEdible() && it.getType() != Material.CHORUS_FRUIT) return;
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        p.sendActionBar(Text.c(Msg.t(pearl ? "combat-pearl-blocked" : "combat-item-blocked")));
    }

    // ---------- ender pearl: usable, 5 min cooldown, never in PvP ----------

    public int pearlRemaining(Player p) {
        Long u = pearlUntil.get(p.getUniqueId());
        if (u == null) return 0;
        return (int) Math.max(0, Math.ceil((u - System.currentTimeMillis()) / 1000.0));
    }

    public void resetPearl(Player p) {
        pearlUntil.remove(p.getUniqueId());
        p.setCooldown(Material.ENDER_PEARL, 0);
    }

    private String fmt(int secs) { return String.format("%d:%02d", secs / 60, secs % 60); }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl) || !(e.getEntity().getShooter() instanceof Player p)) return;
        if (isTagged(p) && plugin.getConfig().getBoolean("ender-pearl.block-in-pvp", true)) {
            e.setCancelled(true);
            p.sendActionBar(Text.c(Msg.t("combat-pearl-blocked")));
            return;
        }
        int rem = pearlRemaining(p);
        if (rem > 0) {
            e.setCancelled(true);
            p.sendActionBar(Text.c(Msg.t("ender-pearl-cooldown", "time", fmt(rem))));
        }
    }

    /** Starts the cooldown once a pearl was really thrown. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlThrown(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl) || !(e.getEntity().getShooter() instanceof Player p)) return;
        int secs = plugin.getConfig().getInt("ender-pearl.cooldown-seconds", 300);
        if (secs <= 0) return;
        pearlUntil.put(p.getUniqueId(), System.currentTimeMillis() + secs * 1000L);
        p.setCooldown(Material.ENDER_PEARL, secs * 20);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        boolean pearl = c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && plugin.getConfig().getBoolean("ender-pearl.cancel-landing-in-pvp", false);
        if ((pearl || (c == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT && blockedItem(Material.CHORUS_FRUIT))) && isTagged(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (blockedItem(e.getItem().getType()) && isTagged(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Text.c(Msg.t("combat-item-blocked")));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p) || p.hasPermission("wincors.bypass")) return;
        String first = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (first.contains(":")) first = first.substring(first.indexOf(':') + 1);
        List<String> blocked = plugin.getConfig().getStringList("combat.blocked-commands");
        for (String b : blocked) {
            if (b.equalsIgnoreCase(first)) {
                e.setCancelled(true);
                Text.msg(p, Msg.t("combat-command-blocked", "seconds", String.valueOf(remaining(p))));
                return;
            }
        }
    }

    // ---------- crystals & anchors ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCrystalOrAnchor(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (p.hasPermission("wincors.bypass")) return;
        Block b = e.getClickedBlock();
        ItemStack it = e.getItem();
        if (b == null) return;

        if (it != null && it.getType() == Material.END_CRYSTAL) {
            boolean dragonRespawn = b.getType() == Material.BEDROCK && p.getWorld().getEnvironment() == World.Environment.THE_END;
            boolean block = dragonRespawn ? isTagged(p) : pvpContext(p);
            if (block) {
                e.setCancelled(true);
                p.sendActionBar(Text.c(Msg.t("combat-crystal-blocked")));
            }
            return;
        }
        if (b.getType() == Material.RESPAWN_ANCHOR && p.getWorld().getEnvironment() != World.Environment.NETHER && pvpContext(p)) {
            e.setCancelled(true);
            p.sendActionBar(Text.c(Msg.t("combat-anchor-blocked")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e instanceof EntityDamageByEntityEvent be && be.getDamager() instanceof EnderCrystal && pvpContext(p)) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && plugin.getConfig().getBoolean("combat.cancel-block-explosion-damage", true) && pvpContext(p)) {
            e.setCancelled(true);
        }
    }
}
