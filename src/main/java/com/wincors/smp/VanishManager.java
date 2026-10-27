package com.wincors.smp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Admin vanish: hidden from every player without wincors.admin, silent join/quit,
 * mobs ignore you, no item pickup, no pressure plates. Saved across restarts.
 */
public final class VanishManager implements Listener {
    private final WincorsSMP plugin;
    /** Players who were potion-invisible the last time they hit / got hit (effects can be cleared at death). */
    private final java.util.Set<java.util.UUID> invisAtHit = new java.util.HashSet<>();

    public VanishManager(WincorsSMP plugin) { this.plugin = plugin; }

    public boolean isVanished(Player p) {
        return plugin.data().get(p.getUniqueId()).vanished;
    }

    public void setVanished(Player p, boolean vanish) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        boolean was = d.vanished;
        d.vanished = vanish;
        plugin.data().markDirty();
        if (vanish) apply(p); else unapply(p);
        if (was != vanish && plugin.getConfig().getBoolean("vanish.fake-messages", true)) {
            String msg = Msg.t(vanish ? "vanish-fake-leave" : "vanish-fake-join", "player", p.getName());
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p) && !o.hasPermission("wincors.admin")) o.sendMessage(Text.c(msg));
            }
        }
        p.sendActionBar(Text.c(Msg.t(vanish ? "vanish-on" : "vanish-off")));
    }

    private void apply(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            if (o.hasPermission("wincors.admin")) o.showPlayer(plugin, p);
            else o.hidePlayer(plugin, p);
        }
        p.setCollidable(false);
        p.setSleepingIgnored(true);
        if (plugin.getConfig().getBoolean("vanish.potion-invisibility", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
    }

    private void unapply(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) o.showPlayer(plugin, p);
        }
        p.setCollidable(true);
        p.setSleepingIgnored(false);
        if (plugin.getConfig().getBoolean("vanish.potion-invisibility", true)) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    /** Called on plugin disable so nobody stays hidden after a reload. */
    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isVanished(p)) unapply(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean vanished = isVanished(p);
        if (vanished) e.joinMessage(null);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            // Hide currently vanished admins from the joiner (unless the joiner is an admin)
            if (!p.hasPermission("wincors.admin")) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!o.equals(p) && isVanished(o)) p.hidePlayer(plugin, o);
                }
            }
            if (vanished) {
                apply(p);
                Text.msg(p, Msg.t("vanish-join"));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        invisAtHit.remove(e.getPlayer().getUniqueId());
        if (isVanished(e.getPlayer())) e.quitMessage(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p && isVanished(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isVanished(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysical(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && isVanished(e.getPlayer())) e.setCancelled(true);
    }

    /**
     * True if this player's name must not appear in public messages:
     * vanished, or (config vanish.hide-invisible-in-death-messages) wearing the invisibility potion effect.
     */
    public boolean isHiddenInChat(Player p) {
        if (isVanished(p)) return true;
        if (!plugin.getConfig().getBoolean("vanish.hide-invisible-in-death-messages", true)) return false;
        return p.hasPotionEffect(PotionEffectType.INVISIBILITY) || invisAtHit.contains(p.getUniqueId());
    }

    private void track(Player p) {
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) invisAtHit.add(p.getUniqueId());
        else invisAtHit.remove(p.getUniqueId());
    }

    /** Remember who was invisible during a fight (the effect may already be gone when the death message is built). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim) track(victim);
        Player attacker = null;
        if (e.getDamager() instanceof Player direct) attacker = direct;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker != null) track(attacker);
    }

    /**
     * A vanished or potion-invisible player never shows up in a death message: if they die the message is
     * removed, if they get a kill the killer's name is replaced with a generic message.
     * Admins (wincors.admin) still receive the real message, tagged [Vanish].
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        boolean victimHidden = isHiddenInChat(victim);
        boolean killerHidden = killer != null && isHiddenInChat(killer);
        invisAtHit.remove(victim.getUniqueId());
        if (!victimHidden && !killerHidden) return;

        Component original = e.deathMessage();
        if (original != null) {
            for (Player a : Bukkit.getOnlinePlayers()) {
                if (a.hasPermission("wincors.admin")) a.sendMessage(Text.c("&8[Vanish] ").append(original));
            }
        }
        if (victimHidden) e.deathMessage(null);
        else e.deathMessage(Text.c(Msg.t("vanish-kill-message", "victim", victim.getName())));
    }

    /** No advancement announcements for vanished players. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (isVanished(e.getPlayer())) e.message(null);
    }
}
