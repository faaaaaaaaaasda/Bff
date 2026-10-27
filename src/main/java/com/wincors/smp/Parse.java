package com.wincors.smp;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns config text ("SLOWNESS:4:1", "FLAME", "ENTITY_BLAZE_SHOOT"...) into Bukkit objects. */
final class Parse {
    private Parse() {}

    private static Map<String, Particle> particles;
    private static Map<String, Sound> sounds;

    static Particle particle(String name, Particle def) {
        if (name == null) return def;
        if (particles == null) {
            particles = new HashMap<>();
            for (Particle p : Registry.PARTICLE_TYPE) particles.put(p.getKey().getKey().toUpperCase(Locale.ROOT), p);
        }
        Particle p = particles.get(name.trim().toUpperCase(Locale.ROOT));
        return p != null ? p : def;
    }

    static Sound sound(String name, Sound def) {
        if (name == null) return def;
        if (sounds == null) {
            sounds = new HashMap<>();
            for (Sound s : Registry.SOUNDS) sounds.put(s.getKey().getKey().replace('.', '_').toUpperCase(Locale.ROOT), s);
        }
        Sound s = sounds.get(name.trim().toUpperCase(Locale.ROOT));
        return s != null ? s : def;
    }

    static PotionEffectType effectType(String name) {
        if (name == null) return null;
        return Registry.EFFECT.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
    }

    static Enchantment enchant(String name) {
        if (name == null) return null;
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
    }

    static Material material(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name.trim());
        return m != null ? m : def;
    }

    /** "TYPE:seconds:amplifier" -> PotionEffect, or null if invalid. */
    static PotionEffect effect(String spec) {
        if (spec == null) return null;
        String[] p = spec.split(":");
        PotionEffectType t = effectType(p[0]);
        if (t == null) return null;
        try {
            int sec = p.length > 1 ? Integer.parseInt(p[1].trim()) : 5;
            int amp = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
            return Abilities.fx(t, sec, amp);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static PotionEffect[] effects(List<?> specs) {
        List<PotionEffect> out = new ArrayList<>();
        if (specs != null) {
            for (Object o : specs) {
                PotionEffect e = effect(String.valueOf(o));
                if (e == null) org.bukkit.Bukkit.getLogger().warning("[WincorsSMP] Invalid effect in config: " + o);
                else out.add(e);
            }
        }
        return out.toArray(new PotionEffect[0]);
    }
}
