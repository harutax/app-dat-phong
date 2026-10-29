# Hệ thống đặt phòng khách sạn / căn hộ

Backend Spring Boot 3.3 / Java 21 viết theo **Đặc tả hệ thống đặt phòng v3.0**, dựng trên khung kiến trúc của app đặt vé xem phim
(Maven, layered `controller / service / repository / entity / dto / exception / config / security / scheduler / client`, JWT, Redis, MinIO, Fake Bank, Docker Compose).

```
hotel/
├── app-dat-phong/   Backend chính (PostgreSQL + Flyway, Redis, MinIO, Mailpit)
├── fake-bank/       Fake Bank ĐỘC LẬP (DB riêng, sổ cái kép, webhook, kịch bản lỗi) – UC-35
├── ops/             Prometheus, script tạo DB bank
├── docker-compose.yml, .env.example
```

## ⚠️ Trạng thái kiểm chứng (đọc trước)

| Hạng mục | Trạng thái |
|---|---|
| Lõi nghiệp vụ không phụ thuộc framework (`domain/`: khoảng lưu trú `[checkIn, checkOut)`, tính giá từng đêm, phí hủy theo snapshot, state machine, HMAC) | **Đã biên dịch và chạy: 22 unit test đều pass** |
| Toàn bộ code Java còn lại (Spring/JPA/Security…) | **Chưa biên dịch bằng Maven, chưa chạy** – môi trường viết code không có Maven/Internet. Đã kiểm tra cú pháp (không có lỗi parse) và rà soát tay chữ ký/hợp đồng giữa các lớp. Rất có thể còn vài lỗi biên dịch/ánh xạ nhỏ khi build lần đầu |
| Flyway SQL (schema, exclusion constraint) | **Chưa chạy trên PostgreSQL** |
| `BookingConcurrencyIT` (50 request đồng thời, idempotency, callback muộn…) | Đã viết, **chưa chạy** (cần Docker) |

Lần đầu: `cd app-dat-phong && mvn -q verify`.

> **Lần rà soát theo tài liệu đặc tả use case (UC-01..UC-36):** đã sửa các lỗi liệt kê trong `BAO_CAO_SUA_LOI.md` (kèm migration `V3__usecase_fixes.sql`). Môi trường rà soát không truy cập được Maven Central nên vẫn **chưa build Maven**; 22 unit test domain chạy lại vẫn pass, toàn bộ mã nguồn đã qua bước parse của `javac` và đối chiếu ánh xạ entity ↔ `V1__init_schema.sql`. `spring.jpa.hibernate.ddl-auto=validate` sẽ chỉ ra ngay cột/kiểu nào lệch giữa entity và `V1__init_schema.sql`.

## Chạy

```bash
cp .env.example .env          # điền các secret (openssl rand -base64 48)
docker compose up --build
```
- API: `http://localhost:8080/api/v1` · Swagger: `/api/v1/swagger-ui.html`
- Mailpit (email): http://localhost:8025 · MinIO console: :9001 · Prometheus :9090 · Grafana :3000
- Dev seed (`SEED_DEMO_DATA=true`): `admin@ / support@ / host@ / customer@datphong.local`, mật khẩu = `SEED_PASSWORD`;
  một cơ sở demo ở Hà Nội với 2 loại phòng, 5 phòng, giá 180 ngày.
- Prometheus scrape cần header `X-Metrics-Token` (actuator không public).

### Tài khoản Fake Bank để thử kịch bản (gửi trong `payerAccount` của `POST /payments`)
| Tài khoản | Hành vi |
|---|---|
| `TEST-OK` | trừ tiền thành công, webhook SUCCEEDED |
| `TEST-POOR` | `INSUFFICIENT_FUNDS`, sổ cái không đổi |
| `TEST-DECLINED` | `DECLINED` |
| `TEST-TIMEOUT` | **đã trừ tiền** nhưng phản hồi quá chậm → client timeout → payment `UNKNOWN` → đối soát |
| `TEST-LOST` | trừ tiền, **mất callback** → chỉ đối soát phục hồi |
| `TEST-DUP` | webhook lặp 3 lần cùng `eventId` |
| `TEST-DELAY` | webhook chậm 25s (đặt `booking.hold.minutes` nhỏ để thử callback muộn sau EXPIRED) |
| `TEST-DBFAIL` | lỗi DB phía bank, rollback toàn bộ |
| `TEST-REFUNDFAIL` | refund bị từ chối (retry rồi `REFUND_STUCK`) |

Đổi kịch bản/nạp tiền: `PUT /fakebank/api/v1/admin/accounts/{no}/scenario?value=…` với `X-Admin-Token`. Kiểm tra sổ cái cân bằng: `GET /fakebank/api/v1/admin/ledger-check`.

