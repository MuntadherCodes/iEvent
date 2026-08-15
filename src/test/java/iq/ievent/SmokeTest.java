package iq.ievent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests against the full Spring context with the demo seed enabled.
 * The datasource comes from the environment (CI provides a postgres:16
 * service via SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.seed.demo=true"})
class SmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void homeRendersAndMentionsIevent() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("iEvent")));
    }

    @Test
    void browseRenders() throws Exception {
        mockMvc.perform(get("/browse"))
                .andExpect(status().isOk());
    }

    @Test
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/auth/login"))
                .andExpect(status().isOk());
    }

    @Test
    void registerPageRenders() throws Exception {
        mockMvc.perform(get("/auth/register"))
                .andExpect(status().isOk());
    }

    @Test
    void seededEventDetailRenders() throws Exception {
        mockMvc.perform(get("/events/baghdad-nights-music-festival"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("General Admission")));
    }

    @Test
    void unknownEventSlugIs404() throws Exception {
        mockMvc.perform(get("/events/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registrationRedirectsToLoginWithFlag() throws Exception {
        String uniqueEmail = "smoke+" + System.currentTimeMillis() + "@test.iq";
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .param("fullName", "Smoke Tester")
                        .param("email", uniqueEmail)
                        .param("phone", "")
                        .param("password", "SmokePassw0rd!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?registered"));
    }
}
