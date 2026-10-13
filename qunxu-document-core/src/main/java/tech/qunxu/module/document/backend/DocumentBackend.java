package tech.qunxu.module.document.backend;

import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.model.QunxuDocument;

/**
 * 格式解析器 SPI（对应 docling 的 *DocumentBackend，如 PDFDocumentBackend / MsWordDocumentBackend）。
 * <p>
 * <b>契约：一种格式的差异止于本接口</b> —— 输入一个 {@link ParseContext}，输出统一中间格式
 * {@link QunxuDocument}；导出不感知格式，分块不感知格式，缓存键由「内容哈希 + {@link #key()} +
 * {@link #version()} + 选项指纹」决定。
 * <p>
 * 三级扩展方式（对齐 docling {@code register_custom_converter}，成本递增）：
 * <ol>
 *   <li><b>L1 编译期 SPI</b>：实现本接口并注册为 Spring Bean，{@code DocumentAutoConfiguration}
 *       会注入到 converter（新增格式 = 丢一个 Bean，不改调用方）；</li>
 *   <li><b>L2 配置覆盖</b>：{@code qunxu.document.backends.preferred.&lt;format&gt; = &lt;backendKey&gt;}，
 *       不改代码即可切换实现（如 pdf 由 {@code tika-pdf} 切 {@code opendataloader}）；</li>
 *   <li><b>L3 运行时注册</b>：{@code converter.register(backend)} / {@code unregister(key)}，
 *       或提供 {@code DocumentConverterCustomizer} Bean 钩子（仿 {@code WebMvcConfigurer}）。</li>
 * </ol>
 * 实现必须<b>无状态</b>（同一实例被并发调用），每次解析都从 {@link ParseContext#newDocument()}
 * 新建文档；解析失败请抛异常，由 converter 统一转成
 * {@link tech.qunxu.module.document.api.ConversionStatus#FAILURE}。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface DocumentBackend {

    /**
     * 解析器唯一标识（如 {@code poi-docx} / {@code tika-pdf}）。
     * <p>
     * 该值进入解析缓存键，<b>一旦发布不得改变含义</b>：改变抽取语义时请同时递增 {@link #version()}，
     * 让旧缓存自然失效。
     */
    String key();

    /** 解析器版本，抽取语义变化时递增（默认 1） */
    default int version() {
        return 1;
    }

    /** 展示名（日志与前端），默认取 {@link #key()} */
    default String label() {
        return key();
    }

    /**
     * 同格式多个实现时的优先级，值越小越优先（内置实现集中在 10~100，兜底实现取大值）。
     */
    default int order() {
        return 100;
    }

    /** 是否能处理该格式 */
    boolean supports(DocumentFormat format);

    /**
     * 解析为统一中间格式。
     *
     * @param context 输入源、格式、选项与图片通道
     * @return 中间格式文档（不得返回 null；无内容请返回空文档，由 converter 记告警）
     * @throws Exception 解析失败（格式损坏、依赖缺失等），由 converter 统一降级并记录
     */
    QunxuDocument parse(ParseContext context) throws Exception;
}
