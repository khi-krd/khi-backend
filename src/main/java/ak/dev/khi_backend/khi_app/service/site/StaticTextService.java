package ak.dev.khi_backend.khi_app.service.site;

import ak.dev.khi_backend.khi_app.model.site.StaticText;
import ak.dev.khi_backend.khi_app.repository.site.StaticTextRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;

/**
 * Editable static text blocks ("Nav.menuNews" -> "هەواڵ"). The public website
 * merges these over its bundled JSON messages at request time; admins edit
 * them from the dashboard. Bundled seed files only fill MISSING keys — admin
 * edits are never overwritten.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaticTextService {

    private final StaticTextRepository repo;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Public read — { "Nav.menuNews": "هەواڵ", ... } for one locale
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, String> getPublicMap(String locale) {
        Map<String, String> out = new LinkedHashMap<>();
        for (StaticText t : repo.findByLocaleOrderByKeyAsc(normLocale(locale))) {
            out.put(t.getKey(), t.getValue());
        }
        return out;
    }

    // =========================================================================
    // Admin listing — one row per key, values per locale
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAll() {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (StaticText t : repo.findAllByOrderByKeyAsc()) {
            Map<String, Object> row = byKey.computeIfAbsent(t.getKey(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("section", t.getSection());
                m.put("values", new LinkedHashMap<String, Object>());
                return m;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) row.get("values");
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", t.getId());
            v.put("value", t.getValue());
            v.put("updatedAt", t.getUpdatedAt());
            v.put("updatedBy", t.getUpdatedBy());
            values.put(t.getLocale(), v);
        }
        return new ArrayList<>(byKey.values());
    }

    // =========================================================================
    // Admin upsert — bulk entries {key, locale, value}
    // =========================================================================

    @Transactional
    public int upsert(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        String user = currentUser();
        int saved = 0;
        for (Entry e : entries) {
            if (e == null || isBlank(e.key())) continue;
            String key = e.key().trim();
            String locale = normLocale(e.locale());
            if (isBlank(locale)) continue;

            StaticText t = repo.findByKeyAndLocale(key, locale).orElse(null);
            if (t == null) {
                t = StaticText.builder()
                        .key(key)
                        .locale(locale)
                        .value(e.value() == null ? "" : e.value())
                        .section(sectionOf(key))
                        .updatedBy(user)
                        .build();
            } else {
                t.setValue(e.value() == null ? "" : e.value());
                t.setSection(sectionOf(key));
                t.setUpdatedBy(user);
            }
            repo.save(t);
            saved++;
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        if (id == null) return;
        repo.deleteById(id);
    }

    // =========================================================================
    // Seed — fill missing keys from the bundled website messages on boot
    // =========================================================================

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        for (String locale : List.of("ckb", "ku")) {
            try {
                Resource res = new ClassPathResource("static-text-seed/" + locale + ".json");
                if (!res.exists()) continue;
                Map<String, String> flat;
                try (InputStream in = res.getInputStream()) {
                    flat = objectMapper.readValue(in, new TypeReference<>() {});
                }
                int added = 0;
                for (Map.Entry<String, String> e : flat.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    if (repo.existsByKeyAndLocale(e.getKey(), locale)) continue;
                    repo.save(StaticText.builder()
                            .key(e.getKey())
                            .locale(locale)
                            .value(e.getValue() == null ? "" : e.getValue())
                            .section(sectionOf(e.getKey()))
                            .updatedBy("seed")
                            .build());
                    added++;
                }
                if (added > 0) log.info("static_texts seeded locale={} added={}", locale, added);
            } catch (Exception ex) {
                log.warn("static_texts seed skipped for locale={}: {}", locale, ex.getMessage());
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public record Entry(String key, String locale, String value) {}

    private String normLocale(String locale) {
        if (isBlank(locale)) return "";
        String l = locale.trim().toLowerCase(Locale.ROOT);
        // the website calls Kurmanji "ku"; accept common aliases
        return switch (l) {
            case "kmr", "kurmanji", "ku" -> "ku";
            case "ckb", "sorani" -> "ckb";
            default -> l;
        };
    }

    private String sectionOf(String key) {
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : key;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getName() != null ? auth.getName() : "system";
    }
}
