package org.apache.seatunnel.web.api;

import org.apache.seatunnel.plugin.datasource.api.plugin.DataSourceProcessorProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication(scanBasePackages = {"org.apache.seatunnel.*"})
@EnableConfigurationProperties
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
public class SeaTunnelWebApplication {

    public static void main(String[] args) {
        try {
            SpringApplication sa = new SpringApplication(SeaTunnelWebApplication.class);
            sa.run(args);
            DataSourceProcessorProvider.initialize();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
