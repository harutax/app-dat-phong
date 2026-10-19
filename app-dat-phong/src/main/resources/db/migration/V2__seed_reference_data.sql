-- Vai trò & quyền chi tiết (least privilege). ADMIN không tự động có mọi quyền: phải có permission cụ thể.
INSERT INTO roles (code, description) VALUES
 ('CUSTOMER','Khách đặt phòng'),('HOST','Chủ cơ sở lưu trú'),('SUPPORT','Hỗ trợ'),('ADMIN','Quản trị');

INSERT INTO permissions (code, description) VALUES
 ('BOOKING_CREATE','Tạo/hủy booking của chính mình'),
 ('REVIEW_CREATE','Viết đánh giá'),
 ('PROPERTY_CREATE','Tạo property'),
 ('PROPERTY_UPDATE','Sửa property/loại phòng/phòng/giá/ảnh'),
 ('BOOKING_VIEW','Xem booking thuộc phạm vi'),
 ('BOOKING_UPDATE','Cập nhật yêu cầu đặc biệt booking'),
 ('STAY_MANAGE','Check-in/check-out'),
 ('ANALYTICS_VIEW','Xem thống kê/báo cáo'),
 ('SUPPORT_BOOKING_CANCEL','Hủy booking thay khách (ngoại lệ)'),
 ('SUPPORT_REFUND_RECONCILE','Đối soát/hoàn tiền thủ công có kiểm soát'),
 ('REVIEW_MODERATE','Kiểm duyệt đánh giá'),
 ('CONVERSATION_SUPPORT','Tham gia hội thoại được phân công'),
 ('MAIL_OPERATE','Tra cứu/gửi lại email'),
 ('USER_MANAGE','Quản trị tài khoản'),
 ('ROLE_MANAGE','Cấp/thu role'),
 ('AMENITY_MANAGE','Quản trị danh mục tiện ích'),
 ('SYSTEM_CONFIG_READ','Xem cấu hình'),
 ('SYSTEM_CONFIG_WRITE','Sửa cấu hình'),
 ('EMAIL_TEMPLATE_MANAGE','Quản trị mẫu email'),
 ('AUDIT_READ','Tra cứu audit');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON
  (r.code='CUSTOMER' AND p.code IN ('BOOKING_CREATE','REVIEW_CREATE'))
 OR (r.code='HOST' AND p.code IN ('PROPERTY_CREATE','PROPERTY_UPDATE','BOOKING_VIEW','BOOKING_UPDATE','STAY_MANAGE','ANALYTICS_VIEW'))
 OR (r.code='SUPPORT' AND p.code IN ('BOOKING_VIEW','SUPPORT_BOOKING_CANCEL','SUPPORT_REFUND_RECONCILE','REVIEW_MODERATE','CONVERSATION_SUPPORT','MAIL_OPERATE','AUDIT_READ'))
 OR (r.code='ADMIN' AND p.code IN ('USER_MANAGE','ROLE_MANAGE','AMENITY_MANAGE','SYSTEM_CONFIG_READ','SYSTEM_CONFIG_WRITE','EMAIL_TEMPLATE_MANAGE','AUDIT_READ','ANALYTICS_VIEW','MAIL_OPERATE'));

INSERT INTO amenities (code, name) VALUES
 ('WIFI','Wi-Fi miễn phí'),('AC','Điều hòa'),('POOL','Hồ bơi'),('PARKING','Bãi đỗ xe'),
 ('BREAKFAST','Bữa sáng'),('KITCHEN','Bếp riêng'),('TV','TV'),('GYM','Phòng gym'),('BALCONY','Ban công');

