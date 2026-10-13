package tech.qunxu.module.document.chunk;

/**
 * 分块硬上限常量。
 * <p>
 * 数值来自改造前 {@code ExcelDocumentSplitter} 的实测结论，改动前请先读注释里的推导：
 * Spring AI 的 {@code TokenCountBatchingStrategy} 默认按
 * {@code round(8191 * (1 - 0.1)) = 7372} token 作为单文档上限（CL100K_BASE），
 * 超过就在 embedding 阶段抛
 * {@code Tokens in a single document exceeds the maximum number of allowed input tokens}，
 * 使<b>整篇文档</b>索引失败。因此这里取 7000 留安全余量：既要吃掉面包屑/表头前缀，
 * 也要给不同模型的 tokenizer 差异留出空间。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class ChunkLimits {

    /** 单个分块的最大 token 预算（硬上限，任何分块都不得超过） */
    public static final int MAX_CHUNK_TOKENS = 7000;

    /** 分块最少 token 数：低于此值的尾巴并入上一块，避免产生检索不到的碎片 */
    public static final int MIN_CHUNK_TOKENS = 8;

    /** 表格行分块的默认行数（与改造前 {@code DocumentServiceImpl.DEFAULT_ROWS_PER_CHUNK} 一致） */
    public static final int DEFAULT_ROWS_PER_CHUNK = 10;

    /** 用户未指定分块大小时的目标 token 数（与 app 侧 {@code chunkSize} 默认值 512 对齐） */
    public static final int DEFAULT_TARGET_TOKENS = 512;

    private ChunkLimits() {
    }
}
