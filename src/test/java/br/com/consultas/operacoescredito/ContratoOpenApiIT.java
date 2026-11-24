package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformidade das respostas reais da rota da fonte primária (gateway do mainframe no WireMock) com o
 * {@code openapi.yaml} de {@code consultas-operacoes-credito} (ADR-0017): sucesso nos três tipos de consulta, com
 * {@code expand}, {@code fields}, {@code filter} e blocos indisponíveis, e os erros 400 e 404. Os 429, 503 e 504
 * são verificados no {@link RateLimiterFontePrimariaIT} e no {@link ResilienciaFontePrimariaIT}, que provocam as
 * falhas do gateway. Mesma configuração de contexto do {@link FontePrimariaIT} (contexto e containers
 * reaproveitados); valores da massa em {@code wiremock/mappings}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ContratoOpenApiIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";

    private final RestTestClient client;
    private final GatewayMainframeSimulado gateway;

    @Autowired
    ContratoOpenApiIT(RestTestClient client, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.gateway = new GatewayMainframeSimulado(wireMock);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "OPERACAO | id_operacao=100000001&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000002&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000004&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000005&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000006&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000007&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000001",
            "OPERACAO | id_operacao=100000001&expand=saldo,plano"
                    + "&fields=operacao.situacao_operacao_credito,saldo.saldo_parcelas.saldo_devedor",
            "OPERACAO | id_operacao=100000001&expand=parcelas&filter=parcelas.situacao_parcela==ABERTA",
            "CLIENTE  | cpf_cnpj=52998224725",
            "CLIENTE  | cpf_cnpj=12ABC34501DE35&expand=" + TODOS_OS_BLOCOS,
            "CLIENTE  | cpf_cnpj=11144477735&expand=" + TODOS_OS_BLOCOS,
            "CLIENTE  | cpf_cnpj=11222333000181&expand=" + TODOS_OS_BLOCOS,
            "CLIENTE  | cpf_cnpj=11222333000181&expand=parcelas&limite_operacoes=5",
            "CLIENTE  | cpf_cnpj=52998224725&filter=operacao.situacao_operacao_credito==ATIVA",
            "CONTA    | conta=434115000065874&expand=" + TODOS_OS_BLOCOS,
            "CONTA    | conta=434115000077712&expand=saldo"
                    + "&fields=operacao.data_contratacao_operacao,saldo.saldo_devedor_operacao",
            "CONTA    | conta=434115000055501&expand=" + TODOS_OS_BLOCOS + "&limite_operacoes=3"})
    void consultaComOperacoesEstaConforme(String tipo, String query) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseBody()).contains("\"operacao\"");
        ContratoOpenApi.assertConforme(resposta);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            // 100000003 (F5) não tem plano nem saldo na fonte primária, nem dependencia_contabil
            "OPERACAO | id_operacao=100000003&expand=" + TODOS_OS_BLOCOS + " | plano,saldo",
            "CLIENTE  | cpf_cnpj=52998224725&expand=" + TODOS_OS_BLOCOS + "  | plano,saldo",
            "CONTA    | conta=434115000099991&expand=saldo,plano               | plano,saldo"})
    void blocoAusenteNuloComOHeaderEstaConforme(String tipo, String query, String indisponiveis) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseHeaders().getFirst("x-blocos-indisponiveis")).isEqualTo(indisponiveis);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void blocoQueFalhouNoGatewayNuloComOHeaderEstaConforme() {
        var id = 930000001L;
        gateway.registrarOperacao(id, 0);
        gateway.registrar(GatewayMainframeSimulado.bloco("SF", id, "saldo"), 500, "erro interno", 0);

        var resposta = consultar("OPERACAO", "id_operacao=" + id + "&expand=parcelas,saldo");

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseHeaders().getFirst("x-blocos-indisponiveis")).isEqualTo("saldo");
        ContratoOpenApi.assertConforme(resposta);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "CLIENTE  | cpf_cnpj=12345678909",
            "CONTA    | conta=999999999&expand=parcelas",
            "OPERACAO | id_operacao=100000001&filter=operacao.situacao_operacao_credito==LIQUIDADA"})
    void consultaSemOperacoesEstaConforme(String tipo, String query) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseBody()).isEqualTo("{\"data\":[]}");
        ContratoOpenApi.assertConforme(resposta);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "-        | id_operacao=100000001",
            "TODAS    | id_operacao=100000001",
            "OPERACAO | id_operacao=abc",
            "OPERACAO | id_operacao=1234567890123456789",
            "OPERACAO | id_operacao=100000001&cpf_cnpj=52998224725",
            "OPERACAO | id_operacao=100000001&expand=parcelas&expand=saldo",
            "OPERACAO | id_operacao=100000001&expand=garantias",
            "OPERACAO | id_operacao=100000001&fields=parcelas.situacao_parcela",
            "OPERACAO | id_operacao=100000001&filter=operacao.situacao_operacao_credito=~ATIVA",
            "CLIENTE  | cpf_cnpj=52998224726",
            "CLIENTE  | cpf_cnpj=52998224725&limite_operacoes=0",
            "CONTA    | conta=12a",
            "CONTA    | conta=434115000065874&limite_operacoes=501"})
    void requisicaoInvalidaDevolveProblemaConforme(String tipo, String query) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(400);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void correlationIdInvalidoDevolveProblemaConforme() {
        var resposta = consultar("OPERACAO", "id_operacao=100000001",
                headers -> headers.header("x-correlationid", "com espaço"));

        assertThat(resposta.getStatus().value()).isEqualTo(400);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void operacaoInexistenteDevolveProblemaConforme() {
        var resposta = consultar("OPERACAO", "id_operacao=999999999");

        assertThat(resposta.getStatus().value()).isEqualTo(404);
        ContratoOpenApi.assertConforme(resposta);
    }

    private EntityExchangeResult<String> consultar(String tipo, String query) {
        return consultar(tipo, query, headers -> { });
    }

    private EntityExchangeResult<String> consultar(String tipo, String query,
                                                   Consumer<RestTestClient.RequestHeadersSpec<?>> headers) {
        var requisicao = client.get().uri(ROTA + "?" + query);
        if (tipo != null) {
            requisicao.header("x-tipo-consulta", tipo);
        }
        headers.accept(requisicao);
        return requisicao.exchange().returnResult(String.class);
    }
}
