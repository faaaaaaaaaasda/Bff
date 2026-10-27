package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ManaManager implements Listener {
    public static final int MAX = 10;

    private final WincorsSMP plugin;
    private final NamespacedKey crystalKey;
    private final NamespacedKey recipeKey;
    private final Map<String, Long> killCooldowns = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pendingWeapons = new HashMap<>();

    public ManaManager(WincorsSMP plugin) {
        this.plugin = plugin;
        this.crystalKey = new NamespacedKey(plugin, "mana_crystal");
        this.recipeKey = new NamespacedKey(plugin, "mana_crystal_recipe");
    }

    // ---------- Mana Crystal item + recipe ----------

    public ItemStack createCrystal(int amount) {
        ItemStack it = new ItemStack(Material.PRISMARINE_CRYSTALS, amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item(plugin.getConfig().getString("items.mana-crystal.name", "&d&lMana Crystal")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("items.mana-crystal.lore")) lore.add(Text.item(line));
        if (!lore.isEmpty()) m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        ResourcePackManager.applyModel(plugin, m, "mana_crystal");
        m.getPersistentDataContainer().set(crystalKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public boolean isCrystal(ItemStack it) {
        return it != null && it.getType() == Material.PRISMARINE_CRYSTALS && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(crystalKey, PersistentDataType.BYTE);
    }

    private List<String> recipeShape = new ArrayList<>(List.of("ABA", "BCB", "ABA"));
    private Map<Character, Material> recipeIngredients = new LinkedHashMap<>();

    public List<String> recipeShape() { return recipeShape; }

    public Map<Character, Material> recipeIngredients() { return recipeIngredients; }

    private void loadRecipeConfig() {
        List<String> shape = plugin.getConfig().getStringList("recipe.shape");
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("recipe.ingredients");
        Map<Character, Material> ing = new LinkedHashMap<>();
        boolean ok = shape.size() == 3 && sec != null;
        if (ok) for (String row : shape) if (row.length() != 3) ok = false;
        if (ok) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(sec.getString(k, ""));
                if (k.length() != 1 || m == null || !m.isItem()) { ok = false; break; }
                ing.put(k.charAt(0), m);
            }
        }
        if (ok) for (String row : shape) for (char ch : row.toCharArray()) if (ch != ' ' && !ing.containsKey(ch)) ok = false;
        if (!ok) {
            plugin.getLogger().warning("Invalid recipe section in config.yml - using the default Mana Crystal recipe.");
            shape = List.of("ABA", "BCB", "ABA");
            ing = new LinkedHashMap<>();
            ing.put('A', Material.DIAMOND_BLOCK);
            ing.put('B', Material.ECHO_SHARD);
            ing.put('C', Material.NETHERITE_INGOT);
        }
        recipeShape = new ArrayList<>(shape);
        recipeIngredients = ing;
    }

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        loadRecipeConfig();
        ShapedRecipe r = new ShapedRecipe(recipeKey, createCrystal(1));
        r.shape(recipeShape.toArray(new String[0]));
        Set<Character> used = new HashSet<>();
        for (String row : recipeShape) for (char ch : row.toCharArray()) if (ch != ' ') used.add(ch);
        for (Map.Entry<Character, Material> en : recipeIngredients.entrySet()) {
            if (used.contains(en.getKey())) r.setIngredient(en.getKey(), en.getValue());
        }
        Bukkit.addRecipe(r);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);
    }

    // ---------- Choosing an origin ----------

    /** origins.selection-mode: "choose" (players pick) or "random". */
    public boolean chooseMode() {
        return "choose".equalsIgnoreCase(plugin.getConfig().getString("origins.selection-mode", "choose"));
    }

    public int limit() { return plugin.getConfig().getInt("origins.max-players-per-origin", 0); }

    public int takenCount(Origin o, UUID except) {
        int n = 0;
        for (Map.Entry<UUID, DataManager.PlayerData> en : plugin.data().all().entrySet()) {
            if (en.getValue().origin == o && !en.getKey().equals(except)) n++;
        }
        return n;
    }

    public boolean isFull(Origin o, Player p) {
        int lim = limit();
        if (lim <= 0) return false;
        if (plugin.data().get(p.getUniqueId()).origin == o) return false;
        return takenCount(o, p.getUniqueId()) >= lim;
    }

    /** True while you still have to pick, or if changing origin is allowed. */
    public boolean canChoose(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.pendingChoice && d.origin == null) return true;
        return d.origin != null && plugin.getConfig().getBoolean("origins.allow-change", false);
    }

    public boolean choose(Player p, Origin o) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (!canChoose(p)) { Text.msg(p, Msg.t("choose-locked")); return false; }
        if (d.origin == o) { Text.msg(p, Msg.t("choose-same")); return false; }
        if (isFull(o, p)) { Text.msg(p, Msg.t("choose-full", "limit", String.valueOf(limit()))); return false; }
        d.origin = o;
        d.pendingChoice = false;
        d.initialized = true;
        if (d.mana < 1) d.mana = 1;
        d.weaponClaimed = false;
        plugin.data().markDirty();
        Text.msg(p, Msg.t("choose-done", "origin", o.colored()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        checkWeapon(p, d);
        return true;
    }

    // ---------- Admin helpers ----------

    public void setOrigin(Player p, Origin o) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        d.origin = o;
        d.pendingChoice = false;
        d.initialized = true;
        if (d.mana < 1) d.mana = 1;
        d.weaponClaimed = false;
        plugin.data().markDirty();
        checkWeapon(p, d);
        Text.msg(p, Msg.t("origin-set", "origin", o.colored()));
    }

    public void clearOrigin(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        d.origin = null;
        d.pendingChoice = false;
        d.mana = 0;
        d.weaponClaimed = false;
        plugin.data().markDirty();
        Text.msg(p, Msg.t("origin-removed"));
    }

    public void resetPlayer(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        d.origin = randomOrigin();
        d.pendingChoice = false;
        d.initialized = true;
        d.mana = Math.max(0, Math.min(MAX, plugin.getConfig().getInt("mana.start", 1)));
        d.weaponClaimed = false;
        plugin.data().markDirty();
        Text.msg(p, Msg.t("data-reset", "origin", d.origin.colored()));
    }

    public void unregisterRecipe() { Bukkit.removeRecipe(recipeKey); }

    // ---------- Core logic ----------

    public int tier(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) return 0;
        if (d.origin == Origin.CALAMITY && !plugin.calamity().allowed(p)) return 0;   // operator-only
        return Math.max(1, Math.min(MAX, d.mana));
    }

    public Origin randomOrigin() {
        List<Origin> all = Origin.playable();
        return all.get(ThreadLocalRandom.current().nextInt(all.size()));
    }

    /** @return false if the player is already at max mana. */
    public boolean gain(Player p, int amount, String reason) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) {
            d.weaponClaimed = false;
            if (chooseMode()) {
                d.mana = d.pendingChoice ? Math.min(MAX, d.mana + amount) : 1;
                d.pendingChoice = true;
                Text.msg(p, Msg.t("choose-prompt"));
                plugin.gui().openChoose(p);
            } else {
                d.origin = randomOrigin();
                d.mana = 1;
                Text.msg(p, Msg.t("origin-awaken", "origin", d.origin.colored(), "reason", reason));
            }
        } else {
            if (d.mana >= MAX) {
                Text.msg(p, Msg.t("mana-max"));
                return false;
            }
            d.mana = Math.min(MAX, d.mana + amount);
            Text.msg(p, Msg.t("mana-gain", "amount", String.valueOf(amount), "reason", reason, "mana", String.valueOf(d.mana), "max", String.valueOf(MAX), "tier", String.valueOf(tier(p))));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        plugin.data().markDirty();
        checkWeapon(p, d);
        return true;
    }

    public void setMana(Player p, int n) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        n = Math.max(0, Math.min(MAX, n));
        if (d.origin == null) {
            if (n == 0) return;
            d.origin = randomOrigin();
        }
        d.mana = n;
        plugin.data().markDirty();
        checkWeapon(p, d);
    }

    public void checkWeapon(Player p, DataManager.PlayerData d) {
        if (d.origin != null && d.origin != Origin.CALAMITY && d.mana >= MAX && !d.weaponClaimed) {
            d.weaponClaimed = true;
            plugin.weapons().give(p);
            if (!plugin.vanish().isVanished(p)) {
                Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t("weapon-earned-broadcast", "player", p.getName(), "origin", d.origin.colored())));
            }
            plugin.data().markDirty();
        }
    }

    private void loseOnDeath(Player v) {
        DataManager.PlayerData d = plugin.data().get(v.getUniqueId());
        if (d.origin == null || d.origin == Origin.CALAMITY) return;   // the Calamity never loses its power
        int loss = plugin.getConfig().getInt("mana.loss-on-death", 1);
        if (d.mana - loss < 0) {
            Text.msg(v, Msg.t("power-lost"));
            d.origin = null;
            d.mana = 0;
            d.weaponClaimed = false;
        } else {
            d.mana -= loss;
            Text.msg(v, Msg.t("mana-lost", "loss", String.valueOf(loss), "mana", String.valueOf(d.mana), "max", String.valueOf(MAX)));
        }
        plugin.data().markDirty();
    }

    private boolean killCooldownOk(Player killer, Player victim) {
        long cd = plugin.getConfig().getLong("mana.kill-cooldown-seconds", 600) * 1000L;
        String key = killer.getUniqueId() + "|" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = killCooldowns.get(key);
        if (last != null && now - last < cd) return false;
        killCooldowns.put(key, now);
        return true;
    }

    // ---------- Events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        p.discoverRecipe(recipeKey);
        if (!d.initialized) {
            d.initialized = true;
            d.mana = Math.max(0, Math.min(MAX, plugin.getConfig().getInt("mana.start", 1)));
            if (chooseMode()) { d.origin = null; d.pendingChoice = true; } else { d.origin = randomOrigin(); }
            plugin.data().markDirty();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (d.origin != null) {
                    Text.msg(p, Msg.t("welcome", "origin", d.origin.colored(), "description", d.origin.description()));
                } else {
                    Text.msg(p, Msg.t("choose-prompt"));
                    if (p.isOnline()) plugin.gui().openChoose(p);
                }
                Text.msg(p, Msg.t("welcome-tip"));
            }, 20L);
        } else if (d.pendingChoice && d.origin == null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || d.origin != null) return;
                Text.msg(p, Msg.t("choose-prompt"));
                plugin.gui().openChoose(p);
            }, 40L);
        }
        giveBackWeapons(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        if (k == null) k = plugin.combat().combatLogKiller(v);

        // Collect bound weapons so they are not lost / stolen
        List<ItemStack> weapons = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack i = it.next();
            if (plugin.weapons().isOwnedBy(i, v.getUniqueId())) {
                weapons.add(i);
                it.remove();
            }
        }

        loseOnDeath(v);

        if (plugin.data().get(v.getUniqueId()).origin != null && !weapons.isEmpty()) {
            pendingWeapons.put(v.getUniqueId(), weapons);
        }

        if (k != null && !k.equals(v) && killCooldownOk(k, v)) {
            gain(k, plugin.getConfig().getInt("mana.per-kill", 1), Msg.t("reason-kill", "victim", v.getName()));
        } else if (k != null && !k.equals(v)) {
            Text.msg(k, Msg.t("no-mana-recent-kill", "victim", v.getName()));
        }
        plugin.combat().clear(v);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> giveBackWeapons(p), 2L);
    }

    private void giveBackWeapons(Player p) {
        List<ItemStack> list = pendingWeapons.remove(p.getUniqueId());
        if (list == null) return;
        for (ItemStack w : list) {
            for (ItemStack left : p.getInventory().addItem(w).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!isCrystal(it)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin != null && d.mana >= MAX) {
            Text.msg(p, Msg.t("mana-max"));
            return;
        }
        if (gain(p, plugin.getConfig().getInt("mana.crystal-gain", 1), Msg.t("reason-crystal"))) {
            if (it.getAmount() > 1) {
                ItemStack rest = it.clone();
                rest.setAmount(it.getAmount() - 1);
                p.getInventory().setItemInMainHand(rest);
            } else {
                p.getInventory().setItemInMainHand(null);
            }
        }
    }

    /** Stop Mana Crystals (prismarine crystals) being used in any other recipe. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof Keyed k && k.getKey().equals(recipeKey)) return;
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (isCrystal(i)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}
