package com.desafio.activation.config;

import com.desafio.activation.web.TraceIdProvider;
import com.desafio.activation.web.filter.ProblemResponseWriter;
import com.desafio.activation.web.filter.RateLimitFilter;
import com.desafio.activation.web.filter.RequestBodyLimitFilter;
import com.desafio.activation.web.filter.TraceIdResponseFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WebFilterConfig.RequestLimitProperties.class, WebFilterConfig.RateLimitProperties.class})
public class WebFilterConfig {

    // Logo depois do filtro de observação, que é quem abre o trace.
    @Bean
    FilterRegistrationBean<TraceIdResponseFilter> traceIdResponseFilter(TraceIdProvider traceIdProvider) {
        var registration = new FilterRegistrationBean<>(new TraceIdResponseFilter(traceIdProvider));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    // Antes da segurança: corpo gigante não deveria custar validação de token.
    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(RequestLimitProperties properties,
                                                                         ProblemResponseWriter writer) {
        var registration = new FilterRegistrationBean<>(
                new RequestBodyLimitFilter(properties.maxBodySize().toBytes(), writer));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    // Depois da segurança, porque a chave do limite é o client_id do token.
    @Bean
    @ConditionalOnProperty(name = "activation.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties,
                                                            ProblemResponseWriter writer) {
        var registration = new FilterRegistrationBean<>(
                new RateLimitFilter(properties.requestsPerSecond(), properties.burst(), writer));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    @ConfigurationProperties("activation.http")
    public record RequestLimitProperties(DataSize maxBodySize) {
        public RequestLimitProperties {
            if (maxBodySize == null) {
                maxBodySize = DataSize.ofKilobytes(16);
            }
        }
    }

    @ConfigurationProperties("activation.rate-limit")
    public record RateLimitProperties(boolean enabled, int requestsPerSecond, int burst) {
    }
}
