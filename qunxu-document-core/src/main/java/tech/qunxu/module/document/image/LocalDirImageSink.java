package tech.qunxu.module.document.image;

import tech.qunxu.module.document.model.ImageRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地目录落地实现：写到 {@code {root}/{base-path}/{fileHash}/{seq}.{ext}}。
 * <p>
 * 与 app 的 {@code FileStorageImageSink} 区别：本实现不经过 {@code FileStorageService}，
 * 适合独立部署/自检场景；{@code urlPrefix} 非空时拼成可访问 URL（如 {@code /document-images}），
 * 为空则 url 与 storagePath 同为相对路径。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class LocalDirImageSink implements ImageSink {

    private static final Logger log = LoggerFactory.getLogger(LocalDirImageSink.class);

    private final Path root;
    private final String basePath;
    private final String urlPrefix;

    /**
     * @param root      本地根目录
     * @param basePath  根下的相对子目录，如 {@code document-images}
     * @param urlPrefix 展示 URL 前缀，可为 null
     */
    public LocalDirImageSink(Path root, String basePath, String urlPrefix) {
        this.root = root == null ? Path.of(System.getProperty("java.io.tmpdir"), "qunxu-document-images") : root;
        this.basePath = basePath == null || basePath.isBlank() ? "document-images" : basePath.trim();
        this.urlPrefix = urlPrefix == null ? "" : urlPrefix.trim();
    }

    @Override
    public String key() {
        return "local";
    }

    @Override
    public ImageRef save(byte[] data, ImageMeta meta) {
        String hash = meta.documentHash() == null || meta.documentHash().isBlank()
                ? "unknown" : meta.documentHash();
        String relative = basePath + "/" + hash + "/" + meta.seq() + "." + meta.extension();
        Path target = root.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("图片落地本地目录失败：{}，{}", target, e.getMessage());
            return null;
        }
        String url = urlPrefix.isEmpty() ? relative : urlPrefix + "/" + relative;
        return new ImageRef(relative, url, Path.of(relative).getFileName().toString(), meta.contentType(),
                meta.width(), meta.height(), data == null ? 0 : data.length, meta.sha256());
    }

    /** 删除落地文件（app 侧清理图片时可用；storagePath 传相对路径） */
    public void delete(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(root.resolve(storagePath));
        } catch (IOException e) {
            log.warn("删除落地图片失败：{}，{}", storagePath, e.getMessage());
        }
    }

    /** 便于复用的临时目录实现（自检用） */
    public static LocalDirImageSink inTempDir() {
        return new LocalDirImageSink(Path.of(System.getProperty("java.io.tmpdir"), "qunxu-document-images"),
                "document-images", null);
    }
}
