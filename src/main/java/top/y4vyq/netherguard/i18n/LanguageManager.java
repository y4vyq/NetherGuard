package top.y4vyq.netherguard.i18n;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import top.y4vyq.netherguard.NetherGuardPlugin;

/**
 * 语言管理器：从 lang/<code>.yml 读取消息。
 *
 * <p>内置语言不再硬编码，而是运行时扫描插件 jar 内 {@code lang/*.yml}
 * 并全部释放到 {@code dataFolder/lang/}。</p>
 */
public final class LanguageManager {

    private static final String LANG_DIR = "lang";
    private static final String LANG_EXT = ".yml";
    private static final String FALLBACK = "zh_CN";

    /** 语言文件整体不可用时的硬编码兜底。 */
    private static final String HARDCODED_BROKEN =
            "&c[NetherGuard] 语言文件缺失或加载失败，请检查 lang/ 目录或重新生成配置。";

    /** 单个 key 缺失时的硬编码兜底，%s 会被替换成 key 名。 */
    private static final String HARDCODED_MISSING_KEY = "&c[Missing i18n key: %s]";

    private final NetherGuardPlugin plugin;
    private YamlConfiguration config;
    private String currentLang = FALLBACK;

    /** 语言文件彻底不可用时置 true，所有取值走硬编码兜底。 */
    private boolean broken = false;

    /** 已记录过 warning 的缺失 key，避免刷屏。 */
    private final Set<String> warnedMissingKeys = new HashSet<>();

    public LanguageManager(NetherGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /* 加载 / 重载                                                         */
    /* ------------------------------------------------------------------ */

    public void load() {
        // 每次重载都重置状态
        this.broken = false;
        this.warnedMissingKeys.clear();

        releaseBuiltinLanguages();

        String code = plugin.getConfig().getString("language", FALLBACK);
        if (code == null || code.trim().isEmpty()) {
            code = FALLBACK;
        }
        code = code.trim();
        this.currentLang = code;

        File file = new File(plugin.getDataFolder(), LANG_DIR + "/" + code + LANG_EXT);
        if (!file.exists()) {
            plugin.getLogger().warning("[i18n] 语言文件不存在: " + file.getPath()
                    + "，回退到 " + FALLBACK);
            this.currentLang = FALLBACK;
            file = new File(plugin.getDataFolder(), LANG_DIR + "/" + FALLBACK + LANG_EXT);
        }

        if (!file.exists()) {
            // 如果连 fallback 文件都没有：启用硬编码兜底
            plugin.getLogger().severe("[i18n] 回退文件也不存在: " + file.getPath()
                    + "，启用硬编码兜底消息");
            this.broken = true;
            this.config = new YamlConfiguration();
            return;
        }

        this.config = YamlConfiguration.loadConfiguration(file);

        String resPath = LANG_DIR + "/" + currentLang + LANG_EXT;
        try (InputStream in = plugin.getResource(resPath)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            } else {
                plugin.getLogger().severe("[i18n] jar 内无 defaults: " + resPath);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[i18n] 加载 defaults 失败", e);
        }

        plugin.getLogger().info("[i18n] Loaded language: " + currentLang
                + " (prefix=" + config.getString("prefix") + ")");
    }

    public void reload() {
        load();
    }

    public String getCurrentLang() {
        return currentLang;
    }

    /** 语言文件是否处于硬编码兜底状态。 */
    public boolean isBroken() {
        return broken;
    }

    /* ------------------------------------------------------------------ */
    /* 内置语言释放                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 扫描插件 jar 内 {@code lang/*.yml}，对数据目录中尚不存在的文件执行释放。
     */
    private void releaseBuiltinLanguages() {
        List<String> codes = scanBuiltinLangCodes();
        if (codes.isEmpty()) {
            plugin.getLogger().fine("[i18n] 未从 jar 扫描到内置语言文件（开发环境属正常）");
            return;
        }

        for (String code : codes) {
            String resPath = LANG_DIR + "/" + code + LANG_EXT;
            File out = new File(plugin.getDataFolder(), resPath);
            if (out.exists()) continue;

            if (plugin.getResource(resPath) == null) {
                plugin.getLogger().warning("[i18n] jar 内无此资源，跳过: " + resPath);
                continue;
            }
            try {
                plugin.saveResource(resPath, false);
                plugin.getLogger().info("[i18n] 已释放内置语言文件: " + resPath);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "[i18n] 释放失败: " + resPath, t);
            }
        }
    }

