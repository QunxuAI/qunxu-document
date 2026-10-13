package tech.qunxu.module.document.post;

import tech.qunxu.module.document.backend.ParseContext;
import tech.qunxu.module.document.model.QunxuDocument;

/**
 * 文档后处理 SPI（对应 docling pipeline 末端的 enrich 半段）。
 * <p>
 * 顶层刻意不做 pipeline：本轮所有 Backend 都是「对结构化容器的单趟确定性抽取」，没有可独立开关的
 * 第二阶段推理。但「解析完之后按顺序补信息」这件事是跨格式、且确实会到来的需求，因此把它单独抽成
 * 一条有序链，而不是塞回 Backend：
 * <ul>
 *   <li>图片理解（{@link OcrPostProcessor}：给无文字层的页补正文；给 {@code PictureItem.altText} 补描述）；</li>
 *   <li>术语标准化、敏感信息脱敏；</li>
 *   <li>表格结构修正、标题层级重排。</li>
 * </ul>
 * 后处理结果仍写回同一个 {@link QunxuDocument}，因此导出、分块、缓存链路零改动。
 * <p>
 * 入参是 {@link ParseContext} 而不是裸 {@code ConvertOptions}：补信息往往要回到源字节
 * （OCR 要重渲染页面，只能拿 {@code context.bytes()}），而格式与文件哈希也在同一个上下文里。
 * <p>
 * 实现者注意：后处理发生在缓存写入之前，任何影响结果的处理器都必须把自己的标识与版本并入
 * {@link tech.qunxu.module.document.api.ConvertOptions#attribute(String, String)}
 * （或专用字段，如 {@code ocrEngine}），从而进入缓存指纹，避免「换了模型还命中旧缓存」。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface DocumentPostProcessor {

    /** 处理器标识（唯一，便于日志与配置开关） */
    String key();

    /** 执行顺序，值越小越先执行 */
    default int order() {
        return 100;
    }

    /** 是否需要处理该文档（按格式/内容快速跳过） */
    default boolean supports(QunxuDocument document, ParseContext context) {
        return true;
    }

    /**
     * 处理文档。
     *
     * @param document 已解析的中间格式（可就地修改后返回同一实例）
     * @param context  本次解析上下文（输入源字节 + 转换选项 + 格式）
     * @return 处理后的文档；返回 {@code null} 视为「保持原样」
     */
    QunxuDocument process(QunxuDocument document, ParseContext context);
}