## Quyết định thiết kế quan trọng (ADR rút gọn)

1. **Mô hình tồn kho: phòng vật lý.** Quy tắc *thống nhất* ở mọi use case: *khóa hàng `rooms` (PESSIMISTIC_WRITE) → kiểm tra overlap trên `room_allocations` → insert allocation*. Exclusion constraint `ex_room_no_overlap (room_id =, stay_range &&) WHERE active` là chốt chặn cuối. `stay_range` là generated column từ `check_in/check_out` nên không cần ánh xạ `daterange`. Giải phóng = `UPDATE … SET active=false WHERE active` (đúng một lần).
2. **Thứ tự khóa:** `booking → payment → refund/allocation`. Webhook, job hết hạn, hủy, check-in đều khóa booking trước. (Đặc tả nêu hai thứ tự hơi khác nhau ở §4.1 và UC-36; chọn một và dùng nhất quán.)
3. **Hold hết hạn nhưng job chưa chạy vẫn chiếm phòng** tối đa một chu kỳ job (15s mặc định) – bảo thủ, không bao giờ bán trùng.
4. **Hủy giải phóng phòng ngay lúc chốt hủy**; hoàn tiền là luồng tiền riêng (`REFUND_PENDING` không giữ phòng). Phí tính từ snapshot (`free_cancel_deadline`, `penalty_percent`) trong booking.
5. **Callback thanh toán muộn** (booking `EXPIRED`/`CANCELLED`): payment ghi nhận `SUCCEEDED`, booking **không bao giờ** thành `CONFIRMED`; `EXPIRED → NEEDS_ATTENTION`, tạo ca `NEEDS_REFUND` + refund `late-{paymentId}` (idempotent), xong refund thì về `EXPIRED`.
6. **Timeout ≠ thất bại:** timeout/5xx khi gọi bank → payment `UNKNOWN`; job đối soát hỏi bank bằng `idempotencyKey` (không debit lại bằng key mới). Bank không có giao dịch và đã quá 2 phút mới kết luận `FAILED`.
7. **Không giữ transaction trong lúc gọi Fake Bank / MinIO / SMTP.** Các bước DB ngắn dùng `TransactionTemplate`; trạng thái `PROCESSING` được commit trước khi gọi bank.
8. **Outbox → notification + mail_event** cùng transaction nghiệp vụ; consumer idempotent (`UNIQUE(event_id, recipient_id)`, `dedupe_key`). Email gắn cố định `template_version`, retry bounded + backoff + jitter → DLQ; SMTP timeout → `UNKNOWN`.
9. **Cache Redis chỉ để tăng tốc**, mọi lỗi Redis bị nuốt. Search cache key chứa version counter (tăng khi đổi catalog/giá); analytics key chứa `scopeHash` (owner/quyền) nên không lộ chéo tenant.
10. **Cấu hình động** (`config_*`): cache bộ nhớ làm mới mỗi 15s → các instance có thể lệch tối đa ~15s; có version + change log + audit; `minLength ≤ maxLength`, sàn an toàn do `min_value` trong định nghĩa.
11. **Khác app đặt vé có chủ ý:** service là class cụ thể (không `interface + Impl`, theo yêu cầu "không tạo abstraction khi chưa có giá trị"); ID dùng `Long`; thời gian lưu `Instant` (UTC) + `LocalDate` theo múi giờ property; schema do Flyway quản lý thay vì `ddl-auto: update`.

## Ma trận use case

✅ đã làm · 🟡 làm một phần / đơn giản hóa · ❌ chưa làm

