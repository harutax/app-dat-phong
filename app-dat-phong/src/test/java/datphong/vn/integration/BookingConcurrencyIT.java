package datphong.vn.integration;

import datphong.vn.constant.*;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import datphong.vn.service.BookingExpiryService;
import datphong.vn.service.BookingFacade;
import datphong.vn.service.PaymentCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm chứng bất biến thật trên PostgreSQL thật (lock / exclusion constraint / unique) - KHÔNG mock DB.
 * Cần Docker. Chạy: mvn -Dtest=BookingConcurrencyIT test
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.scheduler.enabled=false",
        "app.seed.enabled=false",
        "jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "app.verification-secret=test-verification-secret-0123456789",
        "app.fake-bank.base-url=http://localhost:1",
        "app.fake-bank.service-secret=test-service-secret-0123456789",
        "app.fake-bank.webhook-secret=test-webhook-secret-0123456789",
        "app.mail.enabled=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1"          // Redis không sẵn: mọi lỗi cache bị nuốt, DB vẫn đúng
})
class BookingConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired BookingFacade facade;
    @Autowired BookingRepository bookings;
    @Autowired RoomAllocationRepository allocations;
    @Autowired PaymentCompletionService completion;
    @Autowired BookingExpiryService expiry;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PropertyRepository properties;
    @Autowired RoomTypeRepository roomTypes;
    @Autowired RoomRepository rooms;
    @Autowired RoomRateRepository rates;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    Long roomId;
    LocalDate in = LocalDate.now().plusDays(10);
    LocalDate out = in.plusDays(3);

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE bookings, booking_nights, room_allocations, payments, refunds, outbox_events, idempotency_records, "
                + "booking_status_history, reconciliation_cases, payment_events, business_events, rooms, room_rates, room_types, properties, user_roles, users RESTART IDENTITY CASCADE");
        User host = user("host@t.local", RoleName.HOST);
        Property p = new Property();
        p.setOwnerId(host.getId());
        p.setName("T");
        p.setStatus(PropertyStatus.PUBLISHED);
        properties.save(p);
        RoomType t = new RoomType();
        t.setPropertyId(p.getId());
        t.setName("Std");
        t.setCapacity(2);
        roomTypes.save(t);
        Room r = new Room();
        r.setPropertyId(p.getId());
        r.setRoomTypeId(t.getId());
        r.setRoomCode("101");
        r.setStatus(RoomStatus.ACTIVE);
        rooms.save(r);
        roomId = r.getId();
        for (int i = 0; i < 5; i++) {
            RoomRate rr = new RoomRate();
            rr.setRoomTypeId(t.getId());
            rr.setStayDate(in.plusDays(i));
            rr.setAmount(new BigDecimal(500_000 + i * 100_000));   // mỗi đêm một giá
            rates.save(rr);
        }
    }

    private User user(String email, RoleName role) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(email);
        u.setPasswordHash("x");
        u.setStatus(UserStatus.ACTIVE);
        u.getRoles().add(roles.findByCode(role.name()).orElseThrow());
        return users.save(u);
    }

    private BookingRequests.Create req(String key) {
        return new BookingRequests.Create(roomId, in, out, 2, key, null, null, null);
    }

    /** 50 khách cùng đặt một phòng/đêm: đúng 1 thành công, 49 ROOM_UNAVAILABLE, chỉ 1 allocation active. */
    @Test
    void fiftyConcurrentRequestsOnlyOneWins() throws Exception {
        int n = 50;
        List<Long> customers = new ArrayList<>();
        for (int i = 0; i < n; i++) customers.add(user("c" + i + "@t.local", RoleName.CUSTOMER).getId());
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), unavailable = new AtomicInteger(), other = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    facade.create(customers.get(idx), req("key-" + idx + "-aaaaaaaa"));
                    ok.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getCode() == ErrorCode.ROOM_UNAVAILABLE) unavailable.incrementAndGet(); else other.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, ok.get(), "đúng một request được giữ chỗ");
        assertEquals(n - 1, unavailable.get() + other.get());
        assertEquals(0, other.get(), "không có lỗi khác ROOM_UNAVAILABLE (không 500/deadlock mơ hồ)");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM room_allocations WHERE active", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bookings", Long.class));
    }

    /** Cùng idempotencyKey + cùng payload chạy song song: một booking duy nhất, mọi bên nhận cùng kết quả. */
    @Test
    void sameIdempotencyKeyReturnsSameBooking() throws Exception {
        Long c = user("c@t.local", RoleName.CUSTOMER).getId();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) fs.add(pool.submit(() -> facade.create(c, req("same-key-12345")).booking().id()));
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Future<Long> f : fs) ids.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, ids.size());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bookings", Long.class));
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        Long c = user("c@t.local", RoleName.CUSTOMER).getId();
        facade.create(c, req("conflict-key-1"));
        var other = new BookingRequests.Create(roomId, in, out.plusDays(1), 2, "conflict-key-1", null, null, null);
        BusinessException e = assertThrows(BusinessException.class, () -> facade.create(c, other));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, e.getCode());
    }

    @Test
    void priceIsSnapshottedPerNightAndServerComputed() {
        Long c = user("c@t.local", RoleName.CUSTOMER).getId();
        var b = facade.create(c, req("price-key-12345")).booking();
        assertEquals(0, new BigDecimal("1800000").compareTo(b.totalAmount()), "500k+600k+700k, đêm trả phòng không tính");
        assertEquals(3, b.nights().size());
        // client gửi sai tổng tiền -> PRICE_CHANGED. Dùng khoảng KHÔNG giao booking trên: kiểm tra chồng lấn chạy trước
        // kiểm tra giá (UC-09), nên khoảng giao nhau sẽ ra ROOM_UNAVAILABLE thay vì PRICE_CHANGED.
        var wrong = new BookingRequests.Create(roomId, out, out.plusDays(1), 2, "price-key-99999", new BigDecimal("1"), null, null);
        assertEquals(ErrorCode.PRICE_CHANGED, assertThrows(BusinessException.class, () -> facade.create(c, wrong)).getCode());
    }

    /** Back-to-back: A trả phòng đúng ngày B nhận phòng -> không xung đột ([checkIn, checkOut)). */
    @Test
    void backToBackStaysDoNotConflict() {
        Long c1 = user("c1@t.local", RoleName.CUSTOMER).getId();
        Long c2 = user("c2@t.local", RoleName.CUSTOMER).getId();
        facade.create(c1, new BookingRequests.Create(roomId, in, in.plusDays(2), 2, "back-key-1111", null, null, null));
        assertDoesNotThrow(() -> facade.create(c2, new BookingRequests.Create(roomId, in.plusDays(2), in.plusDays(4), 2, "back-key-2222", null, null, null)));
    }

    /** Callback thanh toán SUCCESS đến SAU khi booking hết hạn: không bao giờ CONFIRMED; tạo refund + ca đối soát. */
    @Test
    void lateSuccessCallbackNeverConfirmsExpiredBooking() {
        Long c = user("c@t.local", RoleName.CUSTOMER).getId();
        Long bookingId = facade.create(c, req("late-key-12345")).booking().id();
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)), bookingId);
        assertEquals(1, expiry.runOnce());
        assertEquals(BookingStatus.EXPIRED, bookings.findById(bookingId).orElseThrow().getStatus());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM room_allocations WHERE active", Long.class), "phòng đã được giải phóng");

        Payment p = new Payment();
        p.setIntentCode("PI-TEST-0001");
        p.setBookingId(bookingId);
        p.setCustomerId(c);
        p.setIdempotencyKey("pay-test-key");
        p.setAmount(new BigDecimal("1800000"));
        p.setStatus(PaymentStatus.PROCESSING);
        payments.save(p);

        var res = new PaymentCompletionService.BankResult("pay-test-key", "FBTXN1", "SUCCEEDED", null, new BigDecimal("1800000"), "VND", "evt-1");
        assertEquals(PaymentCompletionService.Outcome.LATE_REFUND_REQUESTED, completion.apply(res));
        assertNotEquals(BookingStatus.CONFIRMED, bookings.findById(bookingId).orElseThrow().getStatus());
        assertEquals(BookingStatus.NEEDS_ATTENTION, bookings.findById(bookingId).orElseThrow().getStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE status = 'REQUESTED'", Long.class));

        // webhook lặp 5 lần: không thêm refund, không đổi trạng thái
        for (int i = 0; i < 5; i++) assertEquals(PaymentCompletionService.Outcome.DUPLICATE, completion.apply(res));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Long.class));
    }

    /** Callback thành công khi hold còn hạn: CONFIRMED đúng một lần dù webhook lặp. */
    @Test
    void successCallbackConfirmsOnceEvenIfDuplicated() {
        Long c = user("c@t.local", RoleName.CUSTOMER).getId();
        Long bookingId = facade.create(c, req("ok-key-123456")).booking().id();
        Payment p = new Payment();
        p.setIntentCode("PI-TEST-0002");
        p.setBookingId(bookingId);
        p.setCustomerId(c);
        p.setIdempotencyKey("pay-test-key-2");
        p.setAmount(new BigDecimal("1800000"));
        p.setStatus(PaymentStatus.PROCESSING);
        payments.save(p);
        var res = new PaymentCompletionService.BankResult("pay-test-key-2", "FBTXN2", "SUCCEEDED", null, new BigDecimal("1800000"), "VND", "evt-ok");
        assertEquals(PaymentCompletionService.Outcome.CONFIRMED, completion.apply(res));
        for (int i = 0; i < 5; i++) completion.apply(res);
        assertEquals(BookingStatus.CONFIRMED, bookings.findById(bookingId).orElseThrow().getStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE booking_id = ? AND to_status = 'CONFIRMED'", Long.class, bookingId));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'BOOKING_CONFIRMED'", Long.class));
    }
}
