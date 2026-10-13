package tech.qunxu.module.document.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 中间格式节点（对应 docling NodeItem/DocItem 族）。
 * <p>
 * 一个 {@link QunxuDocument} 持有扁平的节点列表，节点间以 {@code index} 引用构成树
 * （{@code parentIndex} / {@code childIndexes}），树结构由 {@link QunxuDocument#append} 在追加时按标题层级自动挂载。
 * 子类以嵌套类形式提供，语义与 docling 的 item 类型一一对应：
 * <table border="1">
 *   <caption>与 docling / opendataloader 的字段映射</caption>
 *   <tr><th>本类</th><th>docling</th><th>opendataloader JSON</th></tr>
 *   <tr><td>{@link TextItem}</td><td>TextItem(label=text/paragraph/caption/…)</td><td>{@code type: "paragraph"/"line"}</td></tr>
 *   <tr><td>{@link HeadingItem}</td><td>SectionHeaderItem</td><td>{@code type: "heading"} + {@code heading level}</td></tr>
 *   <tr><td>{@link ListItem}</td><td>ListItem</td><td>{@code type: "list item"}</td></tr>
 *   <tr><td>{@link TableItem}</td><td>TableItem</td><td>{@code type: "table"} + {@code rows[].cells[].{row span,column span,kids}}</td></tr>
 *   <tr><td>{@link PictureItem}</td><td>PictureItem</td><td>{@code type: "image"}</td></tr>
 *   <tr><td>{@link CodeItem}</td><td>CodeItem</td><td>{@code type: "text block"}</td></tr>
 *   <tr><td>{@link ProvenanceItem}</td><td>prov[]</td><td>{@code page number} + {@code bounding box}</td></tr>
 * </table>
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public abstract class NodeItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 在所属文档 items 中的下标，追加时由 QunxuDocument 分配 */
    private int index = -1;
    /** 语义角色 */
    private DocumentKind kind;
    /** 父节点下标，null 表示挂在文档根上 */
    private Integer parentIndex;
    /** 子节点下标 */
    private final List<Integer> childIndexes = new ArrayList<>();
    /** 主要文本（表格为标题/说明，图片为替代文本） */
    private String text;
    /** 溯源信息（页码/包围盒），一个元素可能跨页 */
    private final List<ProvenanceItem> prov = new ArrayList<>();

    protected NodeItem(DocumentKind kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    /** 是否标题类节点（参与章节树） */
    public boolean isHeading() {
        return kind != null && kind.isHeading();
    }

    /** 首个溯源项的页码，无溯源信息返回 0 */
    public int pageIndex() {
        return prov.isEmpty() ? 0 : prov.get(0).pageIndex();
    }

    /** 追加一条溯源信息 */
    public NodeItem prov(ProvenanceItem item) {
        if (item != null) {
            this.prov.add(item);
        }
        return this;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public void setKind(DocumentKind kind) {
        this.kind = kind;
    }

    public Integer getParentIndex() {
        return parentIndex;
    }

    public void setParentIndex(Integer parentIndex) {
        this.parentIndex = parentIndex;
    }

    public List<Integer> getChildIndexes() {
        return childIndexes;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<ProvenanceItem> getProv() {
        return prov;
    }

    // ==================== 便捷工厂 ====================

    /** 普通段落 */
    public static TextItem paragraph(String text) {
        return new TextItem(DocumentKind.PARAGRAPH, text);
    }

    /** 无角色的纯文本块（Tika 兜底等） */
    public static TextItem textBlock(String text) {
        return new TextItem(DocumentKind.TEXT_BLOCK, text);
    }

    /** 章节标题 */
    public static HeadingItem heading(int level, String text) {
        return new HeadingItem(level, text);
    }

    /** 文档主标题（整篇第一级） */
    public static HeadingItem title(String text) {
        return new HeadingItem(DocumentKind.TITLE, 1, text);
    }

    /** 电子表格的 Sheet 边界标题 */
    public static HeadingItem sheet(String name) {
        return new HeadingItem(DocumentKind.SHEET, 1, name);
    }

    /** 列表项 */
    public static ListItem list(String text, String marker) {
        return new ListItem(text, marker);
    }

    /** 图片 */
    public static PictureItem picture(ImageRef ref, String altText) {
        return new PictureItem(ref, altText);
    }

    /** 代码块 */
    public static CodeItem code(String language, String code) {
        return new CodeItem(language, code);
    }

    // ==================== 子类型 ====================

    /** 文本类节点：段落、文本块、图注、脚注、页眉页脚 */
    public static class TextItem extends NodeItem {
        private static final long serialVersionUID = 1L;

        public TextItem(DocumentKind kind, String text) {
            super(kind, text);
        }
    }

    /** 章节标题节点（level 1~6）；kind 可为 SECTION_HEADER / TITLE / SHEET */
    public static class HeadingItem extends NodeItem {
        private static final long serialVersionUID = 1L;

        private int level;

        public HeadingItem(int level, String text) {
            this(DocumentKind.SECTION_HEADER, level, text);
        }

        public HeadingItem(DocumentKind kind, int level, String text) {
            super(kind, text);
            this.level = Math.min(6, Math.max(1, level));
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }
    }

    /** 列表项节点（marker 为项目符号或序号原文） */
    public static class ListItem extends NodeItem {
        private static final long serialVersionUID = 1L;

        private String marker;

        public ListItem(String text, String marker) {
            super(DocumentKind.LIST_ITEM, text);
            this.marker = marker;
        }

        public String getMarker() {
            return marker;
        }

        public void setMarker(String marker) {
            this.marker = marker;
        }
    }

    /** 图片节点 */
    public static class PictureItem extends NodeItem {
        private static final long serialVersionUID = 1L;

        private ImageRef image;

        public PictureItem(ImageRef image, String altText) {
            super(DocumentKind.PICTURE, altText);
            this.image = image;
        }

        public ImageRef getImage() {
            return image;
        }

        public void setImage(ImageRef image) {
            this.image = image;
        }
    }

    /** 代码块节点 */
    public static class CodeItem extends NodeItem {
        private static final long serialVersionUID = 1L;

        private String language;

        public CodeItem(String language, String code) {
            super(DocumentKind.CODE, code);
            this.language = language;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }
    }

    /** 表格单元格：行列坐标 + 横向合并（colSpan）+ 纵向合并（rowSpan） */
    public static class TableCell implements Serializable {
        private static final long serialVersionUID = 1L;

        private String text;
        private int rowIndex;
        private int colIndex;
        private int rowSpan = 1;
        private int colSpan = 1;

        public TableCell() {
        }

        public TableCell(String text, int rowIndex, int colIndex, int rowSpan, int colSpan) {
            this.text = text;
            this.rowIndex = rowIndex;
            this.colIndex = colIndex;
            this.rowSpan = Math.max(1, rowSpan);
            this.colSpan = Math.max(1, colSpan);
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public int getRowIndex() {
            return rowIndex;
        }

        public void setRowIndex(int rowIndex) {
            this.rowIndex = rowIndex;
        }

        public int getColIndex() {
            return colIndex;
        }

        public void setColIndex(int colIndex) {
            this.colIndex = colIndex;
        }

        public int getRowSpan() {
            return rowSpan;
        }

        public void setRowSpan(int rowSpan) {
            this.rowSpan = rowSpan;
        }

        public int getColSpan() {
            return colSpan;
        }

        public void setColSpan(int colSpan) {
            this.colSpan = colSpan;
        }
    }

    /** 表格节点：表头 + 数据行（行优先），或整体网格（首行为表头） */
    public static class TableItem extends NodeItem {
        private static final long serialVersionUID = 1L;

        /** 表头行（无表头时为空列表） */
        private List<String> headers = new ArrayList<>();
        /** 数据行（含合并信息） */
        private List<TableCell> cells = new ArrayList<>();
        /** 数据区行数 */
        private int rowCount;
        /** 列数 */
        private int colCount;
        /** 表格标题/说明 */
        private String caption;

        public TableItem(String caption) {
            super(DocumentKind.TABLE, caption);
            this.caption = caption;
        }

        /**
         * 由纯文本网格构造表格：首行作为表头，其余为数据行（无合并）。
         */
        public static TableItem ofGrid(List<List<String>> grid) {
            TableItem table = new TableItem(null);
            if (grid == null || grid.isEmpty()) {
                return table;
            }
            int cols = 0;
            for (List<String> row : grid) {
                cols = Math.max(cols, row == null ? 0 : row.size());
            }
            table.colCount = cols;
            for (int r = 0; r < grid.size(); r++) {
                List<String> row = grid.get(r);
                int size = row == null ? 0 : row.size();
                if (r == 0) {
                    for (int c = 0; c < cols; c++) {
                        table.headers.add(c < size ? row.get(c) : "");
                    }
                    continue;
                }
                for (int c = 0; c < size; c++) {
                    table.cells.add(new TableCell(row.get(c), r - 1, c, 1, 1));
                }
                table.rowCount = Math.max(table.rowCount, r);
            }
            return table;
        }

        /** 追加一个数据单元格（自动维护 rowCount/colCount） */
        public TableItem addCell(TableCell cell) {
            cells.add(cell);
            rowCount = Math.max(rowCount, cell.getRowIndex() + 1);
            colCount = Math.max(colCount, cell.getColIndex() + cell.getColSpan());
            return this;
        }

        /** 数据区取指定位置单元格文本（合并单元格只在其左上角有值，其余返回 null） */
        public String cellText(int rowIndex, int colIndex) {
            for (TableCell cell : cells) {
                if (cell.getRowIndex() == rowIndex && cell.getColIndex() == colIndex) {
                    return cell.getText();
                }
            }
            return null;
        }

        /** 总行数（含表头行） */
        public int totalRowCount() {
            return rowCount + (headers.isEmpty() ? 0 : 1);
        }

        public List<String> getHeaders() {
            return headers;
        }

        public void setHeaders(List<String> headers) {
            this.headers = headers == null ? new ArrayList<>() : headers;
        }

        public List<TableCell> getCells() {
            return cells;
        }

        public void setCells(List<TableCell> cells) {
            this.cells = cells == null ? new ArrayList<>() : cells;
        }

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        public int getColCount() {
            return colCount;
        }

        public void setColCount(int colCount) {
            this.colCount = colCount;
        }

        public String getCaption() {
            return caption;
        }

        public void setCaption(String caption) {
            this.caption = caption;
        }
    }
}
