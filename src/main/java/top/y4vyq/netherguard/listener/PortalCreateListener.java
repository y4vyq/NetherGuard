package top.y4vyq.netherguard.listener;

import java.util.List;

import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;

import top.y4vyq.netherguard.NetherGuardPlugin;
import top.y4vyq.netherguard.service.RegionService;

public final class PortalCreateListener implements Listener {

    private final NetherGuardPlugin plugin;
    private final RegionService regionService;

    public PortalCreateListener(NetherGuardPlugin plugin, RegionService regionService) {
        this.plugin = plugin;
        this.regionService = regionService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (!plugin.getConfig().getBoolean("event.cancel-portal-create", true)) return;

        List<BlockState> blocks = event.getBlocks();
        if (blocks == null || blocks.isEmpty()) return;

        List<String> reasons = plugin.getConfig().getStringList("event.intercept-reasons");
        if (!reasons.isEmpty()) {
            String reasonName = event.getReason() != null ? event.getReason().name() : "";
            if (!reasons.contains(reasonName)) return;
        }

        World world = blocks.get(0).getWorld();
        if (world == null) return;
        String worldName = world.getName();

        for (int i = 0, n = blocks.size(); i < n; i++) {
            BlockState block = blocks.get(i);
            if (regionService.isRestricted(worldName, block.getX(), block.getY(), block.getZ())) {
                event.setCancelled(true);

                if (plugin.getConfig().getBoolean("event.notify-player", true)) {
                    Entity entity = event.getEntity();
                    if (entity instanceof Player player) {
                        player.sendMessage(plugin.lang().prefixed("event.portal-create-blocked"));
                    }
                }

                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("[DEBUG] Cancelled PortalCreateEvent @ "
                            + worldName + " " + block.getX() + "," + block.getY() + "," + block.getZ());
                }
                return;
            }
        }
    }
}