package datphong.vn.repository;

import datphong.vn.exception.entity.BookingNight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingNightRepository extends JpaRepository<BookingNight, Long> {
    List<BookingNight> findByBookingIdOrderByStayDate(Long bookingId);
}
