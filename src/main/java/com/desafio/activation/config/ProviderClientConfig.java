package com.desafio.activation.config;

import com.desafio.activation.infrastructure.provider.ActivationProvider;
import com.desafio.activation.infrastructure.provider.HttpActivationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
public class ProviderClientConfig {

    // Builder do Boot (e não RestClient.create) para herdar métricas e propagação do traceparent.
    @Bean
    public ActivationProvider activationProvider(RestClient.Builder builder, ProviderProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        var clientBuilder = builder.clone()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory);
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return new HttpActivationProvider(clientBuilder.build());
    }
}
