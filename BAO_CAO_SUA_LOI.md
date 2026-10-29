# Báo cáo rà soát & sửa lỗi theo "Đặc tả luồng use case chuyên sâu"

Phạm vi: đối chiếu mã nguồn `app-dat-phong` + `fake-bank` với UC-01 → UC-36 và checklist nghiệm thu (mục 6, 8, 9 của tài liệu).
Toàn bộ thay đổi chưa commit — xem chi tiết bằng `git diff` (hoặc file `fixes.patch` đi kèm).

## Cách kiểm chứng đã làm

| Hạng mục | Kết quả |
|---|---|
| 22 unit test domain (StayPeriod, PricingCalculator, CancellationCalculator, Transitions, HmacSigner) | **Pass** (chạy lại sau khi sửa) |
| Parse toàn bộ mã nguồn bằng `javac` (app + fake-bank) | Không có lỗi cú pháp |
| Đối chiếu entity JPA ↔ `V1__init_schema.sql` (tên cột/kiểu) bằng script | Khớp, không cột thiếu |
| Symbol do mã mới gọi tới (repository/entity/service trong dự án) | Đều tồn tại |
| `mvn verify`, Flyway trên PostgreSQL, `BookingConcurrencyIT`, `docker compose up` | **Chưa chạy được**: môi trường rà soát bị chặn Maven Central/Docker. Cần chạy lại trên máy có mạng |

## Lỗi chặn build / chạy

| # | Vị trí | Lỗi | Sửa |
|---|---|---|---|
| 1 | `config/DemoDataSeeder.java` | File có BOM UTF-8 ở đầu → `javac` báo `illegal character '﻿'`, **cả project không biên dịch được** | Bỏ BOM |
| 2 | `ops/prometheus.yml` | Header `X-Metrics-Token: ["${METRICS_TOKEN}"]` — Prometheus không thay biến môi trường → scrape luôn 403 | Đọc token từ file `/tmp/metrics_token` (đúng như entrypoint trong compose đã ghi) |
| 3 | `Dockerfile` (app, fake-bank) | Healthcheck dùng `curl` nhưng image JRE không bảo đảm có `curl` → container có thể mãi `unhealthy`, `app` không khởi động | Cài `curl` tường minh |

## Lỗi nghiệp vụ theo use case

