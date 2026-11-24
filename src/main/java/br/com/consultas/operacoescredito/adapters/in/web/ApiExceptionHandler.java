package br.com.consultas.operacoescredito.adapters.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import br.com.consultas.operacoescredito.application.port.out.CotaDaFonteEsgotadaException;
import br.com.consultas.operacoescredito.application.port.out.CustodiasIndisponiveisException;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.domain.Custodia;

import java.time.Duration;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.domain.OperacaoNaoEncontradaException;
import br.com.consultas.operacoescredito.domain.filtro.FiltroInvalidoException;
import br.com.consultas.operacoescredito.domain.selecao.SelecaoDeCamposInvalidaException;

/** Todos os erros em Problem Details (RFC 9457). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** Propriedade do Problem Details com os códigos das custódias indisponíveis. */
    static final String PROPRIEDADE_CUSTODIAS = "custodias_indisponiveis";

    @ExceptionHandler(OperacaoNaoEncontradaException.class)
    ProblemDetail operacaoNaoEncontrada(OperacaoNaoEncontradaException e) {
        return problema(HttpStatus.NOT_FOUND, "Operação de crédito não encontrada", e.getMessage());
    }

    @ExceptionHandler(RequisicaoInvalidaException.class)
    ProblemDetail requisicaoInvalida(RequisicaoInvalidaException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(FiltroInvalidoException.class)
    ProblemDetail filtroInvalido(FiltroInvalidoException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(SelecaoDeCamposInvalidaException.class)
    ProblemDetail selecaoDeCamposInvalida(SelecaoDeCamposInvalidaException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(TempoEsgotadoNaFonteDeOperacoesException.class)
    ProblemDetail tempoEsgotadoNaFonte(TempoEsgotadoNaFonteDeOperacoesException e) {
        LOG.warn("Fonte de operações sem resposta no prazo: {}", e.getMessage(), e);
        return problema(HttpStatus.GATEWAY_TIMEOUT, "Fonte de dados sem resposta no prazo",
                "A fonte de dados não respondeu a tempo; reduza as operações com limite_operacoes, peça menos "
                        + "blocos em expand ou consulte uma operação por vez (x-tipo-consulta: OPERACAO)");
    }

    /**
     * Custódia que não pôde ser consultada sem a qual a resposta não seria confiável (ADR-0012): 503 com as
     * custódias em {@value #PROPRIEDADE_CUSTODIAS} e {@code Retry-After}.
     */
    @ExceptionHandler(CustodiasIndisponiveisException.class)
    ResponseEntity<ProblemDetail> custodiasIndisponiveis(CustodiasIndisponiveisException e) {
        LOG.warn("Custódias indisponíveis, consulta sem resposta confiável: {}", e.getMessage());
        var codigos = e.custodias().stream().map(Custodia::codigo).toList();
        var problema = problema(HttpStatus.SERVICE_UNAVAILABLE, "Custódia temporariamente indisponível",
                "Não foi possível consultar as custódias " + String.join(", ", codigos) + "; sem elas não é possível "
                        + "afirmar o resultado. Tente novamente depois do tempo indicado em Retry-After");
        problema.setProperty(PROPRIEDADE_CUSTODIAS, codigos);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfter(e.tenteNovamenteEm()))
                .body(problema);
    }

    /** Circuit breaker aberto ou bulkhead cheio: 503 com {@code Retry-After} em segundos. */
    @ExceptionHandler(FonteDeOperacoesIndisponivelException.class)
    ResponseEntity<ProblemDetail> fonteIndisponivel(FonteDeOperacoesIndisponivelException e) {
        LOG.warn("Fonte de operações protegida, chamada recusada: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfter(e.tenteNovamenteEm()))
                .body(problema(HttpStatus.SERVICE_UNAVAILABLE, "Fonte de dados temporariamente indisponível",
                        "A fonte de dados está protegida contra sobrecarga ou falhas recentes; tente novamente "
                                + "depois do tempo indicado em Retry-After"));
    }

    /** Cota de chamadas da fonte primária esgotada (ADR-0015): 429 com {@code Retry-After}, sem esperar. */
    @ExceptionHandler(CotaDaFonteEsgotadaException.class)
    ResponseEntity<ProblemDetail> cotaEsgotada(CotaDaFonteEsgotadaException e) {
        LOG.warn("Cota da fonte primária esgotada, consulta recusada: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, retryAfter(e.tenteNovamenteEm()))
                .body(problema(HttpStatus.TOO_MANY_REQUESTS, "Cota da fonte de dados esgotada",
                        "A fonte primária atingiu o limite de chamadas por segundo; tente novamente depois do "
                                + "tempo indicado em Retry-After ou use a visão consolidada"));
    }

    private static String retryAfter(Duration espera) {
        return Long.toString(Math.max(1, (espera.toMillis() + 999) / 1000));
    }

    @ExceptionHandler(FalhaNaFonteDeOperacoesException.class)
    ProblemDetail falhaNaFonte(FalhaNaFonteDeOperacoesException e) {
        LOG.warn("Falha na fonte de operações: {}", e.getMessage(), e);
        return problema(HttpStatus.BAD_GATEWAY, "Fonte de dados indisponível",
                "A fonte de dados não respondeu de forma utilizável; tente novamente");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail erroInesperado(Exception e) {
        LOG.error("Erro inesperado na consulta", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Erro inesperado ao processar a consulta");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        var problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        return problema;
    }
}
