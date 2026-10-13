package tech.qunxu.module.document.support;

import cn.hutool.core.io.CharsetDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 纯文本字节解码（UTF-8 优先，GBK 兜底）。
 * <p>
 * 知识库里的 txt/md/csv 有相当比例是 Windows 导出的 GBK，直接按 UTF-8 读会得到乱码且不可逆；
 * 这里先用 BOM/严格 UTF-8 判定，再用 hutool {@link CharsetDetector} 以严格解码试探候选字符集，
 * 全部失败才回退 UTF-8（保持与改造前 {@code ExcelTableParser} 的 CSV 行为一致）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class TextDecoder {

    private static final Logger log = LoggerFactory.getLogger(TextDecoder.class);

    private TextDecoder() {
    }

    /** 探测字符集（失败回退 UTF-8） */
    public static Charset detectCharset(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        if (startsWith(bytes, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) {
            return StandardCharsets.UTF_8;
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xFE})) {
            return StandardCharsets.UTF_16LE;
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFE, (byte) 0xFF})) {
            return StandardCharsets.UTF_16BE;
        }
        if (isValidUtf8(bytes)) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset detected = CharsetDetector.detect(new ByteArrayInputStream(bytes));
            return detected != null ? detected : StandardCharsets.UTF_8;
        } catch (Exception e) {
            log.debug("字符集探测失败，回退 UTF-8：{}", e.getMessage());
            return StandardCharsets.UTF_8;
        }
    }

    /** 按探测到的字符集解码（去掉 BOM 字符，避免污染首行/首列） */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        Charset charset = detectCharset(bytes);
        String text = new String(bytes, charset);
        return stripBom(text);
    }

    /** 去掉首部 BOM 字符（U+FEFF） */
    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /** 严格 UTF-8 合法性校验（无需解码整串，遇到非法字节即返回 false） */
    public static boolean isValidUtf8(byte[] bytes) {
        int i = 0;
        int len = bytes.length;
        while (i < len) {
            int b = bytes[i] & 0xFF;
            int extra;
            if (b <= 0x7F) {
                i++;
                continue;
            } else if (b >= 0xC2 && b <= 0xDF) {
                extra = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                extra = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
                extra = 3;
            } else {
                return false;
            }
            if (i + extra >= len) {
                return false;
            }
            for (int k = 1; k <= extra; k++) {
                int cont = bytes[i + k] & 0xFF;
                if (cont < 0x80 || cont > 0xBF) {
                    return false;
                }
            }
            i += extra + 1;
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