| UC | Vị trí | Lỗi | Sửa |
|---|---|---|---|
| UC-09 | `BookingCreator` | Idempotency được kiểm tra **sau** khi validate ngày/phòng → gửi lại cùng key + cùng payload sau khi phòng đổi trạng thái/qua ngày nhận phòng thì bị lỗi thay vì trả kết quả cũ | Tra idempotency trước mọi validate |
| UC-08/09 | `BookingCreator` | `priceVersion` của báo giá được nhận nhưng **không bao giờ so sánh** → không phát hiện `PRICE_CHANGED` khi chỉ gửi priceVersion | So `priceVersion` và `expectedTotal`, trả `PRICE_CHANGED` kèm giá mới |
| UC-10 | `PaymentService` | Gọi Fake Bank khi payment còn `INITIATED` (đặc tả: commit `PROCESSING` trước khi gọi bank); booking tổng 0đ làm vi phạm `CHECK amount > 0` → 500 | Commit `PROCESSING` (+attempts) trước khi gọi bank; chặn booking 0đ bằng lỗi nghiệp vụ |
| UC-10/25 | `BusinessEventService` | `REQUIRES_NEW` + JPA save: trùng `event_id` khi 2 luồng ghi đồng thời nổ lúc **commit** (ngoài try/catch) → làm hỏng luôn giao dịch thanh toán/booking đang gọi | Ghi best-effort bằng `INSERT … ON CONFLICT DO NOTHING`, `NOT_SUPPORTED` (autocommit), không bao giờ ném lỗi ngược |
| UC-12 | `CancellationService.preview` | Preview tính tiền đã thu = tổng mọi payment, còn khi hủy thật lại dùng payment gần nhất → số "dự kiến hoàn" lệch với số hoàn thật | Dùng cùng công thức với `cancel()` |
| UC-13/36 | `RefundRepository.committedForPayment` | Không tính refund `FAILED` đang chờ retry → có thể tạo refund mới, rồi refund cũ retry thành công ⇒ **tổng hoàn vượt số đã thu** | Tính mọi refund của payment (FAILED vẫn có thể được retry/đối soát cùng key) |
| UC-13/34 | `RefundService`, `ReconciliationService` | eventId kết quả đồng bộ/đối soát là `sync-refund:{key}:{status}` — lần retry thứ 2 trả `FAILED` bị coi là **bản lặp** → refund kẹt `PROCESSING` mãi, không bao giờ tới `REFUND_STUCK` | Gắn số lần thử vào eventId |
| UC-13 | `RefundService.applyResult` | Bank xác nhận hoàn thành công cho lần thử đã bị đánh `FAILED` (timeout/đối soát sớm) → bị bỏ qua, nội bộ lệch với bank | Cho `FAILED → PROCESSING → SUCCEEDED` khi bank xác nhận |
| UC-14 | `BookingQueryService.hostList` | Lọc theo property không thuộc mình trả danh sách rỗng (đặc tả: `FORBIDDEN_SCOPE`) | Trả `FORBIDDEN_SCOPE` |
| UC-15 | `StayService`, `AdminController`, V3 | Check-out ngoại lệ (chưa check-in) bởi SUPPORT chưa có | `POST /support/bookings/{id}/force-checkout`, quyền mới `SUPPORT_STAY_EXCEPTION`, bắt buộc lý do, đi qua state machine, audit |
| UC-15 | `HostBookingController` | Đường dẫn không nhất quán `/checkin` vs `/check-out` | Nhận cả `/check-in`, `/checkin`, `/check-out`, `/checkout` |
| UC-17 | `OutboxProcessor.markFailure` | Sau lỗi, "claim lại event đầu hàng đợi" để tăng retry → có thể phạt nhầm event khác (instance khác đã lấy event lỗi) | Đánh dấu lỗi theo đúng id event |
| UC-18/21–25 | `AnalyticsService` | (a) HOST truyền `propertyId` của người khác nhận số 0 thay vì `FORBIDDEN_SCOPE`; (b) kỳ báo cáo cắt theo ngày **UTC** thay vì múi giờ cơ sở | (a) kiểm tra owner → `FORBIDDEN_SCOPE`; (b) chuẩn hóa ranh giới ngày theo timezone property (hoặc mặc định Asia/Ho_Chi_Minh), trả `timezone` trong kết quả |
| UC-22 | `AnalyticsService.query` | `limit` âm làm SQL lỗi; `statuses` không validate | Validate `limit` 1..1000 và status |
| UC-23 | `AnalyticsController` | Thiếu báo cáo dòng tiền: giao dịch đang xử lý/thất bại, refund pending/failed, trạng thái đối soát | `GET /analytics/cashflow` |
| UC-24 | `AnalyticsService.occupancy` | Không có hold còn hạn và ngày thiếu giá (đặc tả yêu cầu) | Thêm `heldRoomNights`, `roomTypesMissingRate`, `daysMissingRate` |
| UC-25 | `AnalyticsService.funnel` | Thiếu tỷ lệ hủy và số payment attempt tách riêng khỏi số booking | Thêm `cancellation` và `paymentAttemptsByStatus` |
| UC-05 | `CatalogService` | Tạo phòng/đặt giá cho cơ sở `ARCHIVED`; kiểm tra giảm sức chứa dùng ngày UTC và chặn mọi booking tương lai (kể cả đủ sức chứa); kiểm tra version trước ownership (lộ tồn tại) | Chặn ARCHIVED; chỉ chặn khi có booking/hold tương lai vượt sức chứa mới, theo timezone cơ sở; kiểm tra quyền trước |
| UC-02 | `AuthService.login` | Hash giả để "làm đều thời gian" dài 59 ký tự (không hợp lệ BCrypt) → so khớp trả về ngay ⇒ lộ email tồn tại qua thời gian phản hồi; không ghi nhận kết quả đăng nhập/đăng xuất | Dùng hash BCrypt hợp lệ cùng cost; audit `LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT` |
| UC-27 | `ConversationService`, `MessagingController` | HOST không mở được hội thoại (đặc tả: khách **hoặc HOST**) | HOST mở `BOOKING_SUPPORT` cho booking thuộc cơ sở mình; participant suy ra từ DB |
| UC-29 | `CheckinRequestService` | (a) `respond` đóng yêu cầu (`CLOSED`) rồi ném lỗi → bị rollback, yêu cầu vẫn treo `PENDING`; (b) khách `accept` không thông báo HOST; (c) không có API tra cứu lịch đến | (a) `noRollbackFor`; (b) tin nhắn + outbox `CHECKIN_REQUEST_ACCEPTED` + audit; (c) `GET /bookings/{id}/checkin-requests` (khách/HOST sở hữu) |
| UC-29 | `StayService.noShow` | Không thông báo khách khi bị ghi nhận vắng mặt | Outbox `BOOKING_NO_SHOW` → notification |
| UC-30 | `ConfigService`, V3 | Lên lịch giá trị cấu hình ở tương lai làm hệ thống **rơi về default** cho tới thời điểm hiệu lực (ghi đè bản ghi duy nhất); giá trị `null` gây 500 | Cột `previous_value`: dùng giá trị đang hiệu lực tới `effective_at`; validate null |
| UC-31 | `EmailTemplateService.publish` | Không render thử trước publish (đặc tả bước 3), cú pháp `{{` hở không bị phát hiện | Render thử với dữ liệu mẫu + kiểm tra cú pháp biến |
| UC-32 | `MailService` | Dedupe key gồm cả template version → publish mẫu mới làm cùng sự kiện tạo email thứ hai | Dedupe theo loại sự kiện + khóa nghiệp vụ + người nhận |
| UC-34 | `MailWorker` | `templateVersionId` null → `findById(null)` ném lỗi, mail kẹt `SENDING` | Kiểm tra null → `FAILED_VALIDATION` |
| UC-36 | `ReconciliationService`, `AdminController` | SUPPORT không có thao tác "retry/đối soát payment" và "tạo refund bù trừ" cho ca `NEEDS_ATTENTION` | `POST /support/payments/{id}/reconcile` (hỏi bank bằng idempotencyKey cũ), `POST /support/reconciliation-cases/{id}/compensate` (refund key ổn định `comp-case-{id}`, không vượt captured) — có lý do + audit |
| — | `SecurityConfig` | `JwtAuthenticationFilter` là `@Component` nên bị Spring Boot đăng ký thêm ở mức servlet | Tắt đăng ký servlet, chỉ chạy trong security chain |

