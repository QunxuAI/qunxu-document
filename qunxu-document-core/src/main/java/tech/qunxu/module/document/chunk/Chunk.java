package tech.qunxu.module.document.chunk;

import tech.qunxu.module.document.model.DocumentKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个分块（对应 docling BaseChunker 的产出）。
 * <p>
 * 除文本外还带「结构溯源」：标题面包屑、页码区间、来源节点下标、图片 URL ——
 * 这些是检索命中后能在前端还原「这段话出自哪一节、第几页、配了哪张图」的依据，
 * 也是下一轮引用面板展示页码的数据来源（app 侧写入向量 metadata）。
 * <p>
 * 模块<b>不引用 Spring AI 的 {@code Document}</b>：分块是解析域的概念，
 * 向量化是框架域的概念，由 app 负责 {@code Chunk} → {@code Document} 的映射与 ID 预生成。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class Chunk {

    private String text;
    /** 块内主要元素的语义角色（混合时取第一个非文本元素） */
    private DocumentKind kind;
    /** 标题面包屑（自顶向下） */
    private final List<String> headingPath = new ArrayList<>();
    /** 起止页码，0 表示未知 */
    private int pageStart;
    private int pageEnd;
    /** 贡献文本的节点下标，用于命中后回溯原文 */
    private final List<Integer> elementIndexes = new ArrayList<>();
    /** 块内图片 URL（markdown 渲染与引用面板用） */
    private final List<String> imageUrls = new ArrayList<>();
    /** token 数（装块时已算出，避免二次编码） */
    private int tokenCount;

    public Chunk() {
    }

    public Chunk(String text, DocumentKind kind) {
        this.text = text;
        this.kind = kind;
    }

    /** 以当前文本与角色新建块（其余字段链式补齐） */
    public static Chunk of(String text, DocumentKind kind) {
        return new Chunk(text, kind);
    }

    /** 页码区间（0 忽略） */
    public Chunk pages(int start, int end) {
        if (start > 0 && this.pageStart == 0) {
            this.pageStart = start;
        }
        if (end > 0) {
            this.pageEnd = Math.max(this.pageEnd, end);
        }
        return this;
    }

    /** 记录来源节点下标 */
    public Chunk element(int index) {
        if (index >= 0 && !this.elementIndexes.contains(index)) {
            this.elementIndexes.add(index);
        }
        return this;
    }

    /** 合并另一个块的溯源信息（同一段落被拆到同一块时使用） */
    public Chunk mergeProvenance(Chunk other) {
        if (other == null) {
            return this;
        }
        pages(other.pageStart, other.pageEnd);
        for (Integer index : other.elementIndexes) {
            if (index != null) {
                element(index);
            }
        }
        for (String url : other.imageUrls) {
            if (url != null && !url.isBlank() && !this.imageUrls.contains(url)) {
                this.imageUrls.add(url);
            }
        }
        for (String heading : other.headingPath) {
            if (heading != null && !this.headingPath.contains(heading)) {
                this.headingPath.add(heading);
            }
        }
        return this;
    }

    /** 是否已带页码（app 侧决定是否写 metadata） */
    public boolean hasPage() {
        return pageStart > 0 || pageEnd > 0;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public void setKind(DocumentKind kind) {
        this.kind = kind;
    }

    public List<String> getHeadingPath() {
        return headingPath;
    }

    public int getPageStart() {
        return pageStart;
    }

    public int getPageEnd() {
        return pageEnd;
    }

    public List<Integer> getElementIndexes() {
        return elementIndexes;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    @Override
    public String toString() {
        return "Chunk[len=" + (text == null ? 0 : text.length()) + ", tokens=" + tokenCount
                + ", kind=" + kind + ", pages=" + pageStart + "-" + pageEnd
                + ", elements=" + elementIndexes.size() + "]";
    }
}
