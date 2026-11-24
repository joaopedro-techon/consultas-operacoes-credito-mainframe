package br.com.consultas.operacoescredito.adapters.out.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cache de custódia no Valkey (ADR-0012). A conexão com o Valkey (host, porta, timeouts) fica em
 * {@code spring.data.redis.*}; o circuit breaker em {@code resilience4j.circuitbreaker.instances.valkey}.
 *
 * @param habilitado              {@code false} desliga o cache (toda consulta refaz a descoberta de custódia)
 * @param chavesPorComando        {@code SET}s por rodada de gravação: gravações grandes viram vários comandos
 *                                pequenos em pipeline, cada um dentro do timeout de comando, sem segurar o Valkey
 *                                (single-thread) numa rodada gigante
 * @param maxChavesPorConsulta    acima disso (custódias gravadas de uma vez) a gravação é descartada
 * @param prazoMinimoRestante     com menos que isso do prazo da requisição a leitura no cache é pulada; deve ser
 *                                maior que {@code spring.data.redis.timeout} para que um timeout do Valkey nunca
 *                                seja encurtado pelo prazo
 * @param maxGravacoesSimultaneas gravações em andamento (fora da requisição, em virtual threads); com todas as
 *                                vagas ocupadas, a gravação nova é descartada (o cache é só otimização)
 * @param ttlCustodia             tempo de vida da custódia de uma operação; contrato com o serviço da visão
 *                                consolidada, que usa a mesma chave no mesmo Valkey (ADR-0017)
 */
@ConfigurationProperties("consultas.cache")
public record CacheProperties(@DefaultValue("true") boolean habilitado,
                              @DefaultValue("100") int chavesPorComando,
                              @DefaultValue("500") int maxChavesPorConsulta,
                              @DefaultValue("20ms") Duration prazoMinimoRestante,
                              @DefaultValue("32") int maxGravacoesSimultaneas,
                              @DefaultValue("24h") Duration ttlCustodia) {

    public CacheProperties {
        if (ttlCustodia.isNegative() || ttlCustodia.isZero()) {
            throw new IllegalArgumentException("consultas.cache.ttl-custodia deve ser positivo: " + ttlCustodia);
        }
        if (chavesPorComando < 1) {
            throw new IllegalArgumentException("consultas.cache.chaves-por-comando deve ser ao menos 1");
        }
        if (maxChavesPorConsulta < 1) {
            throw new IllegalArgumentException("consultas.cache.max-chaves-por-consulta deve ser ao menos 1");
        }
        if (prazoMinimoRestante.isNegative()) {
            throw new IllegalArgumentException("consultas.cache.prazo-minimo-restante não pode ser negativo");
        }
        if (maxGravacoesSimultaneas < 1) {
            throw new IllegalArgumentException("consultas.cache.max-gravacoes-simultaneas deve ser ao menos 1");
        }
    }
}
