package ak.dev.khi_backend.khi_app.service.contact;

import ak.dev.khi_backend.khi_app.dto.contact.ContactDTOs.*;
import ak.dev.khi_backend.khi_app.model.contact.Contact;
import ak.dev.khi_backend.khi_app.model.contact.ContactContent;
import ak.dev.khi_backend.khi_app.repository.contact.ContactRepository;
import ak.dev.khi_backend.khi_app.service.media.TiptapHtmlProcessor;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactRepository contactRepository;
    private final TiptapHtmlProcessor tiptapHtmlProcessor;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ============================================================
    // READ
    // ============================================================

    @Transactional(readOnly = true)
    public List<ContactResponse> getAll() {
        return contactRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ContactResponse> getAllActive(int page, int size) {
        return contactRepository
                .findAllByActiveTrueOrderByDisplayOrderAsc(PageRequest.of(page, size))
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ContactResponse getBySlug(String slug) {
        Contact contact = contactRepository.findBySlugCkbOrSlugKmr(slug, slug)
                .orElseThrow(() ->
                        new EntityNotFoundException("Contact page not found: " + slug));
        return toResponse(contact);
    }

    @Transactional(readOnly = true)
    public ContactResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    // ============================================================
    // CREATE
    // ============================================================

    @Transactional
    public ContactResponse create(ContactRequest request) {
        if (request == null) request = new ContactRequest();

        String slugCkb = resolveCkbSlug(request.getSlugCkb(), null, null);
        String slugKmr = resolveKmrSlug(request.getSlugKmr(), slugCkb, null, null);

        Contact contact = Contact.builder()
                .slugCkb(slugCkb)
                .slugKmr(slugKmr)
                .ckbContent(buildContent(request.getCkbContent()))
                .kmrContent(buildContent(request.getKmrContent()))
                .phone(blankToNull(request.getPhone()))
                .secondaryPhone(blankToNull(request.getSecondaryPhone()))
                .email(blankToNull(request.getEmail()))
                .mapEmbedUrl(blankToNull(request.getMapEmbedUrl()))
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .heroImageUrl(blankToNull(request.getHeroImageUrl()))
                .officeType(blankToNull(request.getOfficeType()))
                .badgeCkb(blankToNull(request.getBadgeCkb()))
                .badgeKmr(blankToNull(request.getBadgeKmr()))
                .active(request.getActive() == null || request.getActive())
                .displayOrder(request.getDisplayOrder() == null ? 0 : request.getDisplayOrder())
                .build();

        Contact saved = contactRepository.save(contact);
        log.info("Contact page created — id={}, slugCkb={}", saved.getId(), saved.getSlugCkb());
        return toResponse(saved);
    }

    // ============================================================
    // UPDATE
    // ============================================================

    @Transactional
    public ContactResponse update(Long id, ContactRequest request) {
        if (request == null) request = new ContactRequest();

        Contact contact = findOrThrow(id);
        contact.setSlugCkb(resolveCkbSlug(request.getSlugCkb(), contact.getSlugCkb(), id));
        contact.setSlugKmr(resolveKmrSlug(request.getSlugKmr(), contact.getSlugCkb(), contact.getSlugKmr(), id));
        contact.setCkbContent(buildContent(request.getCkbContent()));
        contact.setKmrContent(buildContent(request.getKmrContent()));
        contact.setPhone(blankToNull(request.getPhone()));
        contact.setSecondaryPhone(blankToNull(request.getSecondaryPhone()));
        contact.setEmail(blankToNull(request.getEmail()));
        contact.setMapEmbedUrl(blankToNull(request.getMapEmbedUrl()));
        contact.setLatitude(request.getLatitude());
        contact.setLongitude(request.getLongitude());
        contact.setHeroImageUrl(blankToNull(request.getHeroImageUrl()));
        contact.setOfficeType(blankToNull(request.getOfficeType()));
        contact.setBadgeCkb(blankToNull(request.getBadgeCkb()));
        contact.setBadgeKmr(blankToNull(request.getBadgeKmr()));
        if (request.getActive() != null) contact.setActive(request.getActive());
        if (request.getDisplayOrder() != null) contact.setDisplayOrder(request.getDisplayOrder());

        Contact updated = contactRepository.save(contact);
        log.info("Contact page updated — id={}", updated.getId());
        return toResponse(updated);
    }

    // ============================================================
    // DELETE
    // ============================================================

    @Transactional
    public void delete(Long id) {
        Contact contact = findOrThrow(id);
        contactRepository.delete(contact);
        log.info("Deleted contact page id={}", id);
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private Contact findOrThrow(Long id) {
        return contactRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Contact not found: " + id));
    }

    /**
     * All fields optional. A missing/duplicate slug falls back to a generated
     * unique one; on update a blank slug keeps the existing value.
     */
    private String resolveCkbSlug(String requested, String existing, Long selfId) {
        String slug = (requested == null || requested.isBlank())
                ? existing
                : requested.trim();
        if (slug == null || slug.isBlank()) {
            slug = "contact";
        }
        return uniqueSlug(slug, selfId, true);
    }

    private String resolveKmrSlug(String requested, String ckbSlug, String existing, Long selfId) {
        String slug = (requested == null || requested.isBlank())
                ? existing
                : requested.trim();
        if (slug == null || slug.isBlank()) {
            return null;
        }
        if (slug.equals(ckbSlug)) {
            slug = slug + "-kmr";
        }
        return uniqueSlug(slug, selfId, false);
    }

    private String uniqueSlug(String slug, Long selfId, boolean ckb) {
        String candidate = slug;
        int n = 2;
        while (slugTaken(candidate, selfId, ckb)) {
            candidate = slug + "-" + n++;
        }
        return candidate;
    }

    private boolean slugTaken(String slug, Long selfId, boolean ckb) {
        var found = ckb
                ? contactRepository.findBySlugCkb(slug)
                : contactRepository.findBySlugKmr(slug);
        return found.isPresent()
                && (selfId == null || !found.get().getId().equals(selfId));
    }

    private ContactContent buildContent(ContactContentRequest req) {
        if (req == null) return new ContactContent();
        return ContactContent.builder()
                .title(req.getTitle())
                .subtitle(req.getSubtitle())
                .address(req.getAddress())
                .workingHours(req.getWorkingHours())
                .description(tiptapHtmlProcessor.process(req.getDescription()))
                .build();
    }

    // ─── Response Mapper ──────────────────────────────────────────────────────

    private ContactResponse toResponse(Contact c) {
        return ContactResponse.builder()
                .id(c.getId())
                .slugCkb(c.getSlugCkb())
                .slugKmr(c.getSlugKmr())
                .ckbContent(toContentResponse(c.getCkbContent()))
                .kmrContent(toContentResponse(c.getKmrContent()))
                .phone(c.getPhone())
                .secondaryPhone(c.getSecondaryPhone())
                .email(c.getEmail())
                .mapEmbedUrl(c.getMapEmbedUrl())
                .latitude(c.getLatitude())
                .longitude(c.getLongitude())
                .heroImageUrl(c.getHeroImageUrl())
                .officeType(c.getOfficeType())
                .badgeCkb(c.getBadgeCkb())
                .badgeKmr(c.getBadgeKmr())
                .displayOrder(c.getDisplayOrder())
                .active(c.isActive())
                .createdAt(c.getCreatedAt() != null ? c.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(c.getUpdatedAt() != null ? c.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private ContactContentResponse toContentResponse(ContactContent content) {
        if (content == null) return null;
        return ContactContentResponse.builder()
                .title(content.getTitle())
                .subtitle(content.getSubtitle())
                .address(content.getAddress())
                .workingHours(content.getWorkingHours())
                .description(content.getDescription())
                .build();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
