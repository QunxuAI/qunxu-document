package tech.qunxu.module.document.ocr;

import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * PDF 页面栅格化（OCR 的图片输入准备）。
 *
 * <p>为什么必须栅格化而不是直接用内嵌图：扫描件的一页可能由多张分块位图、带 smask 的图
 * 或经过缩放的 Form 组成，{@code PdfTikaBackend} 抽到的是 XObject 原始字节，尺寸与页面
 * 呈现并不对应。OCR 要的是「这一页看上去是什么」，只能按页面渲染（与 docling 用
 * pypdfium 渲染页面位图同一口径）。
 *
 * <p>三道闸门按 {@link OcrConfig} 收：
 * <ul>
 *   <li>{@code dpi}：默认 200，A4 约 1654×2339；再高对 VLM 识别帮助有限但字节数线性涨；</li>
 *   <li>{@code maxSide}：长边超等比缩（模型侧本来也要 resize，缩掉的是纯传输成本）；</li>
 *   <li>{@code maxBytes}：编码后仍超上限就再降一档重试，救不回来才放弃这一页 ——
 *       单页 base64 撑到几十 MB 会把同步请求直接打死。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-11
 */
public final class PdfPageRasterizer {

    /** 超字节上限后的重试次数（每次按比例再缩一档） */
    private static final int SHRINK_RETRIES = 2;
    private static final double SHRINK_FACTOR = 0.7d;

    private final OcrConfig config;

    public PdfPageRasterizer(OcrConfig config) {
        this.config = config == null ? new OcrConfig() : config;
    }

    /**
     * 一页的栅格产物。
     *
     * @param bytes    图片字节
     * @param mimeType {@code image/jpeg} 或 {@code image/png}
     * @param width    编码后像素宽
     * @param height   编码后像素高
     */
    public record PageImage(byte[] bytes, String mimeType, int width, int height) {
    }

    /** 每个 PDDocument 建一次渲染器，逐页复用（PDFBox 的渲染器内部有字体与资源缓存） */
    public PDFRenderer rendererFor(org.apache.pdfbox.pdmodel.PDDocument pdf) {
        return new PDFRenderer(pdf);
    }

    /**
     * 渲染一页。
     *
     * @param renderer  {@link #rendererFor} 的产物
     * @param pageIndex 页序号，从 0 开始
     * @return 栅格图；超过字节上限且缩不动时返回 {@code null}
     * @throws IOException 页面不可渲染（损坏或字体缺失）
     */
    public PageImage render(PDFRenderer renderer, int pageIndex) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, config.getDpi(), ImageType.RGB);
        image = fit(image, config.getMaxSide());
        double scale = 1d;
        for (int attempt = 0; attempt <= SHRINK_RETRIES; attempt++) {
            BufferedImage candidate = scale == 1d ? image : scale(image, scale);
            byte[] bytes = encode(candidate);
            if (bytes != null && bytes.length <= config.getMaxBytes()) {
                return new PageImage(bytes, mimeType(), candidate.getWidth(), candidate.getHeight());
            }
            scale *= SHRINK_FACTOR;
        }
        return null;
    }

    /** 编码格式对应的 MIME（默认 jpeg：同画质下体积是 PNG 的几分之一） */
    public String mimeType() {
        return config.isPng() ? "image/png" : "image/jpeg";
    }

    private byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.min(8 * 1024 * 1024, Math.max(1024, image.getWidth() * image.getHeight())));
        if (config.isPng()) {
            ImageIO.write(toRgb(image), "png", out);
            return out.toByteArray();
        }
        return encodeJpeg(toRgb(image), out);
    }

    private byte[] encodeJpeg(BufferedImage image, ByteArrayOutputStream out) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(config.getJpegQuality());
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 长边压到 maxSide 以内（maxSide≤0 表示不限） */
    private BufferedImage fit(BufferedImage image, int maxSide) {
        if (maxSide <= 0) {
            return image;
        }
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= maxSide) {
            return image;
        }
        return scale(image, (double) maxSide / longest);
    }

    private BufferedImage scale(BufferedImage image, double factor) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /** JPEG 不接受带 alpha 的位图，统一压到白底 RGB */
    private BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    /**
     * 栅格化参数（与 {@code DocumentModuleProperties.Ocr} 同名字段，模块内不引 Spring 配置类）。
     */
    public static class OcrConfig {
        /** 渲染 DPI */
        private float dpi = 200f;
        /** 长边像素上限（≤0 不限） */
        private int maxSide = 2048;
        /** 编码格式：jpeg / png */
        private String format = "jpeg";
        /** JPEG 质量（0~1） */
        private float jpegQuality = 0.9f;
        /** 单页字节上限（超出先降 DPI 比例重编码，仍超则放弃该页） */
        private long maxBytes = 6 * 1024 * 1024L;

        public float getDpi() {
            return dpi > 0 ? dpi : 200f;
        }

        public OcrConfig setDpi(float dpi) {
            this.dpi = dpi;
            return this;
        }

        public int getMaxSide() {
            return maxSide;
        }

        public OcrConfig setMaxSide(int maxSide) {
            this.maxSide = maxSide;
            return this;
        }

        public String getFormat() {
            return format;
        }

        public OcrConfig setFormat(String format) {
            this.format = format;
            return this;
        }

        public float getJpegQuality() {
            return jpegQuality <= 0 || jpegQuality > 1 ? 0.9f : jpegQuality;
        }

        public OcrConfig setJpegQuality(float jpegQuality) {
            this.jpegQuality = jpegQuality;
            return this;
        }

        public long getMaxBytes() {
            return maxBytes > 0 ? maxBytes : 6 * 1024 * 1024L;
        }

        public OcrConfig setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        /** 是否用 PNG 编码（其余值一律按 jpeg） */
        public boolean isPng() {
            return format != null && "png".equalsIgnoreCase(format.trim().toLowerCase(Locale.ROOT));
        }
    }
}
