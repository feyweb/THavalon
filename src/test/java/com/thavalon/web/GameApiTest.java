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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GameApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    /** Reads the audit directly. The seal is set to zero here; {@link AuditSealTest} covers it. */
    @Autowired
    private com.thavalon.game.GameService unsealedAudit;

    /** Used to expire a game mid-test, the way the scheduled sweep would. */
    @Autowired
    private com.thavalon.game.GameStore store;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("thavalon-test");
        dir.toFile().deleteOnExit();
        registry.add("thavalon.data-dir", dir::toString);
        registry.add("thavalon.audit-unlock-after", () -> "PT0S");
    }

    // ---------- helpers ----------

    private record Session(String gameId, String token, String name) {
    }

    private Session create(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new Session(node.get("gameId").asText(), node.get("playerToken").asText(), name);
    }

    private Session join(String gameId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/games/{id}/players", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = read(result);
        return new Session(gameId, node.get("playerToken").asText(), name);
    }

    /** A game with {@code count} players, host first. */
    private List<Session> lobbyOf(int count) throws Exception {
        List<Session> sessions = new ArrayList<>();
        sessions.add(create("Host"));
        for (int i = 1; i < count; i++) {
            sessions.add(join(sessions.getFirst().gameId(), "Player" + i));
        }
        return sessions;
    }

    private String body(String name) throws Exception {
        return json.writeValueAsString(new Api.NameRequest(name));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode me(Session session) throws Exception {
        return read(mvc.perform(get("/api/games/{id}/me", session.gameId())
                        .header(GameController.TOKEN_HEADER, session.token()))
                .andExpect(status().isOk())
                .andReturn());
    }

    // ---------- lobby ----------

    @Test
    @DisplayName("create returns a four-character code and makes the creator host")
    void createReturnsCodeAndHost() throws Exception {
        Session host = create("Morgan");
        assertThat(host.gameId()).hasSize(4).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}");
        assertThat(host.token()).isNotBlank();

        mvc.perform(get("/api/games/{id}", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostName").value("Morgan"))
                .andExpect(jsonPath("$.youAreHost").value(true))
                .andExpect(jsonPath("$.state").value("LOBBY"))
                .andExpect(jsonPath("$.canStart").value(false));
    }

    @Test
    @DisplayName("generated game IDs are unambiguous — no O, 0, I or 1")
    void generatedGameIdsAvoidAmbiguousCharacters() throws Exception {
        for (int i = 0; i < 50; i++) {
            assertThat(create("Host" + i).gameId()).doesNotContainAnyWhitespaces()
                    .matches("[^OI01]{4}");
        }
    }

    // ---------- host-chosen game IDs ----------

    private MvcResult createWithId(String gameId, String name) throws Exception {
        return mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Api.CreateRequest(name, gameId))))
                .andReturn();
    }

    @Test
    @DisplayName("the host can name the game, and it is stored upper-case")
    void hostCanChooseGameId() throws Exception {
        MvcResult result = createWithId("friday-night", "Morgan");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = read(result);
        assertThat(created.get("gameId").asText()).isEqualTo("FRIDAY-NIGHT");
        assertThat(created.get("host").asBoolean()).isTrue();

        // Others join with it however they type it.
        join("friday-night", "Robin");
        mvc.perform(get("/api/games/{id}", "Friday-Night"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value("FRIDAY-NIGHT"))
                .andExpect(jsonPath("$.hostName").value("Morgan"))
                .andExpect(jsonPath("$.players.length()").value(2));
    }

    @Test
    @DisplayName("a blank game ID still gets a generated one")
    void blankGameIdFallsBackToGenerated() throws Exception {
        for (String blank : new String[]{null, "", "   "}) {
            JsonNode created = read(createWithId(blank, "Host"));
            assertThat(created.get("gameId").asText()).hasSize(4);
        }
    }

    @Test
    @DisplayName("an ID in use by a live game is refused")
    void duplicateGameIdRefused() throws Exception {
        createWithId("GAMENIGHT", "Morgan");
        MvcResult clash = createWithId("gamenight", "Someone");
        assertThat(clash.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(clash).get("code").asText()).isEqualTo("GAME_ID_TAKEN");
    }

    @Test
    @DisplayName("malformed game IDs are rejected, including path traversal attempts")
    void malformedGameIdsRejected() throws Exception {
        String[] bad = {"ab", "-leading", "has space", "toolonggameid", "sym!bol", "../etc", "a/b"};
        for (String id : bad) {
            assertThat(createWithId(id, "Host").getResponse().getStatus())
                    .as("game ID %s", id)
                    .isIn(400, 404);
        }
    }

    @Test
    @DisplayName("reusing an ID keeps the two games' audit trails separate")
    void reusedIdGetsItsOwnAuditTrail() throws Exception {
        // First game under this ID, played to completion.
        String id = "REMATCH";
        JsonNode first = read(createWithId(id, "Host"));
        for (int i = 1; i < 5; i++) join(id, "P" + i);
        mvc.perform(post("/api/games/{id}/start", id)
                        .header(GameController.TOKEN_HEADER, first.get("playerToken").asText()))
                .andExpect(status().isOk());
        var firstView = unsealedAudit.auditFor(id);

        // Sweep the first game, freeing the ID, then play a second under the same name.
        store.delete(id);
        JsonNode second = read(createWithId(id, "Host"));
        for (int i = 1; i < 6; i++) join(id, "Q" + i);
        mvc.perform(post("/api/games/{id}/start", id)
                        .header(GameController.TOKEN_HEADER, second.get("playerToken").asText()))
                .andExpect(status().isOk());
        var secondView = unsealedAudit.auditFor(id);

        assertThat(secondView.roles()).hasSize(6).containsKey("Q5");
        assertThat(firstView.roles()).hasSize(5).containsKey("P4");
        // The lookup returns the newer game, and the older trail was not appended to.
        assertThat(secondView.roles()).doesNotContainKey("P4");
    }

    @Test
    @DisplayName("lobby lists players in join order and flags when a start is possible")
    void lobbyListsPlayers() throws Exception {
        List<Session> sessions = lobbyOf(5);
        mvc.perform(get("/api/games/{id}", sessions.getFirst().gameId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(5))
                .andExpect(jsonPath("$.players[0]").value("Host"))
                .andExpect(jsonPath("$.canStart").value(true))
                .andExpect(jsonPath("$.minPlayers").value(5))
                .andExpect(jsonPath("$.maxPlayers").value(10));
    }

    @Test
    @DisplayName("a game ID can be typed in any case, with or without stray whitespace")
    void gameIdIsCaseAndWhitespaceInsensitive() throws Exception {
        Session host = create("Host");
        String id = host.gameId();
        String mixed = id.charAt(0) + id.substring(1, 2).toLowerCase() + id.substring(2);

        List<String> spellings = List.of(id, id.toLowerCase(), mixed, "  " + id.toLowerCase() + "  ");
        for (int i = 0; i < spellings.size(); i++) {
            mvc.perform(post("/api/games/{id}/players", spellings.get(i))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("Player" + i)))
                    .andExpect(status().isCreated())
                    // However it was typed, the canonical upper-case ID comes back.
                    .andExpect(jsonPath("$.gameId").value(id));
        }

        mvc.perform(get("/api/games/{id}", id.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(id))
                .andExpect(jsonPath("$.players.length()").value(5));
    }

    @Test
    @DisplayName("duplicate names are rejected, case-insensitively")
    void duplicateNamesRejected() throws Exception {
        Session host = create("Alex");
        mvc.perform(post("/api/games/{id}/players", host.gameId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("ALEX")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));
    }

    @Test
    @DisplayName("an eleventh player is turned away")
    void gameIsCappedAtTen() throws Exception {
        List<Session> sessions = lobbyOf(10);
        mvc.perform(post("/api/games/{id}/players", sessions.getFirst().gameId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Eleven")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("GAME_FULL"));
    }

    @Test
    @DisplayName("blank and overlong names are rejected")
    void invalidNamesRejected() throws Exception {
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON).content(body("x".repeat(21))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unknown game codes 404")
    void unknownGameIs404() throws Exception {
        mvc.perform(get("/api/games/{id}", "ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    }

    @Test
    @DisplayName("leaving the lobby frees the name and passes host duty on")
    void leavingFreesTheNameAndPassesHost() throws Exception {
        List<Session> sessions = lobbyOf(5);
        Session host = sessions.getFirst();

        mvc.perform(delete("/api/games/{id}/players/me", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/games/{id}", host.gameId()))
                .andExpect(jsonPath("$.players.length()").value(4))
                .andExpect(jsonPath("$.hostName").value("Player1"));

        // The freed name can be reused.
        join(host.gameId(), "Host");
    }

    // ---------- starting ----------

    @Test
    @DisplayName("fewer than five players cannot start")
    void cannotStartBelowFive() throws Exception {
        List<Session> sessions = lobbyOf(4);
        mvc.perform(post("/api/games/{id}/start", sessions.getFirst().gameId())
                        .header(GameController.TOKEN_HEADER, sessions.getFirst().token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_PLAYERS"));
    }

    @Test
    @DisplayName("only the host can start")
    void onlyHostCanStart() throws Exception {
        List<Session> sessions = lobbyOf(5);
        mvc.perform(post("/api/games/{id}/start", sessions.get(1).gameId())
                        .header(GameController.TOKEN_HEADER, sessions.get(1).token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_HOST"));
    }

    @Test
    @DisplayName("starting deals a distinct role to every player")
    void startDealsDistinctRoles() throws Exception {
        List<Session> sessions = lobbyOf(7);
        Session host = sessions.getFirst();

        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DEALT"));

        Set<String> roles = new HashSet<>();
        for (Session session : sessions) {
            JsonNode card = me(session);
            assertThat(card.get("name").asText()).isEqualTo(session.name());
            assertThat(card.get("role").asText()).isNotBlank();
            assertThat(card.get("team").asText()).isIn("GOOD", "EVIL");
            assertThat(card.get("description").asText()).isNotBlank();
            roles.add(card.get("role").asText());
        }
        assertThat(roles).as("every player has a unique role").hasSize(7);
    }

    @Test
    @DisplayName("nobody can join once roles are dealt")
    void cannotJoinAfterStart() throws Exception {
        List<Session> sessions = lobbyOf(5);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/games/{id}/players", host.gameId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Latecomer")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ALREADY_STARTED"));
    }

    @Test
    @DisplayName("a game cannot be started twice")
    void cannotStartTwice() throws Exception {
        List<Session> sessions = lobbyOf(5);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isGone());
    }

    // ---------- role privacy and reconnect ----------

    @Test
    @DisplayName("a role is only served against its own token")
    void rolesRequireTheOwnersToken() throws Exception {
        List<Session> sessions = lobbyOf(5);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/games/{id}/me", host.gameId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_TOKEN"));
        mvc.perform(get("/api/games/{id}/me", host.gameId())
                        .header(GameController.TOKEN_HEADER, "not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the lobby never exposes anyone's token")
    void lobbyNeverLeaksTokens() throws Exception {
        List<Session> sessions = lobbyOf(5);
        MvcResult result = mvc.perform(get("/api/games/{id}", sessions.getFirst().gameId()))
                .andExpect(status().isOk()).andReturn();
        String payload = result.getResponse().getContentAsString();
        for (Session session : sessions) {
            assertThat(payload).doesNotContain(session.token());
        }
    }

    @Test
    @DisplayName("reconnecting with the same token returns the identical card")
    void reconnectReturnsSameRole() throws Exception {
        List<Session> sessions = lobbyOf(6);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());

        Session player = sessions.get(3);
        JsonNode first = me(player);
        for (int i = 0; i < 5; i++) {
            assertThat(me(player)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("before the deal, a card has a name but no role")
    void cardBeforeDealHasNoRole() throws Exception {
        Session host = create("Morgan");
        JsonNode card = me(host);
        assertThat(card.get("state").asText()).isEqualTo("LOBBY");
        assertThat(card.get("name").asText()).isEqualTo("Morgan");
        assertThat(card.get("role").isNull()).isTrue();
    }

    // ---------- audit ----------

    @Test
    @DisplayName("once open, the audit is served over HTTP with the roles included")
    void openAuditIsServedOverHttp() throws Exception {
        List<Session> sessions = lobbyOf(5);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/audit/{id}", host.gameId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(host.gameId()))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.roles.Host").isNotEmpty())
                .andExpect(jsonPath("$.roles.length()").value(5));

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.gameId == '" + host.gameId() + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("a lobby that never dealt has nothing to seal")
    void unstartedGameIsNotSealed() throws Exception {
        Session host = create("Morgan");
        mvc.perform(get("/api/audit/{id}", host.gameId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.events[0].type").value("GAME_CREATED"));
    }

    @Test
    @DisplayName("the audit records the game code, the deal time, and who got which role")
    void auditRecordsGameCodeStartTimeAndRoles() throws Exception {
        List<Session> sessions = lobbyOf(7);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());

        // Read past the seal the way the clock eventually will.
        var view = unsealedAudit.auditFor(host.gameId());
        assertThat(view.gameId()).isEqualTo(host.gameId());
        assertThat(view.startedAt()).isNotNull();
        assertThat(view.roles()).hasSize(7)
                .containsKeys(sessions.stream().map(Session::name).toArray(String[]::new));
        assertThat(view.roles().values()).doesNotHaveDuplicates();
        assertThat(view.events()).extracting(e -> e.type().name())
                .contains("GAME_CREATED", "PLAYER_JOINED", "GAME_STARTED");
    }

    @Test
    @DisplayName("opening a card is recorded once, so the host can see who is ready")
    void roleViewsAreAuditedOnce() throws Exception {
        List<Session> sessions = lobbyOf(5);
        Session host = sessions.getFirst();
        mvc.perform(post("/api/games/{id}/start", host.gameId())
                        .header(GameController.TOKEN_HEADER, host.token()))
                .andExpect(status().isOk());

        me(sessions.get(1));
        me(sessions.get(1));
        me(sessions.get(2));

        assertThat(unsealedAudit.auditFor(host.gameId()).events())
                .filteredOn(e -> e.type().name().equals("ROLE_VIEWED"))
                .extracting(e -> e.actor())
                .containsExactly("Player1", "Player2");
    }

    // ---------- caching ----------

    @Test
    @DisplayName("no response is cacheable")
    void nothingIsCacheable() throws Exception {
        Session host = create("Morgan");
        mvc.perform(get("/api/games/{id}", host.gameId()))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0"));
        mvc.perform(get("/api/health"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0"));
    }

    @Test
    @DisplayName("consecutive games in the same room deal differently")
    void consecutiveGamesDiffer() throws Exception {
        Set<String> fingerprints = new HashSet<>();
        for (int game = 0; game < 25; game++) {
            List<Session> sessions = lobbyOf(10);
            mvc.perform(post("/api/games/{id}/start", sessions.getFirst().gameId())
                            .header(GameController.TOKEN_HEADER, sessions.getFirst().token()))
                    .andExpect(status().isOk());

            List<String> assignment = new ArrayList<>();
            for (Session session : sessions) {
                assignment.add(session.name() + ":" + me(session).get("role").asText());
            }
            fingerprints.add(String.join(",", assignment));
        }
        assertThat(fingerprints).as("no two games dealt identically").hasSize(25);
    }
}
