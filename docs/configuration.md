# 配置参考

所有配置项统一使用前缀 `qunxu.document`。本库是 Spring Boot 自动配置库；在 `application.yaml` /
`application.properties` 中设置（或经环境变量，如 `QUNXU_DOCUMENT_IMAGE_SINK=local`）。

英文版：[configuration.en.md](configuration.en.md)

## `qunxu.document.enabled`

模块总开关。为 `false` 时自动配置整体退让，不注册任何 Bean。

## `qunxu.document.backends.preferred.<format>`

按格式指定首选解析器（L2 路由覆盖）。key = 格式小写名（`pdf`、`docx`、…），value = backend key。

```yaml
qunxu:
  document:
    backends:
      preferred:
        pdf: opendataloader   # PDF 默认本来就是 opendataloader；这里用于强制/更换
```

## `qunxu.document.url.*` —— URL 输入源（SSRF 闸门）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `qunxu.document.url.allow-hosts` | 空 | 逗号分隔的主机白名单；以 `.` 开头的条目连子域一起放行。**为空即禁用 `fromUrl`**（直接抛异常，绝不静默放行） |
| `qunxu.document.url.max-bytes` | `52428800` | 单次下载字节上限 |
| `qunxu.document.url.connect-timeout-seconds` | `10` | 连接超时（秒） |
| `qunxu.document.url.read-timeout-seconds` | `60` | 读取超时（秒） |
| `qunxu.document.url.max-redirects` | `3` | 最大跟随重定向次数（每跳重新过 SSRF 校验） |
| `qunxu.document.url.allow-private-network` | `false` | 是否放行回环 / 私网 / 链路本地地址（仅本地联调或内网对象存储直链） |

## `qunxu.document.image.*` —— 内嵌图片抽取

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `qunxu.document.image.enabled` | `true` | 是否抽取内嵌图片（关闭后导出的 markdown 不含图片链接） |
| `qunxu.document.image.sink` | `storage` | 落地方式：`storage`（宿主实现 `ImageSink`）/ `local`（目录）/ `noop`（丢弃） |
| `qunxu.document.image.local-root` | — | `sink=local` 时的根目录 |
| `qunxu.document.image.base-path` | `document-images` | 路径前缀（`storage` sink 作为 `prePath` 提示；`local` sink 作为子目录） |
| `qunxu.document.image.url-prefix` | — | `sink=local` 时拼展示 URL 的前缀 |
| `qunxu.document.image.min-width` / `min-height` | `80` / `80` | 过滤图标 / 线条 |
| `qunxu.document.image.min-bytes` | `10240` | 过滤过小图片 |
| `qunxu.document.image.max-bytes` | `10485760` | 超出此大小的图片不落地 |
| `qunxu.document.image.max-images-per-doc` | `200` | 单文档硬上限（按尝试数计，含被过滤的） |

## `qunxu.document.ocr.*` —— 扫描件 OCR 闸门

部署级渲染预算。**是否 OCR 由每次请求决定**（`ConvertOptions.doOcr(true)`）——OCR 是外部付费调用，
部署方无权替调用方默认开启。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `qunxu.document.ocr.enabled` | `true` | 部署开关（关闭后不注册 OCR 后处理器） |
| `qunxu.document.ocr.max-pages` | `20` | 单篇默认识别页数上限（请求未指定 `ocrMaxPages` 时使用） |
| `qunxu.document.ocr.timeout-seconds` | `120` | 单篇 OCR 时长预算（≤0 不限）；超预算的页停下并告警 |
| `qunxu.document.ocr.dpi` | `200` | 栅格化 DPI |
| `qunxu.document.ocr.max-side` | `2048` | 长边像素上限（≤0 不限） |
| `qunxu.document.ocr.format` | `jpeg` | 栅格图编码：`jpeg` / `png` |
| `qunxu.document.ocr.jpeg-quality` | `0.9` | JPEG 质量（0~1） |
| `qunxu.document.ocr.max-bytes` | `6400000` | 单页栅格图字节上限（缩放重试后仍超出则跳过该页） |

## `qunxu.document.cache.*` —— 解析结果缓存

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `qunxu.document.cache.enabled` | `true` | 缓存解析结果（唯一解析源的去重依据） |
| `qunxu.document.cache.json-spill-threshold` | `1048576` | 中间格式 JSON 超过该字节数时溢出到对象存储，只存路径 |
| `qunxu.document.cache.memory-max-entries` | `64` | 进程内 LRU 条数上限（默认实现；存在持久化 `ConversionCacheStore` Bean 时自动让位） |

## `qunxu.document.chunk.*` —— 分块默认值

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `qunxu.document.chunk.max-tokens` | `7000` | 单块 token 预算（Spring AI 单文档 7372 embedding 上限内留余量） |
| `qunxu.document.chunk.include-heading-breadcrumb` | `true` | 块首是否注入标题面包屑 |
| `qunxu.document.chunk.rows-per-chunk` | `10` | 表格按行分块的每块行数 |
