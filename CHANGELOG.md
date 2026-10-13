# 变更日志

本项目所有重要变更记录于此。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

英文版：[CHANGELOG.en.md](CHANGELOG.en.md)

## [未发布]

### 变更

- **拆分为多模块构建**（对齐 docling-java 布局）：
  - `qunxu-document-core` —— 纯 Java 库（解析器、模型、导出器、分块器、缓存、OCR 闸门、URL 源、自检）。
    **零 Spring 依赖**；`DocumentModuleProperties` 改为纯 POJO（绑定移到 starter 的 `@Bean + @ConfigurationProperties`）。
  - `qunxu-document-spring-boot-starter` —— 基于 core 的 Spring Boot 自动配置（`DocumentAutoConfiguration` +
    `AutoConfiguration.imports`）。
  - `qunxu-document-bom` —— 版本物料清单（无代码）；import 一次即可集中管理全部 qunxu-document 版本。
  - 原单一 `qunxu-document` jar 由这些构件取代；根 POM 变为聚合/父 POM。

## [0.1.0] - 2025-10-13

qunxu-document 首个开源版本——一个独立、自包含的文档解析库
（Java 21 + Spring Boot 4.1.1 自动配置，Apache-2.0）。

### 新增

- **文档解析** —— 8 个内置解析器：`poi-docx`、`poi-pptx`、`tabular`（xlsx/csv，合并单元格感知）、
  `markdown`、`plaintext`、`tika-pdf`、`opendataloader`（PDF 结构化：元素 bbox / 阅读顺序 /
  标题层级 / 合并单元格）、`tika-fallback`。PDF 从结构化优雅降级到 `tika-pdf`。
- **统一模型** —— `QunxuDocument`（页面表 + 带溯源的类型化节点列表），JSON 可往返还原。
- **导出器** —— markdown / html / text / json。
- **分块器** —— `hierarchical`（标题面包屑）、`table-row`（Sheet/表头感知）、`flat`（字符 / 正则），
  另含旧分割器别名与 `AutoChunker` 自动选型；token 预算经 jtokkit CL100K_BASE 计量。
- **解析缓存** —— 内容指纹为键，进程内 LRU，`ConversionCacheStore` SPI 支持持久化。
- **SSRF 防护的 URL 输入源** —— 白名单、重定向逐跳复检、默认拦截回环/私网地址。
- **OCR 闸门** —— 页级栅格化 + `PageOcr` SPI；逐页预算与超时以告警呈现。
- **水印与隐形文字层处理** —— 剔除水印（含 Form 旋转低透明水印），保留可检索的隐形 OCR 文字层。
- **自检** —— 69 项内置检查（真实合成样本），可在 JUnit / CI 中复用。

### 说明

- `org.opendataloader:opendataloader-pdf-core` 尚未发布到 Maven Central，经 JitPack 解析
  （见 [docs/dependencies.md](docs/dependencies.md)）。
