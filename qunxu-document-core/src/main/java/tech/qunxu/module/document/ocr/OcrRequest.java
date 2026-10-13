package tech.qunxu.module.document.ocr;

/**
 * 单页识别请求（一次一页，无状态）。
 *
 * @param imageBytes 页面栅格图字节（JPEG/PNG，由 {@link PdfPageRasterizer} 产出）
 * @param mimeType   图片 MIME（{@code image/jpeg} / {@code image/png}），实现按它拼 data URL
 * @param pageNumber 页码，从 1 开始（只用于日志与错误定位）
 * @param language   主语言（BCP-47，如 {@code zh-CN}），可为 null，实现自行决定是否使用
 * @param engineRef  引擎引用串，可为 null。<b>模块只负责透传、不解释其内容</b>：用哪个模型、
 *                   哪个密钥、哪个端点全是宿主模型表里的事，模块知道得越少越能独立分发；
 *                   宿主实现拿不到它时应回退到自己的部署级默认，并在失败文案里说清缺了什么
 * @author ethanwang
 * @since 2026-10-11
 */
public record OcrRequest(byte[] imageBytes, String mimeType, int pageNumber, String language, String engineRef) {

    /** 旧四参构造（无引擎引用），保持已有实现与调用点的源码兼容 */
    public OcrRequest(byte[] imageBytes, String mimeType, int pageNumber, String language) {
        this(imageBytes, mimeType, pageNumber, language, null);
    }

    /** 图片字节数（空请求返回 0，便于调用方做上限判断） */
    public int byteSize() {
        return imageBytes == null ? 0 : imageBytes.length;
    }
}
