package tech.qunxu.module.document.chunk;

/**
 * token 计数 SPI。
 * <p>
 * 分块预算必须按 token 而不是字符：embedding 模型的输入上限以 token 计，
 * 中文「1 字符 ≈ 1 token」、英文「4 字符 ≈ 1 token」的差异会让按字符切分的结果
 * 要么浪费上下文、要么超限报错。
 * <p>
 * 默认实现是 {@link JTokkitTokenCounter}（CL100K_BASE，与 Spring AI 的
 * {@code JTokkitTokenCountEstimator} 同一口径，保证这里判定不超限时
 * embedding 阶段也不会超）。要按所用模型换 tokenizer（如国产模型的中文分词）时，
 * 注册一个新的 {@code TokenCounter} Bean 即可（L1 扩展），所有分块器自动跟随。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public interface TokenCounter {

    /** 实现标识（观测与自检用） */
    default String key() {
        return "jtokkit";
    }

    /** 文本 token 数（null/空为 0，实现必须保证不抛异常） */
    int count(String text);

    /** 是否已达/超过预算 */
    default boolean exceeds(String text, int budget) {
        return count(text) > budget;
    }

    /**
     * 估算「按 token 预算能切到多少字符」：用整段文本的 token/字符比例反推，
     * 用于超长文本的滑窗截断，避免为了找边界反复编码。
     *
     * @param text    原始文本
     * @param budget  目标 token 数
     * @return 建议字符数（至少 1，至多 {@code text.length()}）
     */
    default int charsForBudget(String text, int budget) {
        if (text == null || text.isEmpty() || budget <= 0) {
            return text == null ? 0 : text.length();
        }
        int tokens = count(text);
        if (tokens <= budget) {
            return text.length();
        }
        long estimate = (long) text.length() * budget / tokens;
        return (int) Math.max(1, Math.min(text.length() - 1L, estimate));
    }
}
