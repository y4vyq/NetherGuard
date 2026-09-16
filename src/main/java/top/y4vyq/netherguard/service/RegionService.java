package top.y4vyq.netherguard.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

import top.y4vyq.netherguard.NetherGuardPlugin;
import top.y4vyq.netherguard.cache.RegionCache;
import top.y4vyq.netherguard.concurrent.WriteExecutor;
import top.y4vyq.netherguard.model.Region;
import top.y4vyq.netherguard.storage.Storage;

public final class RegionService {

    /** 不确定写入后的全量对齐最大重试次数。 */
    private static final int MAX_REALIGN_ATTEMPTS = 5;

    private final NetherGuardPlugin plugin;
    private final Storage storage;
    private final WriteExecutor writer;

    private final AtomicReference<RegionCache> cache =
            new AtomicReference<>(RegionCache.loading());

    private final Object cacheWriteLock = new Object();

    private long generation = 0;

    private final boolean failClosed;

    public RegionService(NetherGuardPlugin plugin, Storage storage, WriteExecutor writer) {
        this(plugin, storage, writer, true);
    }

    public RegionService(NetherGuardPlugin plugin, Storage storage,
                         WriteExecutor writer, boolean failClosed) {
        this.plugin = plugin;
        this.storage = storage;
        this.writer = writer;
        this.failClosed = failClosed;
    }

    /* ------------------------------------------------------------------ */
    /* 命中判断                                                            */
    /* ------------------------------------------------------------------ */

    public boolean isRestricted(String worldName, int x, int y, int z) {
        RegionCache c = cache.get();
        switch (c.getState()) {
            case LOADING:
                return false;          // 启动中不拦截，避免误伤
            case FAILED:
                return failClosed;     // 可配置：默认保守拦截
            case READY:
                break;
        }
        List<Region> list = c.getByWorld().get(worldName);
        if (list == null || list.isEmpty()) return false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(worldName, x, y, z)) return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* 查询                                                                */
    /* ------------------------------------------------------------------ */

    public int countByOwner(UUID owner) {
        List<Region> list = cache.get().getByOwner().get(owner);
        return list == null ? 0 : list.size();
    }

