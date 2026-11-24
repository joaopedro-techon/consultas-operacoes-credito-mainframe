package br.com.consultas.operacoescredito.config.observabilidade;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Logs WARN dos eventos de resiliência de todas as instâncias (fonte primária por custódia e rota,
 * Valkey, SQS), inclusive as criadas depois da subida:
 * <ul>
 *     <li>toda tentativa de retry: instância, tentativa N de M, espera e motivo; e o retry esgotado;</li>
 *     <li>toda transição de estado do circuit breaker (fechado, aberto, meio-aberto...), com o motivo: taxas de
 *     falha e de lentidão e os limiares, ou o fim da espera no estado aberto;</li>
 *     <li>recusas (circuito aberto, bulkhead cheio, rate limiter sem permissão), amostradas: no máximo uma linha
 *     por instância a cada {@value #INTERVALO_AMOSTRAGEM_SEGUNDOS} s, com quantas foram suprimidas.</li>
 * </ul>
 * O logger é {@value #LOGGER}, para que os alertas possam filtrá-lo.
 */
@Configuration(proxyBeanMethods = false)
class EventosDeResilienciaConfig {

    static final String LOGGER = "resiliencia";
    static final long INTERVALO_AMOSTRAGEM_SEGUNDOS = 5;

    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    private final AmostradorDeLog amostrador =
            new AmostradorDeLog(Duration.ofSeconds(INTERVALO_AMOSTRAGEM_SEGUNDOS), Clock.systemUTC());

    @Bean
    RegistryEventConsumer<CircuitBreaker> logDeEventosDoCircuitBreaker() {
        return consumidor(circuito -> {
            var eventos = circuito.getEventPublisher();
            eventos.onStateTransition(evento -> log.warn("Circuit breaker {} mudou de {} para {}: {}",
                    evento.getCircuitBreakerName(), estado(evento.getStateTransition().getFromState()),
                    estado(evento.getStateTransition().getToState()), motivo(circuito, evento)));
            eventos.onCallNotPermitted(evento -> amostrado("cb:" + evento.getCircuitBreakerName(), suprimidos ->
                    log.warn("Circuit breaker {} {} recusou chamada{}", evento.getCircuitBreakerName(),
                            estado(circuito.getState()), suprimidas(suprimidos))));
        });
    }

    @Bean
    RegistryEventConsumer<Retry> logDeEventosDoRetry() {
        return consumidor(retry -> {
            var eventos = retry.getEventPublisher();
            eventos.onRetry(evento -> log.warn("Retry {}: tentativa {} de {} em {} ms, motivo: {}",
                    evento.getName(), evento.getNumberOfRetryAttempts() + 1,
                    retry.getRetryConfig().getMaxAttempts(), evento.getWaitInterval().toMillis(),
                    descrever(evento.getLastThrowable())));
            eventos.onError(evento -> log.warn("Retry {} esgotado após {} tentativas, motivo: {}", evento.getName(),
                    evento.getNumberOfRetryAttempts(), descrever(evento.getLastThrowable())));
        });
    }

    @Bean
    RegistryEventConsumer<Bulkhead> logDeEventosDoBulkhead() {
        return consumidor(bulkhead -> bulkhead.getEventPublisher().onCallRejected(evento ->
                amostrado("bulkhead:" + evento.getBulkheadName(), suprimidos ->
                        log.warn("Bulkhead {} cheio ({} chamadas simultâneas), chamada recusada{}",
                                evento.getBulkheadName(), bulkhead.getBulkheadConfig().getMaxConcurrentCalls(),
                                suprimidas(suprimidos)))));
    }

    @Bean
    RegistryEventConsumer<RateLimiter> logDeEventosDoRateLimiter() {
        return consumidor(limitador -> limitador.getEventPublisher().onFailure(evento ->
                amostrado("ratelimiter:" + evento.getRateLimiterName(), suprimidos ->
                        log.warn("Rate limiter {} sem permissões ({} por {} ms), chamada recusada{}",
                                evento.getRateLimiterName(), limitador.getRateLimiterConfig().getLimitForPeriod(),
                                limitador.getRateLimiterConfig().getLimitRefreshPeriod().toMillis(),
                                suprimidas(suprimidos)))));
    }

    /** Por que o circuito mudou de estado. */
    static String motivo(CircuitBreaker circuito, CircuitBreakerOnStateTransitionEvent evento) {
        var config = circuito.getCircuitBreakerConfig();
        var metricas = circuito.getMetrics();
        var taxas = String.format(Locale.ROOT,
                "taxa de falhas %.1f%% (limiar %.1f%%), taxa de lentidão %.1f%% (limiar %.1f%%), %d chamadas na janela",
                metricas.getFailureRate(), config.getFailureRateThreshold(), metricas.getSlowCallRate(),
                config.getSlowCallRateThreshold(), metricas.getNumberOfBufferedCalls());
        return switch (evento.getStateTransition().getToState()) {
            case OPEN -> "limiar atingido: " + taxas;
            case HALF_OPEN -> "fim da espera no estado aberto; até " + config.getPermittedNumberOfCallsInHalfOpenState()
                    + " chamadas de sonda";
            case CLOSED -> "sondas bem-sucedidas: " + taxas;
            default -> "transição " + evento.getStateTransition() + "; " + taxas;
        };
    }

    private static String estado(CircuitBreaker.State estado) {
        return switch (estado) {
            case CLOSED -> "fechado (CLOSED)";
            case OPEN -> "aberto (OPEN)";
            case HALF_OPEN -> "meio-aberto (HALF_OPEN)";
            default -> estado.name();
        };
    }

    private static String descrever(Throwable erro) {
        return erro == null ? "desconhecido" : erro.getClass().getSimpleName() + ": " + erro.getMessage();
    }

    private static String suprimidas(long suprimidos) {
        return suprimidos == 0 ? "" : " (+" + suprimidos + " recusas suprimidas nos últimos "
                + INTERVALO_AMOSTRAGEM_SEGUNDOS + " s)";
    }

    private void amostrado(String chave, java.util.function.LongConsumer logar) {
        amostrador.permitir(chave).ifPresent(logar);
    }

    /** Liga os listeners em cada instância criada (ou substituída) no registro. */
    private static <T> RegistryEventConsumer<T> consumidor(Consumer<T> ligar) {
        return new RegistryEventConsumer<>() {

            @Override
            public void onEntryAddedEvent(EntryAddedEvent<T> evento) {
                ligar.accept(evento.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<T> evento) {
                // nada a desligar: a instância removida deixa de publicar eventos
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<T> evento) {
                ligar.accept(evento.getNewEntry());
            }
        };
    }
}
