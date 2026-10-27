package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reusable building blocks for the active powers. */
public final class Abilities {
    private Abilities() {}

    private static WincorsSMP plugin;

    static void init(WincorsSMP p) { plugin = p; }

    static WincorsSMP plugin() { return plugin; }

    /** Players pushed around by a power must not trip the anti-cheat. */
    private static void exempt(LivingEntity e) {
        if (plugin != null && plugin.anticheat() != null && e instanceof Player pl) plugin.anticheat().exempt(pl, 3000);
    }

    private static double mult(String path) {
        return plugin == null ? 1.0 : plugin.getConfig().getDouble(path, 1.0);
    }

    private static boolean flag(String path, boolean def) {
        return plugin == null ? def : plugin.getConfig().getBoolean(path, def);
    }

    private static final Map<UUID, Long> NO_FALL = new ConcurrentHashMap<>();

    /** Gives the caster fall-damage immunity for a few seconds (no slow-falling effect). */
    public static Power.Active noFall(int seconds) {
        return p -> {
            if (!flag("movement.fall-protection", true)) return true;
            NO_FALL.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
            return true;
        };
    }

    public static boolean hasFallProtection(Player p) {
        Long until = NO_FALL.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            NO_FALL.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    public static PotionEffect fx(PotionEffectType type, int seconds, int amp) {
        return new PotionEffect(type, seconds * 20, amp, false, true, true);
    }

    static boolean valid(Player caster, Entity e) {
        if (e == caster || !(e instanceof LivingEntity le) || e instanceof ArmorStand || le.isDead()) return false;
        if (e instanceof Player pl) {
            if (pl.getGameMode() == GameMode.SPECTATOR) return false;
            return plugin == null || plugin.grace() == null || !plugin.grace().blocksPvp(caster, pl);
        }
        return true;
    }

    static List<LivingEntity> near(Player caster, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (valid(caster, e) && e.getLocation().distanceSquared(c) <= r * r) out.add((LivingEntity) e);
        }
        return out;
    }

