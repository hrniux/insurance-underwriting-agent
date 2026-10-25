package com.hrniux.underwriting.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
class ManagementApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void draftsPublishesReplacesAndRetiresKnowledgeVersions() throws Exception {
        String documentId = "API-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","title":"面试演示条款","type":"PRODUCT_CLAUSE",
                                 "productCode":"PROPERTY","content":"暴雨红色风险应转人工复核。","metadata":{"source":"api-test"}}
                                """.formatted(documentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(post("/api/v1/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"%s\",\"topK\":20,\"productCode\":\"PROPERTY\"}"
                                .formatted(documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.chunk.documentId == '%s')]".formatted(documentId)).doesNotExist());

        mvc.perform(post("/api/v1/knowledge/documents/{documentId}/versions/1/publish", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.chunkCount").value(1));

        mvc.perform(get("/api/v1/knowledge/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(documentId)).exists());

        mvc.perform(post("/api/v1/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"暴雨人工复核\",\"topK\":4,\"productCode\":\"PROPERTY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].chunk.content").isNotEmpty())
                .andExpect(jsonPath("$[0].score").isNumber())
                .andExpect(jsonPath("$[0].vectorScore").isNumber())
                .andExpect(jsonPath("$[0].lexicalScore").isNumber())
                .andExpect(jsonPath("$[0].mode").isNotEmpty())
                .andExpect(jsonPath("$[0].matchedTerms").isArray());

        mvc.perform(post("/api/v1/knowledge/documents/{documentId}/versions", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"面试演示条款修订版","type":"PRODUCT_CLAUSE",
                                 "productCode":"PROPERTY","content":"台风黑色风险必须使用 replacementkeyword 新版规则。",
                                 "metadata":{"source":"api-test-v2"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(post("/api/v1/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"replacementkeyword\",\"topK\":20,\"productCode\":\"PROPERTY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.chunk.documentId == '%s')]".formatted(documentId)).doesNotExist());

        mvc.perform(post("/api/v1/knowledge/documents/{documentId}/versions/2/publish", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(get("/api/v1/knowledge/documents/{documentId}/versions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RETIRED"))
                .andExpect(jsonPath("$[1].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[1].document.metadata.source").value("api-test-v2"));

        mvc.perform(post("/api/v1/knowledge/documents/{documentId}/versions/1/publish", documentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_KNOWLEDGE_TRANSITION"));

        mvc.perform(post("/api/v1/knowledge/documents/{documentId}/versions/2/retire", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));

        mvc.perform(post("/api/v1/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"replacementkeyword\",\"topK\":20,\"productCode\":\"PROPERTY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.chunk.documentId == '%s')]".formatted(documentId)).doesNotExist());
    }

    @Test
    void evaluatesGoldenRetrievalCasesAgainstQualityThresholds() throws Exception {
        mvc.perform(post("/api/v1/knowledge/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topK": 4,
                                  "minimumRecallAtK": 1.0,
                                  "minimumMeanReciprocalRank": 1.0,
                                  "cases": [
                                    {
                                      "name": "产品条款编号",
                                      "query": "CLAUSE-PROPERTY-001 产品条款",
                                      "expectedDocumentIds": ["CLAUSE-PROPERTY-001"],
                                      "documentType": "PRODUCT_CLAUSE",
                                      "productCode": "PROPERTY"
                                    },
                                    {
                                      "name": "暴雨核保规则",
                                      "query": "RULE-RAIN-001 暴雨洪水核保规则",
                                      "expectedDocumentIds": ["RULE-RAIN-001"],
                                      "documentType": "UNDERWRITING_RULE",
                                      "productCode": "PROPERTY"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(2))
                .andExpect(jsonPath("$.recallAtK").value(1.0))
                .andExpect(jsonPath("$.meanReciprocalRank").value(1.0))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.cases[0].firstRelevantRank").value(1))
                .andExpect(jsonPath("$.cases[1].retrievedDocumentIds[0]").value("RULE-RAIN-001"));
    }

    @Test
    void rejectsAnEmptyGoldenEvaluationSet() throws Exception {
        mvc.perform(post("/api/v1/knowledge/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\":4,\"cases\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void listsCreatesActivatesAndPreviewsPromptVersions() throws Exception {
        String code = "api-template-" + UUID.randomUUID();

        mvc.perform(get("/api/v1/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'underwriting-analysis')]").exists());

        String created = mvc.perform(post("/api/v1/prompts/{code}/versions", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"问题：{{question}}\",\"requiredVariables\":[\"question\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(created, "$.version");

        mvc.perform(post("/api/v1/prompts/{code}/versions/{version}/activate", code, version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(post("/api/v1/prompts/{code}/preview", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"question\":\"是否承保\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rendered").value("问题：是否承保"));
    }

    @Test
    void createsPaddedPromptCodesWithACanonicalLocationAndVersionHistory() throws Exception {
        String code = "api-canonical-" + UUID.randomUUID();
        String body = "{\"body\":\"问题：{{question}}\",\"requiredVariables\":[\"question\"]}";
        mvc.perform(post("/api/v1/prompts/{code}/versions", code)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/prompts/{code}/versions", " " + code + " ")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/prompts/" + code + "/versions/2"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/prompts/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listsAndInvokesTheSharedToolRegistry() throws Exception {
        mvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@ == 'GET_POLICY')]").exists())
                .andExpect(jsonPath("$[?(@ == 'VALIDATE_RULES')]").exists());

        mvc.perform(post("/api/v1/tools/GET_POLICY/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyNo\":\"P-1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.policyNo").value("P-1001"))
                .andExpect(jsonPath("$.trace.status").value("SUCCESS"));
    }
}
