package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta da consulta OPERACAO na fonte primária (gateway do mainframe no WireMock).
 * O contrato é o mesmo da visão consolidada: a rota devolve, byte a byte, o JSON de {@link RespostasEsperadas}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
// Sem isto o Spring Boot desliga o tracing nos testes, e o traceparent não é propagado ao gateway
@AutoConfigureTracing
@Import(FontePrimariaContainers.class)
class FontePrimariaIT {

    private static final String ROTA_FONTE_PRIMARIA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";

    private static final String HEADER_CORRELATION_ID = "x-correlationid";

    private final RestTestClient client;
    private final GenericContainer<?> wireMock;

    @LocalServerPort
    private int porta;

    @Autowired
    FontePrimariaIT(RestTestClient client, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.wireMock = wireMock;
    }

    @Test
    void blocosPadraoVemDaFontePrimariaComOJsonDoContrato() {
        var fontePrimaria = resposta(ROTA_FONTE_PRIMARIA, "100000001", "");

        assertThat(RespostasEsperadas.recebido(fontePrimaria.getResponseBodyContent()))
                .isEqualTo(RespostasEsperadas.corpo("operacao/padrao-100000001"));
    }

    @ParameterizedTest(name = "id_operacao={0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            100000001 | -
            100000002 | -
            100000003 | plano,saldo
            100000004 | -
            100000005 | -
            100000006 | -
            100000007 | -
            """)
    void todosOsBlocosDaMassaVemDaFontePrimariaComOJsonEHeaderDoContrato(String idOperacao, String indisponiveis) {
        var fontePrimaria = resposta(ROTA_FONTE_PRIMARIA, idOperacao, TODOS_OS_BLOCOS);

        assertThat(RespostasEsperadas.recebido(fontePrimaria.getResponseBodyContent()))
                .isEqualTo(RespostasEsperadas.corpo("operacao/todos-os-blocos-" + idOperacao));
        assertThat(fontePrimaria.getResponseHeaders().get(HEADER_INDISPONIVEIS))
                .isEqualTo(indisponiveis == null ? null : List.of(indisponiveis));
    }

    @Test
    void selecaoDeCamposNaFontePrimariaDevolveOJsonDoContrato() {
        var uri = "?id_operacao=100000004&expand=parcelas,saldo"
                + "&fields=operacao.situacao_operacao_credito,parcelas.situacao_parcela,saldo.saldo_devedor_operacao";

        var fontePrimaria = corpoDaUri(ROTA_FONTE_PRIMARIA + uri);

        assertThat(fontePrimaria).contains("situacao_parcela").doesNotContain("data_vencimento_parcela");
        assertThat(fontePrimaria).isEqualTo(RespostasEsperadas.corpo("operacao/selecao-de-campos"));
    }

    @Test
    void blocoAusenteNaFontePrimariaVemNuloEListadoNoHeader() {
        consultar(ROTA_FONTE_PRIMARIA, "100000003", "saldo,parcelas,plano,recebimentos")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo")
                .expectBody()
                .jsonPath("$.data[0].length()").isEqualTo(6)
                .jsonPath("$.data[0].saldo").isEqualTo(null)
                .jsonPath("$.data[0].plano").isEqualTo(null)
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(24)
                .jsonPath("$.data[0].recebimentos").isEmpty();
    }

    @Test
    void trancodeDosBlocosEhConvertidoPelosTiposDoLayout() {
        consultar(ROTA_FONTE_PRIMARIA, "100000001", "amortizacoes,plano,controles_iof")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.data_baixa_operacao").isEqualTo(null)
                .jsonPath("$.data[0].originacao.dependencia_contabil").isEqualTo("004")
                .jsonPath("$.data[0].amortizacoes[5].valor_principal_amortizado").isEqualTo(192.00)
                .jsonPath("$.data[0].amortizacoes[5].indicador_ocorrencia_valida").isEqualTo(true)
                .jsonPath("$.data[0].amortizacoes[5].data_valor_movimento").isEqualTo("2026-11-10T14:32:10")
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].taxa_juros_efetiva").isEqualTo(23.87)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].encargos_moratorios[1].dia_fim").isEqualTo(9999)
                .jsonPath("$.data[0].plano.dados_plano_amortizacao[0].desconto_bom_pagador.length()").isEqualTo(1)
                .jsonPath("$.data[0].controles_iof[?(@.numero_parcela_amortizacao == 6)].data_pagamento_iof")
                .isEqualTo(Arrays.asList("2026-11-10", null));
    }

    @Test
    void operacaoInexistenteNaFontePrimariaDevolve404() {
        consultar(ROTA_FONTE_PRIMARIA, "999999999", "parcelas")
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void blocoDesconhecidoNaFontePrimariaDevolve400SemChamarOGateway() {
        var correlationId = "fp-expand-invalido-1";
        client.get().uri(ROTA_FONTE_PRIMARIA + "?id_operacao=100000001&expand=garantias")
                .header("x-tipo-consulta", "OPERACAO")
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains("controles_iof"));

        assertThat(chamadasAoGateway(Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId)))).isZero();
    }

    @Test
    void correlationIdERepassadoAoGatewayEmCadaChamadaDeBloco() {
        var correlationId = "fp-correlation-" + UUID.randomUUID();
        client.get().uri(ROTA_FONTE_PRIMARIA + "?id_operacao=100000002&expand=parcelas")
                .header("x-tipo-consulta", "OPERACAO")
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_CORRELATION_ID, correlationId);

        // Descoberta: operacao nas 4 custódias; depois originacao e parcelas só na SF
        assertThat(chamadasAoGateway(Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId)))).isEqualTo(6);
    }

    @Test
    void traceparentDaRequisicaoChegaAoGatewayNoMesmoTrace() throws Exception {
        var traceId = UUID.randomUUID().toString().replace("-", "");
        // HttpClient puro: o RestTestClient é instrumentado e sobrescreveria o traceparent com o próprio trace
        var requisicao = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + porta + ROTA_FONTE_PRIMARIA + "?id_operacao=100000002"))
                .header("x-tipo-consulta", "OPERACAO")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .build();
        try (var http = HttpClient.newHttpClient()) {
            assertThat(http.send(requisicao, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        }

        assertThat(chamadasAoGateway(Map.of("traceparent",
                Map.of("matches", "00-" + traceId + "-[0-9a-f]{16}-0[01]")))).isEqualTo(5);
    }

    @Test
    void erroDoGatewayNumBlocoDevolveRespostaParcial() {
        registrarStub(900000001L, "parcelas", 500, "erro interno");
        registrarStub(900000001L, "operacao", 200, "000000000900000001ATIVA          2026-05-21          52998224725   ");
        registrarStub(900000001L, "originacao", 200, "000000000900000001MOBILE              004  ");

        consultar(ROTA_FONTE_PRIMARIA, "900000001", "parcelas")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "parcelas")
                .expectBody()
                .jsonPath("$.data[0].operacao.situacao_operacao_credito").isEqualTo("ATIVA")
                .jsonPath("$.data[0].operacao.custodia").isEqualTo("SF")
                .jsonPath("$.data[0].parcelas").isEqualTo(null);
    }

    @Test
    void blocoListaDe75ParcelasVemEmTresPaginasLidasEmSequencia() {
        consultar(ROTA_FONTE_PRIMARIA, "100000007", "parcelas")
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody()
                .jsonPath("$.data[0].operacao.custodia").isEqualTo("F5")
                .jsonPath("$.data[0].parcelas.length()").isEqualTo(75)
                .jsonPath("$.data[0].parcelas[74].numero_parcela_amortizacao").isEqualTo(75);
    }

    @Test
    void falhaNumaPaginaDeixaOBlocoIndisponivelEmVezDeTruncado() {
        var gateway = new GatewayMainframeSimulado(wireMock);
        var falha = gateway.registrarPagina(GatewayMainframeSimulado.bloco("F5", 100000007L, "parcelas"),
                "PARCELAS000061", 500, "erro");
        try {
            consultar(ROTA_FONTE_PRIMARIA, "100000007", "parcelas,saldo")
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "parcelas")
                    .expectBody()
                    .jsonPath("$.data[0].parcelas").isEqualTo(null)
                    .jsonPath("$.data[0].saldo").exists();
        } finally {
            gateway.remover(falha);
        }
    }

    @ParameterizedTest(name = "trancode de operacao \"{0}\" -> 503")
    @ValueSource(strings = {
            "000000000900000002ATIVA          2026-05-21",
            "000000000900000002ATIVA          2026-05-21          52998224725   XYZ",
            "00000000090000000XATIVA          2026-05-21          52998224725   ",
            "000000000900000002ATIVA          2026-13-45          52998224725   "})
    void trancodeDoBlocoOperacaoForaDoLayoutDevolve503ComACustodia(String trancode) {
        registrarStub(900000002L, "operacao", 200, trancode);
        registrarStub(900000002L, "originacao", 200, "000000000900000002MOBILE              004  ");

        // Sem um bloco operacao legível na SF não se sabe se a operação existe nela (ADR-0013)
        consultar(ROTA_FONTE_PRIMARIA, "900000002", "")
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.custodias_indisponiveis[0]").isEqualTo("SF");
    }

    @Test
    void quantidadeDeItensInvalidaNoBlocoListaODeixaIndisponivel() {
        registrarStub(900000003L, "operacao", 200, "000000000900000003ATIVA          2026-05-21          52998224725   ");
        registrarStub(900000003L, "originacao", 200, "000000000900000003MOBILE              004  ");
        registrarStub(900000003L, "recebimentos", 200, "0A1");

        consultar(ROTA_FONTE_PRIMARIA, "900000003", "recebimentos")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "recebimentos");
    }

    private RestTestClient.ResponseSpec consultar(String rota, String idOperacao, String expand) {
        return client.get().uri(rota + "?id_operacao={id}&expand={expand}", idOperacao, expand)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }

    /** Requisições recebidas pelo gateway simulado com os headers indicados (journal do WireMock). */
    private int chamadasAoGateway(Map<String, Object> headers) {
        var resposta = adminWireMock().post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", "/mainframe/custodias/.*", "headers", headers))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }

    private void registrarStub(long idOperacao, String bloco, int status, String corpo) {
        adminWireMock().post().uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "request", Map.of("method", "GET",
                                "url", GatewayMainframeSimulado.bloco("SF", idOperacao, bloco)),
                        "response", Map.of("status", status, "body", corpo,
                                "headers", Map.of("Content-Type", "text/plain; charset=utf-8"))))
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient adminWireMock() {
        return RestClient.create(FontePrimariaContainers.urlWireMock(wireMock));
    }

    private String corpoDaUri(String uri) {
        var resultado = client.get().uri(uri)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
        return RespostasEsperadas.recebido(resultado.getResponseBodyContent());
    }

    private EntityExchangeResult<String> resposta(String rota, String idOperacao, String expand) {
        return client.get().uri(rota + "?id_operacao={id}&expand={expand}", idOperacao, expand)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
    }
}