    static void ring(Location c, double r, Particle part) {
        World w = c.getWorld();
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48;
            w.spawnParticle(part, c.getX() + Math.cos(a) * r, c.getY() + 0.3, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    /** Area burst around the caster. */
    public static Power.Active nova(double r, double dmg, double kb, int fire, int freeze, Particle part, Sound snd, PotionEffect... fx) {
        return p -> {
            Location c = p.getLocation();
            World w = p.getWorld();
            w.playSound(c, snd, 1.2f, 1f);
            Fx.magicCircle(c, Math.min(r * 0.6, 3.2), part, 14);
            Fx.shockwave(c, r, part, 8, 8);
            Fx.burst(c.clone().add(0, 1, 0), part, 20, 0.4, 0.6, 0.4, 0.05);
            String pk = part.getKey().getKey();
            if (pk.equals("snowflake") || pk.equals("crit")) Fx.spikes(c, r, part, Math.min(14, (int) (r * 2.5)), 5);
            for (LivingEntity t : near(p, c, r)) {
                exempt(t);
                Fx.burst(t.getLocation().add(0, 1, 0), part, 8, 0.3, 0.5, 0.3, 0.02);
                if (dmg > 0) t.damage(dmg, p);
                if (kb > 0) {
                    Vector v = t.getLocation().toVector().subtract(c.toVector()).setY(0);
                    if (v.lengthSquared() > 0.0001) v.normalize().multiply(kb);
                    v.setY(0.4);
                    t.setVelocity(v);
                }
                if (fire > 0) t.setFireTicks(fire);
                if (freeze > 0) t.setFreezeTicks(freeze);
                for (PotionEffect pe : fx) t.addPotionEffect(pe);
            }
            return true;
        };
    }

    /** Self buff. */
    public static Power.Active buff(Particle part, Sound snd, PotionEffect... fx) {
        return p -> {
            p.getWorld().playSound(p.getLocation(), snd, 1f, 1f);
            Fx.spiral(() -> p.getLocation(), part, 0.9, 2.2, 16, 2);
            Fx.shockwave(p.getLocation(), 1.8, part, 6, 0);
            Fx.magicCircle(p.getLocation(), 1.5, part, 16);
            Fx.sphere(() -> p.getLocation().add(0, 1, 0), 1.3, part, 10);
            Fx.burst(p.getLocation().add(0, 1, 0), part, 15, 0.4, 0.7, 0.4, 0.04);
            for (PotionEffect pe : fx) p.addPotionEffect(pe);
            return true;
        };
    }

    /**
     * movement.direction-mode: "look" = exactly where you look (up/down included);
     * "keys" = W/A/S/D/Space choose the direction, falling back to your look direction
     * (or straight up for launch powers if movement.launch-no-keys-up is true).
     */
    static Vector direction(Player p, boolean launch) {
        Vector look = p.getLocation().getDirection().normalize();
        String mode = plugin == null ? "look" : plugin.getConfig().getString("movement.direction-mode", "look");
        if (!"keys".equalsIgnoreCase(mode)) return look;
        var in = p.getCurrentInput();
        Vector flat = look.clone().setY(0);
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0, 0, 1); else flat.normalize();
        Vector right = flat.clone().crossProduct(new Vector(0, 1, 0));
        Vector d = new Vector(0, 0, 0);
        if (in.isForward()) d.add(look);
        if (in.isBackward()) d.subtract(look);
        if (in.isRight()) d.add(right);
        if (in.isLeft()) d.subtract(right);
        if (in.isJump()) d.add(new Vector(0, 1, 0));
        if (d.lengthSquared() < 1e-4) return (launch && flag("movement.launch-no-keys-up", false)) ? new Vector(0, 1, 0) : look;
        return d.normalize();
    }

    /** Dash where you look (up and down included). */
    public static Power.Active dash(double speed, double up, Particle part, Sound snd, PotionEffect... fx) {
        return p -> {
            Vector dir = direction(p, false);
            Vector v = dir.clone().multiply(speed * mult("movement.dash-strength-multiplier"));
            if (flag("movement.dash-lift", false) && dir.getY() > -0.2) v.setY(v.getY() + up);
            p.setVelocity(v);
            if (dir.getY() > -0.2) p.setFallDistance(0);
            p.getWorld().playSound(p.getLocation(), snd, 1f, 1f);
            Fx.ring(p.getLocation(), 1.1, part, 0.15);
            Fx.trail(p, part, 10);
            Fx.burst(p.getLocation().add(0, 0.3, 0), Particle.CLOUD, 12, 0.3, 0.1, 0.3, 0.04);
            for (PotionEffect pe : fx) p.addPotionEffect(pe);
            return true;
        };
    }

    /** Big launch where you look. */
    public static Power.Active launch(double strength, Particle part, Sound snd, PotionEffect... fx) {
        return p -> {
            Vector dir = direction(p, true);
            p.setVelocity(dir.multiply(strength * mult("movement.dash-strength-multiplier")));
            p.setFallDistance(0);
            p.getWorld().playSound(p.getLocation(), snd, 1f, 1f);
            Fx.shockwave(p.getLocation(), 2.2, part, 6, 0);
            Fx.trail(p, part, 14);
            Fx.burst(p.getLocation().add(0, 0.3, 0), Particle.CLOUD, 15, 0.3, 0.1, 0.3, 0.04);
            for (PotionEffect pe : fx) p.addPotionEffect(pe);
            return true;
        };
    }

    private static boolean safe(Location feet) {
        return feet.getBlock().isPassable() && feet.clone().add(0, 1, 0).getBlock().isPassable();
    }

    /** Short-range teleport where you look. */
    public static Power.Active blink(double dist, Particle part, PotionEffect... fx) {
        return p -> {
            Location eye = p.getEyeLocation();
            Vector dir = direction(p, false);
            double distance = dist * mult("movement.blink-distance-multiplier");
            RayTraceResult r = p.getWorld().rayTraceBlocks(eye, dir, distance, FluidCollisionMode.NEVER, true);
            Location dest;
            if (r != null && r.getHitPosition() != null) {
                dest = r.getHitPosition().clone().subtract(dir.clone().multiply(0.6)).toLocation(p.getWorld());
            } else {
                dest = eye.clone().add(dir.clone().multiply(distance));
            }
            dest.subtract(0, 1.62, 0);
            boolean ok = false;
            for (int i = 0; i < 3; i++) {
                if (safe(dest)) { ok = true; break; }
                dest.add(0, 1, 0);
            }
            if (!ok) {
                if (plugin != null) plugin.powers().notice(p, Msg.t("no-room")); else p.sendActionBar(Text.c(Msg.t("no-room")));
                return false;
            }
            dest.setYaw(p.getLocation().getYaw());
            dest.setPitch(p.getLocation().getPitch());
            Location from = p.getLocation();
            Fx.spiral(() -> from, part, 0.7, 2.0, 10, 2);
            Fx.dissolve(from, part, true, 10);
            Fx.burst(from.clone().add(0, 1, 0), Particle.PORTAL, 30, 0.3, 0.6, 0.3, 0.4);
            Fx.line(from.clone().add(0, 1, 0), dest.clone().add(0, 1, 0), Particle.END_ROD);
            p.teleport(dest);
            p.setFallDistance(0);
            Fx.spiral(() -> dest, part, 0.7, 2.0, 10, 2);
            Fx.dissolve(dest, part, false, 10);
            Fx.shockwave(dest, 1.6, part, 5, 0);
            Fx.magicCircle(dest, 1.2, part, 8);
            Fx.burst(dest.clone().add(0, 1, 0), Particle.PORTAL, 30, 0.3, 0.6, 0.3, 0.4);
            dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            for (PotionEffect pe : fx) p.addPotionEffect(pe);
            return true;
        };
    }

    /** Targeted area strike where you are looking (needs a target in range). style "meteor" drops a falling meteor. */
    public static Power.Active strike(double range, double radius, double dmg, boolean lightning, int fire, Particle part, Sound snd, String style, PotionEffect... fx) {
        return p -> {
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            RayTraceResult r = p.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.6, en -> valid(p, en));
            if (r == null || r.getHitPosition() == null) {
                if (plugin != null) plugin.powers().notice(p, Msg.t("no-target")); else p.sendActionBar(Text.c(Msg.t("no-target")));
                return false;
            }
            World w = p.getWorld();
            Location hit = r.getHitPosition().toLocation(w);
            boolean meteor = "meteor".equalsIgnoreCase(style);
            Runnable impact = () -> {
                if (lightning) w.strikeLightningEffect(hit); else w.playSound(hit, snd, 1.5f, 1f);
                Fx.shockwave(hit, Math.max(1.5, radius), part, 6, 6);
                Fx.magicCircle(hit, Math.max(1.6, Math.min(radius, 4.0)), part, 12);
                Fx.column(hit, part, 3.0, 8);
                Fx.burst(hit.clone().add(0, 0.5, 0), part, 35, radius * 0.3, 0.5, radius * 0.3, 0.06);
                for (Entity en : w.getNearbyEntities(hit, radius, radius, radius)) {
                    if (!valid(p, en)) continue;
                    LivingEntity t = (LivingEntity) en;
                    if (dmg > 0) t.damage(dmg, p);
                    if (fire > 0) t.setFireTicks(fire);
                    for (PotionEffect pe : fx) t.addPotionEffect(pe);
                }
            };
            if (meteor && plugin != null) {
                Fx.meteor(hit, part, 16);
                Bukkit.getScheduler().runTaskLater(plugin, impact, 16L);
            } else {
                if (lightning) {
                    Fx.lightningBolt(hit.clone().add(0, 24, 0), hit, part);
                } else {
                    Location hand = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.6)).subtract(0, 0.25, 0);
                    Fx.beam(hand, hit, part, 4);
                }
                impact.run();
            }
            return true;
        };
    }

    /** Wings of the power's colour on your back for a few seconds. */
    public static Power.Active wings(Particle part, int seconds) {
        return p -> {
            Fx.wings(p, part, seconds * 20);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.2f);
            return true;
        };
    }

    /** Spinning halo above your head. */
    public static Power.Active halo(Particle part, int seconds) {
        return p -> {
            Fx.halo(p, part, seconds * 20);
            return true;
        };
    }

    /** Heal yourself. */
    public static Power.Active heal(double amount, PotionEffect... fx) {
        return p -> {
            double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
            p.setHealth(Math.min(max, p.getHealth() + amount));
            Fx.spiral(() -> p.getLocation(), Particle.HAPPY_VILLAGER, 0.7, 2.0, 18, 1);
            Fx.shockwave(p.getLocation(), 1.6, Particle.HAPPY_VILLAGER, 6, 0);
            Fx.magicCircle(p.getLocation(), 1.3, Particle.HAPPY_VILLAGER, 14);
            Fx.burst(p.getLocation().add(0, 1.6, 0), Particle.HEART, 6, 0.4, 0.3, 0.4, 0);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            for (PotionEffect pe : fx) p.addPotionEffect(pe);
            return true;
        };
    }

    /** Pull enemies toward you. */
    public static Power.Active pull(double radius, double strength, Particle part, PotionEffect... fx) {
        return p -> {
            Location c = p.getLocation();
            Fx.converge(c, radius, part, 10);
            Fx.vortex(c, radius, part, 12);
            Fx.magicCircle(c, Math.min(radius * 0.5, 3.0), part, 12);
            for (LivingEntity t : near(p, c, radius)) {
                exempt(t);
                Vector v = c.toVector().subtract(t.getLocation().toVector());
                if (v.lengthSquared() > 0.0001) v.normalize().multiply(strength);
                v.setY(0.25);
                t.setVelocity(v);
                for (PotionEffect pe : fx) t.addPotionEffect(pe);
            }
            p.getWorld().playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
            return true;
        };
    }

    public static Power.Active fireballs(int count) {
        return p -> {
            Vector dir = p.getEyeLocation().getDirection();
            for (int i = 0; i < count; i++) {
                double ang = Math.toRadians((i - (count - 1) / 2.0) * 12);
                Vector v = dir.clone().rotateAroundY(ang).multiply(1.4);
                SmallFireball fb = p.launchProjectile(SmallFireball.class, v);
                Fx.follow(fb, Particle.FLAME, 50);
            }
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
            Fx.burst(p.getEyeLocation().add(p.getEyeLocation().getDirection()), Particle.FLAME, 20, 0.2, 0.2, 0.2, 0.05);
            return true;
        };
    }

    /** "Time stop": nearby entities cannot move or jump for a few seconds. */
    public static Power.Active freezeAll(double radius, int seconds) {
        return p -> {
            Location c = p.getLocation();
            Fx.shockwave(c, radius, Particle.END_ROD, 10, 12);
            Fx.converge(c, radius * 0.8, Particle.SNOWFLAKE, 10);
            Fx.clock(c, Math.min(radius * 0.7, 5.0), Particle.END_ROD, 40);
            Fx.magicCircle(c, Math.min(radius * 0.6, 4.0), Particle.SNOWFLAKE, 16);
            for (LivingEntity t : near(p, c, radius)) {
                t.setVelocity(new Vector(0, 0, 0));
                t.addPotionEffect(fx(PotionEffectType.SLOWNESS, seconds, 9));
                t.addPotionEffect(fx(PotionEffectType.JUMP_BOOST, seconds, 128));
                t.addPotionEffect(fx(PotionEffectType.WEAKNESS, seconds, 3));
            }
            p.getWorld().playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);
            return true;
        };
    }

    public static Power.Active lightningFx(double radius) {
        return p -> {
            for (LivingEntity t : near(p, p.getLocation(), radius)) t.getWorld().strikeLightningEffect(t.getLocation());
            return true;
        };
    }
}
