package cc.cc3c.hive.release.publisher;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ReleaseProperties.class)
public class ReleaseAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ReleasePublisher releasePublisher(ReleaseProperties properties) {
        return new ReleasePublisher(properties);
    }
}
