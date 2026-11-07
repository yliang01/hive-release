package cc.cc3c.hive.release.publisher;

import cc.cc3c.hive.encryption.HiveEncryptionAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNotNull;

public class ReleaseAutoConfigurationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = HiveEncryptionAutoConfiguration.class)
    static class TestApplication { }

    @Test
    public void discoversPublisherWithoutReleaseCredentials() {
        var application = new SpringApplication(TestApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run("--spring.main.banner-mode=off")) {
            assertNotNull(context.getBean(ReleasePublisher.class));
        }
    }
}
