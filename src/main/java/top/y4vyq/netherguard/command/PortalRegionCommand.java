package top.y4vyq.netherguard.command;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import top.y4vyq.netherguard.NetherGuardPlugin;
import top.y4vyq.netherguard.i18n.LanguageManager;
import top.y4vyq.netherguard.model.Region;
import top.y4vyq.netherguard.service.RegionService;

public final class PortalRegionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "pos1", "pos2", "create", "delete", "list", "info", "reload", "cancel");
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final NetherGuardPlugin plugin;
    private final RegionService regionService;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public PortalRegionCommand(NetherGuardPlugin plugin, RegionService regionService) {
        this.plugin = plugin;
        this.regionService = regionService;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    private static final class Selection {
        Location pos1;
        Location pos2;
    }

    /* ------------------------------------------------------------------ */
    /* 主入口                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            return handleReload(sender);
        }

        if (!(sender instanceof Player player)) {
            if (sender != null) {
                sender.sendMessage(lang().prefixed("general.player-only"));
            }
            return true;
        }

        if (!player.hasPermission("netherguard.use")) {
            player.sendMessage(lang().prefixed("general.no-permission"));
            return true;
        }

        switch (sub) {
            case "pos1":   return handlePos(player, label, 1);
            case "pos2":   return handlePos(player, label, 2);
            case "create": return handleCreate(player, label, args);
            case "delete": return handleDelete(player, label, args);
            case "list":   return handleList(player, label, args);
            case "info":   return handleInfo(player, label, args);
            case "cancel": return handleCancel(player);
            default:
                sendHelp(player, label);
                return true;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 子命令                                                              */
    /* ------------------------------------------------------------------ */

    private boolean handlePos(Player player, String label, int which) {
        Location loc = player.getLocation();
        Selection sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Selection());
        if (which == 1) sel.pos1 = loc.clone(); else sel.pos2 = loc.clone();

        Map<String, String> ph = new HashMap<>();
        ph.put("which", "Pos" + which);
        ph.put("x", String.valueOf(loc.getBlockX()));
        ph.put("y", String.valueOf(loc.getBlockY()));
        ph.put("z", String.valueOf(loc.getBlockZ()));
        ph.put("world", loc.getWorld().getName());
        player.sendMessage(lang().prefixed("pos.set", ph));
        return true;
    }

    private boolean handleCreate(Player player, String label, String[] args) {
        if (!player.hasPermission("netherguard.create")) {
            player.sendMessage(lang().prefixed("general.no-permission"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(lang().prefixed("create.usage", "label", label));
            return true;
        }

        String name = args[1];
        if (name.length() > 32) {
            player.sendMessage(lang().prefixed("create.name-too-long"));
            return true;
        }

        Selection sel = selections.get(player.getUniqueId());
        if (sel == null || sel.pos1 == null || sel.pos2 == null) {
            player.sendMessage(lang().prefixed("create.no-selection", "label", label));
            return true;
        }
        if (!sel.pos1.getWorld().equals(sel.pos2.getWorld())) {
            player.sendMessage(lang().prefixed("create.different-world"));
            return true;
        }

        if (!player.hasPermission("netherguard.limit.bypass")) {
            int limit = plugin.getConfig().getInt("limits.max-regions-per-player", 5);
            if (limit > 0 && regionService.countByOwner(player.getUniqueId()) >= limit) {
                player.sendMessage(lang().prefixed("create.limit-reached", "limit", limit));
                return true;
            }
        }

        if (regionService.nameExists(player.getUniqueId(), name)) {
            player.sendMessage(lang().prefixed("create.name-exists"));
            return true;
        }

        World world = sel.pos1.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        if (sel.pos1.getBlockY() < minY || sel.pos1.getBlockY() > maxY
                || sel.pos2.getBlockY() < minY || sel.pos2.getBlockY() > maxY) {
            player.sendMessage(lang().prefixed("create.height-out-of-range",
                    Map.of("min", String.valueOf(minY), "max", String.valueOf(maxY))));
            return true;
        }

        boolean use2d = plugin.getConfig().getBoolean("limits.use-2d", false);
        Region region = Region.create(
                name,
                player.getUniqueId(),
                player.getName(),
                world.getName(),
                sel.pos1.getBlockX(), sel.pos1.getBlockY(), sel.pos1.getBlockZ(),
                sel.pos2.getBlockX(), sel.pos2.getBlockY(), sel.pos2.getBlockZ(),
                use2d);

        long timeout = plugin.getConfig().getLong("command.create-timeout-seconds", 5);

        try {
            Region saved = regionService.createSync(region, timeout);
            player.sendMessage(lang().prefixed("create.success", "name", name));
            selections.remove(player.getUniqueId());
            plugin.debug("Created region '" + name + "' (id=" + saved.getId() + ") for " + player.getName());
        } catch (TimeoutException e) {
            player.sendMessage(lang().prefixed("create.timeout", "timeout", timeout));
            plugin.getLogger().warning("Create region timeout for " + player.getName());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            player.sendMessage(lang().prefixed("create.failed", "reason", String.valueOf(cause.getMessage())));
            plugin.getLogger().log(Level.WARNING, "Create region failed for " + player.getName(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            player.sendMessage(lang().prefixed("create.interrupted"));
        }
        return true;
    }

    private boolean handleDelete(Player player, String label, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang().prefixed("delete.usage", "label", label));
            return true;
        }
        String name = args[1];

        Region region = regionService.getByOwnerAndName(player.getUniqueId(), name);
        boolean canDeleteAny = player.hasPermission("netherguard.delete.any");
        if (region == null && canDeleteAny) {
            region = regionService.getAnyByName(name);
        }
        if (region == null) {
            player.sendMessage(lang().prefixed("delete.not-found", "name", name));
            return true;
        }

        boolean isOwn = region.getOwnerUuid().equals(player.getUniqueId());
        boolean allowed = canDeleteAny
                || (player.hasPermission("netherguard.delete.own") && isOwn);
        if (!allowed) {
            player.sendMessage(lang().prefixed("delete.no-permission"));
            return true;
        }

        long timeout = plugin.getConfig().getLong("command.create-timeout-seconds", 5);
        try {
            regionService.deleteSync(region.getId(), timeout);
            player.sendMessage(lang().prefixed("delete.success", "name", region.getName()));
        } catch (TimeoutException e) {
            player.sendMessage(lang().prefixed("delete.timeout"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            player.sendMessage(lang().prefixed("delete.failed", "reason", String.valueOf(cause.getMessage())));
            plugin.getLogger().log(Level.WARNING, "Delete region failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            player.sendMessage(lang().prefixed("delete.interrupted"));
        }
        return true;
    }

    private boolean handleList(Player player, String label, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(lang().prefixed("general.invalid-page"));
                return true;
            }
        }
        if (page < 1) page = 1;

        List<Region> list = regionService.listByOwner(player.getUniqueId());
        if (list.isEmpty()) {
            player.sendMessage(lang().prefixed("list.empty"));
            return true;
        }

        int pageSize = Math.max(1, plugin.getConfig().getInt("command.list-page-size", 10));
        int totalPages = (list.size() + pageSize - 1) / pageSize;
        if (page > totalPages) {
            player.sendMessage(lang().prefixed("list.page-out-of-range", "max", totalPages));
            return true;
        }

        player.sendMessage(lang().prefixed("list.header",
                Map.of("page", String.valueOf(page), "total", String.valueOf(totalPages))));

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, list.size());
        for (int i = start; i < end; i++) {
            Region r = list.get(i);
            player.sendMessage(lang().get("list.entry", Map.of(
                    "index", String.valueOf(i + 1),
                    "name", r.getName(),
                    "world", r.getWorldName(),
                    "x1", String.valueOf(r.getMinX()),
                    "y1", String.valueOf(r.getMinY()),
                    "z1", String.valueOf(r.getMinZ()),
                    "x2", String.valueOf(r.getMaxX()),
                    "y2", String.valueOf(r.getMaxY()),
                    "z2", String.valueOf(r.getMaxZ()))));
        }
        return true;
    }

    private boolean handleInfo(Player player, String label, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang().prefixed("info.usage", "label", label));
            return true;
        }
        String name = args[1];

        Region region = regionService.getByOwnerAndName(player.getUniqueId(), name);
        boolean canViewAny = player.hasPermission("netherguard.delete.any");
        if (region == null && canViewAny) {
            region = regionService.getAnyByName(name);
        }
        if (region == null) {
            player.sendMessage(lang().prefixed("info.not-found", "name", name));
            return true;
        }
        if (!region.getOwnerUuid().equals(player.getUniqueId()) && !canViewAny) {
            player.sendMessage(lang().prefixed("info.no-permission"));
            return true;
        }

        player.sendMessage(lang().prefixed("info.header", "name", region.getName()));
        player.sendMessage(lang().get("info.owner", Map.of(
                "owner", region.getOwnerName() == null ? "?" : region.getOwnerName(),
                "uuid", region.getOwnerUuid().toString())));
        player.sendMessage(lang().get("info.world", "world", region.getWorldName()));
        player.sendMessage(lang().get("info.bounds", Map.of(
                "x1", String.valueOf(region.getMinX()),
                "y1", String.valueOf(region.getMinY()),
                "z1", String.valueOf(region.getMinZ()),
                "x2", String.valueOf(region.getMaxX()),
                "y2", String.valueOf(region.getMaxY()),
                "z2", String.valueOf(region.getMaxZ()))));
        player.sendMessage(lang().get(region.isUse2d() ? "info.mode2d" : "info.mode3d"));
        player.sendMessage(lang().get("info.created", "time",
                DATE_FMT.format(new Date(region.getCreatedAt()))));
        return true;
    }

    private boolean handleCancel(Player player) {
        selections.remove(player.getUniqueId());
        player.sendMessage(lang().prefixed("cancel.success"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
    if (!sender.hasPermission("netherguard.reload")) {
        sender.sendMessage(lang().prefixed("reload.no-permission"));
        return true;
    }
    sender.sendMessage(lang().prefixed("reload.started"));
    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
        boolean ok = regionService.reload();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                plugin.reloadConfig();   // 先重载 config.yml 到内存
                lang().reload();         // 再让 LanguageManager 读取新配置
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Reload config/language failed", ex);
            }
            sender.sendMessage(ok
                    ? lang().prefixed("reload.success")
                    : lang().prefixed("reload.failed"));
        });
    });
    return true;
}

    /* ------------------------------------------------------------------ */
    /* 帮助                                                                */
    /* ------------------------------------------------------------------ */

    private void sendHelp(CommandSender sender, String label) {
        LanguageManager lm = lang();

        // header
        for (String line : lm.getList("help.header", Map.of("label", label))) {
            sender.sendMessage(line);
        }

        // lines：每条根据 permission 过滤
        boolean any = false;
        for (Map<?, ?> entry : lm.getMapList("help.lines")) {
            Object textObj = entry.get("text");
            if (textObj == null) continue;

            Object permObj = entry.get("permission");
            if (permObj != null) {
                String perm = String.valueOf(permObj).trim();
                if (!perm.isEmpty() && !sender.hasPermission(perm)) continue;
            }

            String text = String.valueOf(textObj).replace("{label}", label);
            sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', text));
            any = true;
        }

        // 全部被过滤时不显示空内容
        if (!any) {
            sender.sendMessage(lm.prefixed("help.no-permission"));
        }

        // footer
        for (String line : lm.getList("help.footer", Map.of("label", label))) {
            sender.sendMessage(line);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Tab 补全                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && sender instanceof Player player) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("delete") || sub.equals("info")) {
                List<String> names = regionService.listByOwner(player.getUniqueId())
                        .stream().map(Region::getName).collect(Collectors.toList());
                return filter(names, args[1]);
            }
            if (sub.equals("list")) {
                return filter(Arrays.asList("1", "2", "3", "4", "5"), args[1]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }
}