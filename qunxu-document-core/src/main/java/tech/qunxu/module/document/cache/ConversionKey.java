package tech.qunxu.module.document.cache;

import java.util.Locale;

/**
 * 解析缓存键：内容哈希 + 解析器标识 + 解析器版本 + 选项指纹。
 * <p>
 * 四项缺一不可：
 * <ul>
 *   <li>{@code fileHash} 让「同一份内容、不同文件名/不同 URL」命中同一条记录 —— 这是「唯一解析源」的基础；</li>
 *   <li>{@code backendKey} 让 docx 与 pdf 之类互不干扰，也让同一格式的多实现并存；</li>
 *   <li>{@code backendVersion} 让解析器升级后旧缓存自然失效，无需手工清库；</li>
 *   <li>{@code optionsFingerprint} 让「抽图/不抽图、只取前 N 页、指定 Sheet」不会互相脏读。</li>
 * </ul>
 *
 * @param fileHash          源内容 SHA-256
 * @param backendKey        解析器标识
 * @param backendVersion    解析器版本
 * @param optionsFingerprint 影响解析结果的选项指纹
 * @author ethanwang
 * @since 2026-10-10
 */
public record ConversionKey(String fileHash, String backendKey, int backendVersion, String optionsFingerprint) {

    public static ConversionKey of(String fileHash, String backendKey, int backendVersion, String optionsFingerprint) {
        return new ConversionKey(
                fileHash == null ? "" : fileHash.trim().toLowerCase(Locale.ROOT),
                backendKey == null ? "" : backendKey.trim(),
                Math.max(1, backendVersion),
                optionsFingerprint == null ? "" : optionsFingerprint);
    }

    /** 进程内 single-flight 与内存缓存使用的规范化键 */
    public String canonical() {
        return fileHash + '|' + backendKey + '|' + backendVersion + '|' + optionsFingerprint;
    }

    /** 内容是否为空（无哈希就没有缓存意义，直接跳过缓存） */
    public boolean valid() {
        return !fileHash.isEmpty() && !backendKey.isEmpty() && !optionsFingerprint.isEmpty();
    }
}
