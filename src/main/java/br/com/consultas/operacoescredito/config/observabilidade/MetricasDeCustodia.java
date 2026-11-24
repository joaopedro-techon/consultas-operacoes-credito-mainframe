package br.com.consultas.operacoescredito.config.observabilidade;

import br.com.consultas.operacoescredito.domain.Custodia;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Métricas da descoberta de custódia (ADR-0012), nas duas rotas:
 * <ul>
 *     <li>{@value #DESCOBERTA}: uma contagem por custódia consultada, com {@code rota}, {@code custodia} e
 *     {@code resultado} ({@code encontrada}, {@code nao_encontrada}, {@code indisponivel}) e uma por consulta em
 *     que a custódia veio do cache ({@code resultado=cache});</li>
 *     <li>{@value #DUPLICADA}: operação encontrada em mais de uma custódia, com {@code rota} (e log WARN).</li>
 * </ul>
 * Cardinalidade baixa: 2 rotas × 4 custódias × 4 resultados.
 */
public final class MetricasDeCustodia {

    public static final String DESCOBERTA = "consultas.custodia.descoberta";
    public static final String DUPLICADA = "consultas.custodia.duplicada";

    private static final Logger log = LoggerFactory.getLogger(MetricasDeCustodia.class);

    /** Resultado da consulta a uma custódia. */
    public enum Resultado {
        ENCONTRADA, NAO_ENCONTRADA, INDISPONIVEL, CACHE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final String rota;

    /** @param rota valor da tag {@code rota} (sempre {@code fonte_primaria} neste serviço) */
    public MetricasDeCustodia(MeterRegistry registry, String rota) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.rota = Objects.requireNonNull(rota, "rota");
    }

    public void contar(Custodia custodia, Resultado resultado) {
        Counter.builder(DESCOBERTA)
                .description("Custódias consultadas na descoberta de custódia, por resultado")
                .tag("rota", rota)
                .tag("custodia", custodia.codigo())
                .tag("resultado", resultado.tag())
                .register(registry)
                .increment();
    }

    /** Operação em mais de uma custódia: conta, loga WARN e informa qual venceu pela precedência. */
    public void duplicada(long idOperacao, Collection<Custodia> encontradas, Custodia vencedora) {
        Counter.builder(DUPLICADA)
                .description("Operações encontradas em mais de uma custódia")
                .tag("rota", rota)
                .register(registry)
                .increment();
        log.warn("Operação {} encontrada em mais de uma custódia ({}) na {}; vale {} pela precedência",
                idOperacao, encontradas.stream().map(Custodia::codigo).collect(Collectors.joining(",")), rota,
                vencedora.codigo());
    }
}
