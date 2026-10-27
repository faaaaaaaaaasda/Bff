package com.wincors.smp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** All menus + the "Origin Core" menu item. */
public final class GuiManager implements Listener {
    private static final int[] SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    private final WincorsSMP plugin;
    private final NamespacedKey menuKey;

    public GuiManager(WincorsSMP plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "menu_item");
    }

    // =================== helpers ===================

    static List<Component> lore(String... lines) {
        List<Component> l = new ArrayList<>();
        for (String s : lines) l.add(Text.item(s));
        return l;
    }

    static ItemStack make(Material m, String name, boolean glint, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        if (lore.length > 0) meta.lore(lore(lore));
        if (glint) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    static ItemStack pane() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(" "));
        it.setItemMeta(m);
        return it;
    }

    static ItemStack head(Player t, String... lines) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(t);
        sm.displayName(Text.item("&f" + t.getName()));
        sm.lore(lore(lines));
        it.setItemMeta(sm);
        return it;
    }

    static Material icon(Origin o) { return o.icon(); }

    static String bar(int mana) {
        StringBuilder sb = new StringBuilder("&b");
        for (int i = 0; i < 10; i++) {
            if (i == mana) sb.append("&8");
            sb.append("\u25A0");
        }
        return sb.append(" &f").append(mana).append("/10").toString();
    }

    static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String s : m.name().toLowerCase().split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    private void back(Gui g, int slot, Runnable r) {
        g.set(slot, make(Material.ARROW, "&7\u00AB Back", false), e -> r.run());
    }

    private boolean adminOk(Player p) {
        if (!p.hasPermission("wincors.admin")) {
            Text.msg(p, Msg.t("no-permission"));
            return false;
        }
        return true;
    }

    // =================== main menu ===================

    public void openMain(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        Gui g = new Gui(Text.c("&6&lWincors &8\u00BB &7Menu"), 4);

        if (d.origin == null && d.pendingChoice) {
            g.set(10, make(Material.NETHER_STAR, "&6&lChoose your origin", true,
                "&7Pick the origin you want to play.", "", "&eClick &7to choose"), e -> openChoose(p));
        } else if (d.origin == null) {
            g.set(10, make(Material.BARRIER, "&cNo origin", false,
                "&7You have no power right now.", "&7Kill a player or use a Mana Crystal", "&7to awaken a new origin."));
        } else {
            g.set(10, make(icon(d.origin), d.origin.title(), true,
                "&8\u00BB &7&o" + d.origin.tagline(), "&7" + d.origin.description(), "",
                "&7Power tier: &6" + plugin.mana().tier(p),
                "&7Mana: " + bar(d.mana), "",
                "&eClick &7to view your powers"), e -> openPowers(p, d.origin));
        }

        g.set(12, make(Material.PRISMARINE_CRYSTALS, "&d&lMana Crystal", true,
            "&7Right-click to gain &b1 mana&7.", "&7Also gained by killing players.", "",
            "&eClick &7to see the recipe"), e -> openRecipe(p));

        g.set(14, make(Material.BOOK, "&eAll origins", false,
            "&7Browse every origin and its", "&710 powers.", "", "&eClick &7to browse"), e -> openOrigins(p));

        if (d.origin != null && d.origin != Origin.CALAMITY) {
            boolean has = d.weaponClaimed;
            g.set(16, make(WeaponManager.materialFor(d.origin), "&6" + WeaponManager.weaponName(d.origin), has,
                "&7Legendary weapon of your origin.",
                has ? "&aYou have earned it." : "&cReach &b10 mana &cto earn it.",
                "&7Only works at max mana."));
        }

        if (d.origin != null && plugin.mana().canChoose(p)) {
            g.set(29, make(Material.COMPASS, "&eChange origin", false, "&7Pick a different origin.", "", "&eClick &7to choose"), e -> openChoose(p));
        }
        boolean tagged = plugin.combat().isTagged(p);
        g.set(20, make(Material.CLOCK, tagged ? "&cIn combat" : "&aNot in combat", false,
            tagged ? "&7Time left: &c" + plugin.combat().remaining(p) + "s" : "&7You can use everything freely."));
        boolean open = plugin.end().isOpen();
        g.set(22, make(Material.END_PORTAL_FRAME, "&5The End", false,
            open ? "&aOpen" : "&cClosed", "&7Controlled by the admins."));
        boolean egg = plugin.egg().hasEgg(p);
        g.set(24, make(Material.DRAGON_EGG, "&5Dragon Egg", egg,
            egg ? "&aActive: 20 hearts + faster cooldowns" : "&7Carry the egg for 20 hearts",
            egg ? "" : "&7and shorter power cooldowns."));

        if (p.hasPermission("wincors.admin")) {
            g.set(35, make(Material.COMMAND_BLOCK, "&c&lAdmin panel", false, "&7Manage players, the End and more."), e -> openAdmin(p));
        }
        g.set(31, make(Material.BARRIER, "&cClose", false), e -> p.closeInventory());
        g.fill(pane(), 0);
        g.open(p);
    }

    // =================== powers ===================

    public void openPowers(Player p, Origin o) {
        if (o == Origin.CALAMITY) { openCalamity(p); return; }
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        boolean own = d.origin == o;
        int tier = own ? plugin.mana().tier(p) : 10;
        Gui g = new Gui(Text.c(o.title() + " &8\u00BB &7Powers" + (own ? "" : " &8(preview)")), 5);

        List<Power> list = PowerRegistry.get(o);
        List<Power> acts = own ? plugin.powers().actives(p) : List.of();
        Power selected = (!acts.isEmpty()) ? acts.get(plugin.powers().selectedIndex(p, acts.size())) : null;

        g.set(4, make(icon(o), o.title(), true, "&7" + o.description(),
            own ? "&7Mana: " + bar(d.mana) : "&7Previewing this origin"));

        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Power pw = list.get(i);
            int slot = SLOTS[i];
            boolean unlocked = pw.tier <= tier;
            if (!unlocked) {
                g.set(slot, make(Material.BARRIER, "&8" + pw.name, false,
                    "&8Tier " + pw.tier + " \u2022 &cLocked", "", "&7" + pw.desc, "",
                    "&c\u2716 &7Reach power tier &6" + pw.tier + " &7to unlock"));
                continue;
            }
            Material mat = pw.icon != null ? pw.icon : switch (pw.type) {
                case ACTIVE -> Material.NETHER_STAR;
                case FLAG -> Material.SHIELD;
                case POTION -> Material.POTION;
            };
            boolean isSel = pw == selected;
            String kind = switch (pw.type) {
                case ACTIVE -> "&d\u26A1 Active";
                case FLAG -> "&6\u2726 Special";
                case POTION -> "&b\u2726 Passive";
            };
            List<String> lines = new ArrayList<>();
            lines.add("&8Tier " + pw.tier + " \u2022 " + kind);
            lines.add("");
            lines.add("&7" + pw.desc);
            lines.add("");
            if (pw.type == Power.Type.ACTIVE) {
                lines.add("&8\u25B8 &7Cooldown: &f" + pw.cooldown + "s");
                if (own) {
                    lines.add("");
                    lines.add(isSel ? "&a\u2714 Selected" : "&8\u25B8 &eLeft-click &7to select");
                    lines.add("&8\u25B8 &eRight-click &7to use now");
                }
            } else {
                lines.add("&8\u25B8 &7Always active");
            }
            ItemStack item = make(mat, (isSel ? "&a&l" : o.color() + "&l") + pw.name, isSel, lines.toArray(new String[0]));
            if (own && pw.type == Power.Type.ACTIVE) {
                int idx = acts.indexOf(pw);
                g.set(slot, item, e -> {
                    plugin.powers().select(p, idx);
                    if (e.isRightClick()) {
                        p.closeInventory();
                        plugin.powers().castSelected(p);
                    } else {
                        openPowers(p, o);
                    }
                });
            } else {
                g.set(slot, item);
            }
        }
        back(g, 40, own ? () -> openMain(p) : (plugin.mana().canChoose(p) ? () -> openChoose(p) : () -> openOrigins(p)));
        g.fill(pane(), 0);
        g.open(p);
    }

    public void openOrigins(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        Gui g = new Gui(Text.c("&6&lOrigins &8\u00BB &7Browse"), 5);
        Origin[] all = Origin.playable().toArray(new Origin[0]);
        for (int i = 0; i < all.length; i++) {
            Origin o = all[i];
            boolean mine = d.origin == o;
            g.set(SLOTS[i], make(icon(o), o.title(), mine,
                "&8\u00BB &7&o" + o.tagline(), "&7" + o.description(), "",
                mine ? "&a\u2714 Your origin" : "&7Click to preview powers",
                "&7Weapon: &6" + WeaponManager.weaponName(o)), e -> openPowers(p, o));
        }
        back(g, 40, () -> openMain(p));
        g.fill(pane(), 0);
        g.open(p);
    }

    // =================== choose origin ===================

    public void openChoose(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        boolean changing = d.origin != null;
        Gui g = new Gui(Text.c(changing ? "&6&lChange your origin" : "&6&lChoose your origin"), 5);
        Origin[] all = Origin.playable().toArray(new Origin[0]);
        int limit = plugin.mana().limit();
        for (int i = 0; i < all.length; i++) {
            Origin o = all[i];
            boolean mine = d.origin == o;
            boolean full = plugin.mana().isFull(o, p);
            List<String> lore = new ArrayList<>();
            lore.add("&8\u00BB &7&o" + o.tagline());
            lore.add("&7" + o.description());
            lore.add("");
            lore.add("&7Weapon: &6" + o.weapon().name);
            if (limit > 0) lore.add("&7Players: &f" + (plugin.mana().takenCount(o, p.getUniqueId()) + (mine ? 1 : 0)) + "&7/&f" + limit);
            lore.add("");
            lore.add(mine ? "&a\u2714 Your current origin" : full ? "&cThis origin is full" : "&eLeft-click: choose");
            lore.add("&eRight-click: preview powers");
            g.set(SLOTS[i], make(icon(o), o.title(), mine, lore.toArray(new String[0])), e -> {
                if (e.isRightClick()) openPowers(p, o);
                else if (!mine && !full) openConfirm(p, o);
            });
        }
        if (changing) back(g, 40, () -> openMain(p));
        g.fill(pane(), 0);
        g.open(p);
    }

    public void openConfirm(Player p, Origin o) {
        Gui g = new Gui(Text.c("&6Confirm: " + o.colored()), 3);
        g.set(11, make(Material.LIME_CONCRETE, "&a&lConfirm", false, "&7Become " + o.colored() + "&7."), e -> {
            if (plugin.mana().choose(p, o)) p.closeInventory(); else openChoose(p);
        });
        g.set(13, make(icon(o), o.title(), true, "&7" + o.description(), "", "&7Weapon: &6" + o.weapon().name));
        g.set(15, make(Material.RED_CONCRETE, "&cCancel", false), e -> openChoose(p));
        g.fill(pane(), 0);
        g.open(p);
    }

    // =================== calamity (operator-only) ===================

    public void openCalamity(Player p) {
        if (!plugin.calamity().allowed(p)) { Text.msg(p, "&cCalamity powers are operator-only."); return; }
        Gui g = new Gui(Text.c("&4&l\u2620 Calamity &8\u00BB &7Operator powers"), 6);
        List<Power> acts = plugin.powers().actives(p);
        int sel = acts.isEmpty() ? -1 : plugin.powers().selectedIndex(p, acts.size());
        for (int i = 0; i < acts.size() && i < 36; i++) {
            Power pw = acts.get(i);
            boolean isSel = i == sel;
            int idx = i;
            g.set(i, make(pw.icon != null ? pw.icon : Material.NETHER_STAR, (isSel ? "&a&l" : "&c&l") + (i + 1) + ". " + pw.name, isSel,
                "&7" + pw.desc, "", "&8\u25B8 &7Cooldown: &f" + pw.cooldown + "s",
                isSel ? "&a\u2714 Selected" : "&8\u25B8 &eLeft-click &7to select",
                "&8\u25B8 &eRight-click &7to use now"), e -> {
                    plugin.powers().select(p, idx);
                    if (e.isRightClick()) { p.closeInventory(); plugin.powers().castSelected(p); } else openCalamity(p);
                });
        }
        g.set(47, make(Material.RECOVERY_COMPASS, "&a&lRewind", false, "&7Undo the destruction of your last move.",
            "&7Stored moves: &f" + plugin.calamity().undoSize(p), "", "&eClick &7to rewind"), e -> { plugin.calamity().undoLast(p); openCalamity(p); });
        g.set(49, make(Material.DRAGON_HEAD, "&4&l\u2620 The Calamity", true, "&7Operator-only origin.", "&7Holding the &fMace &7makes every move stronger.", "",
            "&7Use &f/calamity <1-30> &7to cast by number.", "&7Walking Calamity: " + (plugin.calamity().auraOn(p) ? "&aON" : "&cOFF")));
        g.set(51, make(Material.BARRIER, "&cClose", false), e -> p.closeInventory());
        g.fill(pane(), 36);
        g.open(p);
    }

    // =================== recipe ===================

    public void openRecipe(Player p) {
        Gui g = new Gui(Text.c("&d&lMana Crystal &8\u00BB &7Recipe"), 5);
        int[] grid = {11, 12, 13, 20, 21, 22, 29, 30, 31};
        List<String> shape = plugin.mana().recipeShape();
        Map<Character, Material> ing = plugin.mana().recipeIngredients();
        Map<Material, Integer> counts = new java.util.LinkedHashMap<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                char ch = shape.get(r).charAt(c);
                if (ch == ' ') continue;
                Material m = ing.get(ch);
                if (m == null) continue;
                counts.merge(m, 1, Integer::sum);
                g.set(grid[r * 3 + c], make(m, "&f" + pretty(m), false, "&7Ingredient"));
            }
        }
        g.set(23, make(Material.ARROW, "&7\u2192", false));
        List<String> res = new ArrayList<>();
        res.add("&7Makes &f1 &7Mana Crystal");
        res.add("&7Right-click it for &b+1 mana&7.");
        res.add("");
        res.add("&7You need:");
        for (Map.Entry<Material, Integer> en : counts.entrySet()) res.add("&8- &f" + en.getValue() + "x " + pretty(en.getKey()));
        g.set(24, make(Material.PRISMARINE_CRYSTALS, "&d&lMana Crystal", true, res.toArray(new String[0])));
        back(g, 40, () -> openMain(p));
        g.fill(pane(), 0);
        g.open(p);
    }

    // =================== admin ===================

    public void openAdmin(Player admin) {
        if (!adminOk(admin)) return;
        Gui g = new Gui(Text.c("&c&lAdmin &8\u00BB &7Players"), 6);
        int i = 0;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (i >= 45) break;
            DataManager.PlayerData d = plugin.data().get(t.getUniqueId());
            g.set(i++, head(t,
                "&7Origin: " + (d.origin == null ? "&cnone" : d.origin.colored()),
                "&7Mana: " + bar(d.mana), "", "&eClick &7to manage"), e -> openAdminPlayer(admin, t));
        }
        boolean open = plugin.end().isOpen();
        g.set(47, make(Material.END_PORTAL_FRAME, "&5The End: " + (open ? "&aOPEN" : "&cCLOSED"), open,
            "&eClick &7to " + (open ? "close" : "open") + " the End"), e -> {
            plugin.end().setOpen(!plugin.end().isOpen(), admin);
            openAdmin(admin);
        });
        boolean mace = plugin.data().getMaceId() != null;
        g.set(49, make(Material.MACE, "&6The Mace", mace,
            mace ? "&aExists on the server" : "&7Not crafted yet", "", "&cShift-click &7to reset the mace registry"), e -> {
            if (e.isShiftClick()) {
                plugin.data().setMaceId(null);
                Text.msg(admin, "&aMace registry reset.");
                openAdmin(admin);
            }
        });
        g.set(51, make(Material.COMPARATOR, "&bReload config", false, "&7Reloads config.yml and the recipe"), e -> {
            plugin.reloadAll();
            Text.msg(admin, "&aAll config files reloaded.");
            openAdmin(admin);
        });
        g.set(53, make(Material.BARRIER, "&cClose", false), e -> admin.closeInventory());
        g.fill(pane(), 45);
        g.open(admin);
    }

    public void openAdminPlayer(Player admin, Player t) {
        if (!adminOk(admin)) return;
        if (!t.isOnline()) {
            Text.msg(admin, "&cThat player is offline.");
            openAdmin(admin);
            return;
        }
        DataManager.PlayerData d = plugin.data().get(t.getUniqueId());
        Gui g = new Gui(Text.c("&c&lAdmin &8\u00BB &f" + t.getName()), 5);
        Runnable refresh = () -> openAdminPlayer(admin, t);

        g.set(4, head(t, "&7Origin: " + (d.origin == null ? "&cnone" : d.origin.colored()),
            "&7Mana: " + bar(d.mana), "&7Tier: &6" + plugin.mana().tier(t)));

        g.set(10, make(Material.RED_CONCRETE, "&c-1 mana", false), e -> { plugin.mana().setMana(t, d.mana - 1); refresh.run(); });
        g.set(11, make(Material.PRISMARINE_CRYSTALS, "&bMana: &f" + d.mana, false, "&7Current mana"));
        g.set(12, make(Material.LIME_CONCRETE, "&a+1 mana", false), e -> { plugin.mana().setMana(t, d.mana + 1); refresh.run(); });
        g.set(14, make(Material.GOLD_BLOCK, "&6Set to max (10)", false), e -> { plugin.mana().setMana(t, 10); refresh.run(); });
        g.set(15, make(Material.COAL_BLOCK, "&8Set to 0", false), e -> { plugin.mana().setMana(t, 0); refresh.run(); });

        g.set(19, make(Material.HEART_OF_THE_SEA, "&eChange origin", false, "&7Pick a specific origin"), e -> openOriginPicker(admin, t));
        g.set(20, make(Material.ENDER_EYE, "&eRandom origin", false), e -> { plugin.mana().setOrigin(t, plugin.mana().randomOrigin()); refresh.run(); });
        g.set(21, make(Material.BARRIER, "&cRemove origin", false), e -> { plugin.mana().clearOrigin(t); refresh.run(); });
        g.set(23, make(Material.NETHERITE_SWORD, "&6Give weapon", false, "&7Gives their legendary weapon"), e -> {
            if (d.origin == null) Text.msg(admin, "&cThat player has no origin."); else plugin.weapons().give(t);
        });
        g.set(24, make(Material.ANVIL, "&6Reset weapon claim", false, "&7Lets them earn it again at 10 mana"), e -> {
            d.weaponClaimed = false;
            plugin.data().markDirty();
            plugin.mana().checkWeapon(t, d);
            refresh.run();
        });
        g.set(25, make(Material.CLOCK, "&bReset cooldowns", false), e -> { plugin.powers().resetCooldowns(t); plugin.combat().resetPearl(t); Text.msg(admin, "&aCooldowns reset."); });

        g.set(28, make(Material.PRISMARINE_CRYSTALS, "&dGive Mana Crystal", true), e -> {
            for (ItemStack left : t.getInventory().addItem(plugin.mana().createCrystal(1)).values()) t.getWorld().dropItemNaturally(t.getLocation(), left);
            Text.msg(admin, "&aGave a Mana Crystal.");
        });
        g.set(29, make(Material.IRON_SWORD, "&cClear combat tag", false), e -> { plugin.combat().clear(t); Text.msg(admin, "&aCombat tag cleared."); });
        g.set(30, make(Material.ENDER_PEARL, "&5Teleport to player", false), e -> { admin.closeInventory(); admin.teleport(t); });
        g.set(31, make(Material.TNT, "&4Full reset", false, "&7New random origin, start mana,", "&7weapon claim cleared.", "", "&cShift-click to confirm"), e -> {
            if (!e.isShiftClick()) { Text.msg(admin, "&cShift-click to confirm the reset."); return; }
            plugin.mana().resetPlayer(t);
            refresh.run();
        });
        back(g, 40, () -> openAdmin(admin));
        g.fill(pane(), 0);
        g.open(admin);
    }

    public void openOriginPicker(Player admin, Player t) {
        if (!adminOk(admin)) return;
        Gui g = new Gui(Text.c("&c&lAdmin &8\u00BB &7Pick origin for " + t.getName()), 5);
        Origin[] all = Origin.playable().toArray(new Origin[0]);
        for (int i = 0; i < all.length; i++) {
            Origin o = all[i];
            g.set(SLOTS[i], make(icon(o), o.title(), false, "&7" + o.description(), "", "&eClick &7to set"), e -> {
                plugin.mana().setOrigin(t, o);
                openAdminPlayer(admin, t);
            });
        }
        back(g, 40, () -> openAdminPlayer(admin, t));
        g.fill(pane(), 0);
        g.open(admin);
    }

    // =================== Origin Core item ===================

    public ItemStack menuItem() {
        List<String> coreLore = plugin.getConfig().getStringList("items.origin-core.lore");
        ItemStack it = make(Material.NETHER_STAR, plugin.getConfig().getString("items.origin-core.name", "&6&lOrigin Core"), true, coreLore.toArray(new String[0]));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
        ResourcePackManager.applyModel(plugin, m, "origin_core");
        it.setItemMeta(m);
        return it;
    }

    public boolean isMenuItem(ItemStack it) {
        return it != null && it.getType() == Material.NETHER_STAR && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.BYTE);
    }

    public void ensureMenuItem(Player p) {
        if (!plugin.getConfig().getBoolean("gui.menu-item", true)) return;
        for (ItemStack i : p.getInventory().getContents()) if (isMenuItem(i)) return;
        if (p.getInventory().getItem(8) == null) p.getInventory().setItem(8, menuItem());
        else p.getInventory().addItem(menuItem());
    }

    // =================== events ===================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getInventory())) {
            Bukkit.getScheduler().runTask(plugin, () -> g.click(e));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuItem(e.getItem())) return;
        if (!plugin.getConfig().getBoolean("controls.menu-on-right-click", true)) return;
        e.setCancelled(true);
        openMain(e.getPlayer());
    }

    /** The drop key while holding the Origin Core opens the menu instead of dropping it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (!isMenuItem(e.getItemDrop().getItemStack())) return;
        if (!plugin.getConfig().getBoolean("controls.menu-on-drop", true)) return;
        e.setCancelled(true);
        openMain(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensureMenuItem(p), 5L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> ensureMenuItem(p), 5L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isMenuItem);
    }
}
