package tech.qunxu.module.document.config;

import tech.qunxu.module.document.chunk.ChunkLimits;
import tech.qunxu.module.document.chunk.ChunkOptions;

/**
 * 分块选项工厂：把 {@code qunxu.document.chunk.*} 的全局默认与调用方（app 侧分段参数）的
 * 局部覆盖合成一份 {@link ChunkOptions}。
 * <p>
 * 存在的理由是「预算类参数属于部署决策，块大小属于业务决策」：
 * token 硬上限、面包屑开关、每块行数是运维在配置文件里定的，而 chunkSize/overlapSize 由用户在
 * 管理端填。两者在 app 侧拼装的顺序容易被写错（用户传了 rowsPerChunk 却被全局值覆盖），
 * 因此把合成规则收在这一个类里，全局值只在调用方未提供时生效。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ChunkOptionsFactory {

    private final DocumentModuleProperties properties;

    public ChunkOptionsFactory(DocumentModuleProperties properties) {
        this.properties = properties;
    }

    /** 只给块大小与重叠，其余取全局默认 */
    public ChunkOptions create(Integer chunkSize, Integer overlapSize) {
        return create(chunkSize, overlapSize, null, null, null);
    }

    /**
     * 合成分块选项。
     *
     * @param chunkSize    块大小（结构分块按 token、兼容分块的 chars 策略按字符），null 用默认
     * @param overlapSize  重叠，null 用 0（旧链路默认无重叠时不应被全局值意外加上）
     * @param rowsPerChunk 表格每块行数，null 用 {@code qunxu.document.chunk.rows-per-chunk}
     * @param regex        正则切分表达式，可为 null
     * @param strategy     兼容分块策略（chars/regex/markdown/lines），null 用 chars
     */
    public ChunkOptions create(Integer chunkSize, Integer overlapSize, Integer rowsPerChunk,
                               String regex, String strategy) {
        ChunkOptions options = new ChunkOptions();
        options.chunkSize(chunkSize == null || chunkSize <= 0
                ? ChunkLimits.DEFAULT_TARGET_TOKENS : chunkSize);
        options.overlapSize(overlapSize == null || overlapSize < 0 ? 0 : overlapSize);
        options.maxTokens(maxTokens());
        options.includeHeadingBreadcrumb(includeBreadcrumb());
        options.rowsPerChunk(rowsPerChunk == null || rowsPerChunk <= 0
                ? properties.getChunk().getRowsPerChunk() : rowsPerChunk);
        if (regex != null && !regex.isBlank()) {
            options.regex(regex);
        }
        if (strategy != null && !strategy.isBlank()) {
            options.strategy(strategy);
        }
        return options;
    }

    /** 全局 token 硬上限（非法值退回 {@link ChunkLimits#MAX_CHUNK_TOKENS}） */
    public int maxTokens() {
        int configured = properties.getChunk().getMaxTokens();
        return configured > 0 ? configured : ChunkLimits.MAX_CHUNK_TOKENS;
    }

    public boolean includeBreadcrumb() {
        return properties.getChunk().isIncludeHeadingBreadcrumb();
    }
}
