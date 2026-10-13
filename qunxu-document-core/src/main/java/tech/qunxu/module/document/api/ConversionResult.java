package tech.qunxu.module.document.api;

import tech.qunxu.module.document.model.QunxuDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 转换结果（对应 docling 的 ConversionResult）。
 * <p>
 * 携带状态、中间格式、所用 Backend、耗时与是否命中缓存；失败时 {@link #getDocument()} 为
 * {@code null}（或空文档），错误原因在 {@link #getErrors()}，调用方据此决定降级策略。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ConversionResult {

    private ConversionStatus status;
    private QunxuDocument document;
    private String backendKey;
    private long parseMs;
    private boolean fromCache;
    private final List<String> errors = new ArrayList<>();

    public ConversionResult() {
        this.status = ConversionStatus.FAILURE;
    }

    public ConversionResult(ConversionStatus status, QunxuDocument document, String backendKey, long parseMs) {
        this.status = status;
        this.document = document;
        this.backendKey = backendKey;
        this.parseMs = parseMs;
    }

    /** 成功 */
    public static ConversionResult success(QunxuDocument document, String backendKey, long parseMs) {
        ConversionStatus status = document != null && !document.getWarnings().isEmpty()
                ? ConversionStatus.PARTIAL_SUCCESS : ConversionStatus.SUCCESS;
        return new ConversionResult(status, document, backendKey, parseMs);
    }

    /** 失败（带原因） */
    public static ConversionResult failure(String backendKey, String error) {
        ConversionResult result = new ConversionResult(ConversionStatus.FAILURE, null, backendKey, 0);
        result.getErrors().add(error);
        return result;
    }

    /** 没有可用解析器 */
    public static ConversionResult unsupported(String backendKey, String error) {
        ConversionResult result = new ConversionResult(ConversionStatus.UNSUPPORTED_FORMAT, null, backendKey, 0);
        if (error != null) {
            result.getErrors().add(error);
        }
        return result;
    }

    /** 缓存命中：无解析耗时，状态由文档自身告警决定 */
    public static ConversionResult cached(QunxuDocument document, String backendKey) {
        ConversionStatus status = document != null && !document.getWarnings().isEmpty()
                ? ConversionStatus.PARTIAL_SUCCESS : ConversionStatus.SUCCESS;
        ConversionResult result = new ConversionResult(status, document, backendKey, 0);
        result.fromCache = true;
        if (document != null) {
            result.errors.addAll(document.getWarnings());
        }
        return result;
    }

    /** 是否拿到可用内容 */
    public boolean isSuccess() {
        return status != null && status.usable() && document != null;
    }

    /** 追加一条错误 */
    public ConversionResult error(String message) {
        if (message != null) {
            errors.add(message);
        }
        return this;
    }

    public ConversionStatus getStatus() {
        return status;
    }

    public void setStatus(ConversionStatus status) {
        this.status = status;
    }

    public QunxuDocument getDocument() {
        return document;
    }

    public void setDocument(QunxuDocument document) {
        this.document = document;
    }

    public String getBackendKey() {
        return backendKey;
    }

    public void setBackendKey(String backendKey) {
        this.backendKey = backendKey;
    }

    public long getParseMs() {
        return parseMs;
    }

    public void setParseMs(long parseMs) {
        this.parseMs = parseMs;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }

    public List<String> getErrors() {
        return errors;
    }

    @Override
    public String toString() {
        return status + "(" + backendKey + ", " + parseMs + "ms" + (fromCache ? ", cached" : "") + ")"
                + (errors.isEmpty() ? "" : " " + errors);
    }
}
