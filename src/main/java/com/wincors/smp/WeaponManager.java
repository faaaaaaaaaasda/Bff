package com.wincors.smp;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Legendary weapons (defined per origin in origins.yml). Only work for their owner at max mana. */
public final class WeaponManager implements Listener {
    private final WincorsSMP plugin;
    private final NamespacedKey originKey;
    private final NamespacedKey ownerKey;
    private final Map<UUID, Long> procCooldown = new HashMap<>();

    public WeaponManager(WincorsSMP plugin) {
        this.plugin = plugin;
        this.originKey = new NamespacedKey(plugin, "weapon_origin");
        this.ownerKey = new NamespacedKey(plugin, "weapon_owner");
    }

    public static String weaponName(Origin o) { return o.weapon().name; }

    public static Material materialFor(Origin o) { return o.weapon().material; }

    public ItemStack create(Origin o, Player owner) {
        Origin.WeaponDef w = o.weapon();
        ItemStack it = new ItemStack(w.material);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item(o.color() + "&l" + w.name));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("items.weapon-lore")) {
            lore.add(Text.item(line.replace("{origin}", o.colored()).replace("{owner}", owner.getName()).replace("{weapon}", w.name)));
        }
        if (!lore.isEmpty()) m.lore(lore);
        if (w.unbreakable) {
            m.setUnbreakable(true);
            m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        for (String spec : w.enchants) {
            String[] p = spec.split(":");
            Enchantment e = Parse.enchant(p[0]);
            if (e == null) {
                plugin.getLogger().warning("Unknown enchantment '" + spec + "' on the " + o.name() + " weapon.");
                continue;
            }
            int lvl = 1;
            try { if (p.length > 1) lvl = Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) { }
            m.addEnchant(e, lvl, true);
        }
        ResourcePackManager.applyModel(plugin, m, w.model);
        m.getPersistentDataContainer().set(originKey, PersistentDataType.STRING, o.name());
        m.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        it.setItemMeta(m);
        return it;
    }

    public void give(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) return;
        ItemStack w = create(d.origin, p);
        for (ItemStack left : p.getInventory().addItem(w).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        Text.msg(p, Msg.t("weapon-received", "weapon", d.origin.weapon().name));
    }

    public Origin weaponOrigin(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(originKey, PersistentDataType.STRING);
        return Origin.parse(s);
    }

    public boolean isOwnedBy(ItemStack it, UUID id) {
        if (weaponOrigin(it) == null) return false;
        String s = it.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return id.toString().equals(s);
    }

    private void heal(Player a, double amt) {
        double max = a.getAttribute(Attribute.MAX_HEALTH).getValue();
        a.setHealth(Math.min(max, a.getHealth() + amt));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player a) || !(e.getEntity() instanceof LivingEntity t)) return;
        ItemStack item = a.getInventory().getItemInMainHand();
        Origin wo = weaponOrigin(item);
        if (wo == null) return;

        DataManager.PlayerData d = plugin.data().get(a.getUniqueId());
        if (d.origin != wo || d.mana < ManaManager.MAX || !isOwnedBy(item, a.getUniqueId())) {
            a.sendActionBar(Text.c(Msg.t("weapon-dormant")));
            return;
        }
        Origin.Proc pr = wo.weapon().proc;
        long now = System.currentTimeMillis();
        Long next = procCooldown.get(a.getUniqueId());
        if (next != null && next > now) return;
        procCooldown.put(a.getUniqueId(), now + (long) (pr.cooldownSeconds * 1000L * plugin.powers().cooldownMultiplier(a)));

        if (pr.extraDamage > 0) e.setDamage(e.getDamage() + pr.extraDamage);
        if (pr.fireTicks > 0) t.setFireTicks(pr.fireTicks);
        if (pr.freezeTicks > 0) t.setFreezeTicks(pr.freezeTicks);
        if (pr.knockback > 0) {
            Vector v = t.getLocation().toVector().subtract(a.getLocation().toVector()).setY(0);
            if (v.lengthSquared() > 0.0001) v.normalize().multiply(pr.knockback);
            v.setY(0.4);
            t.setVelocity(v);
        }
        if (pr.launch > 0) t.setVelocity(new Vector(0, pr.launch, 0));
        if (t instanceof Player tp) plugin.anticheat().exempt(tp, 2500);
        if (pr.lightning) t.getWorld().strikeLightningEffect(t.getLocation());
        for (PotionEffect pe : pr.effects) t.addPotionEffect(pe);
        if (pr.healAttacker > 0) heal(a, pr.healAttacker);
        Fx.impact(t.getLocation().add(0, 1, 0), pr.particle);
    }
}
