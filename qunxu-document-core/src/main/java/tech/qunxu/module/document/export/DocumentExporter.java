package tech.qunxu.module.document.export;

import tech.qunxu.module.document.model.QunxuDocument;

/**
 * 导出器 SPI（对应 docling 的 export_to_markdown / export_to_dict / …）。
 * <p>
 * 实现只需注册为 Spring Bean 即可被 {@code DocumentConverter} 收集，
 * 调用方可按 {@link #key()} 取用（如 {@code converter.export(doc, "html")}）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface DocumentExporter {

    /** 导出格式标识（markdown/html/json/text），全局唯一 */
    String key();

    /**
     * 把中间格式渲染为目标文本。
     *
     * @param document 中间格式文档
     * @return 目标格式文本（空文档返回空串，不返回 null）
     */
    String export(QunxuDocument document);
}
