package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.Plan;
import com.zhiyun.rag.VenueCatalog;
import com.zhiyun.rag.VenueQuery;
import com.zhiyun.repo.PlanRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TargetVenueReviewTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    PlanRepo planRepo;

    @Test
    void startReviewPersistsAclAndPublicQueryContainsAcl() throws Exception {
        seedPlan();
        String token = register("venue-acl-" + System.nanoTime() + "@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String paper = """
                # Introduction
                This draft is intended for NeurIPS and prepared on US Letter.
                Firstly, we propose a novel method that outperforms all prior work.
                """;
        MockMultipartFile file = new MockMultipartFile("file", "paper.md", "text/markdown", paper.getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        JsonNode task = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"QUICK_REVIEW\",\"targetVenue\":\"ACL\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(task.get("targetVenue").asText()).isEqualTo("ACL");
        assertThat(task.get("status").asText()).isIn(Codes.DONE, Codes.WAITING_ACCEPT, Codes.FAILED);

        String query = VenueQuery.publicQuery(task.get("targetVenue").asText(), "Letter draft", paper,
                "academic writing humanizer 英文润色 中文润色 中译英 LaTeX 转义 Firstly In conclusion");
        assertThat(query).startsWith("ACL ");
        assertThat(query).contains("ACL");
        assertThat(query).doesNotStartWith("NeurIPS ");

        mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\",\"targetVenue\":\"NotARealVenue\"}"))
                .andExpect(status().isBadRequest());

        JsonNode catalog = mapper.readTree(mvc.perform(get("/api/venues?q=acl").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(catalog.get("venues").toString()).contains("ACL");
        assertThat(VenueCatalog.canonical("ACL")).isEqualTo("ACL");
    }

    private String register(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"U\"}";
        return mapper.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void seedPlan() {
        if (planRepo.count() > 0) {
            return;
        }
        Plan plan = new Plan();
        plan.setCode("starter");
        plan.setName("轻量套餐");
        plan.setQuotaAmount(36);
        plan.setPriceCents(2900);
        plan.setDescription("test");
        planRepo.save(plan);
    }
}
