package ak.dev.khi_backend.khi_app.api.site;

import ak.dev.khi_backend.khi_app.model.site.NavMenuItem;
import ak.dev.khi_backend.khi_app.repository.site.NavMenuItemRepository;
import ak.dev.khi_backend.khi_app.service.site.NavMenuItemKeyMigration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The menu is image-only now — sections are seeded straight through the repository
 * because the API exposes no create endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NavMenuIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NavMenuItemRepository repository;

    @Autowired
    private NavMenuItemKeyMigration keyMigration;

    @PersistenceContext
    private EntityManager entityManager;

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private NavMenuItem seed(String itemKey, String imageUrl, boolean active) {
        return repository.save(NavMenuItem.builder()
                .itemKey(itemKey)
                .labelCkb("هەواڵ")
                .href("/" + itemKey)
                .imageUrl(imageUrl)
                .active(active)
                .build());
    }

    @Test
    void listIsPublicAndHidesInactiveRowsUnlessAsked() throws Exception {
        seed("news", "https://cdn.example.com/news.jpg", true);
        seed("donate", null, false);

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].itemKey").value("news"));

        mockMvc.perform(get("/api/v1/nav-menu").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getByIdAnswers404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/nav-menu/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateSetsAndClearsTheBackgroundPhoto() throws Exception {
        long id = seed("gallery", null, true).getId();

        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("imageUrl", "https://cdn.example.com/gallery-bg.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/gallery-bg.jpg"));

        // blank -> clears
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("imageUrl", "   "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist());
    }

    @Test
    void updateLeavesLabelAndHrefUntouched() throws Exception {
        long id = seed("news", null, true).getId();

        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        // a client resending the old contract cannot clobber them
                        .content(json(Map.of(
                                "imageUrl", "https://cdn.example.com/n.jpg",
                                "labelCkb", "ڕێکخستراو",
                                "href", "/elsewhere"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCkb").value("هەواڵ"))
                .andExpect(jsonPath("$.data.href").value("/news"));
    }

    @Test
    void updateTogglesActiveOnlyWhenSent() throws Exception {
        long id = seed("video", null, true).getId();

        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        // omitted -> unchanged, and no longer listed for the public site
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("imageUrl", "https://cdn.example.com/v.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void updateOnUnknownIdIsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/nav-menu/999999")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("imageUrl", "https://cdn.example.com/x.jpg"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void writeIsAdminOnlyWhileReadsArePublic() throws Exception {
        long id = seed("news", null, true).getId();

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("employee").roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("imageUrl", "https://cdn.example.com/x.jpg"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyItemKeysAreRenamedToTheSiteSectionKeys() throws Exception {
        seed("audio", "https://cdn.example.com/a.jpg", true);
        seed("videos", "https://cdn.example.com/v.jpg", true);

        keyMigration.run(null);
        // The runner renames via JDBC — drop the cached entities so the GET
        // below re-reads the renamed rows instead of the pre-update copies.
        entityManager.clear();

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].itemKey").value("sound"))
                .andExpect(jsonPath("$.data[0].imageUrl").value("https://cdn.example.com/a.jpg"))
                .andExpect(jsonPath("$.data[1].itemKey").value("video"))
                .andExpect(jsonPath("$.data[1].imageUrl").value("https://cdn.example.com/v.jpg"));
    }

    @Test
    void legacyRenameIsSkippedWhenTheCanonicalKeyExists() throws Exception {
        seed("audio", "https://cdn.example.com/legacy.jpg", true);
        seed("sound", "https://cdn.example.com/canon.jpg", true);

        keyMigration.run(null);
        entityManager.clear();

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].itemKey").value("sound"))
                .andExpect(jsonPath("$.data[1].imageUrl").value("https://cdn.example.com/canon.jpg"));
    }

    @Test
    void thereIsNoCreateOrDeleteEndpoint() throws Exception {
        long id = seed("news", null, true).getId();

        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("itemKey", "x"))))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/v1/nav-menu/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isMethodNotAllowed());
    }
}
