package ak.dev.khi_backend.khi_app.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replays the exact payload the dashboard's contact-office card sends for the
 * Duhok office — the save that kept answering 400 in production. If this PUT
 * succeeds, the backend contract is clean and the rejection has to come from a
 * body the browser actually produced (e.g. a cleared required field).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContactUpdateContractIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    // Field-for-field the shape `contactFormValuesToPayload` emits for the
    // live Duhok record (id 8).
    private static final String DUHOK_PAYLOAD = """
            {
              "slugCkb": "نووسینگەی دهۆک",
              "slugKmr": "nusingaha duhoke",
              "active": true,
              "displayOrder": 1,
              "ckbContent": {
                "title": "دهۆک",
                "subtitle": "پەیمانگای کەلەپووری کوردی — نووسینگەی هەرێمی",
                "address": "دهۆک، شەقامی نوهەدرا، بەرامبەر زانکۆی دهۆک، عێراق",
                "workingHours": "شەممە – پێنجشەممە، ٩:٠٠ بەیانی – ٣:٠٠ ئێوارە",
                "description": "نووسینگەی هەرێمی **پەیمانگای کەلەپووری کوردی** لە دهۆک."
              },
              "kmrContent": {
                "title": "Duhokê",
                "subtitle": "Enstîtuya Mîrateya Kurdî — Nivîsgeha herêmî",
                "address": "Duhok, Kolana Nuhedra, hember Zanêngeha Duhokê, Iraq",
                "workingHours": "Şemî – Pêncşem, 9:00 – 15:00",
                "description": "Nivîsgeha herêmî ya **Enstîtuya Mîrateya Kurdî** li Duhokê."
              },
              "phone": "+964 770 444 5566",
              "secondaryPhone": "+964 750 444 5566",
              "email": "duhok@khi.example.org",
              "mapEmbedUrl": "https://www.google.com/maps?q=36.8663,42.9884&z=14&output=embed",
              "latitude": 36.8663,
              "longitude": 42.9884,
              "heroImageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/duhok.jpg",
              "officeType": "REGIONAL",
              "badgeCkb": "نووسینگەی هەرێمی",
              "badgeKmr": "Nivîsgeha herêmî"
            }
            """;

    @Test
    @WithMockUser(roles = "ADMIN")
    void createThenUpdateWithTheDuhokPayloadSucceeds() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DUHOK_PAYLOAD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(put("/api/v1/contact/" + id.longValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DUHOK_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slugKmr").value("nusingaha duhoke"))
                .andExpect(jsonPath("$.data.officeType").value("REGIONAL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateWithoutRequiredFieldsAnswers400WithAReason() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DUHOK_PAYLOAD))
                .andExpect(status().isCreated())
                .andReturn();

        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // The failure mode the dashboard kept hitting: a payload whose
        // @NotBlank fields are empty must 400 — and the body must carry
        // top-level fieldErrors so the toast can say what failed.
        mockMvc.perform(put("/api/v1/contact/" + id.longValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slugCkb": "",
                                  "phone": "",
                                  "email": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").exists());
    }
}
