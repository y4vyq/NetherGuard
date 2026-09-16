package top.y4vyq.netherguard;

import java.util.logging.Level;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import top.y4vyq.netherguard.command.PortalRegionCommand;
import top.y4vyq.netherguard.concurrent.WriteExecutor;
import top.y4vyq.netherguard.i18n.LanguageManager;
import top.y4vyq.netherguard.listener.PlayerPortalListener;
import top.y4vyq.netherguard.listener.PortalCreateListener;
import top.y4vyq.netherguard.service.RegionService;
import top.y4vyq.netherguard.storage.SqlStorage;
import top.y4vyq.netherguard.storage.Storage;

public final class NetherGuardPlugin extends JavaPlugin {

    private Storage storage;
    private WriteExecutor writeExecutor;
    private RegionService regionService;
    private PortalRegionCommand commandExecutor;
    private LanguageManager languageManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 语言管理器
        this.languageManager = new LanguageManager(this);
        this.languageManager.load();

        try {
            this.storage = new SqlStorage(this);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Failed to initialize storage, disabling plugin", t);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.writeExecutor = new WriteExecutor(this);
        this.regionService = new RegionService(this, storage, writeExecutor);

        this.commandExecutor = new PortalRegionCommand(this, regionService);
        PluginCommand cmd = getCommand("netherguard");
        if (cmd != null) {
            cmd.setExecutor(commandExecutor);
            cmd.setTabCompleter(commandExecutor);
        } else {
            getLogger().severe("Command 'netherguard' not registered in plugin.yml!");
        }

        getServer().getPluginManager().registerEvents(
                new PortalCreateListener(this, regionService), this);
        getServer().getPluginManager().registerEvents(
                new PlayerPortalListener(this, regionService), this);

        getServer().getScheduler().runTaskAsynchronously(this, regionService::reload);

        getLogger().info("NetherGuard enabled. Storage="
                + getConfig().getString("storage.type", "sqlite")
                + ", Language=" + languageManager.getCurrentLang());
    }

    @Override
    public void onDisable() {
        if (writeExecutor != null) {
            writeExecutor.close();
        }
        getLogger().info("NetherGuard disabled.");
    }

    public RegionService getRegionService() {
        return regionService;
    }

    /**  全局语言访问入口。 */
    public LanguageManager lang() {
        return languageManager;
    }

    public void debug(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}