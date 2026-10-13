package tech.qunxu.module.document.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 工具（内容指纹是解析缓存的键基础）。
 *
 * @author ethanwang
 * @since 2026-10-10
 */
public final class Sha256Util {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int BUFFER_SIZE = 8192;

    private Sha256Util() {
    }

    /** 新建一个 SHA-256 摘要器 */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备算法，正常不会发生
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 摘要器结果转十六进制小写 */
    public static String hex(MessageDigest digest) {
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** 字节内容哈希 */
    public static String sha256(byte[] bytes) {
        MessageDigest digest = newDigest();
        digest.update(bytes == null ? new byte[0] : bytes);
        return hex(digest);
    }

    /** 文本内容哈希（UTF-8） */
    public static String sha256(String text) {
        return sha256(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 流内容哈希（边读边算，不把整流驻留内存两遍）。
     * <p>
     * 传入的流会被读完；调用方仍负责关闭。
     */
    public static String sha256(InputStream is) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = is.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return hex(digest);
    }

    /**
     * 把流读成字节，同时返回内容与哈希（URL/本地源「先取字节再算指纹」的统一入口）。
     *
     * @param is       输入流（读完即止，由调用方负责关闭）
     * @param maxBytes 字节上限，{@code <=0} 表示不限
     * @return 内容与哈希
     */
    public static Content readWithHash(InputStream is, long maxBytes) throws IOException {
        MessageDigest digest = newDigest();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.min(1 << 20, safeInitial(is, maxBytes)));
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        long total = 0;
        while ((read = is.read(buffer)) > 0) {
            total += read;
            if (maxBytes > 0 && total > maxBytes) {
                throw new IOException("内容字节数超过上限 " + maxBytes + "，已中断读取");
            }
            digest.update(buffer, 0, read);
            bos.write(buffer, 0, read);
        }
        return new Content(bos.toByteArray(), hex(digest));
    }

    private static int safeInitial(InputStream is, long maxBytes) {
        try {
            int available = is.available();
            if (available > 0) {
                return maxBytes > 0 ? (int) Math.min(available, maxBytes) : available;
            }
        } catch (IOException ignored) {
            // available() 不支持时按默认容量
        }
        return BUFFER_SIZE;
    }

    /**
     * 字节内容与内容指纹。
     *
     * @param bytes  内容
     * @param sha256 十六进制小写哈希
     */
    public record Content(byte[] bytes, String sha256) {

        /** 内容的独立可读流（同一份字节可多次解析） */
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }

        /** 字节数 */
        public long size() {
            return bytes == null ? 0 : bytes.length;
        }
    }
}
