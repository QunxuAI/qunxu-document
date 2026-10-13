package tech.qunxu.module.document.model;

/**
 * 文档元素类型（对应 docling DocItem.label）。
 * <p>
 * 中间格式只描述「语义角色」，不描述来源格式：同一份 docx 与 pdf 解析后
 * 都应落到同一组 kind 上，导出器与分块器因此与文件格式彻底解耦。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public enum DocumentKind {

    /** 文档主标题（整篇的第一级标题，一个文档至多一个） */
    TITLE,
    /** 章节标题（level 表示层级，1~6） */
    SECTION_HEADER,
    /** 正文段落 */
    PARAGRAPH,
    /** 列表项（marker 记录项目符号/序号） */
    LIST_ITEM,
    /** 表格（含表头、行列与合并信息） */
    TABLE,
    /** 图片（含落地后的存储路径与访问 URL） */
    PICTURE,
    /** 代码块 */
    CODE,
    /** 公式（latex/text，本轮不产出，为后续解析器留位） */
    FORMULA,
    /** 图注/表注 */
    CAPTION,
    /** 脚注 */
    FOOTNOTE,
    /** 页眉（分块时默认丢弃） */
    PAGE_HEADER,
    /** 页脚（分块时默认丢弃） */
    PAGE_FOOTER,
    /** 电子表格的 Sheet 边界标记（作为其下 TABLE 的父级标题） */
    SHEET,
    /** 无法判定角色的文本块（兜底，按普通文本处理） */
    TEXT_BLOCK;

    /**
     * 是否标题类节点（参与章节树与面包屑构建）。
     */
    public boolean isHeading() {
        return this == TITLE || this == SECTION_HEADER || this == SHEET;
    }

    /**
     * JSON 序列化用的字符串值（小写下划线，与 docling 的 label 命名风格一致）。
     */
    public String jsonValue() {
        return name().toLowerCase();
    }

    /**
     * 从 JSON 值还原枚举，未知值归为 {@link #TEXT_BLOCK}（保证缓存/远程数据向前兼容）。
     */
    public static DocumentKind fromJsonValue(String value) {
        if (value == null || value.isBlank()) {
            return TEXT_BLOCK;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEXT_BLOCK;
        }
    }
}
