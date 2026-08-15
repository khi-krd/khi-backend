package ak.dev.khi_backend.khi_app.exceptions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A URL with no handler must answer 404, not 500. The catch-all
 * {@code @ExceptionHandler(Exception.class)} used to swallow Spring's "no route"
 * exception and report 500, which made a not-yet-deployed endpoint look like a
 * crashed server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoHandlerFoundIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unmappedApiPathIsNotFoundRatherThanServerError() throws Exception {
        mockMvc.perform(get("/api/v1/definitely-not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.details.path").value("/api/v1/definitely-not-a-route"))
                .andExpect(jsonPath("$.details.method").value("GET"));
    }

    @Test
    void unmappedPathIsNotFoundForAuthenticatedUsersToo() throws Exception {
        mockMvc.perform(get("/api/v1/nav-menu-typo").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void realEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/v1/nav-menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
