package br.com.consultas.operacoescredito.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import br.com.consultas.operacoescredito.application.port.in.Consulta;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Rota da fonte primária ({@value #CAMINHO_FONTE_PRIMARIA}), a única deste serviço. O contrato é o mesmo da visão
 * consolidada, que roda em outro serviço (ADR-0017); o ALB separa os dois pelo caminho.
 */
@RestController
@RequestMapping(OperacoesCreditoController.CAMINHO_FONTE_PRIMARIA)
class OperacoesCreditoController {

    static final String HEADER_BLOCOS_INDISPONIVEIS = "x-blocos-indisponiveis";

    /** Caminho da rota da fonte primária. */
    static final String CAMINHO_FONTE_PRIMARIA = "/v1/operacoes-credito/fonte-primaria";

    private final ConsultarOperacoesUseCase consultarOperacoes;

    OperacoesCreditoController(ConsultarOperacoesUseCase consultarOperacoes) {
        this.consultarOperacoes = consultarOperacoes;
    }

    /** Parâmetro do contrato repetido é 400 (o Spring juntaria os valores com vírgula). */
    @GetMapping
    ResponseEntity<ConsultaResponse> consultar(
            @RequestHeader(name = ParametrosConsulta.HEADER_TIPO_CONSULTA, required = false) String tipoConsulta,
            @RequestParam(name = ParametrosConsulta.ID_OPERACAO, required = false) String idOperacao,
            @RequestParam(name = ParametrosConsulta.CPF_CNPJ, required = false) String cpfCnpj,
            @RequestParam(name = ParametrosConsulta.CONTA, required = false) String conta,
            @RequestParam(name = ParametrosConsulta.LIMITE_OPERACOES, required = false) String limiteOperacoes,
            @RequestParam(name = ParametrosConsulta.EXPAND, required = false) String expand,
            @RequestParam(name = ParametrosConsulta.FIELDS, required = false) String fields,
            @RequestParam(name = ParametrosConsulta.FILTER, required = false) String filter,
            HttpServletRequest requisicao) {
        ParametrosConsulta.rejeitarRepetidos(requisicao.getParameterMap());
        var parametros = new ParametrosConsulta(tipoConsulta, idOperacao, cpfCnpj, conta, limiteOperacoes, expand, fields);
        var operacoes = consultarOperacoes.consultar(new Consulta(
                parametros.criterio(), parametros.blocos(), parametros.campos(), FiltroRsql.traduzir(filter)));
        requisicao.setAttribute(RegistroDeConsumoHttp.ATRIBUTO_QUANTIDADE_OPERACOES, operacoes.size());
        var resposta = ResponseEntity.ok();
        blocosIndisponiveis(operacoes).ifPresent(blocos -> resposta.header(HEADER_BLOCOS_INDISPONIVEIS, blocos));
        return resposta.body(ConsultaResponse.de(operacoes));
    }

    /** Blocos pedidos e ausentes em alguma operação, separados por vírgula na ordem canônica. */
    private static Optional<String> blocosIndisponiveis(List<OperacaoConsultada> operacoes) {
        var indisponiveis = EnumSet.noneOf(Bloco.class);
        operacoes.forEach(operacao -> indisponiveis.addAll(operacao.blocosIndisponiveis()));
        return indisponiveis.isEmpty()
                ? Optional.empty()
                : Optional.of(indisponiveis.stream().map(Bloco::nome).collect(Collectors.joining(",")));
    }
}
