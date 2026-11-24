package br.com.consultas.operacoescredito.adapters.in.web;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Tags das requisições HTTP ({@code http.server.requests} e span raiz). Além das padrão do Spring
 * ({@code method}, {@code uri} como template, {@code status}, {@code outcome}, {@code exception}):
 * <ul>
 *     <li>baixa cardinalidade (métricas e span): {@code consumidor}, {@code tipo_consulta}, {@code rota};</li>
 *     <li>alta cardinalidade (só span): {@code correlation_id} e os identificadores da consulta, que
 *     saem mascarados quando são dados pessoais ({@code MascaramentoSpanExporter}).</li>
 * </ul>
 * Valores lidos da requisição pelas mesmas regras do {@link ContextoRequisicaoFilter}.
 */
@Component
class ConsultaObservationConvention extends DefaultServerRequestObservationConvention {

    static final String CONSUMIDOR = "consumidor";
    static final String TIPO_CONSULTA = "tipo_consulta";
    static final String ROTA = "rota";
    static final String NENHUM = "nenhum";

    /** Consumidor vira tag já normalizado ({@link RequisicaoDeConsulta#consumidor}), sem explodir a cardinalidade. */
    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        var request = context.getCarrier();
        var tipoConsulta = RequisicaoDeConsulta.tipoConsulta(request);
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(CONSUMIDOR, RequisicaoDeConsulta.consumidor(request)),
                KeyValue.of(TIPO_CONSULTA, tipoConsulta == null ? NENHUM : tipoConsulta),
                KeyValue.of(ROTA, RequisicaoDeConsulta.rota()));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
        var valores = super.getHighCardinalityKeyValues(context);
        var response = context.getResponse();
        if (response != null && response.getHeader(ContextoRequisicaoFilter.HEADER_CORRELATION_ID) != null) {
            valores = valores.and(ContextoRequisicaoFilter.MDC_CORRELATION_ID,
                    response.getHeader(ContextoRequisicaoFilter.HEADER_CORRELATION_ID));
        }
        var request = context.getCarrier();
        for (var parametro : new String[] {ParametrosConsulta.ID_OPERACAO, ParametrosConsulta.CPF_CNPJ,
                ParametrosConsulta.CONTA}) {
            var valor = request.getParameter(parametro);
            if (valor != null) {
                valores = valores.and(parametro, valor);
            }
        }
        return valores;
    }
}
