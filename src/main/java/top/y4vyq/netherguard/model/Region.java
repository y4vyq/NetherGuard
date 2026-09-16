package top.y4vyq.netherguard.model;

import java.util.UUID;

public final class Region {

    private final long id;
    private final String name;
    private final UUID ownerUuid;
    private final String ownerName;
    private final String worldName;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final boolean use2d;
    private final long createdAt;
    private final long updatedAt;

    public Region(long id, String name, UUID ownerUuid, String ownerName, String worldName,
                  int minX, int minY, int minZ,
                  int maxX, int maxY, int maxZ,
                  boolean use2d, long createdAt, long updatedAt) {
        this.id = id;
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.use2d = use2d;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Region create(String name, UUID ownerUuid, String ownerName, String worldName,
                                int x1, int y1, int z1,
                                int x2, int y2, int z2,
                                boolean use2d) {
        long now = System.currentTimeMillis();
        return new Region(
                0L, name, ownerUuid, ownerName, worldName,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                use2d, now, now);
    }

    public Region withId(long newId) {
        return new Region(newId, name, ownerUuid, ownerName, worldName,
                minX, minY, minZ, maxX, maxY, maxZ, use2d, createdAt, updatedAt);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        if (!this.worldName.equals(worldName)) return false;
        if (x < minX || x > maxX) return false;
        if (z < minZ || z > maxZ) return false;
        if (use2d) return true;
        return y >= minY && y <= maxY;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public String getWorldName() { return worldName; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public boolean isUse2d() { return use2d; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
}