| UC | Nội dung | | Ghi chú |
|---|---|---|---|
| 01–03 | Đăng ký/xác minh, JWT + refresh rotation + reuse detection, hồ sơ | ✅ | `token_version` thu hồi access token tức thì; mã xác minh băm HMAC, giới hạn số lần |
| 04–05 | Property, publish (kiểm tra xuyên bảng), loại phòng, phòng, giá `[from,to)`, đóng bán | ✅ 🟡 | "Lịch đóng bán" chỉ theo `status` phòng, chưa có khóa theo từng ngày |
| 06 | Ảnh MinIO: presigned PUT, finalize (HEAD + magic bytes), dọn mồ côi | ✅ | Endpoint nằm dưới `/host/media/*` (đặc tả: `/media/*`) |
| 07–08 | Tìm kiếm (SQL động, bind param, sort whitelist, cache), chi tiết, báo giá | ✅ | |
| 09 | Booking + hold: lock + exclusion + idempotency + retry lock, snapshot giá/chính sách | ✅ | |
| 10, 35, 36 | Thanh toán Fake Bank, webhook HMAC, UNKNOWN, đối soát, bù trừ | ✅ | `payment_intents` gộp vào `payments` (`intent_code`); SUPPORT: `POST /support/payments/{id}/reconcile`, `POST /support/reconciliation-cases/{id}/compensate` |
| 11 | Hết hạn giữ chỗ (SKIP LOCKED, recheck, giải phóng một lần) | ✅ | |
| 12–13 | Hủy + phí, refund saga, đối soát, ca `NEEDS_ATTENTION` | ✅ | Hủy khi `CHECKED_IN` chỉ SUPPORT |
| 14–15, 29 | Host xử lý booking, check-in/out, no-show, đề nghị giờ check-in | ✅ | Check-out ngoại lệ (chưa check-in) bởi SUPPORT: `POST /support/bookings/{id}/force-checkout` (quyền `SUPPORT_STAY_EXCEPTION`, lý do + audit); tra cứu lịch hẹn `GET /bookings/{id}/checkin-requests` |
| 16 | Review, phản hồi host, kiểm duyệt có audit | ✅ | |
| 17 | Outbox + notification | ✅ | |
| 18, 21–25 | Dashboard, đa chiều, doanh thu/dòng tiền (aggregate **riêng** từng grain), công suất (date spine), funnel | ✅ 🟡 | Kỳ báo cáo theo múi giờ cơ sở; HOST hỏi property ngoài phạm vi -> `FORBIDDEN_SCOPE`; `GET /analytics/cashflow` (UC-23); công suất có hold còn hạn + ngày thiếu giá; funnel có tỷ lệ hủy + payment attempt. **Chưa có bảng fact theo ngày** |
| 26 | Truy vấn động whitelist + cache + chống stampede | 🟡 | **Export bất đồng bộ (`report_job`) ❌** |
| 19–20 | Audit append-only (+ `attempt` REQUIRES_NEW), quản trị user/role/amenity | ✅ | Audit gọi tường minh, **không dùng AOP `@Audited`** |
| 27–28 | Hội thoại (khách hoặc HOST của booking mở), tin nhắn idempotent theo `clientMessageId`, cursor, unread, phản ánh/ticket, SUPPORT nhận khi quá hạn | ✅ 🟡 | Realtime SSE/WebSocket ❌ (client poll theo `afterSeq`); đính kèm ảnh ❌; `read_receipts` gộp vào participants |
| 30–31 | Cấu hình động; mẫu email có version bất biến, whitelist biến, escape HTML | ✅ 🟡 | "Gửi test" template ❌ |
| 32–34 | Email giao dịch, nhắc check-in (DST-safe, EFFECTIVE_FOR_UNSENT), theo dõi/retry an toàn | ✅ | |

**Chưa làm thêm:** frontend (app cũ có React/Vite – chưa dựng bản đặt phòng), dashboard Grafana provisioning, `daily_*_fact`, ADR/ERD dạng sơ đồ.

## Map API chính (`/api/v1`)

`/auth/{register,verify,resend,login,refresh,logout}` · `/users/me` · `/properties` `/rooms/search` `/properties/{id}` `/rooms/{id}/quote` ·
`/host/{properties,room-types,rooms,rates,media/*,bookings}` · `/bookings` (+`/me`, `/{id}`, `/{id}/cancel[-preview]`, `/{id}/reviews`, `/{id}/checkin-requests`) ·
`/payments` `/payments/fakebank/webhook` · `/conversations…` `/complaints/{id}/actions` `/checkin-requests/{id}/respond` · `/notifications` ·
`/analytics/{overview,query,occupancy,funnel,reports}` · `/admin/{users,settings,audit,email-templates,mail-events,amenities}` ·
`/support/{reconciliation-cases,reviews,bookings,complaints}` `/refunds/{id}/reconcile`.

Lỗi JSON thống nhất `{timestamp, traceId, code, message, details}`; 400/401/403/404/409/422/429/503 theo §7/§11.1.

## Kiểm thử đề xuất

```bash
mvn -Dtest=BookingConcurrencyIT test      # cần Docker
```
Bao phủ: 50 request cùng phòng → đúng 1 thắng; cùng idempotencyKey song song → 1 booking; cùng key khác payload → `IDEMPOTENCY_CONFLICT`; giá từng đêm snapshot + `PRICE_CHANGED`; back-to-back không xung đột; callback muộn sau `EXPIRED` không bao giờ `CONFIRMED`; webhook lặp 5 lần không đổi hiệu ứng.
Chưa có test tự động cho: timeout sau debit, refund timeout, hai scheduler song song, DST reminder – nên thử bằng các tài khoản `TEST-*`.
