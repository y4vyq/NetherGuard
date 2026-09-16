package top.y4vyq.netherguard.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import top.y4vyq.netherguard.model.Region;

public final class RegionCache {

    public enum State { LOADING, READY, FAILED }

    private final State state;
    private final List<Region> all;
    private final Map<String, List<Region>> byWorld;
    private final Map<UUID, List<Region>> byOwner;
    private final Map<UUID, List<Region>> byOwnerSorted;

    private RegionCache(State state,
                        List<Region> all,
                        Map<String, List<Region>> byWorld,
                        Map<UUID, List<Region>> byOwner,
                        Map<UUID, List<Region>> byOwnerSorted) {
        this.state = state;
        this.all = all;
        this.byWorld = byWorld;
        this.byOwner = byOwner;
        this.byOwnerSorted = byOwnerSorted;
    }

    public static RegionCache loading() {
        return new RegionCache(State.LOADING,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
    }

    public static RegionCache failed() {
        return new RegionCache(State.FAILED,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
    }

    public static RegionCache build(List<Region> regions) {
        List<Region> all = Collections.unmodifiableList(new ArrayList<>(regions));
        Map<String, List<Region>> byWorld = new HashMap<>();
        Map<UUID, List<Region>> byOwner = new HashMap<>();

        for (Region r : regions) {
            byWorld.computeIfAbsent(r.getWorldName(), k -> new ArrayList<>()).add(r);
            byOwner.computeIfAbsent(r.getOwnerUuid(), k -> new ArrayList<>()).add(r);
        }

        Map<String, List<Region>> frozenByWorld = new HashMap<>(byWorld.size());
        for (Map.Entry<String, List<Region>> e : byWorld.entrySet()) {
            frozenByWorld.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }

        Map<UUID, List<Region>> frozenByOwner = new HashMap<>(byOwner.size());
        for (Map.Entry<UUID, List<Region>> e : byOwner.entrySet()) {
            frozenByOwner.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }

        Map<UUID, List<Region>> byOwnerSorted = new HashMap<>(byOwner.size());
        for (Map.Entry<UUID, List<Region>> e : byOwner.entrySet()) {
            List<Region> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparingLong(Region::getCreatedAt));
            byOwnerSorted.put(e.getKey(), Collections.unmodifiableList(sorted));
        }

        return new RegionCache(State.READY, all,
                Collections.unmodifiableMap(frozenByWorld),
                Collections.unmodifiableMap(frozenByOwner),
                Collections.unmodifiableMap(byOwnerSorted));
    }

    public RegionCache withAdded(Region r) {
        List<Region> newAll = new ArrayList<>(all);
        newAll.add(r);
        return build(newAll);
    }

    public RegionCache withRemoved(long id) {
        List<Region> newAll = new ArrayList<>(all);
        newAll.removeIf(r -> r.getId() == id);
        return build(newAll);
    }

    public State getState() { return state; }
    public List<Region> getAll() { return all; }
    public Map<String, List<Region>> getByWorld() { return byWorld; }
    public Map<UUID, List<Region>> getByOwner() { return byOwner; }
    public Map<UUID, List<Region>> getByOwnerSorted() { return byOwnerSorted; }
}