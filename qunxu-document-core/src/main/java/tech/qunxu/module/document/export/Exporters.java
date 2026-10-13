package tech.qunxu.module.document.export;

import tech.qunxu.module.document.model.QunxuDocument;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置导出器工厂（无 Spring 场景与 {@link QunxuDocument} 便捷方法使用的静态入口）。
 * <p>
 * Spring 环境下应从 {@code DocumentConverter.export(document, key)} 取，那里可以拿到自定义注册的导出器；
 * 本类只保证「脱离容器也能导出」，实例无状态、可安全共享。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class Exporters {

    private static final Map<String, DocumentExporter> BUILTIN = new LinkedHashMap<>();

    static {
        register(new MarkdownExporter());
        register(new HtmlExporter());
        register(new TextExporter());
        register(new JsonExporter());
    }

    private Exporters() {
    }

    /** 登记一个内置导出器（同 key 覆盖） */
    public static void register(DocumentExporter exporter) {
        if (exporter != null && exporter.key() != null) {
            BUILTIN.put(exporter.key(), exporter);
        }
    }

    /** 按 key 取导出器，未注册返回 null */
    public static DocumentExporter of(String key) {
        return key == null ? null : BUILTIN.get(key.trim());
    }

    /** 按 key 取导出器，未注册抛异常 */
    public static DocumentExporter require(String key) {
        DocumentExporter exporter = of(key);
        if (exporter == null) {
            throw new IllegalArgumentException("未注册的导出格式：" + key + "（内置：" + BUILTIN.keySet() + "）");
        }
        return exporter;
    }

    /** 直接导出 */
    public static String export(QunxuDocument document, String key) {
        if (document == null) {
            return "";
        }
        return require(key).export(document);
    }

    /** 已注册的导出 key */
    public static java.util.Set<String> keys() {
        return BUILTIN.keySet();
    }
}
