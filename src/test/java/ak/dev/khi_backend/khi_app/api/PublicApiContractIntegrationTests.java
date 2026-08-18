package ak.dev.khi_backend.khi_app.api;

import ak.dev.khi_backend.khi_app.dto.publishment.writing.WritingDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicApiContractIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publicListContractsReturnExpectedEnvelopes() throws Exception {
        mockMvc.perform(get("/api/v1/about").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/v1/contact/active").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/v1/services/all").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void featuredAndSitemapArePublic() throws Exception {
        mockMvc.perform(get("/featured").param("locale", "ckb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/v1/sitemap").param("locale", "ku"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locale").value("ku"))
                .andExpect(jsonPath("$.data.paths").isArray());
    }

    @Test
    void perResourceFeaturedRoutesReturnPagesInsteadOfFallingThroughToIdRoutes() throws Exception {
        String[] envelopedRoutes = {
                "/api/v1/news/featured",
                "/api/v1/services/featured",
                "/api/v1/projects/featured",
                "/api/v1/image-collections/featured",
                "/api/v1/sound-tracks/featured",
                "/api/v1/writings/featured"
        };

        for (String route : envelopedRoutes) {
            mockMvc.perform(get(route).param("page", "0").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray());
        }

        mockMvc.perform(get("/api/v1/videos/featured")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void filmReklamVideoRouteIsPublicAndDoesNotFallThroughToTheIdRoute() throws Exception {
        // "film-reklam-video" is not a Long. If /{id} won the match this would be a 400
        // from path-variable conversion; a 404 with an AppException body proves the
        // literal route is selected and simply has nothing stored yet.
        mockMvc.perform(get("/api/v1/videos/film-reklam-video"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/videos/film-reklam-video"));
    }

    @Test
    void siteSettingsAreReadableWithoutAuthAndNeverEmpty() throws Exception {
        // The website needs the logo on first paint, and a fresh database must still
        // answer 200 so both branding images can fall back rather than error.
        mockMvc.perform(get("/api/v1/site-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.maxFeaturedSlides").isNumber());
    }

    @Test
    void numericResourceLookupsStillUseIdRoutes() throws Exception {
        String[] numericRoutes = {
                "/api/v1/news/999999",
                "/api/v1/services/999999",
                "/api/v1/projects/999999",
                "/api/v1/videos/999999",
                "/api/v1/image-collections/999999",
                "/api/v1/sound-tracks/999999",
                "/api/v1/writings/999999"
        };

        for (String route : numericRoutes) {
            mockMvc.perform(get(route))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void visitorsCanSubmitContactMessages() throws Exception {
        mockMvc.perform(post("/api/v1/contact/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "API Contract Test",
                                  "email": "visitor@example.com",
                                  "subject": "Question",
                                  "message": "Please send more information.",
                                  "locale": "ckb"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("NEW"));
    }

    @Test
    void visitorsCanSubmitBothDonationFlows() throws Exception {
        mockMvc.perform(post("/api/v1/donations/financial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "donorName": "Donor",
                                  "email": "donor@example.com",
                                  "amount": 25.50,
                                  "currency": "USD",
                                  "paymentMethod": "BANK_TRANSFER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/donations/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "donorName": "Archive Donor",
                                  "email": "archive@example.com",
                                  "materialType": "PHOTOGRAPH",
                                  "title": "Historic photograph",
                                  "description": "A photograph offered to the institute archive."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void soundtrackWebsiteQueryAliasesAreAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/sound-tracks/by-sound-type")
                        .param("type", "POEM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/v1/sound-tracks/search/tag")
                        .param("value", "heritage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/v1/sound-tracks/search/keyword")
                        .param("value", "archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void writingResponseExposesWebsiteCompatibilityFields() throws Exception {
        WritingDtos.Response response = WritingDtos.Response.builder()
                .topic(WritingDtos.TopicInfo.builder()
                        .id(7L)
                        .nameCkb("Topic CKB")
                        .nameKmr("Topic KMR")
                        .build())
                .seriesInfo(WritingDtos.SeriesInfoDto.builder()
                        .seriesId("series-1")
                        .seriesName("Series")
                        .seriesOrder(1.0)
                        .build())
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));
        org.assertj.core.api.Assertions.assertThat(json.path("topicId").asLong()).isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(json.path("topicNameCkb").asText()).isEqualTo("Topic CKB");
        org.assertj.core.api.Assertions.assertThat(json.path("topicNameKmr").asText()).isEqualTo("Topic KMR");
        org.assertj.core.api.Assertions.assertThat(json.path("series").path("seriesId").asText())
                .isEqualTo("series-1");
    }
}
