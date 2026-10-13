package tech.qunxu.module.document.cache;

import tech.qunxu.module.document.model.QunxuDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内 LRU 缓存（模块独立使用或未接入持久化存储时的默认实现）。
 * <p>
 * 只按条数限制、不估算字节数：中间格式 JSON 大小差异极大（几 KB 到几十 MB），
 * 因此持久化场景应换成 {@code ConversionCacheStore} 的数据库实现，本实现仅作兜底。
 * <p>
 * {@link QunxuDocument} 是可变对象，命中时直接返回缓存中的同一实例，
 * 因此调用方必须把转换结果当只读值处理（转换链路即如此：导出与分块都不改文档）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class InMemoryConversionCache implements ConversionCacheStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversionCache.class);

    private final int maxEntries;
    private final LinkedHashMap<String, QunxuDocument> entries;

    private long hitCount;
    private long missCount;

    public InMemoryConversionCache() {
        this(64);
    }

    public InMemoryConversionCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, QunxuDocument> eldest) {
                return size() > InMemoryConversionCache.this.maxEntries;
            }
        };
    }

    @Override
    public synchronized Optional<QunxuDocument> find(ConversionKey key) {
        if (key == null || !key.valid()) {
            return Optional.empty();
        }
        QunxuDocument document = entries.get(key.canonical());
        if (document == null) {
            missCount++;
            return Optional.empty();
        }
        hitCount++;
        return Optional.of(document);
    }

    @Override
    public synchronized void save(ConversionKey key, QunxuDocument document) {
        if (key == null || !key.valid() || document == null || document.isEmpty()) {
            return;
        }
        entries.put(key.canonical(), document);
        log.debug("解析结果已进内存缓存：{}（当前 {} 条）", key.canonical(), entries.size());
    }

    /** 命中/未命中计数（自检与观测） */
    public String stats() {
        synchronized (this) {
            return "hit=" + hitCount + ", miss=" + missCount + ", size=" + entries.size()
                    + ", max=" + maxEntries;
        }
    }

    public synchronized void clear() {
        entries.clear();
    }
}
