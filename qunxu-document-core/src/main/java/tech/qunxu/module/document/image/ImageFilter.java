package tech.qunxu.module.document.image;

/**
 * 图片过滤规则（映射 {@code qunxu.document.image.*}）：挡掉 logo、图标、分隔线等装饰图。
 * <p>
 * 尺寸未知（0）时不按宽高拒绝，只按字节量判断，避免「探测失败就丢图」。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ImageFilter {

    private int minWidth;
    private int minHeight;
    private long minBytes;
    private long maxBytes;
    private int maxImagesPerDoc;

    public ImageFilter() {
        this(80, 80, 10_240L, 10_485_760L, 200);
    }

    public ImageFilter(int minWidth, int minHeight, long minBytes, long maxBytes, int maxImagesPerDoc) {
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.minBytes = minBytes;
        this.maxBytes = maxBytes;
        this.maxImagesPerDoc = maxImagesPerDoc;
    }

    /**
     * 判定是否接受该图片。
     *
     * @param bytes  图片字节数
     * @param width  像素宽，未知传 0
     * @param height 像素高，未知传 0
     * @param seq    文档内序号（从 0 开始）
     * @return 拒绝原因；{@code null} 表示接受
     */
    public String rejectReason(long bytes, int width, int height, int seq) {
        if (bytes <= 0) {
            return "空图片";
        }
        if (maxBytes > 0 && bytes > maxBytes) {
            return "字节数 " + bytes + " 超过上限 " + maxBytes;
        }
        if (minBytes > 0 && bytes < minBytes) {
            return "字节数 " + bytes + " 小于下限 " + minBytes;
        }
        if (minWidth > 0 && width > 0 && width < minWidth) {
            return "宽度 " + width + " 小于下限 " + minWidth;
        }
        if (minHeight > 0 && height > 0 && height < minHeight) {
            return "高度 " + height + " 小于下限 " + minHeight;
        }
        if (maxImagesPerDoc > 0 && seq >= maxImagesPerDoc) {
            return "文档图片数超过上限 " + maxImagesPerDoc;
        }
        return null;
    }

    /** 是否属于「尺寸过小」的装饰图（用于日志分级：装饰图丢弃属正常，不必告警） */
    public boolean isDecoration(long bytes, int width, int height) {
        return (minBytes > 0 && bytes < minBytes)
                || (minWidth > 0 && width > 0 && width < minWidth)
                || (minHeight > 0 && height > 0 && height < minHeight);
    }

    public int getMinWidth() {
        return minWidth;
    }

    public void setMinWidth(int minWidth) {
        this.minWidth = minWidth;
    }

    public int getMinHeight() {
        return minHeight;
    }

    public void setMinHeight(int minHeight) {
        this.minHeight = minHeight;
    }

    public long getMinBytes() {
        return minBytes;
    }

    public void setMinBytes(long minBytes) {
        this.minBytes = minBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxImagesPerDoc() {
        return maxImagesPerDoc;
    }

    public void setMaxImagesPerDoc(int maxImagesPerDoc) {
        this.maxImagesPerDoc = maxImagesPerDoc;
    }
}