-- Định nghĩa cấu hình (min/max là rào chắn an toàn; mọi số thời gian/retry/độ dài đều cấu hình được)
INSERT INTO config_definitions (cfg_key, value_type, min_value, max_value, default_value, scope, public_flag, description) VALUES
 ('auth.password.minLength','INT',8,64,'10','GLOBAL',TRUE,'Độ dài tối thiểu mật khẩu (Unicode code points; sàn hệ thống = 8)'),
 ('auth.password.maxLength','INT',16,128,'64','GLOBAL',TRUE,'Độ dài tối đa mật khẩu'),
 ('auth.maxFailedLogin','INT',3,20,'5','GLOBAL',FALSE,'Số lần đăng nhập sai trước khi khóa tạm'),
 ('auth.lockMinutes','INT',1,1440,'15','GLOBAL',FALSE,'Thời gian khóa tạm (phút)'),
 ('auth.verify.maxAttempts','INT',3,10,'5','GLOBAL',FALSE,'Số lần nhập sai mã xác minh'),
 ('auth.verify.ttlMinutes','INT',5,10080,'1440','GLOBAL',FALSE,'Hạn mã xác minh email'),
 ('auth.verify.resendCooldownSeconds','INT',10,3600,'60','GLOBAL',FALSE,'Khoảng chờ gửi lại mã'),
 ('booking.hold.minutes','INT',1,120,'15','GLOBAL',FALSE,'Thời hạn giữ chỗ chờ thanh toán'),
 ('pricing.taxPercent','DECIMAL',0,30,'0','GLOBAL',FALSE,'Thuế/phụ phí % (hiển thị tách riêng)'),
 ('checkin.reminder.enabled','BOOL',NULL,NULL,'true','GLOBAL',FALSE,'Bật nhắc check-in qua email'),
 ('checkin.reminder.beforeMinutes','INT',5,43200,'2880','GLOBAL',FALSE,'Nhắc trước giờ nhận phòng (phút), mặc định 48 giờ'),
 ('checkin.reminder.cutoffMinutes','INT',0,1440,'60','GLOBAL',FALSE,'Quá hạn bao lâu sau dueAt thì bỏ nhắc'),
 ('mail.retry.maxAttempts','INT',1,20,'5','GLOBAL',FALSE,'Số lần retry email'),
 ('mail.retry.backoffSeconds','INT',5,3600,'30','GLOBAL',FALSE,'Backoff cơ sở (nhân đôi mỗi lần + jitter)'),
 ('mail.defaultLocale','STRING',NULL,NULL,'vi','GLOBAL',FALSE,'Locale mặc định của mẫu email'),
 ('payment.unknown.reconcileAfterSeconds','INT',5,3600,'30','GLOBAL',FALSE,'Sau bao lâu thì đối soát payment chưa rõ'),
 ('refund.retry.maxAttempts','INT',1,20,'5','GLOBAL',FALSE,'Số lần retry refund'),
 ('analytics.cache.ttlSeconds','INT',10,3600,'120','GLOBAL',FALSE,'TTL cache thống kê'),
 ('analytics.maxRangeDays','INT',1,1095,'366','GLOBAL',FALSE,'Khoảng ngày tối đa mỗi truy vấn thống kê'),
 ('analytics.maxPropertyCount','INT',1,500,'100','GLOBAL',FALSE,'Số property tối đa mỗi truy vấn');

INSERT INTO config_definitions (cfg_key, value_type, min_value, max_value, default_value, scope, public_flag, description) VALUES
 ('stay.noShow.afterHours','INT',1,168,'12','GLOBAL',FALSE,'Sau giờ nhận phòng bao lâu HOST được đánh dấu NO_SHOW'),
 ('conversation.maxOpen','INT',1,100,'10','GLOBAL',FALSE,'Số hội thoại mở tối đa mỗi khách'),
 ('complaint.deadlineHours.low','INT',1,720,'72','GLOBAL',FALSE,'Hạn phản hồi phản ánh mức thấp (giờ)'),
 ('complaint.deadlineHours.medium','INT',1,720,'24','GLOBAL',FALSE,'Hạn phản hồi phản ánh mức trung bình (giờ)'),
 ('complaint.deadlineHours.high','INT',1,720,'4','GLOBAL',FALSE,'Hạn phản hồi phản ánh mức cao (giờ)');
