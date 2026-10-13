package tech.qunxu.module.document.api;

/**
 * 转换状态（对应 docling 的 ConversionStatus）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public enum ConversionStatus {

    /** 全部成功 */
    SUCCESS,
    /** 拿到了内容但有告警（如部分图片落地失败、超行数截断） */
    PARTIAL_SUCCESS,
    /** 解析失败（异常或结果为空） */
    FAILURE,
    /** 没有能处理该格式的解析器（含「仅留接口未实现」的格式，如图片） */
    UNSUPPORTED_FORMAT;

    /** 是否可直接使用其 {@code QunxuDocument}（成功或部分成功） */
    public boolean usable() {
        return this == SUCCESS || this == PARTIAL_SUCCESS;
    }
}
