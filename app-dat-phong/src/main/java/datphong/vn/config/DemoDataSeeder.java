package datphong.vn.config;

import datphong.vn.constant.*;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Dữ liệu demo (chỉ khi app.seed.enabled=true; mặc định bật ở profile dev). KHÔNG chứa credential thật:
 * mật khẩu lấy từ SEED_PASSWORD. Ảnh bìa không seed được (cần MinIO) nên property demo ở trạng thái PUBLISHED trực tiếp.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final AppProperties props;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PropertyRepository properties;
    private final RoomTypeRepository roomTypes;
    private final RoomRepository rooms;
    private final RoomRateRepository rates;
    private final AmenityRepository amenities;
    private final PasswordEncoder encoder;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.getSeed().isEnabled() || users.existsByEmail("host@datphong.local")) return;
        User admin = user("admin@datphong.local", "Quản trị viên", RoleName.ADMIN);
        User support = user("support@datphong.local", "Hỗ trợ viên", RoleName.SUPPORT);
        User host = user("host@datphong.local", "Chủ cơ sở Demo", RoleName.HOST);
        user("customer@datphong.local", "Khách hàng Demo", RoleName.CUSTOMER);

        Property p = new Property();
        p.setOwnerId(host.getId());
        p.setName("Khách sạn Hồ Gươm Demo");
        p.setDescription("Cơ sở demo ở trung tâm Hà Nội");
        p.setAddress("1 Đinh Tiên Hoàng, Hoàn Kiếm");
        p.setCity("Hà Nội");
        p.setLatitude(new BigDecimal("21.028511"));
        p.setLongitude(new BigDecimal("105.852000"));
        p.setContactPhone("0900000000");
        p.setContactEmail("host@datphong.local");
        p.setStatus(PropertyStatus.PUBLISHED);
        p.getAmenities().addAll(amenities.findByCodeIn(java.util.List.of("WIFI", "AC", "BREAKFAST")));
        properties.save(p);

        RoomType deluxe = type(p, "Deluxe Double", 2, "Giường đôi");
        RoomType family = type(p, "Family Suite", 4, "2 giường đôi");
        for (int i = 1; i <= 3; i++) room(p, deluxe, "D10" + i);
        for (int i = 1; i <= 2; i++) room(p, family, "F20" + i);
        LocalDate d = LocalDate.now(clock);
        for (int i = 0; i < 180; i++) {
            LocalDate day = d.plusDays(i);
            boolean weekend = day.getDayOfWeek().getValue() >= 5;
            rate(deluxe, day, weekend ? 1_200_000 : 900_000);
            rate(family, day, weekend ? 2_400_000 : 1_900_000);
        }
        log.info("Seeded demo data: admin/support/host/customer@datphong.local (mật khẩu = SEED_PASSWORD)");
    }

    private User user(String email, String name, RoleName role) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(name);
        u.setPasswordHash(encoder.encode(props.getSeed().getPassword()));
        u.setStatus(UserStatus.ACTIVE);
        u.setVerifiedAt(clock.instant());
        u.getRoles().add(roles.findByCode(role.name()).orElseThrow());
        return users.save(u);
    }

    private RoomType type(Property p, String name, int cap, String bed) {
        RoomType t = new RoomType();
        t.setPropertyId(p.getId());
        t.setName(name);
        t.setCapacity(cap);
        t.setBedType(bed);
        return roomTypes.save(t);
    }

    private void room(Property p, RoomType t, String code) {
        Room r = new Room();
        r.setPropertyId(p.getId());
        r.setRoomTypeId(t.getId());
        r.setRoomCode(code);
        r.setStatus(RoomStatus.ACTIVE);
        rooms.save(r);
    }

    private void rate(RoomType t, LocalDate day, long amount) {
        RoomRate r = new RoomRate();
        r.setRoomTypeId(t.getId());
        r.setStayDate(day);
        r.setAmount(BigDecimal.valueOf(amount));
        rates.save(r);
    }
}
