package com.hrniux.underwriting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.hrniux.underwriting.prompt.PromptTemplateRepository;
import com.hrniux.underwriting.prompt.PromptTemplateService;
import com.hrniux.underwriting.rag.KnowledgeVersionRepository;

@SpringBootTest
class ResourceLocationApiIntegrationTest {

    private static final String PROMPT_BODY = "{\"body\":\"Question {{question}}\",\"requiredVariables\":[\"question\"]}";

    @Autowired private WebApplicationContext context;
    @Autowired private KnowledgeVersionRepository knowledgeVersions;
    @Autowired private PromptTemplateRepository promptVersions;
    @Autowired private PromptTemplateService prompts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"with space", "知识条款", "with?query", "with#fragment", "with%percent", "with;matrix"})
    void returnsRetrievableLocationsForKnowledgeDraftAndReplacementVersion(String suffix) throws Exception {
        String id = "LOC-" + UUID.randomUUID() + "-" + suffix;
        String location = mvc.perform(post("/api/v1/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON).content(document(id)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        URI uri = URI.create(location);
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isNull();
        assertThat(uri.toASCIIString()).doesNotContain(" ");
        mvc.perform(get(uri)).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(URI.create(location + "/publish"))).andExpect(status().isOk());
        URI collection = path("/api/v1/knowledge/documents/" + id + "/versions");
        String replacement = mvc.perform(post(collection).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"第二版\",\"type\":\"PRODUCT_CLAUSE\",\"productCode\":\"PROPERTY\",\"content\":\"第二版条款。\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(get(URI.create(replacement))).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.version").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "with space", "核保提示词", "with?query", "with#fragment", "with%percent", "with;matrix"})
    void returnsRetrievablePromptVersionLocationsAndPreservesTheVersionCollection(String suffix) throws Exception {
        String code = "LOC-" + UUID.randomUUID() + "-" + suffix;
        URI create = path("/api/v1/prompts/" + code + "/versions");
        String location = mvc.perform(post(create).contentType(MediaType.APPLICATION_JSON).content(PROMPT_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        URI uri = URI.create(location);
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isNull();
        mvc.perform(get(uri)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.version").value(1));
        String second = mvc.perform(post(create).contentType(MediaType.APPLICATION_JSON).content(PROMPT_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(get(URI.create(second))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(path("/api/v1/prompts/" + code))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].version").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad/slash", "bad\\backslash", ".", "..", "bad\ncontrol"})
    void rejectsUnaddressableIdentifiersBeforePersistingKnowledgeOrPromptVersions(String invalid) throws Exception {
        String id = invalid.equals(".") || invalid.equals("..") ? invalid : invalid + UUID.randomUUID();
        mvc.perform(post("/api/v1/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content(document(id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(knowledgeVersions.findByDocumentId(id)).isEmpty();
        assertThatThrownBy(() -> prompts.createVersion(id, "Question {{question}}", Set.of("question")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(promptVersions.findByCode(id)).isEmpty();
    }

    @Test
    void reportsAMissingPromptVersionWithTheExistingDomainError() throws Exception {
        mvc.perform(get("/api/v1/prompts/underwriting-analysis/versions/2147483647"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROMPT_VERSION_NOT_FOUND"));
    }

    private URI path(String value) throws Exception {
        return URI.create(new URI(null, null, value, null).toASCIIString().replace(";", "%3B"));
    }

    private String document(String id) {
        String escaped = id.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"id\":\"" + escaped + "\",\"title\":\"条款\",\"type\":\"PRODUCT_CLAUSE\","
                + "\"productCode\":\"PROPERTY\",\"content\":\"核保条款。\",\"metadata\":{}}";
    }
}
