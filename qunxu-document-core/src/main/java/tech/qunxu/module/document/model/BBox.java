package tech.qunxu.module.document.model;

/**
 * 元素在页面上的包围盒（坐标系：PDF point，原点左下，与 opendataloader 的
 * {@code bounding box} 字段同构，便于后续接入结构化 PDF 解析器时直接映射）。
 *
 * @param x0 左边界
 * @param y0 下边界
 * @param x1 右边界
 * @param y1 上边界
 * @author ethanwang
 * @since 2026-10-10
 */
public record BBox(float x0, float y0, float x1, float y1) {

    /** 包围盒宽度 */
    public float width() {
        return Math.max(0f, x1 - x0);
    }

    /** 包围盒高度 */
    public float height() {
        return Math.max(0f, y1 - y0);
    }
}
