package tech.qunxu.module.document.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tech.qunxu.module.document.api.DocumentConverter;
import tech.qunxu.module.document.cache.ConversionCacheStore;
import tech.qunxu.module.document.chunk.ChunkerRegistry;
import tech.qunxu.module.document.image.ImageSink;
import tech.qunxu.module.document.image.LocalDirImageSink;
import tech.qunxu.module.document.ocr.PageOcr;
import tech.qunxu.module.document.post.OcrPostProcessor;
import tech.qunxu.module.document.source.UrlDownloader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置装配冒烟测试：用 {@link ApplicationContextRunner} 独立拉起
 * {@link DocumentAutoConfiguration}，验证 core 的纯 Java Bean 在 Spring 容器里能被正确接线。
 * <p>
 * 注意：这里不启动完整 Spring Boot 应用，只验证本模块自动配置本身。
 */
class DocumentAutoConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            // 真实应用中 ConfigurationPropertiesAutoConfiguration 由 Boot 自动配置提供；
            // 测试里显式带上，否则 @ConfigurationProperties 绑定不生效
            .withConfiguration(AutoConfigurations.of(
                    DocumentAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class));

    @Test
    void wiresConverterAndCoreBeans() {
        context.run(ctx -> assertThat(ctx)
                .hasSingleBean(DocumentConverter.class)
                .hasSingleBean(ChunkerRegistry.class)
                .hasBean("documentModuleProperties")
                .hasSingleBean(ConversionCacheStore.class)
                .hasSingleBean(UrlDownloader.class)
                .hasSingleBean(OcrPostProcessor.class)
                // 没有宿主提供 PageOcr 时，OCR 引擎不装配，但后处理器仍在（只告警不识别）
                .doesNotHaveBean(PageOcr.class));
    }

    @Test
    void propertiesBindViaPrefix() {
        context.withPropertyValues(
                        "qunxu.document.image.sink=local",
                        "qunxu.document.image.local-root=/tmp/qd-test-images")
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(LocalDirImageSink.class)
                        .hasSingleBean(ImageSink.class));
    }
}
