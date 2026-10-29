package com.desafio.activation.application;

/** Porta para o serviço externo de ativação. */
public interface ActivationProvider {

    /** Nunca lança exceção por erro de negócio/rede: tudo vira um {@link ProviderResult}. */
    ProviderResult activate(ProviderActivationCommand command);
}
