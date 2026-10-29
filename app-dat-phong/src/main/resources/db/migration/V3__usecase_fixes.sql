-- V3: bổ sung theo đối chiếu đặc tả use case (UC-15, UC-30). Không sửa V1/V2 đã chạy.

-- UC-30: giá trị cấu hình lên lịch ở tương lai không được làm hệ thống rơi về default trước thời điểm hiệu lực.
-- previous_value = giá trị đang hiệu lực ngay trước lần cập nhật (dùng cho tới effective_at).
ALTER TABLE config_values ADD COLUMN previous_value VARCHAR(500);

-- UC-15: check-out ngoại lệ (booking chưa check-in) chỉ SUPPORT có quyền riêng, bắt buộc lý do + audit.
INSERT INTO permissions (code, description) VALUES
 ('SUPPORT_STAY_EXCEPTION', 'Xử lý ngoại lệ lưu trú (check-out khi chưa check-in) có lý do + audit');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'SUPPORT_STAY_EXCEPTION' WHERE r.code = 'SUPPORT';

-- UC-36: SUPPORT xem ca đối soát theo payment nhanh hơn
CREATE INDEX IF NOT EXISTS idx_case_status ON reconciliation_cases (status, created_at);
