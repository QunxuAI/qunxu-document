# 未来路线图

本路线图基于对 `qunxu-document` 0.1.0 的代码级审计。条目按主题分组，每条附**代码证据**、意义与大致工作量。
优先级说明：**P0**（基础 / 正确性）、**P1**（采用 / 生态）、**P2**（差异化）。

英文版：[roadmap.md](roadmap.md)

---

## 1. 发布与分发（0.1.x → 1.0）

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 1.1 | 发布到 Maven Central（`tech.qunxu:qunxu-document`），补齐 GPG 签名、sources/javadoc 包与发布流水线 | POM 已含 `licenses`/`developers` 元数据 | Central 是 Java 消费方默认的信任信号 | **P0** |
| 1.2 | 上游 `opendataloader-pdf-core` 发布到 Central 后弃用 JitPack | `pom.xml` 声明了 `jitpack.io` 仓库 + 改写后的坐标 | 第三方构建仓库对部分组织是供应链层面的阻碍 | **P0** |
| 1.3 | Spring Boot 3.x 兼容构件 | `DocumentAutoConfiguration` 面向 Boot 4.1.x（`spring-boot-autoconfigure`）；核心类为纯 Java | Boot 3 仍是生产主力；当前库无法在 Boot 3 应用中自动装配 | **P1** |

## 2. 格式覆盖

探测器（`DocumentFormat`）认识的格式远多于后端实际服务的；没有专用 Backend 的格式会静默落到
`TikaFallbackBackend`（通用文本转储——无结构、无页码、无表格）。

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 2.1 | 旧版二进制 Office：`.doc` / `.ppt` | `MsWordDocumentBackend.supports == DOCX only`；`PowerPointDocumentBackend.supports == PPTX only` | POI 的 HSSF/HSLF 可结构化解析；目前只得到扁平的 Tika 文本 | **P0**（存量旧文档常见） |
| 2.2 | 语义化 HTML → Markdown/QunxuDocument | `DocumentFormat.HTML` 存在；无 `HtmlDocumentBackend`；`TikaFallbackBackend.supports != IMAGE` 会兜住 | 网页文章 / 导出的网页是 RAG 主要来源之一；今天只能拿到非结构化文本 | **P1** |
| 2.3 | `ODT` / `EPUB` / `RTF` 专用后端 | 格式可识别，仅 Tika 兜底 | 现状是"能解析但无结构"；EPUB 按章节切分对电子书语料价值高 | P2 |
| 2.4 | 图片输入后端（`image` 契约） | `ImageDocumentBackend.parse` 抛 `UnsupportedOperationException`；`TikaFallbackBackend` 刻意排除 `IMAGE` → `UNSUPPORTED_FORMAT` | 图片目前明确"不支持"；OCR / VLM 描述可解锁 | **P1**（见 §3） |

## 3. OCR / VLM / 远程解析

这是最大的能力杠杆。模块已把**契约**备好，缺的只是引擎。

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 3.1 | 一个参考级本地 OCR 适配器（Tesseract JNA）实现 `PageOcr` | `PageOcr.recognize(OcrRequest)` + `PdfPageRasterizer` + `OcrPostProcessor` 已全链路打通，只缺引擎 | 扫描件 PDF 目前除非宿主接入 OCR，否则大声报错 | **P1** |
| 3.2 | 实现一个 `RemoteDocumentBackend`（Docling-serve / MinerU / PaddleOCR-VL 适配器） | `RemoteDocumentBackend` 只有契约（`parse` 抛 `UnsupportedOperationException`），`endpoint()`/`fields()`/`decode()` 已定好 | 桥接 Python 版面/OCR 生态，下游零改动复用整条链路——这是设计上回报最大的点 | **P1** |
| 3.3 | 经 `ImageDocumentBackend` 做 VLM 图片描述（PictureItem + altText） | `ImageDocumentBackend` javadoc 已给出契约；`DocumentPostProcessor` 链已存在 | 图片密集的 PDF 获得 alt 文本，而不是裸 `![…]` 链接 | P2 |

