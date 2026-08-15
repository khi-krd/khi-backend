package ak.dev.khi_backend.khi_app.api.site;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NavMenuIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> newsItem() {
        return Map.of(
                "itemKey", "News",                     // upper case on purpose — stored lower
                "labelCkb", "هەواڵ",
                "labelKmr", "Nûçe",
                "href", "/news",
                "displayOrder", 1,
                "active", true,
                "links", List.of(
                        Map.of("labelCkb", "کەلتوور", "href", "/news?category=culture"),
                        Map.of("labelCkb", "مێژوو", "href", "/news?category=history")));
    }

    private long createNewsItem() throws Exception {
        String body = mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newsItem())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    @Test
    void createStoresItemWithOrderedLinksAndLowerCasedKey() throws Exception {
        long id = createNewsItem();

        mockMvc.perform(get("/api/v1/nav-menu/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.itemKey").value("news"))
                .andExpect(jsonPath("$.data.labelKmr").value("Nûçe"))
                .andExpect(jsonPath("$.data.links.length()").value(2))
                .andExpect(jsonPath("$.data.links[0].displayOrder").value(1))
                .andExpect(jsonPath("$.data.links[1].displayOrder").value(2));
    }

    @Test
    void listIsPublicAndHidesInactiveRowsUnlessAsked() throws Exception {
        createNewsItem();
        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "donate",
                                "labelCkb", "بەخشین",
                                "href", "/donate",
                                "displayOrder", 2,
                                "active", false))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].itemKey").value("news"));

        mockMvc.perform(get("/api/v1/nav-menu").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void publicListDropsInactiveLinks() throws Exception {
        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "services",
                                "labelCkb", "خزمەتگوزاری",
                                "href", "/services",
                                "links", List.of(
                                        Map.of("labelCkb", "یەک", "href", "/services#one", "active", true),
                                        Map.of("labelCkb", "دوو", "href", "/services#two", "active", false))))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].links.length()").value(1))
                .andExpect(jsonPath("$.data[0].links[0].href").value("/services#one"));

        mockMvc.perform(get("/api/v1/nav-menu").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].links.length()").value(2));
    }

    @Test
    void updateReplacesLinksAndOmittedLinksAreLeftAlone() throws Exception {
        long id = createNewsItem();

        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "news",
                                "labelCkb", "هەواڵ",
                                "href", "/news",
                                "links", List.of(Map.of("labelCkb", "ژینگە", "href", "/news?category=nature"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.links.length()").value(1))
                .andExpect(jsonPath("$.data.links[0].href").value("/news?category=nature"));

        // links omitted -> untouched
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "news",
                                "labelCkb", "هەواڵی نوێ",
                                "href", "/news"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCkb").value("هەواڵی نوێ"))
                .andExpect(jsonPath("$.data.links.length()").value(1));

        // empty array -> all removed
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "news",
                                "labelCkb", "هەواڵ",
                                "href", "/news",
                                "links", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.links.length()").value(0));
    }

    /**
     * PUT is a full replace: an omitted imageUrl clears the background photo.
     * Documented as the trap in IMAGES_MENU_AND_FEATURED.md §3.3 — the editor has
     * to send the current URL back when changing anything else.
     */
    @Test
    void omittingImageUrlOnUpdateClearsTheBackgroundPhoto() throws Exception {
        String body = mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "gallery",
                                "labelCkb", "وێنە",
                                "href", "/gallery",
                                "imageUrl", "https://cdn.example.com/gallery-bg.jpg"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/gallery-bg.jpg"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();

        // same item, label edited, imageUrl not sent -> photo is gone
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "gallery",
                                "labelCkb", "وێنەکان",
                                "href", "/gallery"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCkb").value("وێنەکان"))
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist());

        // sending it back preserves it
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "gallery",
                                "labelCkb", "وێنەکان",
                                "href", "/gallery",
                                "imageUrl", "https://cdn.example.com/gallery-bg.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").value("https://cdn.example.com/gallery-bg.jpg"));

        // explicit empty string also clears
        mockMvc.perform(put("/api/v1/nav-menu/" + id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "gallery",
                                "labelCkb", "وێنەکان",
                                "href", "/gallery",
                                "imageUrl", "   "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").doesNotExist());
    }

    @Test
    void duplicateItemKeyIsRejected() throws Exception {
        createNewsItem();

        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "NEWS",
                                "labelCkb", "هەواڵ",
                                "href", "/news"))))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteRemovesItemAndUnknownIdIsNotFound() throws Exception {
        long id = createNewsItem();

        mockMvc.perform(delete("/api/v1/nav-menu/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/nav-menu/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void writesAreAdminOnlyWhileReadsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("employee").roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newsItem())))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/nav-menu/1")
                        .with(user("guest").roles("GUEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newsItem())))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/nav-menu/1").with(user("employee").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingRequiredFieldsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("labelCkb", "هەواڵ", "href", "/news"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/nav-menu")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "itemKey", "video",
                                "labelCkb", "ڤیدیۆ",
                                "href", "/video",
                                "links", List.of(Map.of("labelCkb", "بێ لینک"))))))
                .andExpect(status().isBadRequest());
    }
}