    public boolean nameExists(UUID owner, String name) {
        List<Region> list = cache.get().getByOwner().get(owner);
        if (list == null) return false;
        for (Region r : list) {
            if (r.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public Region getByOwnerAndName(UUID owner, String name) {
        List<Region> list = cache.get().getByOwner().get(owner);
        if (list == null) return null;
        for (Region r : list) {
            if (r.getName().equalsIgnoreCase(name)) return r;
        }
        return null;
    }

    /**
     * 按名字查找。若存在多个同名区域（不同 owner），返回第一个命中的。
     */
    public Region getAnyByName(String name) {
        for (Region r : cache.get().getAll()) {
            if (r.getName().equalsIgnoreCase(name)) return r;
        }
        return null;
    }

    /** 返回所有同名区域，避免(任意一个)的歧义。 */
    public List<Region> listAnyByName(String name) {
        List<Region> all = cache.get().getAll();
        List<Region> result = new ArrayList<>();
        for (Region r : all) {
            if (r.getName().equalsIgnoreCase(name)) result.add(r);
        }
        return result;
    }

    public List<Region> listByOwner(UUID owner) {
        List<Region> list = cache.get().getByOwnerSorted().get(owner);
        return list == null ? Collections.emptyList() : list;
    }

    /* ------------------------------------------------------------------ */
    /* 写入（同步语义）                                                    */
    /* ------------------------------------------------------------------ */

    public Region createSync(Region region, long timeoutSeconds)
            throws TimeoutException, InterruptedException, ExecutionException {

        Future<Region> future = writer.submit(() -> {
            long id = storage.insert(region);
            return region.withId(id);
        });

        Region saved;
        try {
            saved = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            safeRealignAfterUncertainWrite();
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            safeRealignAfterUncertainWrite();
            throw e;
        }

        applyMutation(c -> c.withAdded(saved));
        return saved;
    }

    public boolean deleteSync(long id, long timeoutSeconds)
            throws TimeoutException, InterruptedException, ExecutionException {

        // Storage.delete 返回 boolean，lambda 推断为 Callable<Boolean>
        Future<Boolean> future = writer.submit(() -> storage.delete(id));

        boolean removed;
        try {
            removed = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            safeRealignAfterUncertainWrite();
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            safeRealignAfterUncertainWrite();
            throw e;
        }

        // 只有确实删除了才更新缓存，避免无谓的全量重建
        if (removed) {
            applyMutation(c -> c.withRemoved(id));
        }
        return removed;
    }

    /* ------------------------------------------------------------------ */
    /* 缓存加载 / 重载                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * 从数据库全量加载。
     */
    public boolean reload() {
        // 1) 记录期望代际
        final long expectedGen;
        synchronized (cacheWriteLock) {
            expectedGen = generation;
        }

        // 2) 锁外 I/O
        final List<Region> all;
        try {
            storage.init();
            all = storage.loadAll();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load regions from storage", e);
            synchronized (cacheWriteLock) {
                if (cache.get().getState() == RegionCache.State.LOADING) {
                    cache.set(RegionCache.failed());
                }
            }
            return false;
        }

        final RegionCache fresh;
        try {
            fresh = RegionCache.build(all);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to build region cache", e);
            return false;
        }

        // 3) 锁内替换，带代际校验
        synchronized (cacheWriteLock) {
            if (generation != expectedGen) {
                plugin.getLogger().info(
                        "Reload skipped: concurrent cache update detected "
                                + "(expected gen=" + expectedGen
                                + ", actual=" + generation + ").");
                return false;
            }
            cache.set(fresh);
            generation++;
            plugin.getLogger().info("Loaded " + all.size()
                    + " region(s) from storage (gen=" + generation + ").");
            return true;
        }
    }

    public void reloadAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::reload);
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    private void applyMutation(UnaryOperator<RegionCache> op) {
        synchronized (cacheWriteLock) {
            RegionCache cur = cache.get();
            if (cur.getState() == RegionCache.State.READY) {
                cache.set(op.apply(cur));
                generation++;
                return;
            }
        }
        safeRealignAfterUncertainWrite();
    }

    private void safeRealignAfterUncertainWrite() {
        for (int attempt = 1; attempt <= MAX_REALIGN_ATTEMPTS; attempt++) {
            // 1) 记录期望代际
            final long expectedGen;
            synchronized (cacheWriteLock) {
                expectedGen = generation;
            }

            // 2) 锁外 I/O
            final List<Region> all;
            try {
                storage.init();
                all = storage.loadAll();
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to realign cache after uncertain write", ex);
                synchronized (cacheWriteLock) {
                    if (cache.get().getState() != RegionCache.State.READY) {
                        cache.set(RegionCache.failed());
                    }
                }
                return;
            }

            final RegionCache fresh;
            try {
                fresh = RegionCache.build(all);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to build cache during realign", ex);
                synchronized (cacheWriteLock) {
                    if (cache.get().getState() != RegionCache.State.READY) {
                        cache.set(RegionCache.failed());
                    }
                }
                return;
            }

            synchronized (cacheWriteLock) {
                if (generation == expectedGen) {
                    cache.set(fresh);
                    generation++;
                    plugin.getLogger().warning(
                            "Cache realigned after uncertain write "
                                    + "(gen=" + generation
                                    + ", attempt=" + attempt + ").");
                    return;
                }

                plugin.getLogger().info(
                        "Realign attempt " + attempt + " skipped: "
                                + "concurrent cache update detected "
                                + "(expected gen=" + expectedGen
                                + ", actual=" + generation + ").");
            }
        }

        plugin.getLogger().warning(
                "Cache realign gave up after " + MAX_REALIGN_ATTEMPTS
                        + " attempts due to continuous concurrent cache updates. "
                        + "Cache may be temporarily inconsistent until next reload.");
    }
}