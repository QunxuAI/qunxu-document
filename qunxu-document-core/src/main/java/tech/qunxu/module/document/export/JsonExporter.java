package tech.qunxu.module.document.export;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import tech.qunxu.module.document.model.BBox;
import tech.qunxu.module.document.model.DocumentKind;
import tech.qunxu.module.document.model.DocumentMeta;
import tech.qunxu.module.document.model.QunxuDocument;
import tech.qunxu.module.document.model.ImageRef;
import tech.qunxu.module.document.model.NodeItem;
import tech.qunxu.module.document.model.PageInfo;
import tech.qunxu.module.document.model.ProvenanceItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON dict 导出器（对应 docling export_to_dict）。
 * <p>
 * 这是 {@link QunxuDocument} 的持久化形态：解析缓存落库、远程解析服务传输都用它，
 * {@link #toJson} 与 {@link #parse} 互为逆操作。字段命名对齐 docling
 * （{@code label}/{@code prov}/{@code parent_ref}/{@code children}），
 * 表格与页面坐标字段对齐 opendataloader（{@code num_rows}/{@code cells[].row_span}/
 * {@code prov[].page_number}/{@code prov[].bounding_box}），
 * 后续接入外部解析器时只做字段搬运即可。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class JsonExporter implements DocumentExporter {

    @Override
    public String key() {
        return "json";
    }

    @Override
    public String export(QunxuDocument document) {
        return toJson(document);
    }

    /** 序列化为 JSON 文本 */
    public static String toJson(QunxuDocument document) {
        if (document == null) {
            return "{}";
        }
        JSONObject root = new JSONObject();
        root.put("meta", metaJson(document.getMeta()));
        root.put("pages", pagesJson(document.getPages()));
        root.put("warnings", document.getWarnings());
        JSONArray body = new JSONArray();
        for (NodeItem item : document.getItems()) {
            body.add(itemJson(item));
        }
        root.put("body", body);
        return JSON.toJSONString(root);
    }

    /** 从 JSON 文本还原（缓存命中路径；树结构按已存 index/parent/children 原样恢复，不重新挂载） */
    public static QunxuDocument parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("文档 JSON 为空，无法还原");
        }
        JSONObject root = JSON.parseObject(json);
        if (root == null) {
            throw new IllegalArgumentException("文档 JSON 解析失败");
        }
        QunxuDocument document = new QunxuDocument(metaFrom(root.getJSONObject("meta")));
        JSONArray body = root.getJSONArray("body");
        if (body != null) {
            for (int i = 0; i < body.size(); i++) {
                JSONObject node = body.getJSONObject(i);
                NodeItem item = itemFrom(node);
                if (item == null) {
                    continue;
                }
                // 直接落位：保留原 index 与父子引用，避免重新走 append 的标题栈逻辑
                document.getItems().add(item);
            }
        }
        JSONArray pages = root.getJSONArray("pages");
        if (pages != null) {
            for (int i = 0; i < pages.size(); i++) {
                JSONObject page = pages.getJSONObject(i);
                document.getPages().add(new PageInfo(page.getIntValue("index"),
                        page.getFloatValue("width"), page.getFloatValue("height")));
            }
        }
        JSONArray warnings = root.getJSONArray("warnings");
        if (warnings != null) {
            for (int i = 0; i < warnings.size(); i++) {
                document.warn(warnings.getString(i));
            }
        }
        // 正常数据里 index 就是列表位置；不一致时按位置重建，并同步重映射父子引用
        relocate(document.getItems(), document);
        return document;
    }

    private static void relocate(List<NodeItem> items, QunxuDocument document) {
        boolean aligned = true;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getIndex() != i) {
                aligned = false;
                break;
            }
        }
        if (aligned) {
            return;
        }
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            remap.put(items.get(i).getIndex(), i);
        }
        for (int i = 0; i < items.size(); i++) {
            NodeItem item = items.get(i);
            Integer parent = item.getParentIndex();
            List<Integer> children = new ArrayList<>(item.getChildIndexes());
            item.setIndex(i);
            item.setParentIndex(parent == null ? null : remap.get(parent));
            item.getChildIndexes().clear();
            for (Integer child : children) {
                Integer target = remap.get(child);
                if (target != null) {
                    item.getChildIndexes().add(target);
                }
            }
        }
        document.warn("文档节点的 index 与顺序不一致，已按列表位置重建父子引用");
    }

    // ==================== 写出 ====================

    private static JSONObject metaJson(DocumentMeta meta) {
        JSONObject json = new JSONObject();
        json.put("source_name", meta.getSourceName());
        json.put("file_hash", meta.getFileHash());
        json.put("file_type", meta.getFileType());
        json.put("mime_type", meta.getMimeType());
        json.put("page_count", meta.getPageCount());
        json.put("backend_key", meta.getBackendKey());
        json.put("backend_version", meta.getBackendVersion());
        json.put("parse_ms", meta.getParseMs());
        json.put("origin_url", meta.getOriginUrl());
        json.put("file_size", meta.getFileSize());
        return json;
    }

    private static JSONArray pagesJson(List<PageInfo> pages) {
        JSONArray array = new JSONArray();
        for (PageInfo page : pages) {
            JSONObject json = new JSONObject();
            json.put("index", page.index());
            json.put("width", page.width());
            json.put("height", page.height());
            array.add(json);
        }
        return array;
    }

    private static JSONObject itemJson(NodeItem item) {
        JSONObject json = new JSONObject();
        json.put("index", item.getIndex());
        json.put("label", item.getKind() == null ? DocumentKind.TEXT_BLOCK.jsonValue() : item.getKind().jsonValue());
        json.put("text", item.getText());
        json.put("parent_ref", item.getParentIndex());
        json.put("children", item.getChildIndexes());
        JSONArray prov = new JSONArray();
        for (ProvenanceItem provenance : item.getProv()) {
            JSONObject one = new JSONObject();
            one.put("page_number", provenance.pageIndex());
            if (provenance.bbox() != null) {
                BBox box = provenance.bbox();
                one.put("bounding_box", new JSONArray(List.of(box.x0(), box.y0(), box.x1(), box.y1())));
            }
            prov.add(one);
        }
        json.put("prov", prov);
        if (item instanceof NodeItem.HeadingItem heading) {
            json.put("level", heading.getLevel());
        } else if (item instanceof NodeItem.ListItem list) {
            json.put("marker", list.getMarker());
        } else if (item instanceof NodeItem.CodeItem code) {
            json.put("language", code.getLanguage());
        } else if (item instanceof NodeItem.TableItem table) {
            json.put("caption", table.getCaption());
            json.put("num_rows", table.getRowCount());
            json.put("num_cols", table.getColCount());
            json.put("headers", table.getHeaders());
            JSONArray cells = new JSONArray();
            for (NodeItem.TableCell cell : table.getCells()) {
                JSONObject one = new JSONObject();
                one.put("text", cell.getText());
                one.put("row_index", cell.getRowIndex());
                one.put("column_index", cell.getColIndex());
                one.put("row_span", cell.getRowSpan());
                one.put("col_span", cell.getColSpan());
                cells.add(one);
            }
            json.put("cells", cells);
        } else if (item instanceof NodeItem.PictureItem picture) {
            ImageRef ref = picture.getImage();
            if (ref != null) {
                JSONObject image = new JSONObject();
                image.put("storage_path", ref.storagePath());
                image.put("url", ref.url());
                image.put("file_name", ref.fileName());
                image.put("content_type", ref.contentType());
                image.put("width", ref.width());
                image.put("height", ref.height());
                image.put("bytes", ref.bytes());
                image.put("sha256", ref.sha256());
                json.put("image", image);
            }
        }
        return json;
    }

    // ==================== 读回 ====================

    private static DocumentMeta metaFrom(JSONObject json) {
        DocumentMeta meta = new DocumentMeta();
        if (json == null) {
            return meta;
        }
        meta.setSourceName(json.getString("source_name"));
        meta.setFileHash(json.getString("file_hash"));
        meta.setFileType(json.getString("file_type"));
        meta.setMimeType(json.getString("mime_type"));
        meta.setPageCount(json.getIntValue("page_count"));
        meta.setBackendKey(json.getString("backend_key"));
        meta.setBackendVersion(json.getIntValue("backend_version"));
        meta.setParseMs(json.getLongValue("parse_ms"));
        meta.setOriginUrl(json.getString("origin_url"));
        meta.setFileSize(json.getLongValue("file_size"));
        return meta;
    }

    private static NodeItem itemFrom(JSONObject json) {
        if (json == null) {
            return null;
        }
        DocumentKind kind = DocumentKind.fromJsonValue(json.getString("label"));
        String text = json.getString("text");
        NodeItem item = switch (kind) {
            case TITLE -> new NodeItem.HeadingItem(DocumentKind.TITLE, json.getIntValue("level") <= 0
                    ? 1 : json.getIntValue("level"), text);
            case SECTION_HEADER, SHEET -> new NodeItem.HeadingItem(kind,
                    json.getIntValue("level") <= 0 ? 1 : json.getIntValue("level"), text);
            case LIST_ITEM -> new NodeItem.ListItem(text, json.getString("marker"));
            case CODE -> new NodeItem.CodeItem(json.getString("language"), text);
            case TABLE -> tableFrom(json, text);
            case PICTURE -> new NodeItem.PictureItem(imageFrom(json.getJSONObject("image")), text);
            case PARAGRAPH, CAPTION, FOOTNOTE, PAGE_HEADER, PAGE_FOOTER, TEXT_BLOCK, FORMULA ->
                    new NodeItem.TextItem(kind, text);
        };
        item.setIndex(json.getIntValue("index"));
        item.setParentIndex(json.getInteger("parent_ref"));
        JSONArray children = json.getJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                item.getChildIndexes().add(children.getInteger(i));
            }
        }
        JSONArray prov = json.getJSONArray("prov");
        if (prov != null) {
            for (int i = 0; i < prov.size(); i++) {
                JSONObject one = prov.getJSONObject(i);
                if (one == null) {
                    continue;
                }
                BBox bbox = null;
                JSONArray box = one.getJSONArray("bounding_box");
                if (box != null && box.size() == 4) {
                    bbox = new BBox(box.getFloatValue(0), box.getFloatValue(1),
                            box.getFloatValue(2), box.getFloatValue(3));
                }
                item.prov(new ProvenanceItem(one.getIntValue("page_number"), bbox));
            }
        }
        return item;
    }

    private static NodeItem.TableItem tableFrom(JSONObject json, String text) {
        NodeItem.TableItem table = new NodeItem.TableItem(json.getString("caption"));
        table.setRowCount(json.getIntValue("num_rows"));
        table.setColCount(json.getIntValue("num_cols"));
        JSONArray headers = json.getJSONArray("headers");
        if (headers != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < headers.size(); i++) {
                list.add(headers.getString(i));
            }
            table.setHeaders(list);
        }
        JSONArray cells = json.getJSONArray("cells");
        if (cells != null) {
            for (int i = 0; i < cells.size(); i++) {
                JSONObject one = cells.getJSONObject(i);
                if (one == null) {
                    continue;
                }
                table.getCells().add(new NodeItem.TableCell(one.getString("text"),
                        one.getIntValue("row_index"), one.getIntValue("column_index"),
                        one.getIntValue("row_span"), one.getIntValue("col_span")));
            }
        }
        return table;
    }

    private static ImageRef imageFrom(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new ImageRef(json.getString("storage_path"), json.getString("url"),
                json.getString("file_name"), json.getString("content_type"),
                json.getIntValue("width"), json.getIntValue("height"),
                json.getLongValue("bytes"), json.getString("sha256"));
    }
}
