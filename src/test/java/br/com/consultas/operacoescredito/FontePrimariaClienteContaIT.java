package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta das consultas CLIENTE e CONTA na fonte primária, com a massa do WireMock: a rota da fonte
 * primária devolve, byte a byte, o JSON de {@link RespostasEsperadas} (o mesmo da visão consolidada).
 * Mesma configuração de contexto do {@link FontePrimariaIT} (o contexto e os containers são reaproveitados).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import(FontePrimariaContainers.class)
class FontePrimariaClienteContaIT {

    private static final String ROTA_FONTE_PRIMARIA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";
    private static final String HEADER_CORRELATION_ID = "x-correlationid";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";
    /** Cliente (e conta) com 14 operações mensais, de 100000101 (mais antiga) a 100000114. */
    private static final String CNPJ_14_OPERACOES = "11222333000181";
    private static final String CONTA_14_OPERACOES = "434115000055501";
    /** Prefixo das rotas do gateway em qualquer custódia. */
    private static final String CUSTODIA = "/mainframe/custodias/[A-Z0-9]+";

    private final RestTestClient client;
    private final GenericContainer<?> wireMock;

    @Autowired
    FontePrimariaClienteContaIT(RestTestClient client, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.wireMock = wireMock;
    }

    /** O caso é o nome do corpo esperado em {@code respostas-esperadas/cliente-conta}. */
    @ParameterizedTest(name = "{1} {2}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            01 | CLIENTE | cpf_cnpj=52998224725&expand=TODOS                                      | plano,saldo
            02 | CLIENTE | cpf_cnpj=52998224725                                                   | -
            03 | CLIENTE | cpf_cnpj=12ABC34501DE35&expand=TODOS                                   | -
            04 | CLIENTE | cpf_cnpj=11144477735&expand=TODOS                                      | -
            05 | CLIENTE | cpf_cnpj=11222333000181&expand=TODOS                                   | -
            06 | CLIENTE | cpf_cnpj=52998224725&expand=saldo,plano&limite_operacoes=2             | plano,saldo
            07 | CLIENTE | cpf_cnpj=11222333000181&expand=parcelas&limite_operacoes=5             | -
            08 | CLIENTE | cpf_cnpj=11222333000181&limite_operacoes=500                           | -
            09 | CONTA   | conta=434115000065874&expand=TODOS                                     | -
            10 | CONTA   | conta=434115000099991&expand=saldo,plano                               | plano,saldo
            11 | CONTA   | conta=434115000077712&expand=saldo&fields=operacao.data_contratacao_operacao,saldo.saldo_devedor_operacao | -
            12 | CONTA   | conta=434115000055501&expand=TODOS&limite_operacoes=3                  | -
            13 | CLIENTE | cpf_cnpj=52998224725&filter=operacao.situacao_operacao_credito==ATIVA  | -
            14 | CLIENTE | cpf_cnpj=52998224725&expand=parcelas&filter=parcelas.situacao_parcela==ABERTA | -
            15 | CLIENTE | cpf_cnpj=39053344705                                                   | -
            16 | CLIENTE | cpf_cnpj=98XYZ76501AB46&expand=parcelas                                | -
            17 | CONTA   | conta=999999999                                                        | -
            """)
    void clienteOuContaNaFontePrimariaDevolveOJsonEHeaderDoContrato(String caso, String tipo, String query,
                                                                     String indisponiveis) {
        var uri = "?" + query.replace("expand=TODOS", "expand=" + TODOS_OS_BLOCOS);

        var fontePrimaria = resposta(ROTA_FONTE_PRIMARIA + uri, tipo);

        assertThat(RespostasEsperadas.recebido(fontePrimaria.getResponseBodyContent()))
                .isEqualTo(RespostasEsperadas.corpo("cliente-conta/" + caso));
        assertThat(fontePrimaria.getResponseHeaders().get(HEADER_INDISPONIVEIS))
                .isEqualTo(indisponiveis == null ? null : List.of(indisponiveis));
    }

    @Test
    void consultaPorClienteNaFontePrimariaDevolveAsOperacoesDaMaisRecenteParaAMaisAntiga() {
        consultar("CLIENTE", "cpf_cnpj=52998224725&expand=saldo,plano", "fp-cliente-ordem-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo")
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000003, 100000001, 100000002))
                .jsonPath("$.data[*].operacao.data_contratacao_operacao")
                .isEqualTo(List.of("2026-10-28", "2026-05-21", "2025-09-15"))
                .jsonPath("$.data[0].saldo").isEmpty()
                .jsonPath("$.data[1].saldo.saldo_devedor_operacao").isEqualTo(3956.00);
    }

    @Test
    void clienteOuContaSemOperacoesNaFontePrimariaDevolveListaVazia() {
        consultar("CONTA", "conta=999999999&expand=parcelas", "fp-conta-vazia-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody().json("""
                        {"data": []}
                        """, JsonCompareMode.STRICT);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"clientes/" + CNPJ_14_OPERACOES, "contas/" + CONTA_14_OPERACOES})
    void operacoesDoClienteEDaContaEstaoEspalhadasPelasQuatroCustodias(String rota) {
        var gateway = RestClient.create(FontePrimariaContainers.urlWireMock(wireMock));
        var porCustodia = new ArrayList<Integer>();
        for (var custodia : List.of("SF", "F5", "OD", "NSF")) {
            var trancode = gateway.get().uri("/mainframe/custodias/" + custodia + "/" + rota + "/operacoes")
                    .retrieve()
                    .body(String.class);
            // Página única: quantidade (3) + "N" + chave em branco (20) + registros de 18 (id) + 10 (data)
            var quantidade = Integer.parseInt(trancode.substring(0, 3));
            assertThat(trancode.charAt(3)).isEqualTo('N');
            assertThat(trancode).hasSize(24 + quantidade * 28);
            porCustodia.add(quantidade);
        }

        assertThat(porCustodia).allSatisfy(quantidade -> assertThat(quantidade).isPositive());
        assertThat(porCustodia.stream().mapToInt(Integer::intValue).sum()).isEqualTo(14);
    }

    @Test
    void limiteOperacoesEhAplicadoAntesDoFanOut() {
        var correlationId = "fp-limite-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=" + CNPJ_14_OPERACOES + "&expand=parcelas&limite_operacoes=3", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000114, 100000113, 100000112));

        // A lista em cada custódia + 3 operações × (operacao, originacao, parcelas), cada uma na sua custódia;
        // nenhuma das outras 11 operações
        assertThat(chamadasAoGateway(CUSTODIA + "/clientes/.*", correlationId)).isEqualTo(4);
        assertThat(chamadasAoGateway(CUSTODIA + "/operacoes/.*", correlationId)).isEqualTo(9);
        assertThat(chamadasAoGateway(CUSTODIA + "/operacoes/10000011[234]/blocos/.*", correlationId)).isEqualTo(9);
    }

    @Test
    void semLimiteOFanOutDetalhaTodasAsOperacoesDaContaComCadaBlocoPedido() {
        var correlationId = "fp-sem-limite-" + UUID.randomUUID();

        consultar("CONTA", "conta=" + CONTA_14_OPERACOES + "&expand=saldo", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(14);

        assertThat(chamadasAoGateway(CUSTODIA + "/contas/.*", correlationId)).isEqualTo(4);
        assertThat(chamadasAoGateway(CUSTODIA + "/operacoes/.*/blocos/(operacao|originacao|saldo)", correlationId))
                .isEqualTo(14 * 3);
        assertThat(chamadasAoGateway(CUSTODIA + "/operacoes/.*", correlationId)).isEqualTo(14 * 3);
    }

    /**
     * O caso é o nome do Problem Details esperado em {@code respostas-esperadas/validacao}; o
     * {@code correlation_id} do corpo esperado é o enviado aqui.
     */
    @ParameterizedTest(name = "{1} {2} -> 400 do contrato")
    @CsvSource(delimiter = '|', textBlock = """
            01 | CLIENTE | cpf_cnpj=52998224726
            02 | CLIENTE | cpf_cnpj=12ABC34501DE36
            03 | CLIENTE | cpf_cnpj=12abc34501de35
            04 | CLIENTE | cpf_cnpj=11111111111
            05 | CLIENTE | cpf_cnpj=529.982.247-25
            06 | CLIENTE | cpf_cnpj=
            07 | CLIENTE | conta=434115000065874
            08 | CLIENTE | cpf_cnpj=52998224725&id_operacao=100000001
            09 | CLIENTE | cpf_cnpj=52998224725&limite_operacoes=0
            10 | CLIENTE | cpf_cnpj=52998224725&limite_operacoes=501
            11 | CLIENTE | cpf_cnpj=52998224725&limite_operacoes=abc
            12 | CONTA   | conta=4341150000658A4
            13 | CONTA   | conta=-1
            14 | CONTA   | cpf_cnpj=52998224725
            15 | CONTA   | conta=434115000065874&limite_operacoes=501
            16 | CONTA   | conta=434115000065874&expand=garantias
            17 | CONTA   | conta=434115000065874&fields=parcelas.situacao_parcela
            18 | CONTA   | conta=434115000065874&filter=operacao.campo_inexistente==1
            """)
    void validacaoDaFontePrimariaDevolveO400DoContratoSemChamarOGateway(String caso, String tipo, String query) {
        var correlationId = "captura-validacao-" + caso;

        var fontePrimaria = respostaComErro(ROTA_FONTE_PRIMARIA + "?" + query, tipo, correlationId);

        assertThat(fontePrimaria.getStatus().value()).isEqualTo(400);
        assertThat(fontePrimaria.getResponseHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(RespostasEsperadas.recebido(fontePrimaria.getResponseBodyContent()))
                .isEqualTo(RespostasEsperadas.corpo("validacao/" + caso));
        assertThat(chamadasAoGateway("/mainframe/.*", correlationId)).isZero();
    }

    private RestTestClient.ResponseSpec consultar(String tipo, String query, String correlationId) {
        return client.get().uri(ROTA_FONTE_PRIMARIA + "?" + query)
                .header("x-tipo-consulta", tipo)
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange();
    }

    private EntityExchangeResult<String> resposta(String uri, String tipo) {
        return client.get().uri(uri)
                .header("x-tipo-consulta", tipo)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
    }

    private EntityExchangeResult<String> respostaComErro(String uri, String tipo, String correlationId) {
        return client.get().uri(uri)
                .header("x-tipo-consulta", tipo)
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange()
                .returnResult(String.class);
    }

    /** Requisições recebidas pelo gateway simulado com o correlationId informado (journal do WireMock). */
    private int chamadasAoGateway(String urlPathPattern, String correlationId) {
        var resposta = RestClient.create(FontePrimariaContainers.urlWireMock(wireMock))
                .post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", urlPathPattern,
                        "headers", Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId))))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }
}
