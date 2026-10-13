package tech.qunxu.module.document.image;

import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.support.Sha256Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 单次转换的图片抽取器（Backend 与 {@link ImageSink} 之间的唯一通道）。
 * <p>
 * 职责：按内容哈希去重（Word 同一媒体被多处引用只落一份）、套过滤规则、补齐尺寸与 MIME、
 * 维护文档内序号，并把「图片落地失败」降级为警告而不是让整篇转换失败。
 * 每个转换任务使用独立实例（内部有计数与去重表，非线程安全）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ImageExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractor.class);

    private final ImageSink sink;
    private final ImageFilter filter;
    private final String documentHash;
    private final Map<String, ImageRef> byHash = new HashMap<>();

    private int seq;
    private int dropped;
    private int failed;

    public ImageExtractor(ImageSink sink, ImageFilter filter, String documentHash) {
        this.sink = sink == null ? new NoopImageSink() : sink;
        this.filter = filter == null ? new ImageFilter() : filter;
        this.documentHash = documentHash;
    }

    /**
     * 抽取并落地一张图片。
     *
     * @param data         图片字节
     * @param fileNameHint 建议文件名（含扩展名），可为 null
     * @param contentType  MIME，可为 null（按魔数/文件名推断）
     * @param width        像素宽，未知传 null 或 0
     * @param height       像素高，未知传 null 或 0
     * @param sourceName   来源标识（Word media 名 / PDF XObject 名等）
     * @return 图片引用；被过滤、超限或落地失败时返回 {@code null}
     */
    public ImageRef extract(byte[] data, String fileNameHint, String contentType,
                            Integer width, Integer height, String sourceName) {
        if (data == null || data.length == 0) {
            return null;
        }
        String sha256 = Sha256Util.sha256(data);
        ImageRef cached = byHash.get(sha256);
        if (cached != null) {
            // 同一张图在文档中被多次引用：复用已落地结果，不重复占存储
            return cached;
        }
        int w = width == null ? 0 : Math.max(0, width);
        int h = height == null ? 0 : Math.max(0, height);
        if (w <= 0 || h <= 0) {
            int[] probed = probeSize(data);
            if (probed != null) {
                w = probed[0];
                h = probed[1];
            }
        }
        int currentSeq = seq;
        String reason = filter.rejectReason(data.length, w, h, currentSeq);
        if (reason != null) {
            seq++;
            dropped++;
            if (!filter.isDecoration(data.length, w, h)) {
                log.warn("图片被丢弃：{}（{}x{}, {} 字节，来源 {}）", reason, w, h, data.length, sourceName);
            } else {
                log.debug("装饰图被过滤：{}（{}x{}）", reason, w, h);
            }
            return null;
        }
        String mime = resolveMime(contentType, fileNameHint, data);
        String name = resolveName(fileNameHint, sha256, mime);
        ImageMeta meta = new ImageMeta(documentHash, currentSeq, name, mime, w, h, sha256, sourceName);
        ImageRef ref;
        try {
            ref = sink.save(data, meta);
        } catch (Exception e) {
            ref = null;
            log.warn("图片落地异常（sink={}）：{}", sink.key(), e.getMessage());
        }
        seq++;
        if (ref == null) {
            failed++;
            return null;
        }
        byHash.put(sha256, ref);
        return ref;
    }

    /** 建议文件名：无扩展名时按 MIME 补，主体用哈希前 8 位保证稳定且唯一 */
    private String resolveName(String fileNameHint, String sha256, String mime) {
        String ext = extensionOf(fileNameHint);
        if (ext.isEmpty()) {
            ext = extensionOfMime(mime);
        }
        String stem = "img-" + sha256.substring(0, Math.min(8, sha256.length()));
        return ext.isEmpty() ? stem : stem + "." + ext;
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String extensionOfMime(String mime) {
        if (mime == null) {
            return "";
        }
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/webp" -> "webp";
            case "image/tiff" -> "tiff";
            case "image/svg+xml" -> "svg";
            case "image/x-emf" -> "emf";
            case "image/x-wmf" -> "wmf";
            default -> "";
        };
    }

    /** MIME：显式值 → 魔数 → 文件名后缀 →  octet-stream */
    private String resolveMime(String contentType, String fileNameHint, byte[] data) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType;
        }
        String sniffed = sniff(data);
        if (sniffed != null) {
            return sniffed;
        }
        String ext = extensionOf(fileNameHint);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            case "emf" -> "image/x-emf";
            case "wmf" -> "image/x-wmf";
            default -> "application/octet-stream";
        };
    }

    /** 魔数识别（POI/PDFBox 常给出空 MIME） */
    private String sniff(byte[] data) {
        if (data.length < 12) {
            return null;
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return "image/gif";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "image/bmp";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if ((data[0] == 0x49 && data[1] == 0x49 && data[2] == 0x2A && data[3] == 0x00)
                || (data[0] == 0x4D && data[1] == 0x4D && data[2] == 0x00 && data[3] == 0x2A)) {
            return "image/tiff";
        }
        // EMF：0x01 0x00 0x00 0x00 + 记录长度
        if (data[0] == 0x01 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x00) {
            return "image/x-emf";
        }
        return null;
    }

    /**
     * 只读文件头探测像素尺寸（不解码整图）；不支持的格式（EMF/WMF）返回 null。
     */
    private int[] probeSize(byte[] data) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.debug("图片尺寸探测失败：{}", e.getMessage());
            return null;
        }
    }

    /** 已尝试抽取的图片数（含被过滤的，供 warnings 统计） */
    public int attempted() {
        return seq;
    }

    /** 成功落地（或复用）的图片数 */
    public int stored() {
        return byHash.size();
    }

    /** 被规则过滤掉的图片数 */
    public int dropped() {
        return dropped;
    }

    /** 落地失败的图片数 */
    public int failed() {
        return failed;
    }

    /** 是否启用（noop sink 也视为启用，只是不产文件） */
    public boolean enabled() {
        return sink != null;
    }

    public String sinkKey() {
        return sink.key();
    }
}
