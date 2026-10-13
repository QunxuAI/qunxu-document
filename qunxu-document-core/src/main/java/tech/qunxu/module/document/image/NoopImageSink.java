package tech.qunxu.module.document.image;

import tech.qunxu.module.document.model.ImageRef;

/**
 * 不落地实现：只保留哈希与尺寸，用于「只要文本、不要图片」的转换（如纯索引或单测）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class NoopImageSink implements ImageSink {

    @Override
    public String key() {
        return "noop";
    }

    @Override
    public ImageRef save(byte[] data, ImageMeta meta) {
        return new ImageRef(null, null, meta.fileNameHint(), meta.contentType(),
                meta.width(), meta.height(), data == null ? 0 : data.length, meta.sha256());
    }
}
