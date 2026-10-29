package com.desafio.activation.config;

import com.desafio.activation.application.RetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadLocalRandom;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({DispatchProperties.class, ProviderProperties.class})
public class DispatchConfig {

    @Bean
    RetryPolicy retryPolicy(DispatchProperties p) {
        return new RetryPolicy(p.maxAttempts(), p.initialBackoff(), p.backoffMultiplier(),
                p.maxBackoff(), p.maxRetryAfter(), () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Pool limitado para chamadas ao provedor: protege o provedor e a própria aplicação.
     * O decorator propaga o contexto (traceId) da requisição HTTP para a thread do envio,
     * então o mesmo traceId aparece no log da API e da chamada ao provedor.
     */
    @Bean
    ThreadPoolTaskExecutor providerDispatchExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("provider-dispatch-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
