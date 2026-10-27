package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Netherite armor ban + "only one Mace" rule. */
public final class RestrictionManager implements Listener {
    private final WincorsSMP plugin;
    private final NamespacedKey maceKey;

    public RestrictionManager(WincorsSMP plugin) {
        this.plugin = plugin;
        this.maceKey = new NamespacedKey(plugin, "mace_id");
        reload();
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (plugin.getConfig().getBoolean("restrictions.ban-netherite-armor", true)) {
                for (Player p : Bukkit.getOnlinePlayers()) scanArmor(p);
            }
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::scanMace, 100L, 100L);
    }

    // ---------------- Netherite armor ----------------

    private final Set<Material> banned = new HashSet<>();

    /** Re-reads restrictions.banned-armor from config.yml. */
    public void reload() {
        banned.clear();
        for (String name : plugin.getConfig().getStringList("restrictions.banned-armor")) {
            Material m = Material.matchMaterial(name);
            if (m == null) plugin.getLogger().warning("Unknown item in restrictions.banned-armor: " + name);
            else banned.add(m);
        }
    }

    public boolean isNetheriteArmor(ItemStack it) {
        return it != null && banned.contains(it.getType());
    }

    private boolean armorBanOn() { return plugin.getConfig().getBoolean("restrictions.ban-netherite-armor", true); }

    private void scanArmor(Player p) {
        if (p.hasPermission("wincors.bypass")) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            if (isNetheriteArmor(armor[i])) {
                for (ItemStack left : inv.addItem(armor[i]).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                armor[i] = null;
                changed = true;
            }
        }
        if (changed) {
            inv.setArmorContents(armor);
            Text.msg(p, Msg.t("armor-banned"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSmithing(PrepareSmithingEvent e) {
        if (!armorBanOn()) return;
        if (isNetheriteArmor(e.getResult())) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!armorBanOn() || !(e.getWhoClicked() instanceof Player p) || p.hasPermission("wincors.bypass")) return;
        // Putting it directly into an armor slot
        if (e.getSlotType() == InventoryType.SlotType.ARMOR && isNetheriteArmor(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        // Number-key swap into armor slot
        if (e.getSlotType() == InventoryType.SlotType.ARMOR && e.getHotbarButton() >= 0
                && isNetheriteArmor(p.getInventory().getItem(e.getHotbarButton()))) {
            e.setCancelled(true);
            return;
        }
        // Shift-click auto equip (only when no other inventory is open)
        if (e.isShiftClick() && isNetheriteArmor(e.getCurrentItem())
                && e.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
            e.setCancelled(true);
        }
    }

    // ---------------- Only one Mace ----------------

    private String maceTag(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(maceKey, PersistentDataType.STRING);
    }

    private ItemStack tagMace(ItemStack it, String id) {
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        m.getPersistentDataContainer().set(maceKey, PersistentDataType.STRING, id);
        m.lore(java.util.List.of(Text.item(plugin.getConfig().getString("items.mace-lore", "&6The one and only Mace of Wincors SMP."))));
        c.setItemMeta(m);
        return c;
    }

    /** Admin helper. @return null if a mace already exists. */
    public ItemStack createMace() {
        if (plugin.data().getMaceId() != null) return null;
        String id = UUID.randomUUID().toString();
        plugin.data().setMaceId(id);
        return tagMace(new ItemStack(Material.MACE), id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!plugin.getConfig().getBoolean("restrictions.one-mace", true)) return;
        ItemStack r = e.getInventory().getResult();
        if (r != null && r.getType() == Material.MACE && plugin.data().getMaceId() != null) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        if (!plugin.getConfig().getBoolean("restrictions.one-mace", true)) return;
        ItemStack r = e.getCurrentItem();
        if (r == null || r.getType() != Material.MACE) return;
        Player p = (Player) e.getWhoClicked();
        if (plugin.data().getMaceId() != null) {
            e.setCancelled(true);
            Text.msg(p, Msg.t("mace-exists"));
            return;
        }
        if (e.isShiftClick()) {
            e.setCancelled(true);
            Text.msg(p, Msg.t("mace-no-shift"));
            return;
        }
        String id = UUID.randomUUID().toString();
        plugin.data().setMaceId(id);
        e.setCurrentItem(tagMace(r, id));
        if (!plugin.vanish().isVanished(p)) {
            Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("mace-forged", "player", p.getName())));
        }
    }

    @EventHandler
    public void onLoot(LootGenerateEvent e) {
        if (!plugin.getConfig().getBoolean("restrictions.one-mace", true) || !plugin.getConfig().getBoolean("restrictions.remove-mace-from-loot", true)) return;
        e.getLoot().removeIf(i -> i != null && i.getType() == Material.MACE);
    }

    /** Removes duplicate / stale / untracked maces from online players. */
    private void scanMace() {
        if (!plugin.getConfig().getBoolean("restrictions.one-mace", true)) return;
        boolean seen = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            ItemStack[] c = inv.getContents();
            boolean changed = false;
            for (int i = 0; i < c.length; i++) {
                ItemStack it = c[i];
                if (it == null || it.getType() != Material.MACE) continue;
                String tag = maceTag(it);
                String cur = plugin.data().getMaceId();
                if (tag == null && cur == null) {
                    // Mace obtained some other way (loot, vault...) while none exists -> claim it
                    String id = UUID.randomUUID().toString();
                    plugin.data().setMaceId(id);
                    c[i] = tagMace(it, id);
                    seen = true;
                    changed = true;
                    if (!plugin.vanish().isVanished(p)) {
                        Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("mace-claimed", "player", p.getName())));
                    }
                } else if (tag != null && tag.equals(cur) && !seen) {
                    seen = true;
                } else {
                    c[i] = null;
                    changed = true;
                    Text.msg(p, Msg.t("mace-removed"));
                }
            }
            if (changed) inv.setContents(c);
        }
    }
}
