package tech.qunxu.module.document.api;

import tech.qunxu.module.document.support.Sha256Util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 转换选项（对应 docling 的 PipelineOptions，但只描述「一次解析」的开关）。
 * <p>
 * 各 Backend 按需读取自己关心的字段（{@link #extractImages} / {@link #maxPages} /
 * {@link #sheetNames} / {@link #language}…），互不干扰；OCR 一组开关（{@link #doOcr} 等）
 * 对应 docling {@code PdfPipelineOptions} 的 {@code do_ocr} / {@code ocr_options}，由后处理链消费；
 * 无法用固定字段表达的开关放进 {@link #attribute(String, String)}，由具体 Backend 自取。
 * <p>
 * {@link #fingerprint()} 参与解析缓存键：<b>凡是影响解析结果的开关都必须进入指纹</b>，
 * 否则会出现「关了图片却命中带图片的缓存」这类脏读。因此这里刻意不排除 {@code forceBackend}
 * 之外的任何字段，且对 map 键排序保证稳定。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class ConvertOptions {

    /** 强制指定 Backend key（优先级最高，对应 L2/L3 逃生舱） */
    private String forceBackend;
    /** 是否抽取并落地内嵌图片（PDF/Word/pptx） */
    private boolean extractImages = true;
    /** 只解析前 N 页（PDF/pptx），null 表示不限 */
    private Integer maxPages;
    /** 只解析指定 Sheet（表格类），null 或空表示全部 */
    private List<String> sheetNames;
    /** 主语言（BCP-47，如 zh-CN/en），为后续 OCR/版面的语言参数留位 */
    private String language;
    /**
     * 强制重解析：跳过缓存读取（结果仍回写覆盖旧项）。
     * <b>不进指纹</b>——它不改变解析结果，只改变是否复用缓存；进指纹会让一次重解析产生一条永久新缓存。
     */
    private boolean forceReparse;
    /**
     * 是否对无文字层的页做 OCR（对应 docling 的 {@code do_ocr}）。
     * 默认关：OCR 是花钱的外部模型调用，不请求就不做。
     */
    private boolean doOcr;
    /**
     * OCR 引擎标识（对应 docling {@code ocr_options} 的引擎选择），由宿主填成
     * {@code providerType:模型名} 之类可读且随模型变化的串 —— 它进指纹，换模型才不会命中旧缓存。
     */
    private String ocrEngine;
    /**
     * 是否连有文字层的页也整页重识别（对应 docling 的 {@code force_full_page_ocr}）。
     * 默认关：只补文本层缺失的页，混合文档不为已抽到的字再付一次钱。
     */
    private boolean ocrFullPage;
    /** 单篇最多 OCR 几页（成本与时长闸门），null 表示用部署配置的默认值 */
    private Integer ocrMaxPages;
    /** Backend 私有开关 */
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public static ConvertOptions defaults() {
        return new ConvertOptions();
    }

    /** 复制一份（避免调用方与缓存键计算之间相互篡改） */
    public ConvertOptions copy() {
        ConvertOptions copy = new ConvertOptions();
        copy.forceBackend = this.forceBackend;
        copy.extractImages = this.extractImages;
        copy.maxPages = this.maxPages;
        copy.sheetNames = this.sheetNames == null ? null : new ArrayList<>(this.sheetNames);
        copy.language = this.language;
        copy.forceReparse = this.forceReparse;
        copy.doOcr = this.doOcr;
        copy.ocrEngine = this.ocrEngine;
        copy.ocrFullPage = this.ocrFullPage;
        copy.ocrMaxPages = this.ocrMaxPages;
        copy.attributes.putAll(this.attributes);
        return copy;
    }

    /**
     * 影响解析结果的选项指纹（SHA-256，十六进制小写）。
     * <p>
     * 布尔/数值/字符串按 {@code key=value} 拼接，集合与 map 排序后拼接，null 与空值归一为空串，
     * 使「语义相同、写法不同」的选项得到同一个键。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append("extractImages=").append(extractImages).append('|');
        sb.append("maxPages=").append(maxPages == null ? "" : maxPages).append('|');
        sb.append("language=").append(lower(language)).append('|');
        sb.append("sheetNames=").append(sortedJoined(sheetNames)).append('|');
        // OCR 段只在开启时追加：默认关的调用方拿到的指纹串与改造前逐字节一致，
        // 否则加这几个字段本身就把宿主全部存量解析缓存打成永久未命中
        if (doOcr) {
            sb.append("doOcr=true|");
            sb.append("ocrEngine=").append(lower(ocrEngine)).append('|');
            sb.append("ocrFullPage=").append(ocrFullPage).append('|');
            sb.append("ocrMaxPages=").append(ocrMaxPages == null ? "" : ocrMaxPages).append('|');
        }
        if (!attributes.isEmpty()) {
            sb.append("attrs=");
            new TreeMap<>(attributes).forEach((k, v) -> sb.append(k).append('=').append(nullToEmpty(v)).append(';'));
        }
        return Sha256Util.sha256(sb.toString());
    }

    /** 人类可读摘要（日志与 warnings 用，不用于缓存键） */
    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add("images=" + extractImages);
        if (maxPages != null) {
            parts.add("maxPages=" + maxPages);
        }
        if (sheetNames != null && !sheetNames.isEmpty()) {
            parts.add("sheets=" + sheetNames);
        }
        if (language != null && !language.isBlank()) {
            parts.add("lang=" + language);
        }
        if (forceBackend != null && !forceBackend.isBlank()) {
            parts.add("backend=" + forceBackend);
        }
        if (doOcr) {
            parts.add("ocr=" + (ocrEngine == null || ocrEngine.isBlank() ? "on" : ocrEngine));
            if (ocrFullPage) {
                parts.add("ocrFullPage");
            }
            if (ocrMaxPages != null) {
                parts.add("ocrMaxPages=" + ocrMaxPages);
            }
        }
        new TreeMap<>(attributes).forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(", ", parts);
    }

    private String sortedJoined(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> copy = new ArrayList<>();
        for (String value : values) {
            copy.add(lower(value));
        }
        copy.sort(String::compareTo);
        return String.join(",", copy);
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    // ==================== 取值 ====================

    public String getForceBackend() {
        return forceBackend;
    }

    public boolean isExtractImages() {
        return extractImages;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public List<String> getSheetNames() {
        return sheetNames;
    }

    /** Sheet 是否被选中（空白名单=全选；大小写不敏感） */
    public boolean sheetSelected(String name) {
        if (sheetNames == null || sheetNames.isEmpty()) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String target = name.trim().toLowerCase(Locale.ROOT);
        for (String candidate : sheetNames) {
            if (candidate != null && candidate.trim().toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 页序是否超出 maxPages */
    public boolean pageAllowed(int pageNumber) {
        return maxPages == null || maxPages <= 0 || pageNumber <= maxPages;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isForceReparse() {
        return forceReparse;
    }

    public boolean isDoOcr() {
        return doOcr;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    public boolean isOcrFullPage() {
        return ocrFullPage;
    }

    public Integer getOcrMaxPages() {
        return ocrMaxPages;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    public boolean booleanAttribute(String key, boolean defaultValue) {
        String value = attributes.get(key);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public int intAttribute(String key, int defaultValue) {
        String value = attributes.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== 流式赋值 ====================

    public ConvertOptions forceBackend(String backendKey) {
        this.forceBackend = backendKey;
        return this;
    }

    public ConvertOptions extractImages(boolean value) {
        this.extractImages = value;
        return this;
    }

    public ConvertOptions maxPages(Integer value) {
        this.maxPages = value;
        return this;
    }

    public ConvertOptions sheetNames(List<String> value) {
        this.sheetNames = value == null || value.isEmpty() ? null : new ArrayList<>(value);
        return this;
    }

    public ConvertOptions language(String value) {
        this.language = value;
        return this;
    }

    public ConvertOptions forceReparse(boolean value) {
        this.forceReparse = value;
        return this;
    }

    public ConvertOptions doOcr(boolean value) {
        this.doOcr = value;
        return this;
    }

    public ConvertOptions ocrEngine(String value) {
        this.ocrEngine = value;
        return this;
    }

    public ConvertOptions ocrFullPage(boolean value) {
        this.ocrFullPage = value;
        return this;
    }

    public ConvertOptions ocrMaxPages(Integer value) {
        this.ocrMaxPages = value == null || value <= 0 ? null : value;
        return this;
    }

    public ConvertOptions attribute(String key, String value) {
        if (key != null && !key.isBlank()) {
            attributes.put(key.trim(), value);
        }
        return this;
    }

    @Override
    public String toString() {
        return describe();
    }
}
