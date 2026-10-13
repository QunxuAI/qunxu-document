package tech.qunxu.module.document.model;

/**
 * 页面信息（页码与页面尺寸，PDF/pptx 有值）。
 *
 * @param index  页码，从 1 开始
 * @param width  页面宽度（point），未知为 0
 * @param height 页面高度（point），未知为 0
 * @author ethanwang
 * @since 2026-10-10
 */
public record PageInfo(int index, float width, float height) {
}
