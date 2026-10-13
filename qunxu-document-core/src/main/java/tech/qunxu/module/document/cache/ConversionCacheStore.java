package tech.qunxu.module.document.cache;

import tech.qunxu.module.document.model.QunxuDocument;

import java.util.Optional;

/**
 * 解析结果缓存 SPI（需求 5「唯一解析源」的存储契约）。
 * <p>
 * 模块只提供接口与进程内实现，持久化（MyBatis-Flex + 大 JSON 溢出到对象存储）由 app 侧完成：
 * 命中即免解析，预览翻页、改分段参数、重新索引、批量重复上传都复用同一份 {@link QunxuDocument}。
 * <p>
 * 实现必须线程安全，并且<b>不得抛异常</b>（缓存故障应退化为「未命中」而不是让导入失败）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface ConversionCacheStore {

    /**
     * 查询已缓存的解析结果。
     *
     * @param key 缓存键
     * @return 命中时返回中间格式文档；未命中或缓存不可用时返回 {@link Optional#empty()}
     */
    Optional<QunxuDocument> find(ConversionKey key);

    /**
     * 写入解析结果（同键覆盖，便于 {@code forceReparse} 重解析后刷新缓存）。
     *
     * @param key      缓存键
     * @param document 解析结果（空文档不应被写入，由调用方判断）
     */
    void save(ConversionKey key, QunxuDocument document);
}
