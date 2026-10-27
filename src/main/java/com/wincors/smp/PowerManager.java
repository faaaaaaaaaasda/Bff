package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.attribute.Attribute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PowerManager implements Listener {
    private final WincorsSMP plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> selected = new HashMap<>();
    private final Map<UUID, Long> noticeUntil = new HashMap<>();

    public PowerManager(WincorsSMP plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) applyPassives(p);
        }, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) hud(p);
        }, 20L, 20L);
    }

    // ---------- helpers ----------

    public List<Power> actives(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        List<Power> out = new ArrayList<>();
        if (d.origin == null) return out;
        int tier = plugin.mana().tier(p);
        for (Power pw : PowerRegistry.get(d.origin)) {
            if (pw.type == Power.Type.ACTIVE && pw.tier <= tier) out.add(pw);
        }
        return out;
    }

    public boolean hasFlag(Player p, Power.Flag flag) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) return false;
        int tier = plugin.mana().tier(p);
        for (Power pw : PowerRegistry.get(d.origin)) {
            if (pw.type == Power.Type.FLAG && pw.flag == flag && pw.tier <= tier) return true;
        }
        return false;
    }

    public double cooldownMultiplier(Player p) {
        return plugin.egg().hasEgg(p) ? plugin.getConfig().getDouble("dragon-egg.cooldown-multiplier", 0.7) : 1.0;
    }

    private int sel(Player p, int size) {
        int i = selected.getOrDefault(p.getUniqueId(), 0);
        if (i >= size || i < 0) i = 0;
        return i;
    }

    public int selectedIndex(Player p, int size) { return sel(p, size); }

    private long remaining(Player p, Power pw) {
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0;
        Long until = m.get(pw.name);
        if (until == null) return 0;
        long r = until - System.currentTimeMillis();
        return r <= 0 ? 0 : (long) Math.ceil(r / 1000.0);
    }

    public void resetCooldowns(Player p) { cooldowns.remove(p.getUniqueId()); }

    /** Action-bar message that the HUD will not overwrite for 3 seconds. */
    public void notice(Player p, String legacyText) {
        noticeUntil.put(p.getUniqueId(), System.currentTimeMillis() + 3000L);
        p.sendActionBar(Text.c(legacyText));
    }

    // ---------- passives ----------

    private void applyPassives(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) return;
        int tier = plugin.mana().tier(p);
        Map<PotionEffectType, Integer> best = new HashMap<>();
        for (Power pw : PowerRegistry.get(d.origin)) {
            if (pw.type == Power.Type.POTION && pw.tier <= tier) best.merge(pw.effect, pw.amp, Math::max);
        }
        for (Map.Entry<PotionEffectType, Integer> en : best.entrySet()) {
            PotionEffect cur = p.getPotionEffect(en.getKey());
            if (cur != null && (cur.getAmplifier() > en.getValue() || (cur.getAmplifier() == en.getValue() && cur.getDuration() > 100))) continue;
            p.addPotionEffect(new PotionEffect(en.getKey(), 100, en.getValue(), true, false, false));
        }
    }

    // ---------- actives ----------

    public void castSelected(Player p) {
        List<Power> acts = actives(p);
        if (acts.isEmpty()) {
            Text.msg(p, Msg.t("no-actives"));
            return;
        }
        Power pw = acts.get(sel(p, acts.size()));
        long rem = remaining(p, pw);
        if (rem > 0) {
            notice(p, Msg.t("power-cooldown", "power", pw.name, "seconds", String.valueOf(rem)));
            return;
        }
        plugin.anticheat().exempt(p, 4000);   // dashes/launches/teleports must not trip the movement checks
        boolean fired;
        try {
            fired = pw.active.cast(p);
        } catch (Exception ex) {
            plugin.getLogger().warning("Power '" + pw.name + "' failed: " + ex);
            return;
        }
        if (!fired) return;
        long cd = (long) (pw.cooldown * 1000L * cooldownMultiplier(p) * plugin.getConfig().getDouble("powers.cooldown-multiplier", 1.0)
            * (plugin.data().get(p.getUniqueId()).origin == Origin.CALAMITY ? plugin.getConfig().getDouble("calamity.cooldown-multiplier", 1.0) : 1.0));
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(pw.name, System.currentTimeMillis() + cd);
        notice(p, Msg.t("power-used", "power", pw.name));
    }

    public void cycle(Player p) {
        List<Power> acts = actives(p);
        if (acts.isEmpty()) return;
        int next = (sel(p, acts.size()) + 1) % acts.size();
        selected.put(p.getUniqueId(), next);
        notice(p, Msg.t("power-cycled", "power", acts.get(next).name));
    }

    public void select(Player p, int idx) {
        List<Power> acts = actives(p);
        if (idx < 0 || idx >= acts.size()) {
            Text.msg(p, Msg.t("power-invalid-slot", "count", String.valueOf(acts.size())));
            return;
        }
        selected.put(p.getUniqueId(), idx);
        Text.msg(p, Msg.t("power-selected", "power", acts.get(idx).name));
    }

    private int nextActiveTier(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) return -1;
        int tier = plugin.mana().tier(p);
        for (Power pw : PowerRegistry.get(d.origin)) {
            if (pw.type == Power.Type.ACTIVE && pw.tier > tier) return pw.tier;
        }
        return -1;
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) return;
        if (p.isSneaking() ? !plugin.getConfig().getBoolean("controls.sneak-swap-cycles", true)
                           : !plugin.getConfig().getBoolean("controls.swap-key-casts", true)) return;
        e.setCancelled(true);
        List<Power> acts = actives(p);
        int next = nextActiveTier(p);
        String nextTxt = next > 0 ? Msg.t("next-unlock", "tier", String.valueOf(next)) : Msg.t("all-unlocked");
        if (acts.isEmpty()) {
            notice(p, Msg.t("swap-no-actives", "next", nextTxt));
            return;
        }
        if (p.isSneaking()) {
            if (acts.size() == 1) {
                notice(p, Msg.t("swap-one-active", "power", acts.get(0).name, "next", nextTxt));
                return;
            }
            cycle(p);
        } else {
            castSelected(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selected.remove(e.getPlayer().getUniqueId());
    }

    // ---------- HUD ----------

    private void hud(Player p) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        if (noticeUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) return;
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        String line;
        if (d.origin == null) {
            line = d.pendingChoice ? Msg.t("hud.choose") : Msg.t("hud.no-origin");
        } else {
            String power = "";
            List<Power> acts = actives(p);
            if (!acts.isEmpty()) {
                Power pw = acts.get(sel(p, acts.size()));
                long rem = remaining(p, pw);
                power = rem > 0 ? Msg.t("hud.power-cooldown", "power", pw.name, "seconds", String.valueOf(rem))
                                : Msg.t("hud.power-ready", "power", pw.name);
            }
            String vanished = plugin.vanish().isVanished(p) ? Msg.t("hud.vanished") : "";
            String grace = plugin.grace().globalActive()
                ? Msg.t("hud.grace", "time", GraceManager.fmt(plugin.grace().globalRemaining())) : "";
            String combat = plugin.combat().isTagged(p) ? Msg.t("hud.combat", "seconds", String.valueOf(plugin.combat().remaining(p))) : "";
            line = Msg.t("hud.format", "origin", d.origin.colored(), "mana", String.valueOf(d.mana),
                "max", String.valueOf(ManaManager.MAX), "tier", String.valueOf(plugin.mana().tier(p)),
                "power", power, "vanished", vanished, "combat", combat, "grace", grace);
        }
        p.sendActionBar(Text.c(line));
    }

    // ---------- passive flag effects ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        switch (e.getCause()) {
            case FALL -> { if (hasFlag(p, Power.Flag.NO_FALL) || Abilities.hasFallProtection(p)) { e.setCancelled(true); return; } }
            case FREEZE -> { if (hasFlag(p, Power.Flag.FREEZE_IMMUNE)) { e.setCancelled(true); return; } }
            default -> { }
        }
        if (hasFlag(p, Power.Flag.LAST_STAND) && p.getHealth() - e.getFinalDamage() < plugin.getConfig().getDouble("flags.last-stand-health", 8.0)) {
            e.setDamage(e.getDamage() * plugin.getConfig().getDouble("flags.last-stand-damage-multiplier", 0.6));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        // Victim side
        if (e.getEntity() instanceof Player victim) {
            if (hasFlag(victim, Power.Flag.DODGE) && ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("flags.dodge-chance-percent", 12) / 100.0) {
                e.setCancelled(true);
                victim.getWorld().spawnParticle(Particle.CLOUD, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.05);
                victim.sendActionBar(Text.c(Msg.t("dodged")));
                return;
            }
            if (hasFlag(victim, Power.Flag.REFLECT) && e.getDamager() instanceof LivingEntity att && att != victim) {
                att.damage(Math.max(0.5, e.getFinalDamage() * plugin.getConfig().getDouble("flags.reflect-percent", 20) / 100.0));
            }
        }
        // Attacker side
        Player attacker = null;
        if (e.getDamager() instanceof Player pl) attacker = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) attacker = pl;
        if (attacker == null || attacker.equals(e.getEntity())) return;

        if (hasFlag(attacker, Power.Flag.BONUS_DAMAGE)) e.setDamage(e.getDamage() * (1.0 + plugin.getConfig().getDouble("flags.bonus-damage-percent", 10) / 100.0));
        if (hasFlag(attacker, Power.Flag.LIFESTEAL)) {
            double max = attacker.getAttribute(Attribute.MAX_HEALTH).getValue();
            attacker.setHealth(Math.min(max, attacker.getHealth() + e.getFinalDamage() * plugin.getConfig().getDouble("flags.lifesteal-percent", 15) / 100.0));
        }
        if (e.getEntity() instanceof LivingEntity t) {
            if (hasFlag(attacker, Power.Flag.FIRE_TOUCH)) t.setFireTicks(plugin.getConfig().getInt("flags.fire-touch-ticks", 80));
            if (hasFlag(attacker, Power.Flag.FROST_TOUCH)) {
                t.setFreezeTicks(plugin.getConfig().getInt("flags.frost-touch-freeze-ticks", 60));
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, plugin.getConfig().getInt("flags.frost-touch-slowness-ticks", 40), 0));
            }
        }
    }
}
