package tech.qunxu.module.document.source;

import java.io.IOException;
import java.io.InputStream;

/**
 * 输入流提供者（本地/对象存储路径的惰性读取契约）。
 * <p>
 * 模块不依赖任何存储实现：app 侧用 {@code storageService::readStream} 适配即可，
 * 保持 qunxu-document 可独立分发。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
@FunctionalInterface
public interface StreamOpener {

    /**
     * 打开一次读取流（调用方负责关闭；同一 DocumentSource 可能被多次调用）。
     */
    InputStream open() throws IOException;
}