    /**
     * 扫描插件 jar 内 {@code lang/} 下的所有 {@code *.yml}，
     * 返回语言代码。仅取顶层文件，忽略子目录。
     */
    private List<String> scanBuiltinLangCodes() {
        List<String> codes = new ArrayList<>();
        File jar = getPluginJarFile();
        if (jar == null || !jar.isFile()) {
            // 开发环境（classes 目录）或无法定位 jar
            return codes;
        }

        String prefix = LANG_DIR + "/";
        try (JarFile jarFile = new JarFile(jar)) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.startsWith(prefix)) continue;
                if (!name.endsWith(LANG_EXT)) continue;

                String rest = name.substring(prefix.length());
                // 排除子目录，只要 lang/*.yml
                if (rest.contains("/")) continue;

                codes.add(rest.substring(0, rest.length() - LANG_EXT.length()));
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "[i18n] 读取 jar 失败，跳过内置语言扫描", ex);
        }
        return codes;
    }

    /** 获取插件自身 jar 文件；开发环境（classes 目录）或异常时返回 null。 *
    private File getPluginJarFile() {
        try {
            return new File(plugin.getClass()
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException | NullPointerException ex) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 取值                                                                */
    /* ------------------------------------------------------------------ */

    public String get(String key) {
        return color(rawGet(key));
    }

    public String get(String key, Map<String, String> placeholders) {
        return color(applyPlaceholders(rawGet(key), placeholders));
    }

    public String get(String key, String k1, Object v1) {
        return get(key, Collections.singletonMap(k1, String.valueOf(v1)));
    }

    public String get(String key, String k1, Object v1, String k2, Object v2) {
        return get(key, Map.of(k1, String.valueOf(v1), k2, String.valueOf(v2)));
    }

    public String prefixed(String key) {
        if (broken) return color(HARDCODED_BROKEN);
        return get("prefix") + get(key);
    }

    public String prefixed(String key, Map<String, String> ph) {
        if (broken) return color(HARDCODED_BROKEN);
        return get("prefix") + get(key, ph);
    }

    public String prefixed(String key, String k1, Object v1) {
        if (broken) return color(HARDCODED_BROKEN);
        return get("prefix") + get(key, k1, v1);
    }

    public String prefixed(String key, String k1, Object v1, String k2, Object v2) {
        if (broken) return color(HARDCODED_BROKEN);
        return get("prefix") + get(key, k1, v1, k2, v2);
    }

    public List<String> getList(String key) {
        return getList(key, Collections.emptyMap());
    }

    public List<String> getList(String key, Map<String, String> ph) {
        if (broken) {
            return Collections.singletonList(color(HARDCODED_BROKEN));
        }

        // isSet 考虑 defaults，语义比 contains + raw.isEmpty 更准确
        if (!config.isSet(key)) {
            warnMissingKey(key);
            return Collections.singletonList(
                    color(String.format(HARDCODED_MISSING_KEY, key)));
        }

        List<String> raw = config.getStringList(key);
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            out.add(color(applyPlaceholders(s, ph)));
        }
        return out;
    }

    public List<Map<?, ?>> getMapList(String key) {
        if (broken) return Collections.emptyList();

        if (!config.isSet(key)) {
            warnMissingKey(key);
            return Collections.emptyList();
        }

        List<Map<?, ?>> list = config.getMapList(key);
        return list == null ? Collections.emptyList() : list;
    }

    public boolean has(String key) {
        return !broken && config.isSet(key);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 取原始字符串。处理两种兜底：
     *   - 整体 broken → HARDCODED_BROKEN
     *   - 单 key 缺失 → HARDCODED_MISSING_KEY（并记一次 warning）
     */
    private String rawGet(String key) {
        if (broken) return HARDCODED_BROKEN;

        String v = config.getString(key);
        if (v == null) {
            warnMissingKey(key);
            return String.format(HARDCODED_MISSING_KEY, key);
        }
        return v;
    }

    /** 对同一个缺失 key 只记一次日志，避免高频调用刷屏。 */
    private void warnMissingKey(String key) {
        if (warnedMissingKeys.add(key)) {
            plugin.getLogger().warning("[i18n] Missing key: " + key
                    + " (lang=" + currentLang + ")");
        }
    }

    private String applyPlaceholders(String s, Map<String, String> ph) {
        if (s == null) return "";
        if (ph == null || ph.isEmpty()) return s;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            s = s.replace("{" + e.getKey() + "}", v);
        }
        return s;
    }

    private String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
