package datphong.vn.service;

import datphong.vn.constant.MediaStatus;
import datphong.vn.constant.PropertyStatus;
import datphong.vn.constant.RoomStatus;
import datphong.vn.dto.request.CatalogRequests;
import datphong.vn.dto.response.CatalogResponses.*;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UC-04 (property + publish) và UC-05 (loại phòng, phòng vật lý, giá, đóng bán).
 * ownerId LUÔN lấy từ principal; mọi truy cập property đều qua findByIdAndOwnerId (chống IDOR).
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final PropertyRepository properties;
    private final RoomTypeRepository roomTypes;
    private final RoomRepository rooms;
    private final RoomRateRepository rates;
    private final RoomAllocationRepository allocations;
    private final AmenityRepository amenities;
    private final MediaAssetRepository media;
    private final AuditService audit;
    private final SearchVersionService searchVersion;
    private final Clock clock;

    // ------------------------------------------------------------ scope
    public Property owned(Long ownerId, Long propertyId) {
        return properties.findByIdAndOwnerId(propertyId, ownerId)
                .orElseThrow(() -> BusinessException.forbidden("Cơ sở không thuộc quyền quản lý của bạn"));
    }

    private LocalDate today(Property p) {
        return LocalDate.now(clock.withZone(ZoneId.of(p.getTimezone())));
    }

    // ------------------------------------------------------------ property
    @Transactional
    public PropertyDto createProperty(Long ownerId, CatalogRequests.PropertyUpsert r) {
        Property p = new Property();
        p.setOwnerId(ownerId);
        p.setStatus(PropertyStatus.DRAFT);
        apply(p, r);
        properties.save(p);
        audit.record("PROPERTY_CREATE", "PROPERTY", p.getId(), null, PropertyStatus.DRAFT.name(), null, null);
        return dto(p);
    }

    @Transactional
    public PropertyDto updateProperty(Long ownerId, Long id, CatalogRequests.PropertyUpsert r) {
        Property p = owned(ownerId, id);
        if (p.getStatus() == PropertyStatus.ARCHIVED) throw BusinessException.conflict("Cơ sở đã lưu trữ");
        if (r.version() != null && r.version() != p.getVersion()) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Dữ liệu đã thay đổi, hãy tải lại");
        apply(p, r);
        properties.saveAndFlush(p);
        audit.record("PROPERTY_UPDATE", "PROPERTY", id, null, null, null, null);
        searchVersion.bump();
        return dto(p);
    }

    private void apply(Property p, CatalogRequests.PropertyUpsert r) {
        p.setName(r.name().trim());
        if (r.description() != null) p.setDescription(r.description());
        if (r.propertyType() != null) p.setPropertyType(r.propertyType());
        if (r.address() != null) p.setAddress(r.address());
        if (r.city() != null) p.setCity(r.city());
        if (r.latitude() != null) p.setLatitude(r.latitude());
        if (r.longitude() != null) p.setLongitude(r.longitude());
        if (r.timezone() != null) {
            try { ZoneId.of(r.timezone()); } catch (Exception e) { throw BusinessException.validation("Múi giờ không hợp lệ"); }
            p.setTimezone(r.timezone());
        }
        if (r.checkInTime() != null) p.setCheckInTime(r.checkInTime());
        if (r.checkOutTime() != null) p.setCheckOutTime(r.checkOutTime());
        if (r.freeCancelHours() != null) p.setFreeCancelHours(r.freeCancelHours());
        if (r.penaltyPercent() != null) p.setPenaltyPercent(r.penaltyPercent());
        if (r.contactPhone() != null) p.setContactPhone(r.contactPhone());
        if (r.contactEmail() != null) p.setContactEmail(r.contactEmail());
        if (r.amenityCodes() != null) p.setAmenities(resolveAmenities(r.amenityCodes()));
    }

    private Set<Amenity> resolveAmenities(Set<String> codes) {
        if (codes.isEmpty()) return new HashSet<>();
        List<Amenity> found = amenities.findByCodeIn(codes);
        if (found.size() != codes.size()) throw BusinessException.validation("Có mã tiện ích không tồn tại");
        return new HashSet<>(found);
    }

    @Transactional(readOnly = true)
    public List<PropertyDto> myProperties(Long ownerId) {
        return properties.findByOwnerIdOrderByIdDesc(ownerId).stream().map(p -> dto(p)).toList();
    }

    @Transactional(readOnly = true)
    public PropertyDto myProperty(Long ownerId, Long id) {
        return dto(owned(ownerId, id));
    }

    /**
     * Publish là use case kiểm tra nhất quán xuyên nhiều bảng, không đơn thuần đổi cờ.
     * Thiếu gì trả PROPERTY_NOT_READY kèm danh sách thiếu.
     */
    @Transactional
    public PropertyDto publish(Long ownerId, Long id) {
        Property p = owned(ownerId, id);
        if (p.getStatus() == PropertyStatus.PUBLISHED) return dto(p);
        if (p.getStatus() == PropertyStatus.ARCHIVED) throw BusinessException.conflict("Cơ sở đã lưu trữ");

        List<String> missing = new ArrayList<>();
        if (isBlank(p.getAddress()) || isBlank(p.getCity())) missing.add("address/city");
        if (p.getLatitude() == null || p.getLongitude() == null) missing.add("latitude/longitude");
        if (isBlank(p.getContactPhone()) && isBlank(p.getContactEmail())) missing.add("contact");
        // UC-04: chính sách nhận/trả phòng và hủy phải đầy đủ, hợp lệ trước khi mở bán (booking sẽ snapshot chính sách này)
        if (p.getCheckInTime() == null || p.getCheckOutTime() == null) missing.add("checkInOutTime");
        if (p.getFreeCancelHours() < 0 || p.getPenaltyPercent() < 0 || p.getPenaltyPercent() > 100) missing.add("cancellationPolicy");
        if (!validZone(p.getTimezone())) missing.add("timezone");
        List<RoomType> types = roomTypes.findByPropertyIdOrderById(id).stream().filter(t -> t.getStatus() == RoomStatus.ACTIVE).toList();
        if (types.isEmpty()) missing.add("roomType");
        if (rooms.countActive(id) == 0) missing.add("room");
        LocalDate today = today(p);
        for (RoomType t : types) {
            if (rates.countFrom(t.getId(), today) == 0) missing.add("rates:" + t.getName());
        }
        if (!media.existsByPropertyIdAndStatusAndCoverTrue(id, MediaStatus.READY)) missing.add("coverImage");
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_READY, "Cơ sở chưa đủ điều kiện xuất bản", missing);
        }
        PropertyStatus from = p.getStatus();
        p.setStatus(PropertyStatus.PUBLISHED);
        properties.saveAndFlush(p);
        audit.record("PROPERTY_PUBLISH", "PROPERTY", id, from.name(), "PUBLISHED", null, null);
        searchVersion.bump();
        return dto(p);
    }

    /** PAUSED chặn bán mới nhưng KHÔNG tự hủy booking cũ. */
    @Transactional
    public PropertyDto pause(Long ownerId, Long id) {
        Property p = owned(ownerId, id);
        if (p.getStatus() != PropertyStatus.PUBLISHED) throw BusinessException.conflict("Chỉ cơ sở đang PUBLISHED mới tạm dừng được");
        p.setStatus(PropertyStatus.PAUSED);
        properties.saveAndFlush(p);
        audit.record("PROPERTY_PAUSE", "PROPERTY", id, "PUBLISHED", "PAUSED", null, null);
        searchVersion.bump();
        return dto(p);
    }

    @Transactional
    public PropertyDto resume(Long ownerId, Long id) {
        Property p = owned(ownerId, id);
        if (p.getStatus() != PropertyStatus.PAUSED) throw BusinessException.conflict("Cơ sở không ở trạng thái PAUSED");
        p.setStatus(PropertyStatus.PUBLISHED);
        properties.saveAndFlush(p);
        audit.record("PROPERTY_RESUME", "PROPERTY", id, "PAUSED", "PUBLISHED", null, null);
        searchVersion.bump();
        return dto(p);
    }

    /** Lưu trữ = dừng bán vĩnh viễn, không xóa dữ liệu. Phải PAUSED trước và không còn phòng bị chiếm trong tương lai. */
    @Transactional
    public PropertyDto archive(Long ownerId, Long id) {
        Property p = owned(ownerId, id);
        if (p.getStatus() == PropertyStatus.PUBLISHED) throw BusinessException.conflict("Hãy tạm dừng bán (PAUSED) trước khi lưu trữ");
        LocalDate today = today(p);
        for (Room r : rooms.findByPropertyIdOrderByRoomCode(id)) {
            if (allocations.existsActiveFuture(r.getId(), today)) {
                throw new BusinessException(ErrorCode.ROOM_HAS_FUTURE_BOOKINGS, "Còn booking/giữ chỗ trong tương lai, hãy xử lý trước khi lưu trữ");
            }
        }
        String from = p.getStatus().name();
        p.setStatus(PropertyStatus.ARCHIVED);
        properties.saveAndFlush(p);
        audit.record("PROPERTY_ARCHIVE", "PROPERTY", id, from, "ARCHIVED", null, null);
        searchVersion.bump();
        return dto(p);
    }

    // ------------------------------------------------------------ room types
    @Transactional
    public RoomTypeDto createRoomType(Long ownerId, CatalogRequests.RoomTypeUpsert r) {
        Property p = owned(ownerId, r.propertyId());
        if (p.getStatus() == PropertyStatus.ARCHIVED) throw BusinessException.conflict("Cơ sở đã lưu trữ");
        RoomType t = new RoomType();
        t.setPropertyId(p.getId());
        applyType(t, r);
        roomTypes.save(t);
        audit.record("ROOMTYPE_CREATE", "ROOM_TYPE", t.getId(), null, null, null, null);
        searchVersion.bump();
        return dto(t);
    }

    @Transactional
    public RoomTypeDto updateRoomType(Long ownerId, Long typeId, CatalogRequests.RoomTypeUpsert r) {
        RoomType t = roomTypes.findById(typeId).orElseThrow(() -> BusinessException.forbidden("Loại phòng không thuộc quyền của bạn"));
        Property owner = owned(ownerId, t.getPropertyId());
        if (owner.getStatus() == PropertyStatus.ARCHIVED) throw BusinessException.conflict("Cơ sở đã lưu trữ");
        if (r.version() != null && r.version() != t.getVersion()) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Dữ liệu đã thay đổi, hãy tải lại");
        if (r.capacity() < t.getCapacity()) {
            // Giảm sức chứa: không được làm vi phạm booking/hold tương lai có số khách vượt sức chứa mới (ngày tính theo múi giờ cơ sở)
            if (bookingsOverCapacity(typeId, r.capacity(), today(owner))) {
                throw new BusinessException(ErrorCode.ROOM_HAS_FUTURE_BOOKINGS,
                        "Loại phòng còn booking/giữ chỗ tương lai có số khách vượt sức chứa mới, không thể giảm sức chứa");
            }
        }
        applyType(t, r);
        roomTypes.saveAndFlush(t);
        audit.record("ROOMTYPE_UPDATE", "ROOM_TYPE", typeId, null, null, null, null);
        searchVersion.bump();
        return dto(t);
    }

    private void applyType(RoomType t, CatalogRequests.RoomTypeUpsert r) {
        t.setName(r.name().trim());
        t.setDescription(r.description());
        t.setCapacity(r.capacity());
        t.setBedType(r.bedType());
        if (r.amenityCodes() != null) t.setAmenities(resolveAmenities(r.amenityCodes()));
    }

    @Transactional(readOnly = true)
    public List<RoomTypeDto> listRoomTypes(Long ownerId, Long propertyId) {
        owned(ownerId, propertyId);
        return roomTypes.findWithAmenities(propertyId).stream().map(this::dto).toList();
    }

    // ------------------------------------------------------------ rooms
    @Transactional
    public RoomDto createRoom(Long ownerId, CatalogRequests.RoomCreate r) {
        Property p = owned(ownerId, r.propertyId());
        if (p.getStatus() == PropertyStatus.ARCHIVED) throw BusinessException.conflict("Cơ sở đã lưu trữ");
        RoomType t = roomTypes.findByIdAndPropertyId(r.roomTypeId(), p.getId())
                .orElseThrow(() -> BusinessException.validation("Loại phòng không thuộc cơ sở"));
        if (rooms.existsByPropertyIdAndRoomCode(p.getId(), r.roomCode())) {
            throw new BusinessException(ErrorCode.ROOM_CODE_DUPLICATE, "Mã phòng đã tồn tại trong cơ sở");
        }
        Room room = new Room();
        room.setPropertyId(p.getId());
        room.setRoomTypeId(t.getId());
        room.setRoomCode(r.roomCode());
        room.setStatus(RoomStatus.ACTIVE);
        try {
            rooms.saveAndFlush(room);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ROOM_CODE_DUPLICATE, "Mã phòng đã tồn tại trong cơ sở");
        }
        audit.record("ROOM_CREATE", "ROOM", room.getId(), null, "ACTIVE", null, null);
        searchVersion.bump();
        return dto(room);
    }

    /**
     * Đổi trạng thái phòng. Khóa hàng phòng (cùng cơ chế với giữ chỗ) nên đóng bán và đặt phòng đồng thời được tuần tự hóa.
     * Đóng bán/bảo trì khi còn booking CONFIRMED/CHECKED_IN hoặc hold còn hiệu lực -> ROOM_HAS_FUTURE_BOOKINGS.
     */
    @Transactional
    public RoomDto setRoomStatus(Long ownerId, Long roomId, String statusRaw) {
        RoomStatus to;
        try { to = RoomStatus.valueOf(statusRaw); } catch (Exception e) { throw BusinessException.validation("Trạng thái phòng không hợp lệ"); }
        Room room = rooms.lockById(roomId).orElseThrow(() -> BusinessException.forbidden("Phòng không thuộc quyền của bạn"));
        Property p = owned(ownerId, room.getPropertyId());
        if (to != RoomStatus.ACTIVE && allocations.existsActiveFuture(roomId, today(p))) {
            throw new BusinessException(ErrorCode.ROOM_HAS_FUTURE_BOOKINGS, "Phòng còn booking/giữ chỗ trong tương lai");
        }
        String from = room.getStatus().name();
        room.setStatus(to);
        rooms.saveAndFlush(room);
        audit.record("ROOM_STATUS", "ROOM", roomId, from, to.name(), null, null);
        searchVersion.bump();
        return dto(room);
    }

    @Transactional(readOnly = true)
    public List<RoomDto> listRooms(Long ownerId, Long propertyId) {
        owned(ownerId, propertyId);
        return rooms.findByPropertyIdOrderByRoomCode(propertyId).stream().map(this::dto).toList();
    }

    // ------------------------------------------------------------ rates
    /** Đặt giá các đêm [from, to). Booking cũ giữ nguyên booking_nights snapshot. */
    @Transactional
    public int upsertRates(Long ownerId, CatalogRequests.RateUpsert r) {
        RoomType t = roomTypes.findById(r.roomTypeId()).orElseThrow(() -> BusinessException.forbidden("Loại phòng không thuộc quyền của bạn"));
        Property p = owned(ownerId, t.getPropertyId());
        if (p.getStatus() == PropertyStatus.ARCHIVED) throw BusinessException.conflict("Cơ sở đã lưu trữ");
        if (!r.to().isAfter(r.from())) throw BusinessException.validation("to phải sau from");
        if (r.amount().signum() < 0) throw BusinessException.validation("Giá không được âm");
        if (r.from().isBefore(today(p))) throw BusinessException.validation("Không đổi giá cho ngày đã qua");
        if (r.from().plusDays(366).isBefore(r.to())) throw BusinessException.validation("Tối đa 366 đêm mỗi lần");

        Map<LocalDate, RoomRate> existing = rates.findRange(t.getId(), r.from(), r.to()).stream()
                .collect(Collectors.toMap(RoomRate::getStayDate, x -> x));
        BigDecimal before = existing.values().stream().map(RoomRate::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<RoomRate> toSave = new ArrayList<>();
        int n = 0;
        for (LocalDate d = r.from(); d.isBefore(r.to()); d = d.plusDays(1), n++) {
            RoomRate rr = existing.get(d);
            if (rr == null) {
                rr = new RoomRate();
                rr.setRoomTypeId(t.getId());
                rr.setStayDate(d);
            }
            rr.setAmount(r.amount().setScale(0, java.math.RoundingMode.HALF_UP));
            toSave.add(rr);
        }
        rates.saveAll(toSave);
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("nights", n);
        det.put("replacedExisting", existing.size());
        det.put("sumBefore", before);
        det.put("newAmount", r.amount());
        audit.record("RATE_UPSERT", "ROOM_TYPE", t.getId(), null, null, r.from() + ".." + r.to(), det);
        searchVersion.bump();
        return n;
    }

    @Transactional(readOnly = true)
    public List<RateDto> listRates(Long ownerId, Long roomTypeId, LocalDate from, LocalDate to) {
        RoomType t = roomTypes.findById(roomTypeId).orElseThrow(() -> BusinessException.forbidden("Loại phòng không thuộc quyền của bạn"));
        owned(ownerId, t.getPropertyId());
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) throw BusinessException.validation("Khoảng ngày không hợp lệ");
        return rates.findRange(roomTypeId, from, to).stream().map(x -> new RateDto(x.getStayDate(), x.getAmount(), x.getCurrency())).toList();
    }

    /** Có booking/hold đang chiếm phòng (allocation active, chưa trả phòng) thuộc loại phòng này với số khách > capacity mới không. */
    private boolean bookingsOverCapacity(Long roomTypeId, int newCapacity, LocalDate today) {
        return allocations.existsActiveFutureOverCapacity(roomTypeId, newCapacity, today);
    }

    // ------------------------------------------------------------ mapping
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean validZone(String zone) {
        if (isBlank(zone)) return false;
        try {
            ZoneId.of(zone);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    public static PropertyDto dto(Property p) {
        List<String> am = p.getAmenities().stream().map(Amenity::getCode).sorted().toList();
        return new PropertyDto(p.getId(), p.getOwnerId(), p.getName(), p.getDescription(), p.getPropertyType(), p.getAddress(),
                p.getCity(), p.getLatitude(), p.getLongitude(), p.getTimezone(), p.getStatus().name(), p.getCheckInTime(),
                p.getCheckOutTime(), p.getFreeCancelHours(), p.getPenaltyPercent(), p.getContactPhone(), p.getContactEmail(), am, p.getVersion());
    }

    public RoomTypeDto dto(RoomType t) {
        return new RoomTypeDto(t.getId(), t.getPropertyId(), t.getName(), t.getDescription(), t.getCapacity(), t.getBedType(),
                t.getStatus().name(), t.getAmenities().stream().map(Amenity::getCode).sorted().toList(), t.getVersion());
    }

    public RoomDto dto(Room r) {
        return new RoomDto(r.getId(), r.getPropertyId(), r.getRoomTypeId(), r.getRoomCode(), r.getStatus().name());
    }
}
