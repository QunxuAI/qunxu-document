[English](README.en.md) | **简体中文**

# qunxu-document

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://adoptium.net)

**Docling 式架构的文档解析库**，面向 RAG / 知识库 / AI 应用：

```
文件 / URL ──► 格式专用 DocumentBackend ──► 统一中间格式 QunxuDocument ──► Markdown / HTML / JSON / Text ──► 分块
                  (POI · PDFBox · Tika · OpenDataLoader)                                    (token 预算感知)
```

格式差异止于 Backend：导出、分块、缓存、检索链路只认统一的 `QunxuDocument` 模型，新增一种格式 = 新增一个 Backend。

## 特性

- **8 个内置解析器**：docx、pptx、xlsx/csv（合并单元格感知）、markdown、纯文本，以及三条 PDF 路径——
  OpenDataLoader（元素级 bbox / 阅读顺序 / 标题层级 / 合并单元格）、PDFBox/Tika（文本 + 内嵌图）、Tika 兜底；
  PDF 解析失败时从结构化优雅降级到 `tika-pdf`。
- **统一模型**：`QunxuDocument` = 页面表 + 扁平类型化节点列表（`TITLE` / `SECTION_HEADER` / `PARAGRAPH` / `TABLE`
  / `PICTURE` / `CODE` / `LIST_ITEM` …），带页码与 bbox 溯源，可支撑引用 / 事实核查。
- **4 个导出器**：Markdown、HTML、JSON（可往返还原）、纯文本。
- **3 个分块器 + 自动选型**：层级分块（标题面包屑）、表格按行分块（Sheet/表头感知）、扁平分块；
  `AutoChunker` 按文档结构自动选型；token 预算用 CL100K_BASE（jtokkit）计量，安全落在 embedding 上限内。
- **解析缓存**：内容指纹为键，开箱为进程内 LRU，可经 SPI 替换为持久化实现。
- **SSRF 防护的 URL 输入源**：主机白名单、重定向逐跳复检、默认拦截回环/私网/链路本地地址。
- **扫描件 OCR 闸门**：页级栅格化 + `PageOcr` SPI；钱只花在缺文字层的页上、每页只花一次，
  页数/超时预算以告警呈现（绝不静默交半份正文）。
- **水印与隐形文字层处理**：剔除水印（含 Form 旋转低透明水印），同时刻意保留可检索的隐形 OCR 文字层。
- **零 `@Component`**：全部 Bean 由单个 `DocumentAutoConfiguration`（Spring Boot 自动配置）装配，
  不会与宿主组件扫描冲突；核心类也是纯 Java，不依赖 Spring 也可用。

## 支持的格式

| 格式 | Backend key | 说明 |
| --- | --- | --- |
| PDF | `opendataloader` / `tika-pdf` / `tika-fallback` | 结构化 → 降级 → 兜底 |
| DOCX | `poi-docx` | 标题、表格、内嵌图 |
| PPTX | `poi-pptx` | 幻灯片即页面、占位符文本 |
| XLSX | `tabular` | Sheet 页面、合并单元格感知 |
| CSV | `tabular` | 编码探测（兼容 GBK） |
| Markdown | `markdown` | ATX 标题、列表、代码块 |
| 纯文本 | `plaintext` | |
| 其它 | `tika-fallback` | Tika 兜底 |

## 模块划分

工程拆分为三个 Maven 构件（对齐 docling-java 的 `docling-core` + `docling-bom` 布局）：

| 构件 | 说明 | 依赖 |
| --- | --- | --- |
| `qunxu-document-core` | 纯 Java 库：解析器、统一模型、导出器、分块器、缓存、OCR 闸门、SSRF 防护 URL 源、自检。**零 Spring 依赖** | slf4j、fastjson2、POI、PDFBox、Tika、OpenDataLoader、hutool、commons-io、jtokkit |
| `qunxu-document-spring-boot-starter` | Spring Boot 自动配置：经 `AutoConfiguration.imports` 装配全部 core Bean（依旧**零 `@Component`**，全部 `@ConditionalOnMissingBean`，宿主可经 SPI 覆盖） | `qunxu-document-core` + `spring-boot-autoconfigure` |
| `qunxu-document-bom` | 版本物料清单（无代码）。import 一次即可集中管理全部 qunxu-document 构件版本 | — |

- Spring Boot 4 应用 → 用 **starter**；
- 纯 Java / Boot 3 / 非 Spring 宿主 → 用 **core**，参照 `DocumentSampleRunner` 手动组装；
- 想集中管版本（像 Spring Boot 自己的 BOM 那样）→ import **`qunxu-document-bom`**。

## 快速开始

### 1. 引入依赖

**Spring Boot 4 应用**（自动装配）：

```xml
<dependency>
    <groupId>tech.qunxu</groupId>
    <artifactId>qunxu-document-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

**纯 Java（无 Spring）**：

```xml
<dependency>
    <groupId>tech.qunxu</groupId>
    <artifactId>qunxu-document-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

