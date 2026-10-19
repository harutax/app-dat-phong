package datphong.vn.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Khoảng lưu trú [checkIn, checkOut): tính đêm nhận phòng, KHÔNG tính đêm trả phòng.
 * Hai khoảng giao nhau khi a.checkIn < b.checkOut && b.checkIn < a.checkOut,
 * nên booking A trả phòng đúng ngày booking B nhận phòng thì không xung đột.
 */
public record StayPeriod(LocalDate checkIn, LocalDate checkOut) {

    public StayPeriod {
        if (checkIn == null || checkOut == null) throw new IllegalArgumentException("Thiếu ngày nhận/trả phòng");
        if (!checkOut.isAfter(checkIn)) throw new IllegalArgumentException("Ngày trả phòng phải sau ngày nhận phòng");
    }

    public int nights() {
        return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** Danh sách stay_date của từng đêm: checkIn .. checkOut-1. */
    public List<LocalDate> stayDates() {
        List<LocalDate> out = new ArrayList<>(nights());
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) out.add(d);
        return out;
    }

    public boolean overlaps(StayPeriod other) {
        return checkIn.isBefore(other.checkOut) && other.checkIn.isBefore(checkOut);
    }
}
