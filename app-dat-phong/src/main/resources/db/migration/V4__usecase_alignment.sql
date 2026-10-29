-- V4: đối chiếu "Đặc tả luồng use case chuyên sâu" lần 2. Không sửa V1-V3 đã chạy.

-- UC-14/29: HOST cập nhật giờ đến dự kiến và ghi chú nội bộ (không trả cho khách).
ALTER TABLE bookings ADD COLUMN expected_arrival_time TIME;
ALTER TABLE bookings ADD COLUMN host_note VARCHAR(1000);

-- UC-26: thao tác quản trị cache analytics cần quyền riêng + audit.
INSERT INTO permissions (code, description) VALUES
 ('ANALYTICS_CACHE_MANAGE', 'Làm mới (invalidate) cache thống kê - có audit');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'ANALYTICS_CACHE_MANAGE' WHERE r.code = 'ADMIN';
