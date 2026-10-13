package tech.qunxu.module.document.chunk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 分块选项（对应 docling 的 BaseChunker 构造参数）。
 * <p>
 * <b>同一个字段在不同分块器里的单位不同，这是刻意的</b>：
 * {@link #chunkSize} 对结构分块（{@code HierarchicalChunker} / {@code TableRowChunker}）
 * 是<b>目标 token 数</b>，对兼容分块（{@code FlatChunker} 的 chars 策略）是<b>字符数</b> ——
 * 因为后者必须与改造前 {@code splitByChars} 的语义逐块对齐，不能为了统一口径改掉分段数。
 * 两者的硬上限都是 {@link ChunkLimits#MAX_CHUNK_TOKENS}。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ChunkOptions {

    /** 兼容分块的切分策略（{@link FlatChunker} 使用） */
    public static final String STRATEGY_CHARS = "chars";
    public static final String STRATEGY_REGEX = "regex";
    public static final String STRATEGY_MARKDOWN = "markdown";
    public static final String STRATEGY_LINES = "lines";

    /** 目标块大小：结构分块按 token，兼容分块的 chars 策略按字符 */
    private int chunkSize = ChunkLimits.DEFAULT_TARGET_TOKENS;
    /** 兼容分块 chars 策略的重叠字符数 */
    private int overlapSize = 128;
    /** 硬上限 token，任何块都不得超过（默认留出 embedding 上限余量） */
    private int maxTokens = ChunkLimits.MAX_CHUNK_TOKENS;
    /** 低于该 token 数的尾巴并入前一块，避免碎片（0 表示关闭） */
    private int minChunkTokens = ChunkLimits.MIN_CHUNK_TOKENS;
    /** 是否在块首注入标题面包屑 */
    private boolean includeHeadingBreadcrumb = true;
    /** 表格分块是否每块重复表头 */
    private boolean includeTableHeader = true;
    /** 表格每块最大行数（<=0 表示只按 token 预算分块） */
    private int rowsPerChunk = ChunkLimits.DEFAULT_ROWS_PER_CHUNK;
    /** 分块策略（兼容分块用） */
    private String strategy = STRATEGY_CHARS;
    /** 正则切分表达式 */
    private String regex;
    /** Markdown 标题切分的最大层级（1~6） */
    private Integer markdownLevel;
    /** 分块器私有开关 */
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public static ChunkOptions defaults() {
        return new ChunkOptions();
    }

    /** 复制一份，避免调用方与分块器内部相互篡改 */
    public ChunkOptions copy() {
        ChunkOptions copy = new ChunkOptions();
        copy.chunkSize = this.chunkSize;
        copy.overlapSize = this.overlapSize;
        copy.maxTokens = this.maxTokens;
        copy.minChunkTokens = this.minChunkTokens;
        copy.includeHeadingBreadcrumb = this.includeHeadingBreadcrumb;
        copy.includeTableHeader = this.includeTableHeader;
        copy.rowsPerChunk = this.rowsPerChunk;
        copy.strategy = this.strategy;
        copy.regex = this.regex;
        copy.markdownLevel = this.markdownLevel;
        copy.attributes.putAll(this.attributes);
        return copy;
    }

    // ==================== 归一化取值 ====================

    /** 目标 token 数（不超过硬上限，至少 1） */
    public int targetTokens() {
        int ceiling = maxTokens > 0 ? maxTokens : ChunkLimits.MAX_CHUNK_TOKENS;
        int target = chunkSize > 0 ? chunkSize : ChunkLimits.DEFAULT_TARGET_TOKENS;
        return Math.max(1, Math.min(target, ceiling));
    }

    /** 硬上限 token（至少 1） */
    public int ceilingTokens() {
        return maxTokens > 0 ? maxTokens : ChunkLimits.MAX_CHUNK_TOKENS;
    }

    /** 字符窗口大小（兼容分块用，至少 1） */
    public int charWindow() {
        return Math.max(1, chunkSize);
    }

    /** 滑窗步长：必须 ≥ 1，否则 overlap ≥ chunk 时起点不前进导致死循环 */
    public int charStep() {
        return Math.max(1, charWindow() - Math.max(0, overlapSize));
    }

    /** 每块最大行数（<=0 时返回 Integer.MAX_VALUE，表示只受 token 约束） */
    public int rowLimit() {
        return rowsPerChunk > 0 ? rowsPerChunk : Integer.MAX_VALUE;
    }

    public String strategy() {
        return strategy == null || strategy.isBlank()
                ? STRATEGY_CHARS : strategy.trim().toLowerCase(Locale.ROOT);
    }

    public int markdownLevelOrDefault() {
        if (markdownLevel == null || markdownLevel < 1) {
            return 3;
        }
        return Math.min(markdownLevel, 6);
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    public boolean booleanAttribute(String key, boolean defaultValue) {
        String value = attributes.get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public int intAttribute(String key, int defaultValue) {
        String value = attributes.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== 流式赋值 ====================

    public ChunkOptions chunkSize(int value) {
        this.chunkSize = value;
        return this;
    }

    public ChunkOptions overlapSize(int value) {
        this.overlapSize = Math.max(0, value);
        return this;
    }

    public ChunkOptions maxTokens(int value) {
        this.maxTokens = value;
        return this;
    }

    public ChunkOptions minChunkTokens(int value) {
        this.minChunkTokens = Math.max(0, value);
        return this;
    }

    public ChunkOptions includeHeadingBreadcrumb(boolean value) {
        this.includeHeadingBreadcrumb = value;
        return this;
    }

    public ChunkOptions includeTableHeader(boolean value) {
        this.includeTableHeader = value;
        return this;
    }

    public ChunkOptions rowsPerChunk(int value) {
        this.rowsPerChunk = value;
        return this;
    }

    public ChunkOptions strategy(String value) {
        this.strategy = value;
        return this;
    }

    public ChunkOptions regex(String value) {
        this.regex = value;
        return this;
    }

    public ChunkOptions markdownLevel(Integer value) {
        this.markdownLevel = value;
        return this;
    }

    public ChunkOptions attribute(String key, String value) {
        if (key != null && !key.isBlank()) {
            attributes.put(key.trim(), value);
        }
        return this;
    }

    // ==================== 直读字段 ====================

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlapSize() {
        return overlapSize;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getMinChunkTokens() {
        return minChunkTokens;
    }

    public boolean isIncludeHeadingBreadcrumb() {
        return includeHeadingBreadcrumb;
    }

    public boolean isIncludeTableHeader() {
        return includeTableHeader;
    }

    public int getRowsPerChunk() {
        return rowsPerChunk;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getRegex() {
        return regex;
    }

    public Integer getMarkdownLevel() {
        return markdownLevel;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public String toString() {
        return "chunkSize=" + chunkSize + ", overlap=" + overlapSize + ", maxTokens=" + maxTokens
                + ", rows=" + rowsPerChunk + ", strategy=" + strategy
                + ", breadcrumb=" + includeHeadingBreadcrumb;
    }
}
