package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.application.port.in.Consumo;
import br.com.consultas.operacoescredito.application.port.in.RegistrarConsumoUseCase;
import br.com.consultas.operacoescredito.domain.Bloco;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Monta, ao fim de cada requisição às rotas de consulta, o consumo para o evento de consumo (ADR-0004) e o entrega
 * ao caso de uso, que só enfileira. Sucesso e erro geram evento. Nunca falha a requisição: qualquer erro aqui é
 * registrado em log e o evento se perde.
 * <p>
 * Só entra no evento o que é dado de negócio bem formado: blocos do catálogo e campos {@code bloco.campo}; o resto
 * (inclusive o que poderia ser um dado pessoal digitado no lugar errado) fica de fora.
 */
@Component
class RegistroDeConsumoHttp {

    /** Atributo da requisição com a quantidade de operações em {@code data}, definido pelo controller. */
    static final String ATRIBUTO_QUANTIDADE_OPERACOES = RegistroDeConsumoHttp.class.getName() + ".quantidade";

    private static final Logger log = LoggerFactory.getLogger(RegistroDeConsumoHttp.class);
    private static final Pattern LIMITE = Pattern.compile("\\d{1,9}");
    private static final Pattern CAMPO = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)+");
    /** Código do erro por status: cada status de erro da API vem de um único tipo de falha. */
    private static final Map<Integer, String> CODIGO_ERRO = Map.of(
            400, "requisicao_invalida",
            404, "operacao_nao_encontrada",
            405, "metodo_nao_permitido",
            406, "formato_nao_aceito",
            500, "erro_interno",
            502, "falha_na_fonte",
            503, "fonte_indisponivel",
            504, "tempo_esgotado");

    /**
     * Resolvido na primeira requisição: o filtro (e este bean) é criado junto com o servidor web, antes das demais
     * configurações; o caso de uso e o publicador seguem o ciclo normal dos beans.
     */
    private final ObjectProvider<RegistrarConsumoUseCase> registrarConsumo;
    private final ObjectProvider<Tracer> tracer;

    RegistroDeConsumoHttp(ObjectProvider<RegistrarConsumoUseCase> registrarConsumo, ObjectProvider<Tracer> tracer) {
        this.registrarConsumo = registrarConsumo;
        this.tracer = tracer;
    }

    void registrar(HttpServletRequest request, HttpServletResponse response, Instant inicio, long duracaoMs,
                   String correlationId) {
        try {
            registrarConsumo.getObject().registrar(new Consumo(
                    inicio,
                    correlationId,
                    traceId(),
                    RequisicaoDeConsulta.consumidor(request),
                    RequisicaoDeConsulta.rota(),
                    RequisicaoDeConsulta.tipoConsulta(request),
                    idOperacao(request.getParameter(ParametrosConsulta.ID_OPERACAO)),
                    informado(request.getParameter(ParametrosConsulta.CPF_CNPJ)),
                    informado(request.getParameter(ParametrosConsulta.CONTA)),
                    blocos(request),
                    campos(request.getParameter(ParametrosConsulta.FIELDS)),
                    informado(request.getParameter(ParametrosConsulta.FILTER)),
                    limite(request.getParameter(ParametrosConsulta.LIMITE_OPERACOES)),
                    quantidade(request),
                    blocosIndisponiveis(response),
                    // Sem cache de blocos neste serviço: os mapas do schema vêm sempre vazios
                    ContextoRequisicaoFilter.SEM_CACHE_DE_BLOCOS,
                    ContextoRequisicaoFilter.SEM_CACHE_DE_BLOCOS,
                    response.getStatus(),
                    codigoErro(response.getStatus()),
                    duracaoMs));
        } catch (RuntimeException e) {
            log.warn("Evento de consumo não registrado: {}", e.toString());
        }
    }

    private String traceId() {
        var atual = tracer.getIfAvailable();
        var span = atual == null ? null : atual.currentSpan();
        if (span == null) {
            return null;
        }
        var traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() || traceId.chars().allMatch(c -> c == '0') ? null : traceId;
    }

    private static Long idOperacao(String valor) {
        return valor != null && ParametrosConsulta.NUMERO_OPERACAO.matcher(valor).matches() ? Long.parseLong(valor) : null;
    }

    private static Integer limite(String valor) {
        return valor != null && LIMITE.matcher(valor).matches() ? Integer.parseInt(valor) : null;
    }

    private static String informado(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    private static List<String> blocos(HttpServletRequest request) {
        return RequisicaoDeConsulta.blocos(request).stream()
                .filter(nome -> Bloco.porNome(nome).isPresent())
                .toList();
    }

    private static List<String> campos(String fields) {
        if (fields == null || fields.isBlank()) {
            return List.of();
        }
        return Arrays.stream(fields.split(","))
                .map(String::strip)
                .filter(campo -> CAMPO.matcher(campo).matches())
                .toList();
    }

    private static int quantidade(HttpServletRequest request) {
        return request.getAttribute(ATRIBUTO_QUANTIDADE_OPERACOES) instanceof Integer quantidade ? quantidade : 0;
    }

    private static List<String> blocosIndisponiveis(HttpServletResponse response) {
        var header = response.getHeader(OperacoesCreditoController.HEADER_BLOCOS_INDISPONIVEIS);
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split(",")).map(String::strip).filter(bloco -> !bloco.isEmpty()).toList();
    }

    private static String codigoErro(int status) {
        if (status < 400) {
            return null;
        }
        return CODIGO_ERRO.getOrDefault(status, "http_" + status);
    }
}
