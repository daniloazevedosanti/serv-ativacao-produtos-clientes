package com.desafio.activation.config;

import com.desafio.activation.application.ActivationProvider;
import com.desafio.activation.infrastructure.provider.HttpActivationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
public class ProviderClientConfig {

    /**
     * Usa o {@link RestClient.Builder} do Spring Boot: já vem com observabilidade
     * (métrica {@code http.client.requests} e propagação do header {@code traceparent}).
     * Timeouts explícitos: sem eles, uma lentidão do provedor seguraria threads indefinidamente.
     */
    @Bean
    ActivationProvider activationProvider(RestClient.Builder builder, ProviderProperties properties) {
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
