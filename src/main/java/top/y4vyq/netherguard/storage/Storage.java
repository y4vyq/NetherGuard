package top.y4vyq.netherguard.storage;

import java.sql.SQLException;
import java.util.List;

import top.y4vyq.netherguard.model.Region;

public interface Storage extends AutoCloseable {

    /** 建表 / 建索引（幂等）。 */
    void init() throws SQLException;

    /** 加载全部区域。 */
    List<Region> loadAll() throws SQLException;

    /** 插入区域，返回自增主键。 */
    long insert(Region region) throws SQLException;

    /**
     * 按 id 删除。
     */
    boolean delete(long id) throws SQLException;

    @Override
    default void close() throws Exception { /* 默认无操作 */ }
}