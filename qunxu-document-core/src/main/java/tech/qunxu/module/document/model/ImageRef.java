package tech.qunxu.module.document.model;

/**
 * 图片引用：解析出的内嵌图片经 {@code ImageSink} 落地后的定位信息。
 * <p>
 * {@code storagePath} 用于删除/GC，{@code url} 用于 Markdown/HTML 导出与前端展示；
 * 本地存储下 url 形如 {@code /attachment/2026/10/10/{uuid}/img.png}（同源相对路径），
 * 对象存储（OSS/MinIO）下 url 为绝对地址。
 *
 * @param storagePath 存储路径或访问地址（由存储实现返回，可用于 delete）
 * @param url         展示用 URL（与 storagePath 同源，预留 CDN 覆盖能力）
 * @param fileName    落地的文件名
 * @param contentType MIME 类型，如 image/png
 * @param width       像素宽，未知为 0
 * @param height      像素高，未知为 0
 * @param bytes       字节数
 * @param sha256      图片内容哈希（同图去重与后续 OCR 回填定位）
 * @author ethanwang
 * @since 2026-10-10
 */
public record ImageRef(String storagePath,
                       String url,
                       String fileName,
                       String contentType,
                       int width,
                       int height,
                       long bytes,
                       String sha256) {

    /** 是否已真正落地（noop sink 或被过滤的图片只有哈希与尺寸） */
    public boolean stored() {
        return storagePath != null && !storagePath.isBlank();
    }
}
