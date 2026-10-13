package tech.qunxu.module.document.backend;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 水印文字识别：找出「以装饰方式画在页面上的文字」，供两个 PDF 解析器在产出节点前剔除。
 * <p>
 * 判定用内容流里的<b>绘制状态</b>，不用字符串长相 —— 水印没有固定文案，而"含'机密'字样就当水印"
 * 会把正文里的密级说明一起删掉。绘制状态必须在<b>逐字形回调</b>里读，见 {@link RunStripper}。两条信号都要满足：
 * <ul>
 *   <li><b>非常规着色</b>：渲染模式 {@code Tr 3/7}（画了但不落墨）、填充透明度 ca 明显小于 1、
 *       或填充色接近白；</li>
 *   <li><b>装饰性形态</b>：绘制方向偏离水平/垂直（斜排水印）、字号显著高于本页正文中位数、
 *       或同一串文字跨页重复出现（角标式水印）。</li>
 * </ul>
 * 第二条是护栏。搜索型扫描件把 OCR 结果以 {@code Tr 3} 隐形文字垫在整页位图下面，只满足第一条，
 * 而那份隐形文字恰好是它唯一的正文 —— 按第一条剔会把这份 PDF 剔成空文档（并且现在无正文是要判
 * 失败的）。所以隐形不等于水印，必须再看形态。此外还有一道总量闸门：候选字符量超过全文
 * {@value #MAX_HIDDEN_SHARE} 时整体放弃剔除，兜住"整页都是装饰性文字层"的极端版式。
 * <p>
 * OpenDataLoader 只处理注释对象里的 {@code /Watermark} subtype（见其 {@code AutoTaggingProcessor}），
 * 内容流画的文字它照单全收，还会因为字号大而判成一级标题 —— 这正是水印被当成文档主标题的原因。
 * <p>
 * 更狠的是它会把真实标题<b>吞掉</b>：斜排水印的外接框几乎盖住整页，落在框里的真标题被库合进
 * 水印节点里，产出里根本不存在那份标题（实测）。所以光删节点救不回来，必须同时提供一份
 * 「抹掉水印绘制算符」的字节给解析器（{@link Result#parseBytes(byte[])}），让它从一开始就看不到水印。
 * 两个 PDF 解析器都走这一招：只按整行剔的 {@code tika-pdf} 拦不住被拆成散字贴进正文行的水印（实测）。
 * 删节点的那一层仍留着：它兼管预剔除管不到的情形（浅色字、超出页限额的页、改写失败）。
 * <p>
 * 预剔除必须跟进 Form XObject：企业受控文件的水印绝大多数是「Adobe 水印功能生成的一个 Form，
 * 每页用 {@code q /GSca0.1 gs <旋转 cm> /XOb Do Q} 调进去」（实测样本：16 页共用同一个 Form，
 * 里面只有 {@code [<d1cfbdfbb8b4d6c6>] TJ} 一条 GBK 编码的「严禁复制」）。这种画法不跟进 Form，
 * 结构化解析器就会把水印的散字<b>合并进邻近的表格单元格</b>，既删不掉节点也救不回版式（实测泄漏 6 处）。
 * 跟进 Form 时按<b>调用点</b>的绘制状态决定改不改：同一个 Form 可能被一处当水印淡画、被另一处正常画成
 * 正文图章，只要有一个正常调用点就不能动它的内容流。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
final class PdfWatermarkFilter {

    private static final Logger log = LoggerFactory.getLogger(PdfWatermarkFilter.class);

    /** 填充透明度低于此值即认为字是画淡的（1.0 为完全不透明） */
    private static final double FAINT_ALPHA = 0.95;
    /** 填充色亮度高于此值即认为字是浅色（0~1，浅色字通常是背景装饰） */
    private static final double LIGHT_BRIGHTNESS = 0.70;
    /** 字号达到本页正文中位数的此倍数，视为非常规大字（水印/装饰标题） */
    private static final float OVERSIZE_RATIO = 1.8f;
    /** 偏离水平或垂直超过此角度数，视为斜排 */
    private static final float ROTATION_TOLERANCE = 5f;
    /** 候选剔除量占全文字符数的上限，超过则整体放弃（极可能是 OCR 文字层） */
    private static final double MAX_HIDDEN_SHARE = 0.40;
    /** 同一串文字至少出现在这么多页上，才靠"跨页重复"这条形态信号成立 */
    private static final int REPEAT_PAGES = 3;
    /** 按"水印片段"剔除时的最短文本，太短的碎片极易与正文撞车 */
    private static final int MIN_FRAGMENT = 6;
    /** 保留的正文比对文本上限，超大文档到此为止（只影响碎片判定，不影响整串判定） */
    private static final int MAX_VISIBLE_GUARD = 1 << 18;
    /** 预剔除最多跟进多少个被淡化调用的 Form，防止异常文件里 Form 数量把改写成本放大 */
    private static final int MAX_FORM_CARRIERS = 64;

    private PdfWatermarkFilter() {
    }

    /**
     * 扫描结果。
     *
     * @param hidden      判定为水印的规范化文本集合（空集合表示不剔除任何内容）
     * @param visible     未被判为水印的文本拼接（碎片判定时的正文护栏，防止把正文当水印碎片删掉）
     * @param hiddenCount 被判定的文字段数
     * @param reason      放弃判定时的人话原因（正常判定为 null），仅用于日志与自检断言
     * @param stripped    抹掉水印绘制算符后的 PDF 字节（null 表示没改写成）
     */
    record Result(Set<String> hidden, String visible, int hiddenCount, String reason, byte[] stripped) {

        static Result none(String reason) {
            return new Result(Set.of(), "", 0, reason, null);
        }

        /** 交给解析器的字节：能抹掉水印就用抹掉的，否则原样 */
        byte[] parseBytes(byte[] original) {
            return stripped != null ? stripped : original;
        }
    }

    /** 一次文字绘制事件：同一绘制状态下连续的字符合并成一段（通常就是一个 Tj/TJ） */
    private static final class Run {
        private final StringBuilder text = new StringBuilder(64);
        private int page;
        private float fontSize;
        /** 绘制倾角（度），来自文本渲染矩阵 */
        private float angle;
        private boolean invisible;
        private double alpha = 1;
        private boolean light;
        private String normalized = "";

        /** 与本段状态一致即可续接到同一段（浮点都带容差，避免微小抖动把一段拆碎） */
        private boolean sameState(Run other) {
            return page == other.page && invisible == other.invisible && light == other.light
                    && Math.abs(alpha - other.alpha) < 1e-3
                    && Math.abs(angle - other.angle) < 0.6f
                    && Math.abs(fontSize - other.fontSize) < 0.1f;
        }
    }

    /**
     * 扫一遍内容流，命中水印时顺带抹掉它的绘制算符（{@link Result#parseBytes(byte[])}）。
     * <p>
     * 两个 PDF 解析器都要这份改写后的字节，只是原因不同：结构化解析器按整页字号聚类推断层级，
     * 水印不仅会抢到一级标题，还会把落在它外接框里的真实标题一并吞掉（实测）；{@code tika-pdf}
     * 按行剔除，而企业样本里的水印常被拆成散字贴进正文行（实测 8 行残留「严/禁/复/制」单字），
     * 整行比对根本拦不住。改写只在确实判出水印时才跑（{@code hidden} 为空就直接返回），
     * 没水印的文档不付这份代价。
     *
     * @param maxPage 允许解析的最大页码（0 表示不限），与解析器保持同一口径
     */
    static Result scan(byte[] bytes, int maxPage) {
        List<Run> runs = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            int pageLimit = maxPage <= 0
                    ? pdf.getNumberOfPages()
                    : Math.min(maxPage, pdf.getNumberOfPages());
            RunStripper stripper = new RunStripper(runs);
            stripper.setStartPage(1);
            stripper.setEndPage(pageLimit);
            stripper.getText(pdf);
            // 末段不会再有回调，必须显式落袋，否则最后一页的水印正好漏掉
            stripper.finish();
            Result classified = classify(runs);
            if (classified.hidden().isEmpty()) {
                return classified;
            }
            byte[] stripped = stripHidden(pdf, classified, pageLimit, bytes.length);
            return new Result(classified.hidden(), classified.visible(), classified.hiddenCount(),
                    classified.reason(), stripped);
        } catch (Exception e) {
            // 内容流读不动就完全不剔除：漏删水印只是脏一点，误删正文是把文档判死
            log.debug("水印扫描跳过（内容流读取失败）：{}", e.getMessage());
            return Result.none("水印扫描失败");
        }
    }

    /**
     * 把判为水印的显示算符的字符串换成空串，返回改写后的 PDF 字节。
     * <p>
     * 只抹字符串，不动算符个数也不删 BT/ET/Tf/Tm 之类的排版状态算符：改写内容流最容易出事的是
     * 把操作流改坏，改坏了就再也不是“少一个水印”而是整个文件解不了。任何一页改写失败就整体放弃
     * （返回 null 让调用方用原字节），反正还有一层删节点的护栏。
     */
    private static byte[] stripHidden(PDDocument pdf, Result result, int pageLimit, int sourceSize) {
        try {
            FormCarriers carriers = new FormCarriers();
            int blanked = 0;
            int pageNumber = 0;
            for (PDPage page : pdf.getPages()) {
                if (++pageNumber > pageLimit) {
                    break;
                }
                PDResources resources = page.getResources();
                Iterator<PDStream> streams = page.getContentStreams();
                while (streams.hasNext()) {
                    blanked += rewriteStream(streams.next(), resources, result, carriers, false);
                }
            }
            // 第二遍才改 Form：先把全部调用点看完再动手。同一个 Form 可能既被淡画（水印）又被正常画（图章），
            // 只要见过一个正常调用点就不能改它的内容流，否则那处的正文会被抹空
            blanked += rewriteForms(result, carriers);
            if (blanked == 0) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(sourceSize);
            pdf.save(out);
            log.info("已从内容流抹掉 {} 处水印绘制（含 Form XObject），结构化解析器不会再看到它", blanked);
            return out.toByteArray();
        } catch (Exception e) {
            log.debug("水印预剔除失败，改为只删节点：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 改写「只被淡化调用过」的 Form 内容流（逐层递归到嵌套 Form）。
     * <p>
     * Form 自己通常不带 {@code gs}，透明度是调用页通过 {@code Do} 下沉的，所以把淡化状态当入口条件带进去；
     * Form 自己的资源字典能读到字库，中文水印的 CMap 编码靠它才能解成可比对的文本。
     */
    private static int rewriteForms(Result result, FormCarriers carriers) throws IOException {
        int blanked = 0;
        for (int i = 0; i < carriers.faint.size() && i < MAX_FORM_CARRIERS; i++) {
            PDFormXObject form = carriers.faint.get(i);
            if (carriers.solid.contains(form.getCOSObject())) {
                continue;
            }
            blanked += rewriteStream(form.getContentStream(), form.getResources(), result, carriers, true);
        }
        return blanked;
    }

    /** 改写一条内容流，返回被置空的字符串个数 */
    private static int rewriteStream(PDStream stream, PDResources resources, Result result,
            FormCarriers carriers, boolean inheritedFaint) throws IOException {
        byte[] raw;
        try (InputStream in = stream.createInputStream()) {
            raw = in.readAllBytes();
        }
        List<Object> tokens;
        PDFStreamParser parser = new PDFStreamParser(raw);
        try {
            tokens = parser.parse();
        } finally {
            parser.close();
        }
        List<Object> kept = new ArrayList<>(tokens.size());
        List<Object> operands = new ArrayList<>();
        List<FaintSlot> faintSlots = new ArrayList<>();
        StringBuilder faintText = new StringBuilder();
        PaintState paint = new PaintState(inheritedFaint);
        int blanked = 0;
        for (Object token : tokens) {
            if (!(token instanceof Operator operator)) {
                operands.add(token);
                continue;
            }
            switch (operator.getName()) {
                case "q" -> paint.push();
                case "Q" -> paint.pop();
                case "gs" -> paint.applyGraphicsState(resources, firstName(operands));
                case "Tr" -> paint.setRenderingMode(firstNumber(operands));
                case "Tf" -> paint.applyFont(resources, firstName(operands));
                case "Do" -> carriers.recordForm(resources, firstName(operands), paint);
                case "Tj", "TJ", "'", "\"" -> {
                    recordFaint(operands, paint, kept, kept.size(), faintSlots, faintText);
                    blanked += blankHidden(operands, paint, result);
                }
                default -> {
                    // 其余算符原样保留
                }
            }
            kept.addAll(operands);
            operands.clear();
            kept.add(token);
        }
        blanked += blankWholeStream(result, faintSlots, faintText);
        if (blanked == 0) {
            return 0;
        }
        kept.addAll(operands);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(raw.length);
        new ContentStreamWriter(encoded).writeTokens(kept);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(encoded.toByteArray());
        }
        return blanked;
    }

    /**
     * 记下这条流里被淡化画出的字串（位置 + 解码文本），供 {@link #blankWholeStream} 做整流比对。
     * <p>
     * 位点要在置空之前记：kept 的下标用「当前长度 + 操作数下标」算，紧跟着的 {@code kept.addAll(operands)}
     * 只会往后追加，所以记下的下标到改写完成始终指着同一个字串。
     */
    private static void recordFaint(List<Object> operands, PaintState paint, List<Object> kept,
            int keptBase, List<FaintSlot> slots, StringBuilder text) {
        if (!paint.faint()) {
            return;
        }
        for (int i = 0; i < operands.size(); i++) {
            Object operand = operands.get(i);
            if (operand instanceof COSString string) {
                slots.add(new FaintSlot(kept, null, keptBase + i, string));
                text.append(decode(string, paint.font()));
            } else if (operand instanceof COSArray array) {
                for (int j = 0; j < array.size(); j++) {
                    if (array.get(j) instanceof COSString string) {
                        slots.add(new FaintSlot(null, array, j, string));
                        text.append(decode(string, paint.font()));
                    }
                }
            }
        }
    }

    /**
     * 整条流就是一串水印时，把它里面淡化画出的字串全部置空。
     * <p>
     * 逐串比对会被「一行一个词」的水印画法卡住：水印 Form 里常见按词分开的好几个 {@code Tj}，每串
     * 归一化后既不等于整串水印、又短于碎片下限，一处都剔不掉，水印于是被结构化解析器拆进邻近的单
     * 元格里（实测合成样本 zeta/iota/muomega 三个词各自不足 6 字符，两个解析器均泄漏）。而逐字形回
     * 调那一侧同状态的相邻字会被合成一段，所以整串水印是已知的。这里把本流淡化的字拼起来再比一次：
     * 正好等于一串已判定的水印，就说明这条流本身是水印本体（Form 常见）。
     * 只动淡化画出的字串，正常正文即使同文也不受影响。
     */
    private static int blankWholeStream(Result result, List<FaintSlot> slots, StringBuilder faintText) {
        String joined = normalize(faintText.toString());
        if (joined.isEmpty() || !result.hidden().contains(joined)) {
            return 0;
        }
        int blanked = 0;
        for (FaintSlot slot : slots) {
            if (slot.blankIfFilled()) {
                blanked++;
            }
        }
        return blanked;
    }

    /**
     * 一个被淡化画出的字串位置：宿主要么是 {@code kept}（绝对下标），要么是某个 TJ 数组（元素下标）。
     * <p>
     * 除了宿主还记下字串自己：{@code blankHidden} 可能已把命中的串换成空串，置空前比对引用，已经改过
     * 的就不重复改也不重复计数。
     */
    private static final class FaintSlot {

        private final List<Object> list;
        private final COSArray array;
        private final int index;
        private final COSString string;

        private FaintSlot(List<Object> list, COSArray array, int index, COSString string) {
            this.list = list;
            this.array = array;
            this.index = index;
            this.string = string;
        }

        /** 槽位上还指着这个串就把它换成空串，返回本次是否真的改了 */
        private boolean blankIfFilled() {
            Object current = array != null
                    ? (index < array.size() ? array.get(index) : null)
                    : (index < list.size() ? list.get(index) : null);
            if (current != string) {
                return false;
            }
            COSBase empty = new COSString("");
            if (array != null) {
                array.set(index, empty);
            } else {
                list.set(index, empty);
            }
            return true;
        }
    }

    /**
     * 置空本次绘制的水印字符串。
     * <p>
     * 两道都要中：本次绘制确实很淡（透明度或隐形模式，或是从淡化调用点进到的 Form）**且** 文本已被判成
     * 水印。前者是关键护栏：正文永远不以 ca&lt;0.95 或 {@code Tr 3} 绘制，即使它恰好与某串水印同文也不会被抹掉。
     */
    private static int blankHidden(List<Object> operands, PaintState paint, Result result) {
        if (!paint.faint()) {
            return 0;
        }
        int blanked = 0;
        for (int i = 0; i < operands.size(); i++) {
            Object operand = operands.get(i);
            if (operand instanceof COSString text) {
                if (isHidden(result, decode(text, paint.font()))) {
                    operands.set(i, new COSString(""));
                    blanked++;
                }
            } else if (operand instanceof COSArray array) {
                blanked += blankArray(array, paint, result);
                if (blanked > 0) {
                    operands.set(i, array);
                }
            }
        }
        return blanked;
    }

    /**
     * 置空 TJ 数组里的字符串。
     * <p>
     * 带字距数组的水印常被拆成一串单字（{@code [(I)(N)(T)...] TJ}），逐字比对永远够不上碎片下限，
     * 所以先把整个数组的字串拼起来比一次；整串命中就把数组里所有字串清空（数字字距留着不影响抽取）。
     */
    private static int blankArray(COSArray array, PaintState paint, Result result) {
        StringBuilder joined = new StringBuilder();
        for (COSBase element : array) {
            if (element instanceof COSString text) {
                joined.append(decode(text, paint.font()));
            }
        }
        boolean whole = isHidden(result, joined.toString());
        int blanked = 0;
        for (int i = 0; i < array.size(); i++) {
            COSBase element = array.get(i);
            if (!(element instanceof COSString text)) {
                continue;
            }
            if (whole || isHidden(result, decode(text, paint.font()))) {
                array.set(i, new COSString(""));
                blanked++;
            }
        }
        return blanked;
    }

    /**
     * 把内容流里的字串解成可比对的文本。
     * <p>
     * 不能用 {@code COSString.getString()}：它按 PDFDocEncoding/UTF-16 解，而中文水印常用 GBK-EUC-H
     * 之类的 CMap 编码（实测样本里「严禁复制」就是 {@code <d1cfbdfbb8b4d6c6>}），按默认解码只能拿到乱码，
     * 与逐字形回调读到的水印文本永远对不上。这里按当前字体的 {@code readCode} + {@code toUnicode} 逐码解，
     * 跟 PDFBox 抽正文走的是同一条路径，两边文本因此一致。
     */
    private static String decode(COSString text, PDFont font) {
        if (font == null) {
            return text.getString();
        }
        byte[] bytes = text.getBytes();
        try {
            StringBuilder sb = new StringBuilder(bytes.length);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            int remaining = in.available();
            while (remaining > 0) {
                int code = font.readCode(in);
                if (in.available() == remaining) {
                    // readCode 不消耗字节就无法前进，直接止损（宁可漏删，不能死循环）
                    break;
                }
                remaining = in.available();
                String unicode = font.toUnicode(code);
                if (unicode != null) {
                    sb.append(unicode);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 尾字节不齐、CMap 读不动等：退回默认解码，比对不中就什么也不改
            return text.getString();
        }
    }

    /**
     * Form XObject 的调用情况：被淡化调用过的进改写队列，被正常调用过的进黑名单（两处都见过时黑名单赢）。
     */
    private static final class FormCarriers {

        private final List<PDFormXObject> faint = new ArrayList<>();
        private final Set<COSBase> faintKeys = new HashSet<>();
        private final Set<COSBase> solid = new HashSet<>();

        /** 记录一个 {@code Do}：只关 Form，图片等其他 XObject 与水印无关 */
        private void recordForm(PDResources resources, COSName name, PaintState paint) {
            if (resources == null || name == null) {
                return;
            }
            PDXObject xobject;
            try {
                xobject = resources.getXObject(name);
            } catch (Exception e) {
                log.debug("XObject {} 解析失败，跳过：{}", name.getName(), e.getMessage());
                return;
            }
            if (!(xobject instanceof PDFormXObject form)) {
                return;
            }
            COSBase key = form.getCOSObject();
            if (paint.faint()) {
                if (faintKeys.add(key)) {
                    faint.add(form);
                }
            } else {
                solid.add(key);
            }
        }
    }

    private static COSName firstName(List<Object> operands) {
        return !operands.isEmpty() && operands.get(0) instanceof COSName name ? name : null;
    }

    private static int firstNumber(List<Object> operands) {
        return !operands.isEmpty() && operands.get(0) instanceof COSNumber number ? number.intValue() : 0;
    }

    /**
     * 内容流里的绘制状态跟踪：只跟 {@code q/Q}、{@code gs}（透明度）、{@code Tr}（渲染模式）与
     * {@code Tf}（当前字体，只为解码字串）四件事，足够判定「这次绘制确实很淡」。颜色不跟，
     * 浅色水印走不了预剔除，由删节点那层兼管。
     */
    private static final class PaintState {

        private static final int MODE_INVISIBLE = 3;
        private static final int MODE_INVISIBLE_CLIP = 7;

        private final Deque<double[]> stack = new ArrayDeque<>();
        /** 从调用点带进 Form 的淡化状态（Form 自己没有 {@code gs}，透明度是外面给的） */
        private final boolean inheritedFaint;
        private double alpha = 1;
        private int mode;
        private PDFont font;

        private PaintState(boolean inheritedFaint) {
            this.inheritedFaint = inheritedFaint;
        }

        void push() {
            stack.push(new double[]{alpha, mode});
        }

        void pop() {
            double[] saved = stack.poll();
            if (saved != null) {
                alpha = saved[0];
                mode = (int) saved[1];
            }
        }

        void setRenderingMode(int value) {
            mode = value;
        }

        /** 记下当前字体：解码字串要用它（中文水印多为 CMap 编码），取不到字体就只能退回默认解码 */
        void applyFont(PDResources resources, COSName name) {
            if (resources == null || name == null) {
                font = null;
                return;
            }
            try {
                font = resources.getFont(name);
            } catch (Exception e) {
                log.debug("字体 {} 读取失败，本串按默认解码：{}", name.getName(), e.getMessage());
                font = null;
            }
        }

        void applyGraphicsState(PDResources resources, COSName name) {
            if (resources == null || name == null) {
                return;
            }
            PDExtendedGraphicsState state = resources.getExtGState(name);
            if (state == null) {
                return;
            }
            Float value = state.getNonStrokingAlphaConstant();
            if (value != null) {
                alpha = value;
            }
        }

        boolean faint() {
            return inheritedFaint || alpha < FAINT_ALPHA || mode == MODE_INVISIBLE || mode == MODE_INVISIBLE_CLIP;
        }

        PDFont font() {
            return font;
        }
    }

    private static Result classify(List<Run> runs) {
        if (runs.isEmpty()) {
            return Result.none(null);
        }
        // 先归一化并按页分组：字号中位数必须是「整页正文的尺寸」，后面逐段比对靠它判大字
        Map<Integer, List<Run>> byPage = new HashMap<>();
        Map<String, Set<Integer>> pagesByString = new HashMap<>();
        long totalChars = 0;
        for (Run run : runs) {
            run.normalized = normalize(run.text.toString());
            if (run.normalized.isEmpty()) {
                continue;
            }
            totalChars += run.normalized.length();
            byPage.computeIfAbsent(run.page, key -> new ArrayList<>()).add(run);
            pagesByString.computeIfAbsent(run.normalized, key -> new HashSet<>()).add(run.page);
        }
        Map<Integer, Float> medianByPage = new HashMap<>();
        byPage.forEach((page, pageRuns) -> medianByPage.put(page, medianSize(pageRuns)));
        int distinctPages = byPage.size();

        Set<String> hidden = new HashSet<>();
        long hiddenChars = 0;
        for (Run run : runs) {
            if (run.normalized.isEmpty() || hidden.contains(run.normalized)) {
                continue;
            }
            int stringPages = pagesByString.getOrDefault(run.normalized, Set.of()).size();
            if (!isOverlay(run, medianByPage.getOrDefault(run.page, 0f), stringPages, distinctPages)) {
                continue;
            }
            hidden.add(run.normalized);
            hiddenChars += run.normalized.length();
        }
        if (hidden.isEmpty()) {
            return new Result(Set.of(), "", 0, null, null);
        }
        // 总量闸门：装饰文字不该比正文还多，一旦反超说明这些“看不见的大片文字”其实是 OCR 文字层
        if (totalChars > 0 && hiddenChars > (long) (totalChars * MAX_HIDDEN_SHARE)) {
            log.info("水印候选文字量 {} 超过全文 {} 的 {}%，按文字层处理，不做剔除",
                    hiddenChars, totalChars, (int) (MAX_HIDDEN_SHARE * 100));
            return Result.none("水印候选占比过高，按文字层保留");
        }
        // 留下正常文本作为碎片护栏：碎片能在正文里找到就不删
        StringBuilder visible = new StringBuilder((int) Math.min(totalChars, MAX_VISIBLE_GUARD));
        for (Run run : runs) {
            if (run.normalized.isEmpty() || hidden.contains(run.normalized)) {
                continue;
            }
            if (visible.length() >= MAX_VISIBLE_GUARD) {
                break;
            }
            visible.append(run.normalized);
        }
        log.info("判定 {} 段装饰文字（水印）将从解析结果中剔除，全文共 {} 段文字", hidden.size(), runs.size());
        return new Result(hidden, visible.toString(), hidden.size(), null, null);
    }

    /** 两条信号同时成立才算水印 */
    private static boolean isOverlay(Run run, float median, int stringPages, int distinctPages) {
        boolean unusualPaint = run.invisible || run.alpha < FAINT_ALPHA || run.light;
        if (!unusualPaint) {
            return false;
        }
        boolean diagonal = offAxis(run.angle) > ROTATION_TOLERANCE;
        boolean oversized = median > 0 && run.fontSize >= median * OVERSIZE_RATIO;
        boolean repeated = distinctPages >= REPEAT_PAGES && stringPages >= Math.min(REPEAT_PAGES, distinctPages);
        return diagonal || oversized || repeated;
    }

    /**
     * 绘制倾角（度）。
     * <p>
     * 不能用 {@code TextPosition.getDir()}：它只返回 0/90/180/270 四个正交值（官方 javadoc 已写明），
     * 45 度斜排会被归一成 0，斜排信号就废了。取文本渲染矩阵（叠字库矩阵）的反正切才是真倾角。
     * <p>
     * 这里拿到的文本矩阵已经是设备空间的：PDFBox 把 {@code cm}（含 Form XObject 的变换）也算了进去，
     * 所以画在 Form 里、旋转写在 {@code cm} 上的水印同样能读到倾角（真实企业 PDF 的主流画法，实测）。
     */
    private static float angleOf(TextPosition text) {
        Matrix matrix = text.getTextMatrix().clone();
        PDFont font = text.getFont();
        if (font != null) {
            matrix.concatenate(font.getFontMatrix());
        }
        return (float) Math.toDegrees(Math.atan2(matrix.getShearY(), matrix.getScaleY()));
    }

    /** 与最近的水平/垂直轴的夹角（0~45 度） */
    private static float offAxis(float dir) {
        float normalized = Math.abs(dir) % 90f;
        return Math.min(normalized, 90f - normalized);
    }

    private static float medianSize(List<Run> pageRuns) {
        List<Float> sizes = new ArrayList<>();
        for (Run run : pageRuns) {
            if (run.fontSize > 0 && !run.normalized.isEmpty()) {
                sizes.add(run.fontSize);
            }
        }
        if (sizes.isEmpty()) {
            return 0f;
        }
        sizes.sort(Comparator.naturalOrder());
        return sizes.get(sizes.size() / 2);
    }

    /** 归一化：去掉全部空白并转小写，让排版差异不影响比对 */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 节点文本是否命中水印集合。
     * <p>
     * 先按归一化全等比对；不相等时再允许“水印碎片”——解析器会把一整串水印按行/按词间距拆成
     * 几段（tika 就拆得尤其碎），只按全等则这些碎片会漏网。碎片判定带两道护栏：长度下限，
     * 以及“该文本在正常正文里也出现过就不删”。
     */
    static boolean isHidden(Result result, String text) {
        if (result == null || result.hidden().isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        if (result.hidden().contains(normalized)) {
            return true;
        }
        if (normalized.length() < MIN_FRAGMENT || result.visible().contains(normalized)) {
            return false;
        }
        for (String watermark : result.hidden()) {
            if (watermark.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 浅色判定：填充色转 RGB 后按亮度加权。取不到色空间（CMYK/专色异常）就按不浅色处理 */
    private static boolean isLight(PDColor color) {
        if (color == null) {
            return false;
        }
        try {
            int rgb = color.toRGB();
            double luminance = (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF)
                    + 0.114 * (rgb & 0xFF)) / 255.0;
            return luminance >= LIGHT_BRIGHTNESS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 逐次文字绘制收集绘制状态。
     * <p>
     * 接的是 {@code processTextPosition(text)} 而不是 {@code writeString(text, positions)}：
     * 后者在 PDFBox 里是<b>输出阶段</b>调用的（{@code writeLine} 会等全部内容流处理完再逐行写出），
     * 那会儿图形状态已随最后一个 {@code Q} 弹干净，读到的 alpha 恒为 1、渲染模式恒为落墨；
     * 而 {@code processTextPosition} 在 {@code Tj/TJ} 的处理链里逐字形回调，此时状态就是本次绘制的状态。
     * 解码、换页、子流（表单 XObject）仍由 stripper 处理，我们只读状态并把同状态的相邻字符合成一段。
     */
    private static final class RunStripper extends PDFTextStripper {

        private final List<Run> runs;
        private Run current;

        private RunStripper(List<Run> runs) {
            this.runs = runs;
        }

        /** 落袋当前段（换页与收尾都要调） */
        private void finish() {
            if (current != null && current.text.length() > 0) {
                runs.add(current);
            }
            current = null;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            // 先交给父类维护它自己的排版记录，再读本次字形（重复重叠文本抑制可能已改写 unicode）
            super.processTextPosition(text);
            String unicode = text.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                return;
            }
            Run run = new Run();
            run.page = getCurrentPageNo();
            run.fontSize = text.getFontSize();
            try {
                PDGraphicsState graphics = getGraphicsState();
                PDTextState state = graphics.getTextState();
                RenderingMode mode = state.getRenderingMode();
                run.invisible = mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP;
                run.alpha = graphics.getNonStrokeAlphaConstant();
                run.light = isLight(graphics.getNonStrokingColor());
                run.angle = angleOf(text);
            } catch (Exception e) {
                // 状态读不到就按正文默认值：宁可漏删水印，不可误删正文
                log.debug("读取绘制状态失败，该段按正文保留：{}", e.getMessage());
            }
            if (current != null && current.sameState(run)) {
                current.text.append(unicode);
                return;
            }
            finish();
            run.text.append(unicode);
            current = run;
        }
    }
}
