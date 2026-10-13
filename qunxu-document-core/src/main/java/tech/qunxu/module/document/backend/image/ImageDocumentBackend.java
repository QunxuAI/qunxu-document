package tech.qunxu.module.document.backend.image;

import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.backend.DocumentBackend;
import tech.qunxu.module.document.backend.ParseContext;
import tech.qunxu.module.document.model.QunxuDocument;

/**
 * 图片输入的解析扩展位（本轮<b>只有接口，没有实现</b>）。
 * <p>
 * png/jpg/webp 等图片进知识库时，{@code DocumentConverter} 在注册表里找不到可用的非兜底
 * Backend（{@code TikaFallbackBackend} 显式排除 {@link DocumentFormat#IMAGE}：Tika 对图片只能
 * 给出尺寸元数据，产不出正文，兜底只会得到「解析成功但内容为空」的假象），
 * 因此返回 {@code UNSUPPORTED_FORMAT} 而不是静默产出空文档。
 * <p>
 * 下一轮实现时按这个契约补齐即可，app 侧与缓存/分块/导出链路零改动：
 * <ol>
 *   <li><b>OCR</b>：Tesseract / PaddleOCR（后者需要外部服务，走 {@code RemoteDocumentBackend} 更合适）；</li>
 *   <li><b>VLM 图片描述</b>：把图交给多模态模型，产出 {@code PictureItem} + {@code altText} 说明，
 *       与 {@code DocumentPostProcessor} 的图片描述链是同一件事的两种落点（前者按格式、后者跨格式）；</li>
 *   <li>字节获取用 {@code ParseContext#images()} 已有的落地通道，不要自己写存储。</li>
 * </ol>
 * 实现后注册为 Spring Bean（L1）即可被路由到：{@code supports(IMAGE)} 返回 true，
 * {@code order()} 建议 ≥ 30（图片是「增强能力」，不应抢在文本类 Backend 前面）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface ImageDocumentBackend extends DocumentBackend {

    /** 约定的注册 key（实现类可直接沿用，改名需同步 docs 的支持矩阵） */
    String KEY = "image";

    @Override
    default String key() {
        return KEY;
    }

    @Override
    default boolean supports(DocumentFormat format) {
        return format == DocumentFormat.IMAGE;
    }

    /**
     * 本轮没有实现：抛明确的 {@link UnsupportedOperationException}，
     * 让「注册了却没实现」在日志里一眼可辨，而不是产出空文档去猜原因。
     */
    @Override
    default QunxuDocument parse(ParseContext context) throws Exception {
        throw new UnsupportedOperationException(
                "图片解析尚未实现（需 OCR 或 VLM 描述），当前仅保留扩展位：" + context.fileName());
    }
}
