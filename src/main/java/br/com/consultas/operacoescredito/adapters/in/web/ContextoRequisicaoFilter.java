package br.com.consultas.operacoescredito.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolve o contexto da requisição antes de qualquer processamento: {@code x-correlationid}
 * (validado ou gerado), {@code x-consumidor} (normalizado por {@link RequisicaoDeConsulta}), tipo de consulta e
 * rota vão para o MDC, o correlationId volta no header da resposta e, ao final, sai um log de acesso, o evento de
 * consumo das rotas de consulta ({@link RegistroDeConsumoHttp}) e as
 * chaves que este filtro pôs no MDC são removidas (as demais, como {@code traceId}/{@code spanId}, são de quem as pôs).
 * Roda logo depois do {@code ServerHttpObservationFilter} (ordem {@code HIGHEST_PRECEDENCE + 1}),
 * para que até a rejeição de um {@code x-correlationid} inválido seja medida e tenha span, e o log
 * de acesso saia com o {@code trace_id}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class ContextoRequisicaoFilter extends OncePerRequestFilter {

    static final String HEADER_CORRELATION_ID = "x-correlationid";
    static final String MDC_CORRELATION_ID = "correlation_id";
    static final String MDC_CONSUMIDOR = "consumidor";
    static final String MDC_TIPO_CONSULTA = "tipo_consulta";
    static final String MDC_ROTA = "rota";

    private static final Logger ACESSO = LoggerFactory.getLogger("acesso");
    private static final Pattern CORRELATION_ID_VALIDO = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final List<String> CHAVES_DO_MDC = List.of(MDC_CORRELATION_ID, MDC_CONSUMIDOR, MDC_TIPO_CONSULTA,
            MDC_ROTA);
    /**
     * Hits e misses do cache de blocos, que só existe na visão consolidada: aqui vêm sempre vazios, para que o log
     * de acesso tenha o mesmo formato nos dois serviços (ADR-0017).
     */
    static final Map<String, Long> SEM_CACHE_DE_BLOCOS = Map.of();
    private static final List<String> PARAMETROS_NO_LOG = List.of(ParametrosConsulta.ID_OPERACAO,
            ParametrosConsulta.CPF_CNPJ, ParametrosConsulta.CONTA, ParametrosConsulta.LIMITE_OPERACOES,
            ParametrosConsulta.FIELDS, ParametrosConsulta.FILTER);

    private final HandlerExceptionResolver resolvedorDeErros;
    private final RegistroDeConsumoHttp registroDeConsumo;

    ContextoRequisicaoFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolvedorDeErros,
                             RegistroDeConsumoHttp registroDeConsumo) {
        this.resolvedorDeErros = resolvedorDeErros;
        this.registroDeConsumo = registroDeConsumo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var inicio = System.nanoTime();
        var instanteInicio = Instant.now();
        var correlationIdRecebido = request.getHeader(HEADER_CORRELATION_ID);
        var correlationIdValido = correlationIdRecebido == null || CORRELATION_ID_VALIDO.matcher(correlationIdRecebido).matches();
        var correlationId = correlationIdRecebido != null && correlationIdValido
                ? correlationIdRecebido
                : UUID.randomUUID().toString();
        try {
            MDC.put(MDC_CORRELATION_ID, correlationId);
            MDC.put(MDC_CONSUMIDOR, RequisicaoDeConsulta.consumidor(request));
            putSeInformado(MDC_TIPO_CONSULTA, RequisicaoDeConsulta.tipoConsulta(request));
            MDC.put(MDC_ROTA, RequisicaoDeConsulta.rota());
            response.setHeader(HEADER_CORRELATION_ID, correlationId);

            if (correlationIdValido) {
                chain.doFilter(request, response);
            } else {
                resolvedorDeErros.resolveException(request, response, null, new RequisicaoInvalidaException(
                        "Header " + HEADER_CORRELATION_ID + " inválido: use até 64 caracteres [A-Za-z0-9-]"));
            }
        } finally {
            var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;
            registrarAcesso(request, response, duracaoMs);
            if (RequisicaoDeConsulta.ehRotaDeConsulta(request)) {
                registroDeConsumo.registrar(request, response, instanteInicio, duracaoMs, correlationId);
            }
            CHAVES_DO_MDC.forEach(MDC::remove);
        }
    }

    private static void registrarAcesso(HttpServletRequest request, HttpServletResponse response, long duracaoMs) {
        var log = ACESSO.atInfo()
                .addKeyValue("metodo", request.getMethod())
                .addKeyValue("uri", request.getRequestURI())
                .addKeyValue("blocos", RequisicaoDeConsulta.blocos(request))
                .addKeyValue("status", response.getStatus())
                .addKeyValue("duracao_ms", duracaoMs)
                .addKeyValue("cache_hits", SEM_CACHE_DE_BLOCOS)
                .addKeyValue("cache_misses", SEM_CACHE_DE_BLOCOS);
        for (var parametro : PARAMETROS_NO_LOG) {
            var valor = request.getParameter(parametro);
            if (valor != null) {
                log = log.addKeyValue(parametro, valor);
            }
        }
        log.log("Requisição atendida");
    }

    private static void putSeInformado(String chave, String valor) {
        if (valor != null) {
            MDC.put(chave, valor);
        }
    }
}
