package com.wincors.smp;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Freeze, mute, chat lock and lockdown state (in memory, cleared on restart). */
public final class AdminTools implements Listener {
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> muted = new HashSet<>();
    private boolean chatLocked;
    private boolean lockdown;
    private String lockdownReason = "Maintenance";

    /** @return true if now frozen */
    public boolean toggleFreeze(Player p) {
        if (frozen.remove(p.getUniqueId())) return false;
        frozen.add(p.getUniqueId());
        return true;
    }

    /** @return true if now muted */
    public boolean toggleMute(Player p) {
        if (muted.remove(p.getUniqueId())) return false;
        muted.add(p.getUniqueId());
        return true;
    }

    public boolean isChatLocked() { return chatLocked; }
    public void setChatLocked(boolean v) { chatLocked = v; }
    public boolean isLockdown() { return lockdown; }
    public String lockdownReason() { return lockdownReason; }
    public void setLockdown(boolean v, String reason) {
        lockdown = v;
        if (reason != null && !reason.isBlank()) lockdownReason = reason;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location l = from.clone();
            l.setYaw(to.getYaw());
            l.setPitch(to.getPitch());
            e.setTo(l);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (muted.contains(p.getUniqueId())) {
            e.setCancelled(true);
            Text.msg(p, Msg.t("muted"));
        } else if (chatLocked && !p.hasPermission("wincors.admin")) {
            e.setCancelled(true);
            Text.msg(p, Msg.t("chat-locked"));
        }
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent e) {
        if (lockdown && !e.getPlayer().hasPermission("wincors.admin")) {
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, Text.c(Msg.t("lockdown-kick", "reason", lockdownReason)));
        }
    }
}
