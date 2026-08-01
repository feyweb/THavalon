package com.thavalon.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other half of the audit contract: while a game is in progress its trail is sealed, and
 * there is no credential, header or role that opens it early. Only the clock does.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditSealTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("thavalon-seal-test");
        dir.toFile().deleteOnExit();
        registry.add("thavalon.data-dir", dir::toString);
        registry.add("thavalon.audit-unlock-after", () -> "PT4H");
    }

    @Test
    @DisplayName("a game in progress is sealed, and nothing opens it early")
    void gameInProgressIsSealed() throws Exception {
        String body = json.writeValueAsString(new Api.NameRequest("Host"));
        MvcResult created = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode host = json.readTree(created.getResponse().getContentAsString());
        String gameId = host.get("gameId").asText();
        String hostToken = host.get("playerToken").asText();

        for (int i = 1; i < 5; i++) {
            mvc.perform(post("/api/games/{id}/players", gameId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new Api.NameRequest("Player" + i))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/games/{id}/start", gameId)
                        .header(GameController.TOKEN_HEADER, hostToken))
                .andExpect(status().isOk());

        // No credential of any kind opens a sealed game.
        mvc.perform(get("/api/audit/{id}", gameId))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("AUDIT_SEALED"));
        mvc.perform(get("/api/audit/{id}", gameId)
                        .header(GameController.TOKEN_HEADER, hostToken))
                .andExpect(status().isLocked());

        // And it is absent from the index rather than merely redacted within it.
        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.gameId == '" + gameId + "')]").isEmpty());
    }

    @Test
    @DisplayName("an unknown game code 404s rather than reporting a seal")
    void unknownGameIsNotFound() throws Exception {
        mvc.perform(get("/api/audit/{id}", "ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }
}
