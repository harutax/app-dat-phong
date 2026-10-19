package datphong.vn.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Tiện ích tiền tệ: mọi khoản tiền dùng BigDecimal, VND không có phần thập phân. */
public final class Money {

    public static final String VND = "VND";
    public static final int VND_SCALE = 0;

    private Money() {}

    public static BigDecimal vnd(BigDecimal v) {
        return v.setScale(VND_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(VND_SCALE);
    }

    /** So sánh bằng giá trị (không phụ thuộc scale): 100.0 == 100. */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }
}