**集中管理版本（可选）** —— import 一次 BOM，之后免写版本号：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>tech.qunxu</groupId>
            <artifactId>qunxu-document-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>tech.qunxu</groupId>
        <artifactId>qunxu-document-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

> 结构化 PDF 后端（`opendataloader-pdf-core`）尚未发布到 Maven Central，POM 已声明
> [JitPack](https://jitpack.io) 仓库，从上游 GitHub 源码自动构建分发；详见 [docs/dependencies.md](docs/dependencies.md)。

### 2. 使用（Spring Boot 4 应用，注入自动装配的 Bean）

```java
@Component
class DocumentIngestService {

    private final DocumentConverter converter;
    private final ChunkerRegistry chunkers;

    public DocumentIngestService(DocumentConverter converter, ChunkerRegistry chunkers) {
        this.converter = converter;
        this.chunkers = chunkers;
    }

    public List<Chunk> ingest(byte[] pdf, String fileName) {
        ConversionResult result = converter.convert(
                DocumentSource.fromBytes(fileName, pdf),
                ConvertOptions.defaults().extractImages(true));
        if (!result.isSuccess()) {
            throw new IllegalStateException("parse failed: " + result.getErrors());
        }
        QunxuDocument document = result.getDocument();
        String markdown = document.exportAsMarkdown();   // 或 exportAsHtml() / exportAsText() / toJson()
        return chunkers.chunk(ChunkerRegistry.AUTO_NAME, document,
                new ChunkOptionsFactory(new DocumentModuleProperties()).create(512, 128, 10, null, null));
    }
}
```

### 3. 不用 Spring 也能跑

核心类均为纯 Java，只有自动配置需要 Spring。引入 `qunxu-document-core` 后参照 `DocumentSampleRunner` 的装配方式
手动组装，或直接跑自带自检/查看器（在 `qunxu-document-core/` 模块目录下）：

```bash
cd qunxu-document-core
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" tech.qunxu.module.document.tool.DocumentSampleRunner --make-samples
```

## 配置（前缀 `qunxu.document`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `qunxu.document.enabled` | `true` | 模块总开关 |
| `qunxu.document.backends.preferred.pdf` | — | 按格式指定首选解析器 |
| `qunxu.document.url.allow-hosts` | 空 | URL 白名单；**为空即禁用 fromUrl** |
| `qunxu.document.url.allow-private-network` | `false` | 是否放行回环/私网（SSRF 闸门） |
| `qunxu.document.image.enabled` | `true` | 是否抽取内嵌图片 |
| `qunxu.document.image.sink` | `storage` | `storage`（实现 `ImageSink`）/ `local` / `noop` |
| `qunxu.document.ocr.enabled` | `true` | OCR 部署开关（每次请求经 `ConvertOptions.doOcr` 决定） |
| `qunxu.document.cache.enabled` | `true` | 解析结果缓存 |
| `qunxu.document.chunk.max-tokens` | `7000` | 单块 token 预算 |

完整参考：[docs/configuration.md](docs/configuration.md)。

## 扩展点（SPI）

全部可在不改库的前提下覆盖（Spring 下以 `@ConditionalOnMissingBean` 生效，或编程式注入）：

| SPI | 用途 |
| --- | --- |
| `DocumentBackend` | 新增文件格式 |
| `DocumentExporter` | 新增输出格式 |
| `BaseChunker` / `ChunkerRegistry` | 自定义分块策略 |
| `ImageSink` | 对象存储图片落地（`storage` sink） |
| `PageOcr` | 接入识别引擎（扫描件） |
| `ConversionCacheStore` | 持久化解析缓存 |
| `DocumentConverterCustomizer` | 运行期增删解析器/导出器 |

## 安全

文档解析本身就是"不可信输入"的攻击面。库内置：

- **URL 输入的 SSRF 闸门**——白名单、重定向逐跳复检、默认拦截回环/私网/链路本地。
- **解析缓存以内容指纹为键**——绝不用路径/文件名，跨租户不会被污染。
- **尺寸/数量上限**——下载大小、图片数量/大小、OCR 页数/超时、token 预算。
- 失败大声降级（状态 + 原因 + 告警），绝不静默产出半成品。

漏洞报告见 [SECURITY.md](SECURITY.md)。

## 文档

- [配置参考](docs/configuration.md) · [English](docs/configuration.en.md)
- [分块与导出](docs/chunking.md) · [English](docs/chunking.en.md)
- [已知限制](docs/limitations.md) · [English](docs/limitations.en.md)
- [依赖与构建可复现性](docs/dependencies.md) · [English](docs/dependencies.en.md)
- [未来路线图](docs/roadmap.md) · [English](docs/roadmap.en.md)
- [变更日志](CHANGELOG.md) · [English](CHANGELOG.en.md)

## License

Apache License 2.0 —— 见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
