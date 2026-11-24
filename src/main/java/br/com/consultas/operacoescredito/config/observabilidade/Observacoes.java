package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Objects;

/**
 * Observações (span + timer) padronizadas para os adapters. Dois tipos, com nomes e tags fixos para
 * que os dashboards e o tail sampling não dependam de cada adapter:
 * <ul>
 *     <li>{@link #dependencia}: chamada a um sistema externo (Valkey, fonte primária, SQS).
 *     Métrica {@code consultas.dependencia}, tags {@code dependencia} e {@code operacao};</li>
 *     <li>{@link #etapa}: passo interno da consulta (consulta GSI, batch, fan-out, filtro).
 *     Métrica {@code consultas.etapa}, tag {@code etapa}.</li>
 * </ul>
 * Só valores de baixa cardinalidade em {@code lowCardinalityKeyValue}; identificadores vão em
 * {@code highCardinalityKeyValue} (apenas no span, mascarados pelo {@link MascaramentoSpanExporter}).
 */
public final class Observacoes {

    public static final String DEPENDENCIA = "consultas.dependencia";
    public static final String ETAPA = "consultas.etapa";

    private Observacoes() {
    }

    /** Observação ainda não iniciada de uma chamada a {@code dependencia} (ex.: {@code valkey}, {@code MGET}). */
    public static Observation dependencia(ObservationRegistry registry, String dependencia, String operacao) {
        Objects.requireNonNull(dependencia, "dependencia");
        Objects.requireNonNull(operacao, "operacao");
        return Observation.createNotStarted(DEPENDENCIA, registry)
                .contextualName(dependencia + " " + operacao)
                .lowCardinalityKeyValue("dependencia", dependencia)
                .lowCardinalityKeyValue("operacao", operacao);
    }

    /** Observação ainda não iniciada de uma etapa da consulta (ex.: {@code fan_out}). */
    public static Observation etapa(ObservationRegistry registry, String etapa) {
        Objects.requireNonNull(etapa, "etapa");
        return Observation.createNotStarted(ETAPA, registry)
                .contextualName(etapa)
                .lowCardinalityKeyValue("etapa", etapa);
    }
}
