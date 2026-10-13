package tech.qunxu.module.document.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * 基于 jtokkit（CL100K_BASE）的 token 计数器。
 * <p>
 * 刻意与 Spring AI 的 {@code JTokkitTokenCountEstimator} 保持同一实现口径：
 * 分块阶段判定「不超预算」和 embedding 阶段判定「不超限」必须是同一个数字，
 * 否则会出现这里算着没超、模型侧却报错的偶发整篇索引失败。
 * <p>
 * 已知偏差（写进 docs 的「本轮不做」）：CL100K 对中文偏保守（约 1 字 1.3~1.5 token），
 * 若换用国产模型，注册一个自定义 {@link TokenCounter} Bean 覆盖即可，不必改分块器。
 * <p>
 * {@link Encoding} 无状态且线程安全，因此整个应用共用一个实例；
 * 用 lazy registry 是为了避免启动时一次性加载全部词表。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public class JTokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JTokkitTokenCounter() {
        this(EncodingType.CL100K_BASE);
    }

    public JTokkitTokenCounter(EncodingType type) {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(type);
    }

    @Override
    public String key() {
        return "jtokkit";
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return encoding.countTokens(text);
        } catch (RuntimeException e) {
            // 计数失败不能阻断分块：退化为粗估（英文 4 字符 1 token，CJK 1 字 1 token）
            return roughEstimate(text);
        }
    }

    private int roughEstimate(String text) {
        int tokens = 0;
        int latinRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                tokens++;
            } else {
                latinRun++;
                if (latinRun == 4) {
                    tokens++;
                    latinRun = 0;
                }
            }
        }
        return Math.max(1, tokens + (latinRun > 0 ? 1 : 0));
    }
}
