package com.facedesk.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/** 加载实际 YAML，保证无参数启动与显式集群启动选择各自的路由。 */
class GatewayProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test void defaultStartupProxiesSingleApiWithoutNacos() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("http://127.0.0.1:8090", context.getEnvironment().getProperty("spring.cloud.gateway.routes[0].uri"));
            assertEquals("false", context.getEnvironment().getProperty("spring.cloud.nacos.discovery.enabled"));
            assertEquals("Path=/api/**", context.getEnvironment().getProperty("spring.cloud.gateway.routes[0].predicates[0]"));
        });
    }

    @Test void clusterStartupUsesNacosServiceDiscovery() {
        runner.withPropertyValues("spring.profiles.active=cluster").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("lb://facedesk-api", context.getEnvironment().getProperty("spring.cloud.gateway.routes[0].uri"));
            assertEquals("true", context.getEnvironment().getProperty("spring.cloud.nacos.discovery.enabled"));
        });
    }

    @Test void standaloneBackendCanBeOverridden() {
        runner.withPropertyValues("FACEDESK_API_URI=http://127.0.0.1:18090").run(context -> {
            assertEquals("http://127.0.0.1:18090", context.getEnvironment().getProperty("spring.cloud.gateway.routes[0].uri"));
        });
    }
}
