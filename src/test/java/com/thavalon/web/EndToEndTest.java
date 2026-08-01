package com.thavalon.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thavalon.game.Game;
import com.thavalon.game.GameStore;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end probes for the seams between layers: restored state, concurrency, and malformed
 * input. These are the paths the per-layer tests do not reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private GameStore store;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("thavalon-e2e");
        dir.toFile().deleteOnExit();
        registry.add("thavalon.data-dir", dir::toString);
        registry.add("thavalon.audit-unlock-after", () -> "PT0S");
    }

    private JsonNode create(String gameId, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Api.CreateRequest(name, gameId))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /**
     * Regression: a snapshot written before {@code auditKey} existed restores with a null key,
     * and every audit write then rejects it — the game becomes permanently unjoinable. This was
     * live, and surfaced as "400 Invalid audit key" on join.
     */
    @Test
    @DisplayName("a game restored without an audit key is still fully playable")
    void restoredGameWithoutAuditKeyStillWorks() throws Exception {
        JsonNode created = create("LEGACY", "Host");
        String id = created.get("gameId").asText();

        // Simulate a snapshot from before the field existed.
        Game restored = store.find(id).orElseThrow();
        restored.setAuditKey(null);

        mvc.perform(post("/api/games/{id}/players", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Api.NameRequest("Joiner"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("two hosts racing for the same game ID cannot both win it")
    void concurrentCreatesCannotClobberEachOther() throws Exception {
        int racers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Callable<Integer>> attempts = IntStream.range(0, racers)
                .mapToObj(i -> (Callable<Integer>) () -> mvc.perform(post("/api/games")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(
                                        new Api.CreateRequest("Host" + i, "RACE"))))
                        .andReturn().getResponse().getStatus())
                .toList();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(attempts)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201)
                .as("exactly one host may claim an ID").hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409)
                .as("the rest are told it is taken").hasSize(racers - 1);
        // The winner's lobby survived intact rather than being overwritten.
        mvc.perform(get("/api/games/{id}", "RACE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1));
    }

    @Test
    @DisplayName("concurrent joins never lose a player or duplicate a name")
    void concurrentJoinsAreConsistent() throws Exception {
        create("CROWD", "Host");
        int joiners = 20;                       // more than the cap, deliberately
        ExecutorService pool = Executors.newFixedThreadPool(8);
        Set<String> tokens = ConcurrentHashMap.newKeySet();

        List<Callable<Integer>> attempts = IntStream.range(0, joiners)
                .mapToObj(i -> (Callable<Integer>) () -> {
                    MvcResult r = mvc.perform(post("/api/games/{id}/players", "CROWD")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new Api.NameRequest("P" + i)))).andReturn();
                    if (r.getResponse().getStatus() == 201) {
                        tokens.add(json.readTree(r.getResponse().getContentAsString())
                                .get("playerToken").asText());
                    }
                    return r.getResponse().getStatus();
                })
                .toList();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(attempts)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        long admitted = statuses.stream().filter(s -> s == 201).count();
        assertThat(admitted).as("the cap holds under concurrency").isEqualTo(9);   // 10 minus host
        assertThat(tokens).as("every admitted player got a distinct token").hasSize(9);

        MvcResult lobby = mvc.perform(get("/api/games/{id}", "CROWD")).andReturn();
        JsonNode players = json.readTree(lobby.getResponse().getContentAsString()).get("players");
        assertThat(players).hasSize(10);
        assertThat(players.findValuesAsText("")).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("malformed requests are client errors, not server errors")
    void malformedRequestsAreClientErrors() throws Exception {
        // Empty body.
        assertThat(mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus()).isBetween(400, 499);
        // Not JSON at all.
        assertThat(mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andReturn().getResponse().getStatus()).isBetween(400, 499);
        // Wrong content type.
        assertThat(mvc.perform(post("/api/games")
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andReturn().getResponse().getStatus()).isBetween(400, 499);
        // Unknown route.
        assertThat(mvc.perform(get("/api/nope")).andReturn().getResponse().getStatus())
                .isBetween(400, 499);
    }

    @Test
    @DisplayName("names with awkward characters round-trip safely")
    void awkwardNamesRoundTrip() throws Exception {
        create("ODDNAMES", "Host");
        // All within the 20-character limit; the point is the characters, not the length.
        List<String> names = List.of(
                "<img src=x>", "Ann \"Boss\"", "Zoë", "名前", "a'b", "back\\slash");
        for (String name : names) {
            mvc.perform(post("/api/games/{id}/players", "ODDNAMES")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new Api.NameRequest(name))))
                    .andExpect(status().isCreated());
        }
        MvcResult lobby = mvc.perform(get("/api/games/{id}", "ODDNAMES")).andReturn();
        // Read as UTF-8 explicitly: MockHttpServletResponse decodes as ISO-8859-1 by default,
        // which mangles non-ASCII names that the real HTTP stack handles correctly.
        JsonNode players = json.readTree(
                lobby.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("players");

        assertThat(players).hasSize(names.size() + 1);
        // Names come back byte-identical: not HTML-escaped, not stripped, not mangled. Markup is
        // safe because the pages insert names with textContent, never innerHTML.
        List<String> returned = new ArrayList<>();
        players.forEach(p -> returned.add(p.asText()));
        assertThat(returned).containsAll(names);
    }

    @Test
    @DisplayName("runs of whitespace inside a name are collapsed to one space")
    void whitespaceInNamesIsNormalised() throws Exception {
        create("SPACES", "Host");
        MvcResult r = mvc.perform(post("/api/games/{id}/players", "SPACES")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Api.NameRequest("  P\t\t  spaced  "))))
                .andExpect(status().isCreated()).andReturn();

        assertThat(json.readTree(r.getResponse().getContentAsString()).get("name").asText())
                .isEqualTo("P spaced");

        // And so two names differing only in spacing collide, rather than both being admitted.
        mvc.perform(post("/api/games/{id}/players", "SPACES")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Api.NameRequest("P   spaced"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a game ID differing only by case is the same game, not a second one")
    void caseVariantsDoNotCreateSeparateGames() throws Exception {
        create("CaseTest", "Host");
        // Creating the lower-case spelling must collide, not silently make a parallel game.
        mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Api.CreateRequest("Other", "casetest"))))
                .andExpect(status().isConflict());
    }
}
