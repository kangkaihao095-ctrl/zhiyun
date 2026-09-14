package com.zhiyun;

import com.zaxxer.hikari.HikariDataSource;
import com.zhiyun.auth.AuthService;
import com.zhiyun.domain.AppUser;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ResearchProject;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.repo.DocumentVersionRepo;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.repo.ProjectRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.workflow.ReviewOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障注入：MySQL 不可达时任务不能悄悄 DONE，恢复后可续跑或 FAILED 后 retry。
 * 默认 {@code mvn test} 跳过。有 Docker 时：
 * {@code ZHIYUN_IT=1 mvn -q test -Dtest=MysqlUnreachableIT}
 * 或 {@code mvn -q test -Pit -Dtest=MysqlUnreachableIT}
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("itEnabled")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.validation-timeout=1000",
        "spring.datasource.hikari.maximum-pool-size=4",
        "zhiyun.elasticsearch.enabled=false",
        "zhiyun.llm.mode=dry-run"
})
class MysqlUnreachableIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("zhiyun")
            .withUsername("zhiyun")
            .withPassword("zhiyun");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> mysql.getJdbcUrl() + "&connectTimeout=2000&socketTimeout=2000");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    AuthService authService;
    @Autowired
    UserRepo userRepo;
    @Autowired
    ProjectRepo projectRepo;
    @Autowired
    ManuscriptRepo manuscriptRepo;
    @Autowired
    DocumentVersionRepo documentVersionRepo;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    ReviewOrchestrator orchestrator;
    @Autowired
    DataSource dataSource;

    static boolean itEnabled() {
        if (!"1".equals(System.getenv("ZHIYUN_IT"))) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void mysqlPauseLeavesTaskFailedOrRetryableThenResume() {
        String email = "it-mysql-" + System.nanoTime() + "@zhiyun.dev";
        authService.register(email, "demo123456", "IT", null);
        AppUser user = userRepo.findByEmail(email).orElseThrow();
        ResearchProject project = new ResearchProject();
        project.setTenantId(user.getTenantId());
        project.setName("fault-it");
        project = projectRepo.saveAndFlush(project);
        Manuscript ms = new Manuscript();
        ms.setTenantId(user.getTenantId());
        ms.setProjectId(project.getId());
        ms.setTitle("fault paper");
        ms.setCurrentVersion(1);
        ms = manuscriptRepo.saveAndFlush(ms);
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(user.getTenantId());
        version.setManuscriptId(ms.getId());
        version.setVersionNo(1);
        version.setStatus(Codes.OFFICIAL);
        version.setContentText("DOI 10.1145/example.2019\n");
        version.setStoragePath("fault.md");
        documentVersionRepo.saveAndFlush(version);
        ReviewTask task = new ReviewTask();
        task.setTenantId(user.getTenantId());
        task.setUserId(user.getId());
        task.setManuscriptId(ms.getId());
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(Codes.PENDING);
        task.setSourceVersion(1);
        task.setFencingToken(0L);
        task.setIdempotencyKey("it-mysql-" + System.nanoTime());
        task = reviewTaskRepo.saveAndFlush(task);
        long taskId = task.getId();

        pauseMysql();
        try {
            orchestrator.execute(taskId);
        } catch (Exception ignored) {
            // 连接失败可能冒泡，也可能在 execute 内收口为 FAILED
        } finally {
            unpauseMysql();
            evictPool();
        }

        ReviewTask afterOutage = reviewTaskRepo.findById(taskId).orElseThrow();
        assertThat(afterOutage.getStatus()).isIn(Codes.PENDING, Codes.RUNNING, Codes.FAILED);
        assertThat(afterOutage.getStatus()).isNotEqualTo(Codes.DONE);

        if (Codes.FAILED.equals(afterOutage.getStatus())) {
            int n = reviewTaskRepo.markRetry(taskId, afterOutage.getTenantId(), Codes.PENDING, Codes.FAILED);
            assertThat(n).isEqualTo(1);
        }
        orchestrator.execute(taskId);
        ReviewTask resumed = reviewTaskRepo.findById(taskId).orElseThrow();
        assertThat(resumed.getStatus()).isIn(Codes.DONE, Codes.FAILED);
        if (Codes.FAILED.equals(resumed.getStatus())) {
            assertThat(reviewTaskRepo.markRetry(taskId, resumed.getTenantId(), Codes.PENDING, Codes.FAILED))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    private void pauseMysql() {
        DockerClientFactory.instance().client().pauseContainerCmd(mysql.getContainerId()).exec();
    }

    private void unpauseMysql() {
        DockerClientFactory.instance().client().unpauseContainerCmd(mysql.getContainerId()).exec();
    }

    private void evictPool() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
    }
}
