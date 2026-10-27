package com.wincors.smp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A simple clickable inventory menu. */
public final class Gui implements InventoryHolder {
    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Gui(Component title, int rows) {
        this.inv = Bukkit.createInventory(this, rows * 9, title);
    }

    public void set(int slot, ItemStack item) { inv.setItem(slot, item); }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        actions.put(slot, action);
    }

    /** Fill every empty slot from {@code from} onward. */
    public void fill(ItemStack item, int from) {
        for (int i = from; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, item);
        }
    }

    public void click(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> a = actions.get(e.getSlot());
        if (a != null) a.accept(e);
    }

    public void open(Player p) { p.openInventory(inv); }

    @Override
    public Inventory getInventory() { return inv; }
}
