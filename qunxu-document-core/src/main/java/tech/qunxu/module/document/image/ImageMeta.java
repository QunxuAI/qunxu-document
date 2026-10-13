package tech.qunxu.module.document.image;

/**
 * 图片元信息（由 {@code ImageExtractor} 在落地前补齐）。
 *
 * @param documentHash 所属文档的内容哈希（用于按文档聚合图片、删除时定位）
 * @param seq          文档内图片序号（从 0 开始，参与落地文件名，保证稳定可预期）
 * @param fileNameHint 建议文件名（含扩展名），落地实现可改写
 * @param contentType  MIME 类型，如 image/png
 * @param width        像素宽，未知为 0
 * @param height       像素高，未知为 0
 * @param sha256       图片字节哈希（同图去重、后续 OCR 回填定位）
 * @param sourceName   来源文件名（Word 内嵌媒体名、PDF XObject 名等，便于排查）
 * @author ethanwang
 * @since 2026-10-10
 */
public record ImageMeta(String documentHash,
                        int seq,
                        String fileNameHint,
                        String contentType,
                        int width,
                        int height,
                        String sha256,
                        String sourceName) {

    /** 建议扩展名（小写，不含点），无则 png */
    public String extension() {
        if (fileNameHint != null) {
            int idx = fileNameHint.lastIndexOf('.');
            if (idx >= 0 && idx < fileNameHint.length() - 1) {
                return fileNameHint.substring(idx + 1).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "png";
    }
}
