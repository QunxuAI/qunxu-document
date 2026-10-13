package tech.qunxu.module.document.model;

/**
 * 元素溯源信息（对应 docling ProvenanceItem / opendataloader 的 {@code page number}）。
 * <p>
 * 本轮：PDF 与 pptx 能给出页码，{@code bbox} 恒为 null；
 * 接入结构化 PDF 解析器（opendataloader 等）后只需填充 bbox，中间格式契约不变。
 *
 * @param pageIndex 页码，从 1 开始；0 表示未知
 * @param bbox      页面包围盒，可为 null（无版面信息的格式）
 * @author ethanwang
 * @since 2026-10-10
 */
public record ProvenanceItem(int pageIndex, BBox bbox) {

    /** 未知页码构造 */
    public static ProvenanceItem ofPage(int pageIndex) {
        return new ProvenanceItem(Math.max(0, pageIndex), null);
    }
}
