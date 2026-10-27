package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

/** Carrying the Dragon Egg: 20 hearts + shorter power cooldowns. */
public final class EggManager implements Listener {
    private final WincorsSMP plugin;
    private final NamespacedKey key;

    public EggManager(WincorsSMP plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "dragon_egg_health");
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 10L, 10L);
    }

    public boolean hasEgg(Player p) {
        for (ItemStack i : p.getInventory().getContents()) {
            if (i != null && i.getType() == Material.DRAGON_EGG) return true;
        }
        return false;
    }

    private AttributeModifier find(AttributeInstance a) {
        for (AttributeModifier m : a.getModifiers()) {
            if (key.equals(m.getKey())) return m;
        }
        return null;
    }

    public void update(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        AttributeModifier cur = find(a);
        boolean has = hasEgg(p);
        if (has && cur == null) {
            double target = plugin.getConfig().getDouble("dragon-egg.target-max-health", 40.0);
            double add = target - a.getBaseValue();
            if (add > 0) {
                a.addModifier(new AttributeModifier(key, add, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                Text.msg(p, Msg.t("egg-gain", "hearts", String.valueOf((int) (target / 2))));
            }
        } else if (!has && cur != null) {
            a.removeModifier(cur);
            if (p.getHealth() > a.getValue()) p.setHealth(a.getValue());
            Text.msg(p, Msg.t("egg-lose"));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { update(e.getPlayer()); }
}
