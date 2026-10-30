package org.apache.seatunnel.web.api.config;

import org.apache.seatunnel.web.engine.client.rest.SeaTunnelClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(SeaTunnelClientProperties.class)
public class SeaTunnelClientConfig {

    @Bean
    public RestTemplate seaTunnelRestTemplate(SeaTunnelClientProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.getConnectTimeoutMs());
        f.setReadTimeout(props.getReadTimeoutMs());
        return new RestTemplate(f);
    }

    @Bean
    public RestTemplate seaTunnelJobSubmitRestTemplate(SeaTunnelClientProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.getConnectTimeoutMs());
        f.setReadTimeout(Math.max(1_000, props.getJobSubmitReadTimeoutMs()));
        return new RestTemplate(f);
    }

}
