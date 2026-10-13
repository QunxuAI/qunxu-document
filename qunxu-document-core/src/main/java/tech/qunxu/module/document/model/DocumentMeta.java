package tech.qunxu.module.document.model;

import java.io.Serializable;

/**
 * 文档元信息：来源、内容指纹、所用解析器与耗时。
 * <p>
 * {@code fileHash} 是「唯一解析源 + 缓存」的键基础（内容哈希，与文件名/URL 无关）；
 * {@code backendKey} + {@code backendVersion} 标识产出该中间格式的解析器，
 * 解析器升级后缓存键自然失效，无需手工清缓存。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class DocumentMeta implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 来源文件名（含扩展名） */
    private String sourceName;
    /** 源文件内容 SHA-256（十六进制小写） */
    private String fileHash;
    /** 文件类型（小写扩展名，如 docx/pdf/xlsx） */
    private String fileType;
    /** MIME 类型（探测所得，可为 null） */
    private String mimeType;
    /** 页数（PDF/pptx 有值，其余为 0） */
    private int pageCount;
    /** 产出本文档的解析器标识 */
    private String backendKey;
    /** 产出本文档的解析器版本 */
    private int backendVersion;
    /** 解析耗时（毫秒）；缓存命中时为 0 */
    private long parseMs;
    /** URL 输入源的原始地址（本地/字节输入为 null） */
    private String originUrl;
    /** 源文件字节数 */
    private long fileSize;

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public String getBackendKey() {
        return backendKey;
    }

    public void setBackendKey(String backendKey) {
        this.backendKey = backendKey;
    }

    public int getBackendVersion() {
        return backendVersion;
    }

    public void setBackendVersion(int backendVersion) {
        this.backendVersion = backendVersion;
    }

    public long getParseMs() {
        return parseMs;
    }

    public void setParseMs(long parseMs) {
        this.parseMs = parseMs;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public void setOriginUrl(String originUrl) {
        this.originUrl = originUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
}
