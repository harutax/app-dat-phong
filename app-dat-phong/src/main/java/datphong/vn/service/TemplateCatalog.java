package datphong.vn.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Danh mục event email: biến cho phép + mẫu mặc định (tiếng Việt). Backend giới hạn tập biến theo từng loại sự kiện. */
public final class TemplateCatalog {

    public record Def(List<String> vars, String subject, String text) {}

    public static final Map<String, Def> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("ACCOUNT_CREATED", new Def(List.of("customerName", "maskedEmail", "verificationLink"),
                "Chào mừng {{customerName}} đến với hệ thống đặt phòng",
                "Xin chào {{customerName}},\nTài khoản {{maskedEmail}} đã được tạo. Bấm {{verificationLink}} để xác minh.\nChúng tôi không bao giờ gửi mật khẩu qua email."));
        DEFAULTS.put("EMAIL_VERIFICATION", new Def(List.of("customerName", "verificationCode", "verificationLink"),
                "Mã xác minh email của bạn",
                "Xin chào {{customerName}},\nMã xác minh: {{verificationCode}}\nHoặc bấm: {{verificationLink}}\nMã có hạn dùng và chỉ dùng được một lần."));
        DEFAULTS.put("BOOKING_CONFIRMED", new Def(List.of("customerName", "bookingCode", "propertyName", "checkInDate", "checkOutDate", "totalAmount", "currency"),
                "Xác nhận đặt phòng {{bookingCode}}",
                "Xin chào {{customerName}},\nBooking {{bookingCode}} tại {{propertyName}} đã được xác nhận.\nNhận phòng: {{checkInDate}} - Trả phòng: {{checkOutDate}}\nTổng tiền: {{totalAmount}} {{currency}}"));
        DEFAULTS.put("PAYMENT_SUCCEEDED", new Def(List.of("customerName", "bookingCode", "amount", "currency"),
                "Thanh toán thành công cho {{bookingCode}}",
                "Xin chào {{customerName}},\nChúng tôi đã nhận {{amount}} {{currency}} cho booking {{bookingCode}}."));
        DEFAULTS.put("PAYMENT_FAILED", new Def(List.of("customerName", "bookingCode", "amount", "currency"),
                "Thanh toán chưa thành công cho {{bookingCode}}",
                "Xin chào {{customerName}},\nThanh toán {{amount}} {{currency}} cho booking {{bookingCode}} chưa thành công. Bạn có thể thử lại trong thời hạn giữ chỗ."));
        DEFAULTS.put("REFUND_SUCCEEDED", new Def(List.of("customerName", "bookingCode", "refundAmount", "currency", "refundCode"),
                "Đã hoàn tiền cho booking {{bookingCode}}",
                "Xin chào {{customerName}},\nBooking {{bookingCode}} đã hoàn {{refundAmount}} {{currency}}, mã hoàn {{refundCode}}."));
        DEFAULTS.put("REFUND_FAILED", new Def(List.of("customerName", "bookingCode", "refundAmount", "currency", "refundCode"),
                "Hoàn tiền cho {{bookingCode}} đang được xử lý thủ công",
                "Xin chào {{customerName}},\nViệc hoàn {{refundAmount}} {{currency}} (mã {{refundCode}}) cho booking {{bookingCode}} chưa hoàn tất. Bộ phận hỗ trợ sẽ liên hệ."));
        DEFAULTS.put("CHECKIN_REMINDER", new Def(List.of("customerName", "bookingCode", "propertyName", "checkInDate", "checkInTime", "propertyAddress", "contactLink"),
                "Nhắc nhận phòng {{checkInDate}} tại {{propertyName}}",
                "Xin chào {{customerName}},\nBạn sắp nhận phòng tại {{propertyName}} vào {{checkInDate}} lúc {{checkInTime}}.\nĐịa chỉ: {{propertyAddress}}\nMã booking: {{bookingCode}}\nLiên hệ chủ cơ sở: {{contactLink}}"));
        DEFAULTS.put("COMPLAINT_REPLY", new Def(List.of("customerName", "propertyName", "bookingCode"),
                "Chủ cơ sở đã phản hồi phản ánh của bạn",
                "Xin chào {{customerName}},\n{{propertyName}} đã phản hồi phản ánh của bạn về booking {{bookingCode}}. Mở ứng dụng để xem chi tiết."));
    }

    private TemplateCatalog() {}

    public static String htmlFromText(String text) {
        StringBuilder sb = new StringBuilder("<div style=\"font-family:Arial,sans-serif;font-size:14px;line-height:1.5\">");
        for (String line : text.split("\n")) sb.append("<p>").append(line).append("</p>");
        return sb.append("</div>").toString();
    }
}
