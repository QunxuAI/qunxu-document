package tech.qunxu.module.document.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qunxu-document 配置（前缀 {@link #PREFIX}，纯 Java POJO，无 Spring 注解）。
 * <p>
 * 绑定由 {@code DocumentAutoConfiguration}（spring-boot-starter 模块）以
 * {@code @Bean + @ConfigurationProperties(prefix = PREFIX)} 方式完成；本类刻意保持零 Spring 依赖，
 * 这样不引入 Spring 也能程序化使用（如 {@code DocumentSampleRunner} 的纯 Java 场景）。
 * 模块内不使用 {@code @Component}：app 的 {@code @ComponentScan("tech.qunxu")}
 * 会扫到子包，若模块自带 stereotype 注解会与自动配置重复注册。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class DocumentModuleProperties {

    /** 配置前缀（starter 侧 {@code @ConfigurationProperties} 绑定与 {@code @ConditionalOnProperty} 共用） */
    public static final String PREFIX = "qunxu.document";

    /** 模块总开关（关闭时 app 侧回退原 Tika 链路） */
    private boolean enabled = true;

    private final Backends backends = new Backends();
    private final Url url = new Url();
    private final Image image = new Image();
    private final Ocr ocr = new Ocr();
    private final Cache cache = new Cache();
    private final Chunk chunk = new Chunk();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Backends getBackends() {
        return backends;
    }

    public Url getUrl() {
        return url;
    }

    public Image getImage() {
        return image;
    }

    public Ocr getOcr() {
        return ocr;
    }

    public Cache getCache() {
        return cache;
    }

    public Chunk getChunk() {
        return chunk;
    }

    /** 解析器路由配置 */
    public static class Backends {

        /**
         * 按格式指定首选实现（L2 扩展点）：key 为格式小写名（pdf/docx/…），value 为 backend key。
         * 例：{@code qunxu.document.backends.preferred.pdf=opendataloader}
         */
        private final Map<String, String> preferred = new LinkedHashMap<>();

        public Map<String, String> getPreferred() {
            return preferred;
        }

        /** 取格式的首选 backend key（大小写不敏感，无配置返回 null） */
        public String preferredOf(String format) {
            if (format == null || format.isBlank()) {
                return null;
            }
            String value = preferred.get(format.trim().toLowerCase(java.util.Locale.ROOT));
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** URL 输入源配置（本轮仅在模块内交付） */
    public static class Url {

        /**
         * 主机白名单：逗号分隔；条目以 {@code .} 开头表示连子域一起放行（如 {@code .qunxu.com}）。
         * <b>为空即禁用 URL 输入源</b>——调用 {@code fromUrl} 直接抛异常，不静默放行任何域名。
         */
        private List<String> allowHosts = new ArrayList<>();
        /** 单次下载字节上限 */
        private long maxBytes = 52_428_800L;
        /** 连接超时（秒） */
        private int connectTimeoutSeconds = 10;
        /** 读取超时（秒） */
        private int readTimeoutSeconds = 60;
        /** 最大跟随重定向次数（每跳重新过 SSRF 校验） */
        private int maxRedirects = 3;
        /** 是否允许解析到回环/私有/链路本地地址（仅本地联调或内网对象存储直链） */
        private boolean allowPrivateNetwork = false;

        public List<String> getAllowHosts() {
            return allowHosts;
        }

        public void setAllowHosts(List<String> allowHosts) {
            this.allowHosts = allowHosts == null ? new ArrayList<>() : allowHosts;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public int getMaxRedirects() {
            return maxRedirects;
        }

        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }

        public boolean isAllowPrivateNetwork() {
            return allowPrivateNetwork;
        }

        public void setAllowPrivateNetwork(boolean allowPrivateNetwork) {
            this.allowPrivateNetwork = allowPrivateNetwork;
        }
    }

    /** 内嵌图片抽取与落地配置 */
    public static class Image {

        /** 是否抽取内嵌图片（关闭时 Backend 跳过图片，导出的 markdown 不含图片链接） */
        private boolean enabled = true;
        /** 落地方式：storage（跟随 qunxu.storage.type）/ local / noop */
        private String sink = "storage";
        /** sink=local 时的根目录 */
        private String localRoot;
        /** 落地路径前缀（storage sink 作为 prePath 提示，local sink 作为子目录） */
        private String basePath = "document-images";
        /** sink=local 时拼展示 URL 的前缀 */
        private String urlPrefix;
        /** 宽度下限（挡图标/线条） */
        private int minWidth = 80;
        /** 高度下限 */
        private int minHeight = 80;
        /** 字节下限 */
        private long minBytes = 10_240L;
        /** 字节上限（超出不落地，防单图撑爆存储） */
        private long maxBytes = 10_485_760L;
        /** 单文档图片数上限（按尝试数计，含被过滤的） */
        private int maxImagesPerDoc = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSink() {
            return sink;
        }

        public void setSink(String sink) {
            this.sink = sink;
        }

        public String getLocalRoot() {
            return localRoot;
        }

        public void setLocalRoot(String localRoot) {
            this.localRoot = localRoot;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        public String getUrlPrefix() {
            return urlPrefix;
        }

        public void setUrlPrefix(String urlPrefix) {
            this.urlPrefix = urlPrefix;
        }

        public int getMinWidth() {
            return minWidth;
        }

        public void setMinWidth(int minWidth) {
            this.minWidth = minWidth;
        }

        public int getMinHeight() {
            return minHeight;
        }

        public void setMinHeight(int minHeight) {
            this.minHeight = minHeight;
        }

        public long getMinBytes() {
            return minBytes;
        }

        public void setMinBytes(long minBytes) {
            this.minBytes = minBytes;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getMaxImagesPerDoc() {
            return maxImagesPerDoc;
        }

        public void setMaxImagesPerDoc(int maxImagesPerDoc) {
            this.maxImagesPerDoc = maxImagesPerDoc;
        }
    }

    /**
     * 扫描件 OCR 配置（栅格化闸门与部署级开关）。
     * <p>
     * 这里只放「一次部署怎么渲染页面、最多肯花多少页」；<b>要不要 OCR 是每次请求决定的</b>
     * （{@code ConvertOptions.doOcr}）：识别是外部付费调用，部署方无权替调用方默认开启。
     * 具体用哪个模型由宿主的 {@code PageOcr} 实现决定，模块配置对它零知识。
     */
    public static class Ocr {

        /** 部署级开关：关闭时不注册 OCR 后处理器，请求 doOcr 也不会识别（用于临时止血外部调用） */
        private boolean enabled = true;
        /** 单篇默认识别页数上限（请求未指定 ocrMaxPages 时使用） */
        private int maxPages = 20;
        /**
         * 单篇 OCR 时长预算（秒，≤0 不限）：分块预览是同步请求，一个卡死的识别服务
         * 乘以页数上限会把请求拖到网关超时，超预算时停下剩下的页并写告警比全部失败有用。
         */
        private int timeoutSeconds = 120;
        /** 页面渲染 DPI */
        private float dpi = 200f;
        /** 长边像素上限（≤0 不限） */
        private int maxSide = 2048;
        /** 栅格图编码格式：jpeg / png */
        private String format = "jpeg";
        /** JPEG 质量（0~1） */
        private float jpegQuality = 0.9f;
        /** 单页字节上限（缩放重试后仍超出则跳过该页） */
        private long maxBytes = 6_400_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public float getDpi() {
            return dpi;
        }

        public void setDpi(float dpi) {
            this.dpi = dpi;
        }

        public int getMaxSide() {
            return maxSide;
        }

        public void setMaxSide(int maxSide) {
            this.maxSide = maxSide;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public float getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(float jpegQuality) {
            this.jpegQuality = jpegQuality;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    /** 解析结果缓存配置 */
    public static class Cache {

        /** 是否启用解析缓存（唯一解析源） */
        private boolean enabled = true;
        /** 中间格式 JSON 超过该字节数时溢出到对象存储，DB 只存路径 */
        private long jsonSpillThreshold = 1_048_576L;
        /** 进程内 LRU 条数上限（未接入持久化 store 时的默认实现） */
        private int memoryMaxEntries = 64;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getJsonSpillThreshold() {
            return jsonSpillThreshold;
        }

        public void setJsonSpillThreshold(long jsonSpillThreshold) {
            this.jsonSpillThreshold = jsonSpillThreshold;
        }

        public int getMemoryMaxEntries() {
            return memoryMaxEntries;
        }

        public void setMemoryMaxEntries(int memoryMaxEntries) {
            this.memoryMaxEntries = memoryMaxEntries;
        }
    }

    /** 分块配置 */
    public static class Chunk {

        /** 单块 token 上限（Spring AI embedding 单文档 7372 上限内留余量） */
        private int maxTokens = 7000;
        /** 块首是否注入标题面包屑 */
        private boolean includeHeadingBreadcrumb = true;
        /** 表格行分块的默认行数（对齐旧 ExcelDocumentSplitter 的 rowsPerChunk） */
        private int rowsPerChunk = 10;

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public boolean isIncludeHeadingBreadcrumb() {
            return includeHeadingBreadcrumb;
        }

        public void setIncludeHeadingBreadcrumb(boolean includeHeadingBreadcrumb) {
            this.includeHeadingBreadcrumb = includeHeadingBreadcrumb;
        }

        public int getRowsPerChunk() {
            return rowsPerChunk;
        }

        public void setRowsPerChunk(int rowsPerChunk) {
            this.rowsPerChunk = rowsPerChunk;
        }
    }
}
