package tech.qunxu.module.document.api;

/**
 * 转换器定制钩子（L3 扩展点，仿 {@code WebMvcConfigurer}）。
 * <p>
 * 与 {@link DocumentConverter#register} 的区别：本钩子在 <b>应用启动期</b>由自动配置统一调用，
 * 适合「按环境注册测试桩 / 灰度替换某个格式的实现」，而不需要在运行期任意时刻改注册表。
 * <p>
 * 用法（在 app 侧）：
 * <pre>{@code
 * @Bean
 * DocumentConverterCustomizer grayPdfBackend() {
 *     return converter -> {
 *         converter.register(new MyPdfBackend());
 *         converter.preferBackend("pdf", "my-pdf");
 *     };
 * }
 * }</pre>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
@FunctionalInterface
public interface DocumentConverterCustomizer {

    /** 定制转换器（注册解析器/导出器/后处理器、调整首选实现） */
    void customize(DocumentConverter converter);

    /** 多个定制器时的执行顺序 */
    default int order() {
        return 0;
    }
}
