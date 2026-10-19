package datphong.vn.util;

import java.security.SecureRandom;
import java.util.UUID;

/** Tiện ích chuỗi: chuẩn hóa email, loại HTML, che PII, sinh mã. */
public final class Texts {
    private static final SecureRandom RND = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private Texts() {}

    public static String normEmail(String e) {
        return e == null ? null : e.trim().toLowerCase();
    }

    /** Loại thẻ HTML khỏi nội dung người dùng gửi (chat, review). */
    public static String stripHtml(String s) {
        if (s == null) return null;
        return s.replaceAll("(?is)<script.*?</script>", "").replaceAll("<[^>]*>", "").replace("\u0000", "").trim();
    }

    public static String maskEmail(String e) {
        if (e == null || !e.contains("@")) return "***";
        int at = e.indexOf('@');
        String local = e.substring(0, at);
        return (local.length() <= 2 ? "*" : local.substring(0, 2) + "***") + e.substring(at);
    }

    public static String maskPhone(String p) {
        if (p == null || p.length() < 4) return null;
        return "*".repeat(p.length() - 3) + p.substring(p.length() - 3);
    }

    public static String code(String prefix, int len) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public static String numericCode(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(RND.nextInt(10));
        return sb.toString();
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Độ dài theo Unicode code points (thống nhất backend/frontend, không theo byte). */
    public static int length(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** Chống CSV formula injection khi export. */
    public static String csvSafe(String s) {
        if (s == null) return "";
        return (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) ? "'" + s : s;
    }
}
