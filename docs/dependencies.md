# 依赖与构建可复现性

英文版：[dependencies.en.md](dependencies.en.md)

## 运行时依赖

| Group:Artifact | 版本（`pom.xml` 管理） | 许可证 | 用途 |
| --- | --- | --- | --- |
| `org.springframework.boot:spring-boot-autoconfigure` | 4.1.1（BOM） | Apache-2.0 | 自动配置与 `@ConfigurationProperties`（纯 Java 用法下运行时可选） |
| `org.slf4j:slf4j-api` | 2.x（BOM） | MIT | 日志 |
| `com.alibaba.fastjson2:fastjson2` | 2.0.65 | Apache-2.0 | `QunxuDocument` dict 序列化 / 缓存格式 |
| `org.apache.poi:poi-ooxml` / `poi-scratchpad` | 5.5.1 | Apache-2.0 | docx / pptx / xlsx 解析 |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | Apache-2.0 | PDF 文本 + 内嵌图片 |
| `org.apache.tika:tika-core` / `tika-parsers-standard-package` | 3.3.2 | Apache-2.0 | mime 探测 + 兜底 / pdf 解析 |
| `cn.hutool:hutool-all` | 5.8.47 | Mulan PSL v2 | CSV 解析、字符集探测 |
| `commons-io:commons-io` | 2.18.0 | Apache-2.0 | IO 工具 |
| `com.knuddels:jtokkit` | 1.1.0 | MIT | CL100K_BASE token 计数 |

## `opendataloader-pdf-core` —— 经 JitPack 解析

结构化 PDF 后端依赖 `opendataloader-pdf-core`（`org.opendataloader`）。该构件**尚未发布到 Maven Central**，
因此 POM：

1. 依赖 `com.github.opendataloader-project.opendataloader-pdf:opendataloader-pdf-core:2.5.12`
   （JitPack 构建的[上游 Apache-2.0 源码](https://github.com/opendataloader-project/opendataloader-pdf)），且
2. 声明了 `https://jitpack.io` 仓库。

后果：

- **对消费方透明**：`mvn` 自动解析。
- **JitPack 是第三方构建服务**：如果你的环境屏蔽它，要么
  - 加一个 JitPack 的内部镜像/代理，要么
  - 自己构建上游并装进本地仓库
    （`git clone --branch v2.5.12 --depth 1 https://github.com/opendataloader-project/opendataloader-pdf && cd java && mvn install -DskipTests`），
    然后从 POM 里删掉 JitPack `<repository>`。
- 上游发布到 Maven Central 后，README 里的这条说明、JitPack 仓库与改写后的坐标都会换回官方
  `org.opendataloader:opendataloader-pdf-core`。

## 构建

仓库是多模块 Maven 构建（父 POM `tech.qunxu:qunxu-document`）：

```
qunxu-document/                          父 POM（聚合 + 依赖管理）
├── qunxu-document-core/                 纯 Java 库（无 Spring 依赖）
├── qunxu-document-spring-boot-starter/  Spring Boot 自动配置（依赖 core）
└── qunxu-document-bom/                  版本物料清单（无代码；import 集中管版本）
```

```bash
# Java 21+ 与 Maven 3.9+，仓库根目录（构建全部模块）
mvn verify          # 编译 + 跑 69 项自检 + 单测 + 自动配置冒烟测试
mvn install         # 装入本地仓库
```

自检会在 `java.io.tmpdir` 生成真实样本（docx/pptx/xlsx/pdf/md/csv）并走完整链路；依赖缓存后无需网络或数据库。
要直接跑自检的 `main`，在 `qunxu-document-core/` 模块下执行（见 runner 的 javadoc）。