## 4. 正确性与质量

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 4.1 | 逐 Backend 的 JUnit 套件（目前只靠 69 项自检端到端覆盖） | 仅有 `DocumentSampleRunnerTest` + 一个模型往返测试 | 今天要定位某个 Backend 的回归，得跑整个样本生成器 | **P0** |
| 4.2 | CJK 感知的 token 计数 | `JTokkitTokenCounter` 文档注明 CL100K 对 CJK 保守（约 1 字 1.3~1.5 token），刻意偏保守 | 中文分块偏小；经 `TokenCounter` SPI 换用与 embedding 同一标准的计数，可保住"单一标准"保证 | **P1** |
| 4.3 | 对不可信输入的属性/模糊测试（任意字节、截断的 office 文件、PDF 炸弹） | 解析器经 `DocumentSource` 直接消费原始字节；有预算但无模糊测试框架 | 解析库长期处于攻击面上；模糊测试是性价比最高的加固手段 | **P1** |
| 4.4 | 导出器 golden-file 快照测试（markdown/html/text/json） | 导出器是 `QunxuDocument` 的纯函数 | 廉价、高信号，防静默输出漂移 | P2 |

## 5. 性能与内存

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 5.1 | 流式 / 按大小分级的输入 | `convert()` 调用 `source.bytes()` + `source.sha256()`——整文件进内存；`safeSize` 只设上限 | 100MB+ 文档内存翻倍（字节 + 解析模型）；需要磁盘溢出或基于 `InputStream` 的后端 | **P1** |
| 5.2 | 按字节感知的缓存淘汰 | `InMemoryConversionCache` 仅按条数 LRU；`json-spill-threshold` 属性存在但无存储实现它 | 一个 40MB 的 JSON 条目可能挤掉几十个小条目；需要字节预算或换 Caffeine | **P1** |
| 5.3 | `OpenDataLoaderPdfBackend` 并发审计 | javadoc 警告 `DocumentProcessor` 有静态可变状态 + 一次临时文件往返 | 避免并发 PDF 任务下的静默损坏；加池或守卫 | **P1** |
| 5.4 | 逐格式基准测试（`perfs/`） | 无基准框架 | 解析吞吐/延迟是核心卖点，需要用数据支撑性能声明 | **P1** |
| 5.5 | PDF 文本/OCR 的可选并行逐页处理 | 后端串行解析；`SingleFlight` 只去重同文件的并发调用 | 多页 PDF 是延迟长尾 | P2 |

## 6. 安全加固

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 6.1 | OOXML / zip 炸弹防护（条目数、压缩比、总大小） | 图片/OCR/下载有预算，但容器文件未经检查直接打开 | DOCX/XLSX 是 zip 容器——经典 zip 炸弹面 | **P0** |
| 6.2 | 解析前对 PDF 对象数 / xref 做合理性检查 | PDF 预算仅按页/图 | 恶意 PDF 可在页数上限生效前耗尽内存 | **P0** |
| 6.3 | CI 中加入 SSRF + URL 输入源端到端测试 | `SsrfGuard` 只在自检里做了单测 | 网络输入值得专项端到端测试（重定向链、DNS 重绑定场景） | P2 |
| 6.4 | 依赖 SBOM + 签名校验 | 版本已钉死；JitPack 是构建服务信任点 | 满足企业供应链预期（CI 中生成 CycloneDX） | P2 |

## 7. 可观测性与生态

| # | 事项 | 代码证据 | 意义 | 优先级 |
| --- | --- | --- | --- | --- |
| 7.1 | 处于 Spring 上下文时接入 Micrometer 指标（解析耗时、缓存命中率、降级次数、OCR 成本） | `DocumentConverter.stats()` + `InMemoryConversionCache` 的 hit/miss 计数存在但未接 Micrometer | RAG 导入流水线在生产环境需要这些面板 | **P1** |
| 7.2 | 文档站点（mkdocs/GitHub Pages）+ Javadoc | 目前只有 Markdown 文档 | 对标 docling 的可发现性；API 参考是用户第一眼要找的东西 | **P1** |
| 7.3 | `scripts/` 可复现依赖辅助（被 JitPack 阻断的组织可用的 vendoring / 镜像指引） | 已写进 `docs/dependencies.md`，未自动化 | 消除第一大采用阻碍 | P2 |

---

## 北极星

在 RAG 场景下达到 docling 的功能水平，同时默认不引入 Python 生态：

1. **发布** —— Central、Boot 3 构件、core/starter 拆分（1.x）。
2. **覆盖** —— 探测器认识的每种格式都有*结构化*后端，而不是 Tika 文本转储（2.x）。
3. **桥接** —— `RemoteDocumentBackend` 适配器对接版面/OCR 引擎（MinerU / Docling-serve / PaddleOCR-VL），
   需要时按需获得版面模型级质量；离线场景用本地 Tesseract。
4. **信任** —— 模糊测试 + 预算 + SBOM + 基准测试，把"不可信输入"变成有文档、有测试的属性。
5. **差异化** —— CJK token 感知与 SSRF 优先的 URL 输入，是 docling 没有的卖点。
