package tech.qunxu.module.document.ocr;

/**
 * 页面 OCR 引擎 SPI（模块定契约，宿主给实现，与 {@code ImageSink} 同一套路）。
 * <p>
 * 为什么模块自己不直连 OCR 服务：OCR 模型是「供应商 + API Key + 模型行」的组合，
 * 全部信息在宿主的模型表里；模块反过来依赖模型层就破坏了独立分发的前提。
 * <p>
 * 契约是<b>单页同步识别</b>：入参一页栅格图，出参该页纯文本。不要在这里做任务提交/轮询
 * 之类的异步形态 —— 那属于宿主实现内部的事，{@link #recognize} 必须返回时文字就已可用。
 * <p>
 * 实现注意：
 * <ul>
 *   <li>识别失败要抛异常，由 {@code OcrPostProcessor} 逐页隔离并计入告警，不要返回 null 冒充空白页；</li>
 *   <li>真空白页返回空串（长度 0），后处理据此不产出段落；</li>
 *   <li>换模型/换提示词请改 {@code ConvertOptions#ocrEngine} 的标识串，让缓存指纹跟着变，
 *       否则新模型的效果永远命中不上旧解析结果。</li>
 * </ul>
 *
 * @author ethanwang
 * @since 2026-10-11
 */
public interface PageOcr {

    /**
     * 引擎引用的 {@code ConvertOptions} 属性名：宿主把「用哪个模型」写进这个属性，
     * 后处理原样递到 {@link OcrRequest#engineRef()}。
     * <p>
     * 取属性而不是新增一个固定字段：模块不知道这个串长什么样（可以是模型行 id、也可以是
     * 供应商编码），但它必须进缓存指纹 —— 放进 {@code attributes} 就天然随
     * {@link tech.qunxu.module.document.api.ConvertOptions#fingerprint()} 一起算。
     */
    String ENGINE_REF_ATTRIBUTE = "engineRef";

    /** 引擎标识（日志与告警用，如 {@code model-ocr}） */
    String key();

    /**
     * 识别一页图片。
     *
     * @param request 单页请求（栅格图字节 + 页码 + 语言）
     * @return 该页文本（Markdown 或纯文本均可）；空白页返回空串
     * @throws Exception 调用失败（网络、鉴权、模型异常），由调用方逐页隔离
     */
    String recognize(OcrRequest request) throws Exception;
}
