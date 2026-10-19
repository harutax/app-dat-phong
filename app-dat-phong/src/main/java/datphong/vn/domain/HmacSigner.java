package datphong.vn.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 dùng cho webhook Fake Bank và băm mã xác minh. So sánh chữ ký bằng thời gian hằng. */
public final class HmacSigner {

    private HmacSigner() {}

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không tính được HMAC", e);
        }
    }

    public static boolean verify(String secret, String data, String signatureHex) {
        if (signatureHex == null || secret == null || secret.isBlank()) return false;
        byte[] expected = sign(secret, data).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signatureHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public static String sha256Hex(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
