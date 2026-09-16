package top.y4vyq.netherguard.listener;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

import top.y4vyq.netherguard.NetherGuardPlugin;
import top.y4vyq.netherguard.service.RegionService;

public final class PlayerPortalListener implements Listener {

    private final NetherGuardPlugin plugin;
    private final RegionService regionService;

    public PlayerPortalListener(NetherGuardPlugin plugin, RegionService regionService) {
        this.plugin = plugin;
        this.regionService = regionService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!plugin.getConfig().getBoolean("event.cancel-player-portal", true)) return;

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        if (!regionService.isRestricted(
                to.getWorld().getName(),
                to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            return;
        }

        event.setCancelled(true);

        try {
            event.setCanCreatePortal(false);
        } catch (NoSuchMethodError ignored) {
        }

        if (plugin.getConfig().getBoolean("event.notify-player", true)) {
            event.getPlayer().sendMessage(plugin.lang().prefixed("event.player-portal-blocked"));
        }

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] Cancelled PlayerPortalEvent for "
                    + event.getPlayer().getName() + " -> "
                    + to.getWorld().getName() + " "
                    + to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ());
        }
    }
}