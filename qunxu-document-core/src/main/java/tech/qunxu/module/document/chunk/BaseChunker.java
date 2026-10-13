package tech.qunxu.module.document.chunk;

import tech.qunxu.module.document.model.QunxuDocument;

import java.util.List;

/**
 * 分块器 SPI（对应 docling 的 {@code BaseChunker}）。
 * <p>
 * 输入是统一中间格式，输出是带结构溯源的 {@link Chunk}：
 * 「怎么切」与「怎么解析」彻底分离 —— 新加分块策略（如按问答对切、按代码函数切）
 * 只需实现本接口并注册为 Spring Bean（L1），解析与导出链路零改动；
 * app 侧再在 {@code splitterOptions} 里暴露名字即可被选择。
 * <p>
 * 实现约定：
 * <ul>
 *   <li>不得产出超过 {@link ChunkOptions#ceilingTokens()} 的块（embedding 会整篇失败）；</li>
 *   <li>不得抛受检异常，也不得返回 null（无内容时返回空列表）；</li>
 *   <li>必须无状态或线程安全：一个实例服务所有文档；</li>
 *   <li>{@link #prefers(QunxuDocument)} 用于「按文档结构自动选型」，只有确定自己的分块质量
 *       更契合该文档时才返回 true。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface BaseChunker {

    /** 分块器标识，app 侧 splitterName 用它路由（发布后不得改变含义） */
    String key();

    /** 人类可读名称（管理端下拉选项） */
    default String label() {
        return key();
    }

    /** 同一 splitterName 有多个候选时的优先级，越小越优先 */
    default int order() {
        return 100;
    }

    /** 是否更适合该文档（自动选型钩子，如表格文档优先行分块） */
    default boolean prefers(QunxuDocument document) {
        return false;
    }

    /**
     * 分块。
     *
     * @param document 统一中间格式（不会被修改）
     * @param options  分块选项，null 时按 {@link ChunkOptions#defaults()}
     * @return 分块列表，按文档顺序，永不为 null
     */
    List<Chunk> chunk(QunxuDocument document, ChunkOptions options);
}
