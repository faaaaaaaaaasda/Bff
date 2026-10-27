package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.List;
import java.util.Map;

/** Builds an active power from its "actions" list in powers.yml. */
final class Actions {
    private Actions() {}

    private static double d(Map<?, ?> m, String k, double def) { return m.get(k) instanceof Number n ? n.doubleValue() : def; }
    private static int i(Map<?, ?> m, String k, int def) { return m.get(k) instanceof Number n ? n.intValue() : def; }
    private static boolean b(Map<?, ?> m, String k, boolean def) { return m.get(k) instanceof Boolean v ? v : def; }
    private static String s(Map<?, ?> m, String k) { return m.get(k) == null ? null : String.valueOf(m.get(k)); }
    private static List<?> l(Map<?, ?> m, String k) { return m.get(k) instanceof List<?> v ? v : List.of(); }

    /** @return the combined active, or null if nothing valid was found */
    static Power.Active build(List<Map<?, ?>> list, String ctx) {
        Power.Active result = null;
        for (Map<?, ?> m : list) {
            Power.Active a = one(m, ctx);
            if (a == null) continue;
            result = result == null ? a : result.and(a);
        }
        return result;
    }

    private static Power.Active one(Map<?, ?> m, String ctx) {
        String type = String.valueOf(m.get("type")).toUpperCase();
        Particle part = Parse.particle(s(m, "particle"), Particle.CLOUD);
        Sound snd = Parse.sound(s(m, "sound"), Sound.ENTITY_PLAYER_LEVELUP);
        var fx = Parse.effects(l(m, "effects"));
        switch (type) {
            case "NOVA":
                return Abilities.nova(d(m, "radius", 6), d(m, "damage", 0), d(m, "knockback", 0), i(m, "fire-ticks", 0), i(m, "freeze-ticks", 0), part, snd, fx);
            case "BUFF":
                return Abilities.buff(part, snd, fx);
            case "DASH":
                return Abilities.dash(d(m, "speed", 1.5), d(m, "up", 0), part, snd, fx);
            case "LAUNCH":
                return Abilities.launch(d(m, "strength", 1.5), part, snd, fx);
            case "BLINK":
                return Abilities.blink(d(m, "distance", 8), Parse.particle(s(m, "particle"), Particle.PORTAL), fx);
            case "STRIKE":
                return Abilities.strike(d(m, "range", 30), d(m, "radius", 4), d(m, "damage", 5), b(m, "lightning", false), i(m, "fire-ticks", 0), part, snd, s(m, "style"), fx);
            case "HEAL":
                return Abilities.heal(d(m, "amount", 6), fx);
            case "PULL":
                return Abilities.pull(d(m, "radius", 8), d(m, "strength", 1.5), part, fx);
            case "FIREBALLS":
                return Abilities.fireballs(i(m, "count", 3));
            case "FREEZE_ALL":
                return Abilities.freezeAll(d(m, "radius", 10), i(m, "seconds", 4));
            case "LIGHTNING_FX":
                return Abilities.lightningFx(d(m, "radius", 10));
            case "WINGS":
                return Abilities.wings(part, i(m, "seconds", 6));
            case "HALO":
                return Abilities.halo(part, i(m, "seconds", 8));
            case "NO_FALL":
                return Abilities.noFall(i(m, "seconds", 6));
            default:
                Bukkit.getLogger().warning("[WincorsSMP] Unknown action type '" + type + "' in " + ctx);
                return null;
        }
    }
}