## Fake Bank (UC-35)

| Lỗi | Sửa |
|---|---|
| `TEST-LOST`, `TEST-DELAY`, `TEST-DUP` trả `SUCCEEDED` đồng bộ → booking xác nhận ngay, **không bao giờ** đi qua nhánh mất/chậm/lặp callback như ma trận kịch bản | Trả `ACCEPTED` (kết quả cuối qua webhook/đối soát); tra cứu vẫn trả trạng thái thật |
| `TEST-DBFAIL` ném lỗi trước khi ghi gì → không thực sự kiểm chứng rollback | Ghi số dư/sổ cái/outbox rồi mới lỗi → rollback toàn bộ, trả 503 |
| Refund `FAILED` không thể thử lại cùng `refundKey` (trả mãi kết quả cũ) dù kịch bản đã đổi | Cho retry cùng key, eventId mới mỗi lần |
| Nhận mọi chuỗi `payerAccount` (đặc tả: tuyệt đối không dùng tài khoản/thẻ thật) | Chỉ nhận `TEST-*`/`MERCHANT-*`, chặn chuỗi giống số thẻ |
| Thiếu API tạo tài khoản giả lập; `ledger-check` chỉ so tổng | `POST /fakebank/api/v1/admin/accounts`; ledger-check kiểm tra từng giao dịch, số dư âm, giao dịch thiếu bút toán, hoàn vượt |
| Số tiền gửi dạng số thực JSON | Gửi chuỗi thập phân (không mất chính xác) |

## Migration mới

`V3__usecase_fixes.sql`: cột `config_values.previous_value`, quyền `SUPPORT_STAY_EXCEPTION` cho SUPPORT, index ca đối soát.

## Còn lại (backlog đúng như đặc tả ghi nhận, chưa làm)

Export analytics bất đồng bộ (`report_job`), realtime SSE/WebSocket, đính kèm ảnh trong chat, gửi thử email template, lịch đóng bán theo ngày, bảng `daily_*_fact`, frontend.

## Việc cần làm trên máy có mạng

