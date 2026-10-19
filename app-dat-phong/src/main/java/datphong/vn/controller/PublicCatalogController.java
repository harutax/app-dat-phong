package datphong.vn.controller;

import datphong.vn.dto.response.SearchResponses.*;
import datphong.vn.service.PublicCatalogService;
import datphong.vn.service.SearchService;
import datphong.vn.repository.AmenityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** GUEST: tìm kiếm, chi tiết, báo giá (không cần đăng nhập, không lộ thông tin cá nhân). */
@RestController
@RequiredArgsConstructor
public class PublicCatalogController {

    private final SearchService search;
    private final PublicCatalogService catalog;
    private final AmenityRepository amenities;

    @GetMapping("/properties")
    public SearchPage properties(@RequestParam(required = false) String city,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                                 @RequestParam(defaultValue = "2") int guests,
                                 @RequestParam(required = false) List<String> amenities,
                                 @RequestParam(required = false) BigDecimal minPrice,
                                 @RequestParam(required = false) BigDecimal maxPrice,
                                 @RequestParam(required = false) String propertyType,
                                 @RequestParam(defaultValue = "price_asc") String sort,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return search.search(new SearchService.Filter(city, checkIn, checkOut, guests, amenities, minPrice, maxPrice, propertyType, sort, page, size));
    }

    @GetMapping("/rooms/search")
    public FlatPage rooms(@RequestParam(required = false) String city,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                          @RequestParam(defaultValue = "2") int guests,
                          @RequestParam(required = false) List<String> amenities,
                          @RequestParam(required = false) BigDecimal minPrice,
                          @RequestParam(required = false) BigDecimal maxPrice,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        return search.searchRooms(new SearchService.Filter(city, checkIn, checkOut, guests, amenities, minPrice, maxPrice, null, "price_asc", page, size));
    }

    @GetMapping("/properties/{id}")
    public PropertyDetail detail(@PathVariable Long id) {
        return catalog.detail(id);
    }

    @GetMapping("/rooms/{id}/quote")
    public QuoteDto quote(@PathVariable Long id,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                          @RequestParam(defaultValue = "2") int guests,
                          @RequestHeader(value = "X-Session-Id", required = false) String sessionKey) {
        return catalog.quote(id, checkIn, checkOut, guests, sessionKey);
    }

    @GetMapping("/public/amenities")
    public List<Map<String, Object>> amenities() {
        return amenities.findByActiveTrueOrderByName().stream().map(a -> Map.<String, Object>of("code", a.getCode(), "name", a.getName())).toList();
    }
}
