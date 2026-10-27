package com.wincors.smp;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

public final class Power {
    public enum Type { POTION, FLAG, ACTIVE }

    public enum Flag { NO_FALL, LIFESTEAL, FIRE_TOUCH, FROST_TOUCH, BONUS_DAMAGE, REFLECT, LAST_STAND, DODGE, FREEZE_IMMUNE }

    @FunctionalInterface
    public interface Active {
        /** @return true if the ability actually fired (cooldown is only used when true) */
        boolean cast(Player p);

        default Active and(Active other) {
            return p -> {
                boolean a = cast(p);
                boolean b = other.cast(p);
                return a || b;
            };
        }
    }

    public final int tier;
    public final String name;
    public final String desc;
    public final Type type;
    public final PotionEffectType effect;
    public final int amp;
    public final Flag flag;
    public final int cooldown;
    public final Active active;
    /** Optional icon in the power menu (powers.yml -> icon). */
    public Material icon;

    private Power(int tier, String name, String desc, Type type, PotionEffectType effect, int amp, Flag flag, int cooldown, Active active) {
        this.tier = tier;
        this.name = name;
        this.desc = desc;
        this.type = type;
        this.effect = effect;
        this.amp = amp;
        this.flag = flag;
        this.cooldown = cooldown;
        this.active = active;
    }

    public static Power potion(int tier, String name, String desc, PotionEffectType e, int amp) {
        return new Power(tier, name, desc, Type.POTION, e, amp, null, 0, null);
    }

    public static Power flag(int tier, String name, String desc, Flag f) {
        return new Power(tier, name, desc, Type.FLAG, null, 0, f, 0, null);
    }

    public static Power active(int tier, String name, String desc, int cooldown, Active a) {
        return new Power(tier, name, desc, Type.ACTIVE, null, 0, null, cooldown, a);
    }
}
