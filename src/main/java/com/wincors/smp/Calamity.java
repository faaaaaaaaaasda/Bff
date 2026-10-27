package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The operator-only CALAMITY origin: 30 world-ending abilities.
 * Block destruction is spread over several ticks, capped per move (config calamity.max-blocks-per-cast), never touches
 * protected blocks, and every move can be undone with the Rewind ability or /wincors calamity undo.
 * Holding the Mace makes everything stronger (calamity.mace-multiplier).
 */
public final class Calamity {
    private record Entry(Block block, int delay) {}

    private final WincorsSMP plugin;
    private final Map<UUID, Deque<List<BlockState>>> undo = new HashMap<>();
    private final Set<UUID> aura = new HashSet<>();
    private final Set<UUID> casting = new HashSet<>();
    private Set<Material> protectedBlocks = EnumSet.noneOf(Material.class);
    private Particle expl = Particle.FLAME;
    private Particle lava = Particle.FLAME;

    public Calamity(WincorsSMP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String n : plugin.getConfig().getStringList("calamity.protected-blocks")) {
            Material m = Material.matchMaterial(n);
            if (m != null) set.add(m);
        }
        protectedBlocks = set;
        expl = Parse.particle("EXPLOSION", Particle.FLAME);
        lava = Parse.particle("LAVA", Particle.FLAME);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::auraTick, 20L, 10L);
    }

    // ------------------------------------------------------------------ config / permission

    private double cfg(String k, double d) { return plugin.getConfig().getDouble("calamity." + k, d); }

    public boolean enabled() { return plugin.getConfig().getBoolean("calamity.enabled", true); }

    public boolean allowed(Player p) { return enabled() && p.hasPermission("wincors.calamity"); }

    public boolean isCasting(Player p) { return casting.contains(p.getUniqueId()); }

    public boolean auraOn(Player p) { return aura.contains(p.getUniqueId()); }

    private boolean disabledWorld(World w) { return plugin.getConfig().getStringList("calamity.disabled-worlds").contains(w.getName()); }

    /** 1.0 normally, calamity.mace-multiplier while holding the Mace. */
    private double scale(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.MACE ? cfg("mace-multiplier", 1.5) : 1.0;
    }

    private int budget(Player p) {
        int base = (int) cfg("max-blocks-per-cast", 2000);
        int hard = (int) cfg("hard-limit", 20000);
        return Math.max(1, Math.min(hard, scale(p) > 1.0 ? base * 2 : base));
    }

    private void say(Player p, String text) { plugin.powers().notice(p, text); }

    private void boom(Location l) {
        l.getWorld().playSound(l, Parse.sound("ENTITY_GENERIC_EXPLODE", Sound.ENTITY_PLAYER_LEVELUP), 4f, 0.7f);
    }

    // ------------------------------------------------------------------ block destruction engine

    private boolean canBreak(Player p, Block b) {
        Material t = b.getType();
        if (t.isAir() || b.isLiquid() || protectedBlocks.contains(t)) return false;
        if (disabledWorld(b.getWorld())) return false;
        if (plugin.getConfig().getBoolean("calamity.respect-protection", false)) {
            casting.add(p.getUniqueId());
            try {
                BlockBreakEvent ev = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(ev);
                if (ev.isCancelled()) return false;
            } finally {
                casting.remove(p.getUniqueId());
            }
        }
        return true;
    }

    /** A batch of blocks to destroy, spread over ticks (nearest to the origin first), with a hard cap. */
    private final class Plan {
        final Player p;
        final int cap;
        final List<Entry> entries = new ArrayList<>();
        final Set<Long> seen = new HashSet<>();

        Plan(Player p) { this(p, budget(p)); }

        Plan(Player p, int cap) { this.p = p; this.cap = cap; }

        void add(Collection<Block> blocks, Location origin, int delayBase, double ticksPerBlock) {
            Vector o = origin.toVector();
            List<Block> sorted = new ArrayList<>(blocks);
            sorted.sort(Comparator.comparingDouble(b -> b.getLocation().add(0.5, 0.5, 0.5).toVector().distanceSquared(o)));
            for (Block b : sorted) {
                if (entries.size() >= cap) return;
                if (!seen.add(b.getBlockKey()) || !canBreak(p, b)) continue;
                double dist = Math.sqrt(b.getLocation().add(0.5, 0.5, 0.5).toVector().distanceSquared(o));
                entries.add(new Entry(b, delayBase + (int) (dist * ticksPerBlock)));
            }
        }

        void start() {
            if (entries.isEmpty()) return;
            entries.sort(Comparator.comparingInt(Entry::delay));
            final int per = Math.max(50, (int) cfg("blocks-per-tick", 400));
            final List<BlockState> snap = new ArrayList<>(entries.size());
            final UUID id = p.getUniqueId();
            new BukkitRunnable() {
                int tick = 0;
                int idx = 0;

                @Override
                public void run() {
                    int done = 0;
                    while (idx < entries.size() && entries.get(idx).delay() <= tick && done < per) {
                        Block b = entries.get(idx).block();
                        idx++;
                        if (b.getType().isAir()) continue;
                        snap.add(b.getState());
                        if (idx % 5 == 0) Fx.debris(b.getLocation().add(0.5, 0.5, 0.5), b.getBlockData());
                        b.setType(Material.AIR, false);
                        done++;
                    }
                    tick++;
                    if (idx >= entries.size()) {
                        cancel();
                        pushUndo(id, snap);
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }
    }

    private void pushUndo(UUID id, List<BlockState> snap) {
        if (snap.isEmpty()) return;
        Deque<List<BlockState>> dq = undo.computeIfAbsent(id, k -> new ArrayDeque<>());
        dq.addLast(snap);
        int max = Math.max(1, (int) cfg("undo-history", 5));
        while (dq.size() > max) dq.pollFirst();
    }

    /** Puts back everything destroyed by the player's last move. */
    public boolean undoLast(Player p) {
        Deque<List<BlockState>> dq = undo.get(p.getUniqueId());
        if (dq == null || dq.isEmpty()) { say(p, "&cNothing to rewind."); return false; }
        final List<BlockState> snap = dq.pollLast();
        final int per = Math.max(50, (int) cfg("blocks-per-tick", 400));
        new BukkitRunnable() {
            int i = snap.size() - 1;

            @Override
            public void run() {
                int n = 0;
                while (i >= 0 && n < per) {
                    try { snap.get(i).update(true, false); } catch (Exception ignored) { }
                    i--;
                    n++;
                }
                if (i < 0) {
                    cancel();
                    if (p.isOnline()) say(p, "&aRewound &f" + snap.size() + " &ablocks.");
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    public int undoSize(Player p) {
        Deque<List<BlockState>> dq = undo.get(p.getUniqueId());
        return dq == null ? 0 : dq.size();
    }

    // ------------------------------------------------------------------ shapes

    private boolean inWorld(World w, int x, int y, int z) {
        return y >= w.getMinHeight() && y < w.getMaxHeight() && w.isChunkLoaded(x >> 4, z >> 4);
    }

    private List<Block> sphere(Location c, double r) {
        List<Block> out = new ArrayList<>();
        World w = c.getWorld();
        int R = (int) Math.ceil(r), cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        double r2 = r * r;
        for (int x = -R; x <= R; x++) {
            for (int y = -R; y <= R; y++) {
                for (int z = -R; z <= R; z++) {
                    if (x * x + y * y + z * z > r2 || !inWorld(w, cx + x, cy + y, cz + z)) continue;
                    out.add(w.getBlockAt(cx + x, cy + y, cz + z));
                }
            }
        }
        return out;
    }

    private List<Block> cylinder(Location c, double r, int down, int up) {
        List<Block> out = new ArrayList<>();
        World w = c.getWorld();
        int R = (int) Math.ceil(r), cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        double r2 = r * r;
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (x * x + z * z > r2) continue;
                for (int y = -down; y <= up; y++) {
                    if (!inWorld(w, cx + x, cy + y, cz + z)) continue;
                    out.add(w.getBlockAt(cx + x, cy + y, cz + z));
                }
            }
        }
        return out;
    }

    private List<Block> tunnel(Location from, Vector dir, double length, double r) {
        List<Block> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        World w = from.getWorld();
        Vector d = dir.clone().normalize();
        int R = (int) Math.ceil(r);
        double r2 = r * r;
        for (double t = 0; t <= length; t += 0.5) {
            Location c = from.clone().add(d.clone().multiply(t));
            int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
            for (int x = -R; x <= R; x++) {
                for (int y = -R; y <= R; y++) {
                    for (int z = -R; z <= R; z++) {
                        if (x * x + y * y + z * z > r2 || !inWorld(w, cx + x, cy + y, cz + z)) continue;
                        Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                        if (seen.add(b.getBlockKey())) out.add(b);
                    }
                }
            }
        }
        return out;
    }

    private List<Block> cone(Location from, Vector dir, double length, double halfAngleDeg) {
        List<Block> out = new ArrayList<>();
        World w = from.getWorld();
        Vector d = dir.clone().normalize();
        double cos = Math.cos(Math.toRadians(halfAngleDeg));
        int L = (int) Math.ceil(length), fx = from.getBlockX(), fy = from.getBlockY(), fz = from.getBlockZ();
        for (int x = -L; x <= L; x++) {
            for (int y = -L; y <= L; y++) {
                for (int z = -L; z <= L; z++) {
                    double len2 = x * x + y * y + z * z;
                    if (len2 == 0 || len2 > length * length) continue;
                    double len = Math.sqrt(len2);
                    if ((x * d.getX() + y * d.getY() + z * d.getZ()) / len < cos) continue;
                    if (!inWorld(w, fx + x, fy + y, fz + z)) continue;
                    out.add(w.getBlockAt(fx + x, fy + y, fz + z));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Location aim(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.5, e -> Abilities.valid(p, e));
        if (r != null && r.getHitPosition() != null) return r.getHitPosition().toLocation(p.getWorld());
        return eye.clone().add(dir.multiply(range));
    }

    private Location ground(Location l) {
        World w = l.getWorld();
        if (w.getEnvironment() == World.Environment.NETHER || !w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return l.clone();
        return new Location(w, l.getX(), w.getHighestBlockYAt(l) + 1, l.getZ());
    }

    private void exempt(Entity e) {
        if (e instanceof Player pl) plugin.anticheat().exempt(pl, 4000);
    }

    private void hurt(Player p, Collection<LivingEntity> ents, double dmg) {
        for (LivingEntity t : ents) {
            exempt(t);
            t.setNoDamageTicks(0);
            t.damage(dmg, p);
        }
    }

    private void noFall(Player p, int seconds) { Abilities.noFall(seconds).cast(p); }

    /** Sphere blast: destroys blocks and damages everything inside. */
    private void blast(Player p, Location c, double r, double dmg, Particle fx, double ticksPerBlock) {
        Plan plan = new Plan(p);
        plan.add(sphere(c, r), c, 0, ticksPerBlock);
        plan.start();
        hurt(p, Abilities.near(p, c, r * 1.4), dmg);
        Fx.shockwave(c, r * 1.5, fx, 10, 12);
        Fx.magicCircle(c, Math.min(r, 6), fx, 14);
        Fx.burst(c, fx, 60, r * 0.3, r * 0.3, r * 0.3, 0.1);
        boom(c);
    }

    // ------------------------------------------------------------------ the 30 abilities

    private Power ab(String name, String desc, Material icon, int cooldown, Power.Active a) {
        Power.Active guarded = p -> {
            if (!allowed(p)) { say(p, "&cCalamity powers are operator-only."); return false; }
            if (disabledWorld(p.getWorld())) { say(p, "&cCalamity is disabled in this world."); return false; }
            return a.cast(p);
        };
        Power pw = Power.active(1, name, desc, cooldown, guarded);
        pw.icon = icon;
        return pw;
    }

    public List<Power> buildPowers() {
        List<Power> l = new ArrayList<>();
        // ---- passives (always on)
        l.add(Power.potion(1, "Calamity Body", "Resistance V.", PotionEffectType.RESISTANCE, 4));
        l.add(Power.potion(1, "Calamity Might", "Strength V.", PotionEffectType.STRENGTH, 4));
        l.add(Power.potion(1, "Calamity Swiftness", "Speed III.", PotionEffectType.SPEED, 2));
        l.add(Power.potion(1, "Calamity Vitality", "Regeneration III and +20 hearts.", PotionEffectType.REGENERATION, 2));
        l.add(Power.potion(1, "Calamity Blood", "+20 hearts.", PotionEffectType.HEALTH_BOOST, 9));
        l.add(Power.potion(1, "Calamity Sight", "Night vision.", PotionEffectType.NIGHT_VISION, 0));
        l.add(Power.potion(1, "Calamity Hide", "Fire resistance.", PotionEffectType.FIRE_RESISTANCE, 0));
        l.add(Power.flag(1, "Weightless", "No fall damage.", Power.Flag.NO_FALL));
        l.add(Power.flag(1, "Soul Drinker", "Heal from damage dealt.", Power.Flag.LIFESTEAL));
        // ---- 30 abilities
        l.add(ab("Cataclysm", "Obliterate everything in a huge sphere around you.", Material.TNT, 20, this::cataclysm));
        l.add(ab("Annihilation Beam", "A beam that bores a tunnel through the world.", Material.END_ROD, 15, this::beam));
        l.add(ab("Meteor Rain", "Call down a storm of meteors on your target.", Material.FIRE_CHARGE, 30, this::meteorRain));
        l.add(ab("Apocalypse Cone", "Destroy everything in a vast cone ahead.", Material.BLAZE_ROD, 20, this::apocalypseCone));
        l.add(ab("Gravity Crush", "Drag everything in, then crush it.", Material.OBSIDIAN, 25, this::gravityCrush));
        l.add(ab("Earth Splitter", "Tear a deep trench across the land.", Material.NETHERITE_PICKAXE, 20, this::earthSplitter));
        l.add(ab("Skyfall Pillar", "A pillar of destruction from sky to bedrock.", Material.BEACON, 25, this::skyfallPillar));
        l.add(ab("Void Sphere", "Erase a giant sphere where you aim.", Material.END_PORTAL_FRAME, 30, this::voidSphere));
        l.add(ab("Thunder God", "20 lightning strikes rip through the area.", Material.LIGHTNING_ROD, 25, this::thunderGod));
        l.add(ab("Inferno", "Set everything within 40 blocks on fire.", Material.LAVA_BUCKET, 25, this::inferno));
        l.add(ab("Black Hole", "A singularity that devours everything.", Material.COAL_BLOCK, 45, this::blackHole));
        l.add(ab("Shockwave Stomp", "Shatter the ground and hurl everything away.", Material.ANVIL, 15, this::stomp));
        l.add(ab("Judgment", "Smite every living thing within 40 blocks.", Material.TOTEM_OF_UNDYING, 40, this::judgment));
        l.add(ab("Blink Strike", "Teleport to your target and slam down.", Material.ENDER_PEARL, 12, this::blinkStrike));
        l.add(ab("Tsunami", "A wave that hurls everything ahead of you.", Material.WATER_BUCKET, 20, this::tsunami));
        l.add(ab("Earthquake", "Crack the whole area apart.", Material.COARSE_DIRT, 30, this::earthquake));
        l.add(ab("Magma Burst", "An eruption at your target.", Material.MAGMA_BLOCK, 18, this::magmaBurst));
        l.add(ab("Ice Age", "Freeze everything within 30 blocks.", Material.BLUE_ICE, 30, this::iceAge));
        l.add(ab("Plague", "Wither and poison everything nearby.", Material.SPIDER_EYE, 30, this::plague));
        l.add(ab("Time Stop", "Freeze time around you for 6 seconds.", Material.CLOCK, 45, this::timeStop));
        l.add(ab("Dragon Breath", "A cone of devouring dragon fire.", Material.DRAGON_HEAD, 18, this::dragonBreath));
        l.add(ab("Soul Harvest", "Drain the life of everything nearby.", Material.SOUL_LANTERN, 30, this::soulHarvest));
        l.add(ab("Colossus Form", "Become a god for 40 seconds.", Material.NETHERITE_CHESTPLATE, 60, this::colossus));
        l.add(ab("Warp Step", "Teleport up to 100 blocks.", Material.CHORUS_FRUIT, 6, this::warpStep));
        l.add(ab("Skyfall Dive", "Rocket up, then crash down as a meteor.", Material.ELYTRA, 20, this::skyfallDive));
        l.add(ab("Annihilate Target", "Delete one target from existence.", Material.WITHER_SKELETON_SKULL, 10, this::annihilateTarget));
        l.add(ab("Chain Collapse", "A ripple of destruction spreading outward.", Material.PISTON, 25, this::chainCollapse));
        l.add(ab("Worldbreaker", "Carve a giant crater out of the world.", Material.DIAMOND_PICKAXE, 40, this::worldbreaker));
        l.add(ab("Walking Calamity", "Toggle: disaster follows you everywhere.", Material.NETHER_STAR, 2, this::toggleAura));
        l.add(ab("Rewind", "Undo the destruction of your last move.", Material.RECOVERY_COMPASS, 3, this::rewind));
        return l;
    }

    private boolean cataclysm(Player p) {
        double s = scale(p);
        noFall(p, 8);
        blast(p, p.getLocation().add(0, 1, 0), 7 * s, 700 * s, expl, 0.6);
        return true;
    }

    private boolean beam(Player p) {
        double s = scale(p);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double len = 60 * s;
        Plan plan = new Plan(p);
        plan.add(tunnel(eye, dir, len, 2.5 * s), eye, 0, 0.35);
        plan.start();
        Location end = eye.clone().add(dir.clone().multiply(len));
        Location hand = eye.clone().add(dir.clone().multiply(1.0)).subtract(0, 0.25, 0);
        Fx.beam(hand, end, Particle.ELECTRIC_SPARK, 6);
        Fx.beam(hand, end, Particle.END_ROD, 6);
        Set<UUID> hit = new HashSet<>();
        for (double d = 0; d <= len; d += 3) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            List<LivingEntity> fresh = new ArrayList<>();
            for (LivingEntity t : Abilities.near(p, pt, 4 * s)) if (hit.add(t.getUniqueId())) fresh.add(t);
            hurt(p, fresh, 900 * s);
        }
        boom(eye);
        return true;
    }

    private boolean meteorRain(Player p) {
        double s = scale(p);
        Location t = aim(p, 80);
        int n = (int) (12 * s);
        Plan plan = new Plan(p);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 22 * s;
            final Location m = ground(t.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            final long start = i * 3L;
            final int impact = i * 3 + 14;
            Bukkit.getScheduler().runTaskLater(plugin, () -> Fx.meteor(m, Particle.FLAME, 14), start);
            plan.add(sphere(m, 3.5 * s), m, impact, 0.5);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                hurt(p, Abilities.near(p, m, 5 * s), 500 * s);
                Fx.shockwave(m, 5, Particle.FLAME, 6, 6);
                boom(m);
            }, impact);
        }
        plan.start();
        return true;
    }

    private boolean apocalypseCone(Player p) {
        double s = scale(p);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Plan plan = new Plan(p);
        plan.add(cone(eye, dir, 32 * s, 32), eye, 0, 0.5);
        plan.start();
        Location mid = eye.clone().add(dir.clone().multiply(16 * s));
        List<LivingEntity> in = new ArrayList<>();
        for (LivingEntity t : Abilities.near(p, mid, 24 * s)) {
            Vector v = t.getLocation().toVector().subtract(eye.toVector());
            if (v.lengthSquared() > 0 && v.normalize().dot(dir.clone().normalize()) > 0.8) in.add(t);
        }
        hurt(p, in, 700 * s);
        for (int k = -2; k <= 2; k++) {
            Vector d2 = dir.clone().rotateAroundY(Math.toRadians(k * 12));
            Fx.beam(eye.clone().add(d2.clone().multiply(1.2)), eye.clone().add(d2.clone().multiply(30 * s)), expl, 6);
        }
        boom(eye);
        return true;
    }

    private boolean gravityCrush(Player p) {
        double s = scale(p);
        Location t = aim(p, 60);
        Abilities.pull(18 * s, 2.2, Particle.SMOKE).cast(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> blast(p, t, 5 * s, 700 * s, Particle.SMOKE, 0.8), 14L);
        return true;
    }

    private boolean earthSplitter(Player p) {
        double s = scale(p);
        Location start = p.getLocation();
        Vector d = start.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(0, 0, 1);
        d.normalize();
        Plan plan = new Plan(p);
        for (int i = 0; i < 40 * s; i++) {
            Location c = start.clone().add(d.clone().multiply(i));
            plan.add(cylinder(c, 1.6, 14, 2), c, i / 2, 0);
            if (i % 4 == 0) hurt(p, Abilities.near(p, c, 3), 400 * s);
        }
        plan.start();
        Fx.beam(start.clone().add(0, 0.3, 0), start.clone().add(d.clone().multiply(40 * s)).add(0, 0.3, 0), Particle.CRIT, 8);
        boom(start);
        return true;
    }

    private boolean skyfallPillar(Player p) {
        double s = scale(p);
        Location t = aim(p, 80);
        Plan plan = new Plan(p);
        plan.add(cylinder(t, 4 * s, 30, 50), t, 0, 0.15);
        plan.start();
        Fx.lightningBolt(t.clone().add(0, 40, 0), t, Particle.ELECTRIC_SPARK);
        Fx.column(t, Particle.END_ROD, 6, 12);
        t.getWorld().strikeLightningEffect(t);
        hurt(p, Abilities.near(p, t, 6 * s), 800 * s);
        Fx.shockwave(t, 9 * s, expl, 8, 8);
        boom(t);
        return true;
    }

    private boolean voidSphere(Player p) {
        double s = scale(p);
        Location t = aim(p, 80).add(0, 2, 0);
        Plan plan = new Plan(p);
        plan.add(sphere(t, 9 * s), t, 0, 0.8);
        plan.start();
        hurt(p, Abilities.near(p, t, 12 * s), 900 * s);
        Fx.vortex(t, 10 * s, Particle.SMOKE, 12);
        Fx.shockwave(t, 11 * s, Particle.SMOKE, 10, 10);
        Fx.magicCircle(t, 6, Particle.WITCH, 16);
        boom(t);
        return true;
    }

    private boolean thunderGod(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 20; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 4 + r.nextDouble() * 22 * s;
            final Location pos = ground(c.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pos.getWorld().strikeLightningEffect(pos);
                Fx.lightningBolt(pos.clone().add(0, 24, 0), pos, Particle.ELECTRIC_SPARK);
                hurt(p, Abilities.near(p, pos, 4), 350 * s);
                Plan crater = new Plan(p, 120);
                crater.add(sphere(pos, 1.8), pos, 0, 0);
                crater.start();
            }, i * 2L);
        }
        return true;
    }

    private boolean inferno(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        List<LivingEntity> in = Abilities.near(p, c, 40 * s);
        for (LivingEntity t : in) {
            t.setFireTicks(1000);
            Fx.burst(t.getLocation().add(0, 1, 0), Particle.FLAME, 30, 0.4, 0.6, 0.4, 0.05);
        }
        hurt(p, in, 150 * s);
        Fx.shockwave(c, 40 * s, Particle.FLAME, 16, 16);
        Fx.magicCircle(c, 6, Particle.FLAME, 20);
        boom(c);
        return true;
    }

    private boolean blackHole(Player p) {
        final double s = scale(p);
        final Location t = aim(p, 60).add(0, 2, 0);
        Plan plan = new Plan(p);
        plan.add(sphere(t, 8 * s), t, 0, 12.0);
        plan.start();
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                if (tick > 130) { cancel(); return; }
                for (LivingEntity e : Abilities.near(p, t, 16 * s)) {
                    exempt(e);
                    Vector v = t.toVector().subtract(e.getLocation().toVector());
                    if (v.lengthSquared() > 0.01) v.normalize().multiply(0.9);
                    e.setVelocity(v);
                    if (tick % 10 == 0) { e.setNoDamageTicks(0); e.damage(60 * s, p); }
                }
                if (tick % 6 == 0) {
                    Fx.vortex(t, 8 * s, Particle.SMOKE, 8);
                    Fx.burst(t, Particle.SMOKE, 30, 1.2, 1.2, 1.2, 0.05);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        boom(t);
        return true;
    }

    private boolean stomp(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        for (LivingEntity t : Abilities.near(p, c, 10 * s)) {
            exempt(t);
            Vector v = t.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (v.lengthSquared() > 0.01) v.normalize().multiply(2.5);
            v.setY(1.2);
            t.setVelocity(v);
        }
        hurt(p, Abilities.near(p, c, 10 * s), 300 * s);
        Plan plan = new Plan(p);
        plan.add(cylinder(c, 8 * s, 3, 0), c, 0, 0.5);
        plan.start();
        Fx.shockwave(c, 11 * s, Particle.CRIT, 10, 12);
        Fx.spikes(c, 8 * s, Particle.CRIT, 14, 5);
        boom(c);
        return true;
    }

    private boolean judgment(Player p) {
        double s = scale(p);
        List<LivingEntity> in = Abilities.near(p, p.getLocation(), 40 * s);
        for (LivingEntity t : in) t.getWorld().strikeLightningEffect(t.getLocation());
        hurt(p, in, 1000 * s);
        Fx.shockwave(p.getLocation(), 40 * s, Particle.ELECTRIC_SPARK, 16, 16);
        Fx.halo(p, Particle.END_ROD, 60);
        boom(p.getLocation());
        return true;
    }

    private boolean blinkStrike(Player p) {
        double s = scale(p);
        Location t = aim(p, 80 * s);
        Location dest = t.clone().add(0, 1, 0);
        for (int i = 0; i < 3 && !dest.getBlock().isPassable(); i++) dest.add(0, 1, 0);
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());
        Fx.dissolve(p.getLocation(), Particle.PORTAL, true, 10);
        p.teleport(dest);
        noFall(p, 6);
        blast(p, t, 4 * s, 500 * s, expl, 0.6);
        return true;
    }

    private boolean tsunami(Player p) {
        double s = scale(p);
        Location base = p.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        final Vector d = dir.normalize();
        Location mid = base.clone().add(d.clone().multiply(15 * s));
        List<LivingEntity> in = new ArrayList<>();
        for (LivingEntity t : Abilities.near(p, mid, 20 * s)) {
            Vector v = t.getLocation().toVector().subtract(base.toVector()).setY(0);
            if (v.lengthSquared() > 0 && v.clone().normalize().dot(d) > 0.5) {
                exempt(t);
                t.setVelocity(d.clone().multiply(3.0).setY(0.6));
                in.add(t);
            }
        }
        hurt(p, in, 250 * s);
        for (int i = 1; i <= 12; i++) {
            final Location ring = base.clone().add(d.clone().multiply(i * 2.5 * s));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Fx.ring(ring, 4, Particle.SPLASH, 0.3);
                Fx.burst(ring.clone().add(0, 1, 0), Particle.SPLASH, 40, 2.5, 1.0, 2.5, 0.1);
            }, i);
        }
        return true;
    }

    private boolean earthquake(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        World w = c.getWorld();
        Plan plan = new Plan(p);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 400; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 20 * s;
            int x = (int) Math.floor(c.getX() + Math.cos(a) * d), z = (int) Math.floor(c.getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            List<Block> col = new ArrayList<>();
            for (int k = 0; k < 3; k++) col.add(w.getBlockAt(x, y - k, z));
            plan.add(col, c, r.nextInt(30), 0);
        }
        plan.start();
        List<LivingEntity> in = Abilities.near(p, c, 20 * s);
        for (LivingEntity t : in) t.addPotionEffect(Abilities.fx(PotionEffectType.SLOWNESS, 5, 3));
        hurt(p, in, 200 * s);
        Fx.shockwave(c, 20 * s, Particle.CRIT, 16, 16);
        boom(c);
        return true;
    }

    private boolean magmaBurst(Player p) {
        double s = scale(p);
        Location t = aim(p, 60);
        for (LivingEntity e : Abilities.near(p, t, 6 * s)) {
            exempt(e);
            e.setVelocity(new Vector(0, 2.5, 0));
            e.setFireTicks(200);
        }
        hurt(p, Abilities.near(p, t, 6 * s), 350 * s);
        blast(p, t, 3 * s, 0, Particle.FLAME, 0.6);
        Fx.column(t, Particle.FLAME, 8, 14);
        Fx.burst(t, lava, 40, 2, 1, 2, 0.1);
        return true;
    }

    private boolean iceAge(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        List<LivingEntity> in = Abilities.near(p, c, 30 * s);
        for (LivingEntity t : in) {
            t.setFreezeTicks(600);
            t.addPotionEffect(Abilities.fx(PotionEffectType.SLOWNESS, 10, 4));
        }
        hurt(p, in, 100 * s);
        Fx.shockwave(c, 30 * s, Particle.SNOWFLAKE, 14, 16);
        Fx.spikes(c, 12, Particle.SNOWFLAKE, 14, 6);
        boom(c);
        return true;
    }

    private boolean plague(Player p) {
        double s = scale(p);
        List<LivingEntity> in = Abilities.near(p, p.getLocation(), 40 * s);
        for (LivingEntity t : in) {
            t.addPotionEffect(Abilities.fx(PotionEffectType.WITHER, 10, 3));
            t.addPotionEffect(Abilities.fx(PotionEffectType.POISON, 10, 3));
            t.addPotionEffect(Abilities.fx(PotionEffectType.WEAKNESS, 10, 3));
        }
        hurt(p, in, 50 * s);
        Fx.shockwave(p.getLocation(), 40 * s, Particle.SMOKE, 16, 12);
        return true;
    }

    private boolean timeStop(Player p) {
        return Abilities.freezeAll(30 * scale(p), 6).cast(p);
    }

    private boolean dragonBreath(Player p) {
        double s = scale(p);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Plan plan = new Plan(p);
        plan.add(tunnel(eye, dir, 25 * s, 2.0), eye, 0, 0.4);
        plan.start();
        Location mid = eye.clone().add(dir.clone().multiply(12 * s));
        List<LivingEntity> in = new ArrayList<>();
        for (LivingEntity t : Abilities.near(p, mid, 18 * s)) {
            Vector v = t.getLocation().toVector().subtract(eye.toVector());
            if (v.lengthSquared() > 0 && v.normalize().dot(dir.clone().normalize()) > 0.88) {
                t.addPotionEffect(Abilities.fx(PotionEffectType.WITHER, 6, 2));
                in.add(t);
            }
        }
        hurt(p, in, 400 * s);
        for (int k = -2; k <= 2; k++) {
            Vector d2 = dir.clone().rotateAroundY(Math.toRadians(k * 9));
            Fx.beam(eye.clone().add(d2.clone().multiply(1.2)), eye.clone().add(d2.clone().multiply(25 * s)), Particle.WITCH, 6);
        }
        return true;
    }

    private boolean soulHarvest(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        List<LivingEntity> in = Abilities.near(p, c, 25 * s);
        hurt(p, in, 350 * s);
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.min(max, p.getHealth() + 40));
        Fx.vortex(c, 25 * s, Particle.SOUL_FIRE_FLAME, 16);
        Fx.spiral(() -> p.getLocation(), Particle.SOUL_FIRE_FLAME, 0.9, 2.2, 20, 3);
        return true;
    }

    private boolean colossus(Player p) {
        p.addPotionEffect(Abilities.fx(PotionEffectType.STRENGTH, 40, 9));
        p.addPotionEffect(Abilities.fx(PotionEffectType.RESISTANCE, 40, 4));
        p.addPotionEffect(Abilities.fx(PotionEffectType.ABSORPTION, 40, 9));
        p.addPotionEffect(Abilities.fx(PotionEffectType.SPEED, 40, 3));
        p.addPotionEffect(Abilities.fx(PotionEffectType.REGENERATION, 40, 3));
        Fx.wings(p, Particle.FLAME, 200);
        Fx.halo(p, Particle.END_ROD, 200);
        Fx.magicCircle(p.getLocation(), 3, Particle.FLAME, 20);
        boom(p.getLocation());
        return true;
    }

    private boolean warpStep(Player p) {
        return Abilities.blink(100 * scale(p), Particle.PORTAL).cast(p);
    }

    private boolean skyfallDive(Player p) {
        final double s = scale(p);
        noFall(p, 12);
        p.setVelocity(new Vector(0, 4.2, 0));
        new BukkitRunnable() {
            int t = 0;
            boolean down = false;

            @Override
            public void run() {
                if (!p.isOnline()) { cancel(); return; }
                t++;
                exempt(p);
                if (!down && (t >= 22 || (t > 6 && p.getVelocity().getY() < 0.2))) {
                    down = true;
                    p.setVelocity(new Vector(0, -4.5, 0));
                }
                if (down && (p.isOnGround() || t > 90)) {
                    cancel();
                    blast(p, p.getLocation().add(0, 0.5, 0), 6 * s, 600 * s, expl, 0.6);
                }
            }
        }.runTaskTimer(plugin, 2L, 1L);
        return true;
    }

    private boolean annihilateTarget(Player p) {
        double s = scale(p);
        Entity e = p.getTargetEntity(80);
        if (!(e instanceof LivingEntity t) || !Abilities.valid(p, e)) { say(p, "&cNo target in range."); return false; }
        Location l = t.getLocation();
        Fx.lightningBolt(l.clone().add(0, 30, 0), l, Particle.ELECTRIC_SPARK);
        l.getWorld().strikeLightningEffect(l);
        hurt(p, List.of(t), 5000 * s);
        Plan plan = new Plan(p, 300);
        plan.add(sphere(l, 2.5), l, 0, 0.5);
        plan.start();
        Fx.shockwave(l, 6, Particle.ELECTRIC_SPARK, 8, 8);
        boom(l);
        return true;
    }

    private boolean chainCollapse(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        Plan plan = new Plan(p);
        plan.add(cylinder(c, 25 * s, 3, 3), c, 0, 1.0);
        plan.start();
        Fx.shockwave(c, 25 * s, expl, 25, 16);
        hurt(p, Abilities.near(p, c, 25 * s), 300 * s);
        boom(c);
        return true;
    }

    private boolean worldbreaker(Player p) {
        double s = scale(p);
        Location c = p.getLocation();
        noFall(p, 10);
        Plan plan = new Plan(p);
        plan.add(cylinder(c, 14 * s, 20, 4), c, 0, 0.4);
        plan.start();
        hurt(p, Abilities.near(p, c, 18 * s), 600 * s);
        Fx.shockwave(c, 18 * s, expl, 12, 16);
        Fx.magicCircle(c, 8, expl, 24);
        Fx.column(c, Particle.END_ROD, 10, 16);
        boom(c);
        return true;
    }

    private boolean toggleAura(Player p) {
        if (!aura.remove(p.getUniqueId())) {
            aura.add(p.getUniqueId());
            say(p, "&4Walking Calamity: &aON");
            Fx.wings(p, Particle.SMOKE, 80);
        } else {
            say(p, "&4Walking Calamity: &cOFF");
        }
        return true;
    }

    private boolean rewind(Player p) { return undoLast(p); }

    // ------------------------------------------------------------------ Walking Calamity

    private void auraTick() {
        Iterator<UUID> it = aura.iterator();
        while (it.hasNext()) {
            Player p = Bukkit.getPlayer(it.next());
            if (p == null || !p.isOnline() || !allowed(p) || plugin.data().get(p.getUniqueId()).origin != Origin.CALAMITY) {
                it.remove();
                continue;
            }
            double s = scale(p);
            Location l = p.getLocation();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Fx.burst(l.clone().add(0, 1, 0), Particle.SMOKE, 25, 1.4, 1.0, 1.4, 0.03);
            Fx.burst(l.clone().add(0, 0.5, 0), lava, 8, 1.2, 0.3, 1.2, 0.0);
            Fx.ring(l, 4.0, Particle.SOUL_FIRE_FLAME, 0.2);
            if (r.nextDouble() < 0.6) {
                double a = r.nextDouble() * Math.PI * 2, d = 4 + r.nextDouble() * 10 * s;
                Location pos = ground(l.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
                pos.getWorld().strikeLightningEffect(pos);
                hurt(p, Abilities.near(p, pos, 3.5), 250 * s);
            }
            List<LivingEntity> close = Abilities.near(p, l, 8 * s);
            for (LivingEntity t : close) t.setFireTicks(80);
            hurt(p, close, 40 * s);
            if (plugin.getConfig().getBoolean("calamity.aura-destroys-blocks", true)) {
                Plan plan = new Plan(p, 150);
                plan.add(sphere(l.clone().add(0, 0.5, 0), cfg("aura-radius", 3.5)), l, 0, 0.2);
                plan.start();
            }
        }
    }

    public void stopAura(Player p) { aura.remove(p.getUniqueId()); }

    public void toggleAuraFor(Player p) { toggleAura(p); }
}
