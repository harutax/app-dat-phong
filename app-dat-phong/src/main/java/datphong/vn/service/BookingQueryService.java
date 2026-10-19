package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.dto.response.BookingResponses.*;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Đọc booking theo đúng phạm vi: CUSTOMER chỉ của mình; HOST chỉ property có owner_id hợp lệ (ép trong truy vấn). */
@Service
@RequiredArgsConstructor
public class BookingQueryService {

    private final BookingRepository bookings;
    private final BookingNightRepository nights;
    private final BookingStatusHistoryRepository history;
    private final PropertyRepository properties;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public BookingDto customerView(Long customerId, Long bookingId) {
        Booking b = bookings.findByIdAndCustomerId(bookingId, customerId).orElseThrow(() -> BusinessException.notFound("Booking"));
        return toDto(b, true);
    }

    @Transactional(readOnly = true)
    public Page<BookingDto> myBookings(Long customerId, int page, int size) {
        Page<Booking> p = bookings.findByCustomerId(customerId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50), Sort.by("id").descending()));
        Map<Long, Property> props = properties.findAllById(p.stream().map(Booking::getPropertyId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Property::getId, Function.identity()));
        Map<Long, Room> rm = rooms.findAllById(p.stream().map(Booking::getRoomId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<Long, RoomType> rts = roomTypes.findAllById(p.stream().map(Booking::getRoomTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(RoomType::getId, Function.identity()));
        return p.map(b -> build(b, props.get(b.getPropertyId()), rm.get(b.getRoomId()), rts.get(b.getRoomTypeId()), List.of(), null, null, null));
    }

    /** HOST: owner scope được ÉP trong SQL (JOIN properties ... owner_id), không lọc sau ở tầng Java. */
    @Transactional(readOnly = true)
    public Page<BookingDto> hostList(Long ownerId, Long propertyId, BookingStatus status, LocalDate from, LocalDate to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE p.owner_id = :owner");
        MapSqlParameterSource ps = new MapSqlParameterSource("owner", ownerId);
        if (propertyId != null) { where.append(" AND b.property_id = :pid"); ps.addValue("pid", propertyId); }
        if (status != null) { where.append(" AND b.status = :st"); ps.addValue("st", status.name()); }
        if (from != null) { where.append(" AND b.check_in >= :from"); ps.addValue("from", from); }
        if (to != null) { where.append(" AND b.check_in < :to"); ps.addValue("to", to); }
        int sz = Math.min(Math.max(1, size), 100);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM bookings b JOIN properties p ON p.id = b.property_id" + where, ps, Long.class);
        ps.addValue("lim", sz).addValue("off", (long) Math.max(0, page) * sz);
        List<Long> ids = jdbc.queryForList("SELECT b.id FROM bookings b JOIN properties p ON p.id = b.property_id" + where
                + " ORDER BY b.id DESC LIMIT :lim OFFSET :off", ps, Long.class);
        Map<Long, Booking> byId = bookings.findAllById(ids).stream().collect(Collectors.toMap(Booking::getId, Function.identity()));
        Map<Long, Property> props = properties.findAllById(byId.values().stream().map(Booking::getPropertyId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Property::getId, Function.identity()));
        Map<Long, Room> rm = rooms.findAllById(byId.values().stream().map(Booking::getRoomId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<Long, RoomType> rts = roomTypes.findAllById(byId.values().stream().map(Booking::getRoomTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(RoomType::getId, Function.identity()));
        List<BookingDto> items = ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(b -> build(b, props.get(b.getPropertyId()), rm.get(b.getRoomId()), rts.get(b.getRoomTypeId()), List.of(), null, null, null)).toList();
        return new org.springframework.data.domain.PageImpl<>(items, PageRequest.of(Math.max(0, page), sz), total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public HostBookingDto hostDetail(Long ownerId, Long bookingId) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> BusinessException.notFound("Booking"));
        // 404 (không phải 403) để không lộ sự tồn tại của booking cơ sở khác
        properties.findByIdAndOwnerId(b.getPropertyId(), ownerId).orElseThrow(() -> BusinessException.notFound("Booking"));
        User c = users.findById(b.getCustomerId()).orElseThrow();
        boolean active = b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.CHECKED_IN;
        List<StatusEntry> hist = history.findByBookingIdOrderByAtAscIdAsc(bookingId).stream()
                .map(h -> new StatusEntry(h.getFromStatus() == null ? null : h.getFromStatus().name(), h.getToStatus().name(), h.getActorType(), h.getReason(), h.getAt())).toList();
        return new HostBookingDto(toDto(b, false), c.getFullName(), Texts.maskEmail(c.getEmail()), active ? c.getPhone() : Texts.maskPhone(c.getPhone()), hist);
    }

    /** SUPPORT/ADMIN (có BOOKING_VIEW): xem theo quyền được cấp, dữ liệu liên hệ che. */
    @Transactional(readOnly = true)
    public HostBookingDto supportDetail(Long bookingId) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> BusinessException.notFound("Booking"));
        User c = users.findById(b.getCustomerId()).orElseThrow();
        List<StatusEntry> hist = history.findByBookingIdOrderByAtAscIdAsc(bookingId).stream()
                .map(h -> new StatusEntry(h.getFromStatus() == null ? null : h.getFromStatus().name(), h.getToStatus().name(), h.getActorType(), h.getReason(), h.getAt())).toList();
        return new HostBookingDto(toDto(b, false), c.getFullName(), Texts.maskEmail(c.getEmail()), Texts.maskPhone(c.getPhone()), hist);
    }

    private BookingDto toDto(Booking b, boolean customerView) {
        Property p = properties.findById(b.getPropertyId()).orElseThrow();
        Room r = rooms.findById(b.getRoomId()).orElseThrow();
        RoomType t = roomTypes.findById(b.getRoomTypeId()).orElseThrow();
        List<Night> ns = nights.findByBookingIdOrderByStayDate(b.getId()).stream().map(n -> new Night(n.getStayDate(), n.getNightlyRate())).toList();
        Payment pay = payments.findFirstByBookingIdOrderByIdDesc(b.getId()).orElse(null);
        BigDecimal captured = payments.capturedForBooking(b.getId());
        BigDecimal refunded = refunds.succeededForBooking(b.getId());
        return build(b, p, r, t, ns, pay, captured, refunded);
    }

    private BookingDto build(Booking b, Property p, Room r, RoomType t, List<Night> ns, Payment pay, BigDecimal captured, BigDecimal refunded) {
        PaymentView pv = pay == null ? null : new PaymentView(pay.getId(), pay.getStatus().name(), pay.getAmount(), pay.getFailureCode(), pay.getCapturedAt());
        String instr = b.getStatus() == BookingStatus.PENDING_PAYMENT ? "Thanh toán trước hạn giữ chỗ qua POST /payments" : null;
        return new BookingDto(b.getId(), b.getBookingCode(), b.getStatus().name(), b.getPropertyId(), p == null ? null : p.getName(),
                b.getRoomId(), r == null ? null : r.getRoomCode(), b.getRoomTypeId(), t == null ? null : t.getName(), b.getCheckIn(),
                b.getCheckOut(), b.getGuestCount(), b.getSubtotal(), b.getTaxAmount(), b.getTotalAmount(), b.getCurrency(), b.getExpiresAt(),
                b.getFreeCancelDeadline(), b.getPenaltyPercent(), b.getSpecialNote(), b.getCancelledAt(), b.getCancelReason(),
                b.getCancellationFee(), b.getRefundableAmount(), b.getActualCheckIn(), b.getActualCheckOut(), ns, pv, captured, refunded,
                instr, b.getVersion(), b.getCreatedAt());
    }
}
