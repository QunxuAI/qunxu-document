package tech.qunxu.module.document.backend.remote;

import tech.qunxu.module.document.api.DocumentFormat;
import tech.qunxu.module.document.backend.DocumentBackend;
import tech.qunxu.module.document.backend.ParseContext;
import tech.qunxu.module.document.model.QunxuDocument;

import java.util.Map;

/**
 * 远程解析 Backend 的契约（本轮<b>只有接口，不发 HTTP</b>）。
 * <p>
 * 面向 MinerU / Docling-serve / PaddleOCR-VL 这类「进程外解析服务」：它们的价值在于
 * 版面模型与 OCR，而这恰好是 Java 侧最难自研的部分。把它们收敛成一个 Backend，
 * 中间格式、缓存、分块、导出全部照旧复用 —— 这正是把 {@link QunxuDocument} 定为契约的收益：
 * <b>换解析引擎不改下游一行代码</b>。
 * <p>
 * 实现约定（写死在契约里，避免各家服务各写一套）：
 * <ol>
 *   <li>请求：{@code POST endpoint}，body 为源文件字节，
 *       multipart 字段名用 {@link #FIELD_FILE}，附加字段用 {@link #fields(ParseContext)}；</li>
 *   <li>响应：服务返回 {@link QunxuDocument} 的 JSON dict（{@code JsonExporter} 格式，
 *       即 docling 的 {@code export_to_dict}），本地用 {@link #decode(String)} 还原；</li>
 *   <li>失败要抛异常，让 {@code DocumentConverter} 走 {@code tika-fallback} 降级，
 *       <b>不要</b>在实现内部吞异常返回空文档（空文档同样触发降级，但错误原因会丢）；</li>
 *   <li>{@code version()} 必须随服务端模型/协议升级递增：远程结果同样进解析缓存，
 *       版本不动会让旧解析结果被永久命中；</li>
 *   <li>超时、重试、熔断属于实现细节（建议用 {@code java.net.http.HttpClient} 的
 *       {@code connectTimeout} + 请求级 {@code timeout}，与 {@code UrlDownloader} 保持一致）。</li>
 * </ol>
 * 通过 L1（注册为 Bean）或 L2（{@code qunxu.document.backends.preferred.pdf = <key>}）
 * 接入即可；{@link #supports(DocumentFormat)} 建议只放行服务真支持的格式，
 * 其余格式继续走本地 Backend，避免把不支持的格式发给远程服务浪费一次往返。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface RemoteDocumentBackend extends DocumentBackend {

    /** multipart 里文件字段名（Docling-serve 用 {@code files}，实现里自行覆盖） */
    String FIELD_FILE = "file";

    /** 远程服务地址（含协议与路径），由配置注入 */
    String endpoint();

    /**
     * 随请求提交的额外表单字段（如 {@code do_ocr=true}、{@code table_mode=accurate}）。
     * <p>
     * 默认空：只由实现自己决定哪些 {@code ConvertOptions} 开关值得上报，
     * 避免把无关开关发给服务端导致参数校验失败。
     *
     * @param context 本次解析上下文
     * @return 字段名 → 值，可为空 Map，不可为 null
     */
    default Map<String, String> fields(ParseContext context) {
        return Map.of();
    }

    /**
     * 把服务返回的 JSON dict 还原成中间格式（缓存与远程传输用的是同一个格式，
     * 因此远程 Backend 的结果天然可被 {@code ConversionCacheStore} 直接落库）。
     */
    default QunxuDocument decode(String documentJson) {
        return QunxuDocument.fromJson(documentJson);
    }

    /**
     * 本轮不实现 HTTP 调用：注册了却没实现传输时给出明确原因，
     * 而不是让调用方拿到一个空文档去猜哪里出了问题。
     */
    @Override
    default QunxuDocument parse(ParseContext context) throws Exception {
        throw new UnsupportedOperationException("远程解析 Backend 尚未实现 HTTP 调用（本轮仅契约）："
                + key() + " -> " + endpoint());
    }
}
