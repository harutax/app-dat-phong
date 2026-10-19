package datphong.vn.controller;

import datphong.vn.dto.request.CatalogRequests;
import datphong.vn.dto.response.CatalogResponses.*;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.CatalogService;
import datphong.vn.service.MediaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** HOST quản lý catalog. @PreAuthorize = quyền chức năng; owner scope kiểm tra trong CatalogService. */
@RestController
@RequestMapping("/host")
@RequiredArgsConstructor
public class HostCatalogController {

    private final CatalogService catalog;
    private final MediaService media;

    @PostMapping("/properties")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROPERTY_CREATE')")
    public PropertyDto create(@Valid @RequestBody CatalogRequests.PropertyUpsert r) {
        return catalog.createProperty(CurrentUser.id(), r);
    }

    @GetMapping("/properties")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public List<PropertyDto> mine() {
        return catalog.myProperties(CurrentUser.id());
    }

    @GetMapping("/properties/{id}")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public PropertyDto get(@PathVariable Long id) {
        return catalog.myProperty(CurrentUser.id(), id);
    }

    @PatchMapping("/properties/{id}")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public PropertyDto update(@PathVariable Long id, @Valid @RequestBody CatalogRequests.PropertyUpsert r) {
        return catalog.updateProperty(CurrentUser.id(), id, r);
    }

    @PostMapping("/properties/{id}/publish")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public PropertyDto publish(@PathVariable Long id) {
        return catalog.publish(CurrentUser.id(), id);
    }

    @PostMapping("/properties/{id}/pause")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public PropertyDto pause(@PathVariable Long id) {
        return catalog.pause(CurrentUser.id(), id);
    }

    @PostMapping("/properties/{id}/resume")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public PropertyDto resume(@PathVariable Long id) {
        return catalog.resume(CurrentUser.id(), id);
    }

    @PostMapping("/properties/{id}/archive")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public PropertyDto archive(@PathVariable Long id) {
        return catalog.archive(CurrentUser.id(), id);
    }

    @PostMapping("/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public RoomTypeDto createType(@Valid @RequestBody CatalogRequests.RoomTypeUpsert r) {
        return catalog.createRoomType(CurrentUser.id(), r);
    }

    @PatchMapping("/room-types/{id}")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public RoomTypeDto updateType(@PathVariable Long id, @Valid @RequestBody CatalogRequests.RoomTypeUpsert r) {
        return catalog.updateRoomType(CurrentUser.id(), id, r);
    }

    @GetMapping("/properties/{id}/room-types")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public List<RoomTypeDto> types(@PathVariable Long id) {
        return catalog.listRoomTypes(CurrentUser.id(), id);
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public RoomDto createRoom(@Valid @RequestBody CatalogRequests.RoomCreate r) {
        return catalog.createRoom(CurrentUser.id(), r);
    }

    @PatchMapping("/rooms/{id}/status")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public RoomDto roomStatus(@PathVariable Long id, @Valid @RequestBody CatalogRequests.RoomStatusUpdate r) {
        return catalog.setRoomStatus(CurrentUser.id(), id, r.status());
    }

    @GetMapping("/properties/{id}/rooms")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public List<RoomDto> rooms(@PathVariable Long id) {
        return catalog.listRooms(CurrentUser.id(), id);
    }

    @PostMapping("/rates")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public Map<String, Integer> rates(@Valid @RequestBody CatalogRequests.RateUpsert r) {
        return Map.of("nights", catalog.upsertRates(CurrentUser.id(), r));
    }

    @GetMapping("/room-types/{id}/rates")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public List<RateDto> listRates(@PathVariable Long id,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return catalog.listRates(CurrentUser.id(), id, from, to);
    }

    // ---- media (UC-06)
    @PostMapping("/media/uploads")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public UploadTicket initUpload(@Valid @RequestBody CatalogRequests.UploadInit r) {
        return media.init(CurrentUser.id(), r);
    }

    @PostMapping("/media/finalize")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public MediaDto finalizeUpload(@Valid @RequestBody CatalogRequests.UploadFinalize r) {
        return media.finalizeUpload(CurrentUser.id(), r.assetId());
    }

    @PostMapping("/media/{id}/cover")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public Map<String, String> cover(@PathVariable Long id) {
        media.setCover(CurrentUser.id(), id);
        return Map.of("status", "OK");
    }

    @DeleteMapping("/media/{id}")
    @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
    public Map<String, String> deleteMedia(@PathVariable Long id) {
        media.delete(CurrentUser.id(), id);
        return Map.of("status", "DELETING");
    }
}
