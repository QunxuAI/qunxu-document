package tech.qunxu.module.document.image;

import tech.qunxu.module.document.model.ImageRef;

/**
 * 图片落地 SPI（对应需求「PDF/Word 内嵌图片保存到本地或 OSS/MinIO」）。
 * <p>
 * 模块只定义契约，不依赖任何存储实现：
 * <ul>
 *   <li>{@link NoopImageSink}：只算哈希与尺寸，不落盘（导出 markdown 时图片以 alt 文本呈现）；</li>
 *   <li>{@link LocalDirImageSink}：写配置目录，返回相对路径；</li>
 *   <li>app 侧 {@code FileStorageImageSink}：委托 {@code FileStorageService}，
 *       因此天然跟随 {@code qunxu.storage.type}（local 或 xFileStorage → 阿里云 OSS / MinIO）。</li>
 * </ul>
 * 实现必须线程安全（同一实例可被并发转换复用）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface ImageSink {

    /** 落地方式标识（storage/local/noop） */
    String key();

    /**
     * 保存一张图片。
     *
     * @param data 图片字节
     * @param meta 图片元信息（含哈希、尺寸、序号）
     * @return 引用（{@code storagePath} 供删除/GC，{@code url} 供导出与前端）；
     *         落地失败返回 {@code null}，调用方降级为「只保留 alt 文本」，不阻断转换
     */
    ImageRef save(byte[] data, ImageMeta meta);
}