```bash
cd app-dat-phong && mvn -q verify            # build + unit test
mvn -Dtest=BookingConcurrencyIT test         # cần Docker
docker compose up --build                    # thử các tài khoản TEST-* của Fake Bank
```

---

# Lần 2: đối chiếu lại toàn bộ UC-01 → UC-36 (nhánh `fix/dac-ta-use-case`)

Kiểm chứng: `mvn test` với JDK 21 **build thành công, 22/22 unit test pass**; fake-bank biên dịch OK.
Chưa chạy: Flyway trên PostgreSQL, `BookingConcurrencyIT`, `docker compose up` (Docker chưa bật trên máy rà soát).

## Lỗi chặn build (lần rà trước chỉ parse cú pháp nên bỏ sót)

| Vị trí | Lỗi | Sửa |
|---|---|---|
| `service/MediaService.java` | `import io.minio.http.Method` không tồn tại ở MinIO 9.0.2 | `import io.minio.Http.Method` |
| `service/BookingFacade.java` | multi-catch `CannotAcquireLockException \| PessimisticLockingFailureException` (lớp con/lớp cha) không biên dịch | chỉ bắt `PessimisticLockingFailureException` |

## Chỗ lệch đặc tả đã sửa

| UC | Vị trí | Lệch | Sửa |
|---|---|---|---|
| UC-04 | `CatalogService.publish` | Không kiểm tra "chính sách" khi xuất bản | Thêm điều kiện giờ nhận/trả, chính sách hủy (0–100%), timezone hợp lệ vào `PROPERTY_NOT_READY` |
| UC-07 | `SearchService` | `sort` lạ bị âm thầm đổi về mặc định | Trả `VALIDATION_ERROR` nếu ngoài whitelist |
| UC-09 | `BookingFacade` | `requestHash` bỏ qua `specialNote` → cùng key khác payload không bị từ chối | Hash phủ cả ghi chú |
| UC-11 | `BookingExpiryService` | Không "kiểm tra payment mới nhất" trước khi EXPIRED | Booking đã có payment `SUCCEEDED` thì không tự hết hạn (chờ đối soát), có metric |
| UC-12 | `BookingController` | `cancel-preview`/`cancel` không có kiểm tra RBAC | `@PreAuthorize` `BOOKING_CREATE` hoặc `SUPPORT_BOOKING_CANCEL` |
| UC-14 | `StayService`, `Booking`, V4 | HOST không cập nhật được giờ đến; ghi chú HOST không được lưu | Cột `expected_arrival_time`, `host_note`; trả trong chi tiết HOST/SUPPORT; chỉ thông báo khách khi đổi trường khách thấy |
| UC-17 | `OutboxProcessor` | `BOOKING_CREATED`, `REFUND_REQUESTED` không tạo thông báo | Thông báo cho khách (và HOST với booking mới) |
| UC-19 | `AdminController`, `BookingQueryService` | Chưa truy vết được booking ↔ payment ↔ refund ↔ job | `GET /support/bookings/{id}/trace` (lịch sử, payment, sự kiện bank, refund, ca đối soát, audit); lượt xem có audit |
| UC-20 | `AdminController` | Không có API xem role/permission/danh mục; sửa tiện ích không làm mới cache | `GET /admin/roles`, `GET /admin/amenities`; `searchVersion.bump()` khi upsert |
| UC-22 | `AnalyticsService.query` | Thiếu lọc roomType/số khách/số đêm tối đa/nguồn đặt và so sánh kỳ | Thêm bộ lọc + `comparePrevious` (kỳ liền trước cùng độ dài), trả `semantics` công thức metric |
| UC-26 | `AnalyticsService`, `AnalyticsController`, V4 | Không có quản trị/invalidation cache | Khóa cache có version; `POST /analytics/cache/invalidate?reason=` cần quyền mới `ANALYTICS_CACHE_MANAGE` (ADMIN), có audit |

Migration mới: `V4__usecase_alignment.sql`.

## Vẫn là backlog (đặc tả mục 8 ghi nhận chưa làm)

Export analytics bất đồng bộ (`report_job`), realtime SSE/WebSocket, đính kèm ảnh trong chat, gửi thử email template, lịch đóng bán theo ngày, bảng `daily_*_fact`, frontend.

Package entity vẫn để ở `datphong.vn.exception.entity` đúng như đặc tả mục 5.2 khuyến nghị (không tự đổi package khi chưa có integration test).
