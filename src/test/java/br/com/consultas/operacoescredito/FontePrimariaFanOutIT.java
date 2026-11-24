package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.consultas.operacoescredito.GatewayMainframeSimulado.PAGINA_VAZIA;
import static br.com.consultas.operacoescredito.GatewayMainframeSimulado.bloco;
import static br.com.consultas.operacoescredito.GatewayMainframeSimulado.custodia;
import static br.com.consultas.operacoescredito.GatewayMainframeSimulado.pagina;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fan-out da fonte primária em CLIENTE/CONTA com massas próprias registradas no WireMock durante o teste
 * (operações 9100000xx, que não existem na massa do WireMock): teto de chamadas simultâneas por requisição,
 * falha de uma operação deixando só os blocos dela indisponíveis (ADR-0013), lista de uma custódia que falha
 * (503), listas paginadas em várias custódias com operação repetida (ADR-0012/0016) e correlationId em toda
 * chamada filha. Contexto próprio, com teto e timeout de resposta baixos, e sem LocalStack (só a fonte primária é
 * usada). As listas são registradas na custódia SF; nas outras, sem stub, o gateway responde 404 (nenhuma
 * operação nelas).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.fonte-primaria.fan-out.max-chamadas-simultaneas=" + FontePrimariaFanOutIT.TETO,
        "consultas.fonte-primaria.pool.timeout-resposta=400ms"})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class FontePrimariaFanOutIT {

    static final int TETO = 3;

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_CORRELATION_ID = "x-correlationid";
    private static final String BLOCOS_INDISPONIVEIS = "x-blocos-indisponiveis";

    private final RestTestClient client;
    private final GenericContainer<?> wireMock;
    private final GatewayMainframeSimulado gateway;

    @Autowired
    FontePrimariaFanOutIT(RestTestClient client, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.wireMock = wireMock;
        this.gateway = new GatewayMainframeSimulado(wireMock);
    }

    @Test
    void fanOutRespeitaOTetoDeChamadasSimultaneasPorRequisicao() {
        var ids = List.of(910000001L, 910000002L, 910000003L, 910000004L);
        registrarLista(custodia("SF") + "/clientes/70000000159/operacoes", ids, "2026-01-01");
        ids.forEach(id -> gateway.registrarOperacao(id, 150));
        var correlationId = "fp-teto-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=70000000159&expand=parcelas", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(4);

        var chamadas = chamadasDeBloco(correlationId);
        assertThat(chamadas).hasSize(4 * 3);
        var simultaneas = maximoDeChamadasSimultaneas(chamadas);
        assertThat(simultaneas).as("chamadas simultâneas ao gateway").isLessThanOrEqualTo(TETO).isGreaterThan(1);
    }

    @Test
    void erro5xxEmUmBlocoDeUmaOperacaoDeixaSoEleIndisponivel() {
        var ids = List.of(910000011L, 910000012L, 910000013L);
        registrarLista(custodia("SF") + "/clientes/70000000230/operacoes", ids, "2026-02-01");
        ids.forEach(id -> gateway.registrarOperacao(id, 0));
        gateway.registrar(bloco("SF", 910000012L, "parcelas"), 500, "erro interno no mainframe", 0);

        consultar("CLIENTE", "cpf_cnpj=70000000230&expand=parcelas", "fp-5xx-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectHeader().valueEquals(BLOCOS_INDISPONIVEIS, "parcelas")
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(3)
                .jsonPath("$.data[1].operacao.id_operacao").isEqualTo(910000012L)
                .jsonPath("$.data[1].parcelas").isEqualTo(null)
                .jsonPath("$.data[0].parcelas").isArray()
                .jsonPath("$.data[2].parcelas").isArray();
    }

    @Test
    void timeoutDeUmaTentativaDeixaOBlocoIndisponivelSemEsperarOAtrasoDoGateway() {
        var ids = List.of(910000021L, 910000022L, 910000023L);
        registrarLista(custodia("SF") + "/contas/700000000000002/operacoes", ids, "2026-03-01");
        ids.forEach(id -> gateway.registrarOperacao(id, 0));
        gateway.registrar(bloco("SF", 910000022L, "parcelas"), 200, PAGINA_VAZIA, 3000);

        var inicio = System.nanoTime();
        consultar("CONTA", "conta=700000000000002&expand=parcelas", "fp-timeout-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectHeader().valueEquals(BLOCOS_INDISPONIVEIS, "parcelas")
                .expectBody()
                .jsonPath("$.data[1].parcelas").isEqualTo(null);
        var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(duracaoMs).as("timeout de resposta de 400 ms, sem esperar o atraso de 3 s").isLessThan(2500);
    }

    @Test
    void erroNaListaDeUmaCustodiaDevolve503ComACustodia() {
        gateway.registrar(custodia("SF") + "/contas/700000000000003/operacoes", 503, "indisponivel", 0);

        consultar("CONTA", "conta=700000000000003", "fp-lista-5xx-" + UUID.randomUUID())
                .expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.custodias_indisponiveis[0]").isEqualTo("SF")
                .jsonPath("$.custodias_indisponiveis.length()").isEqualTo(1);
    }

    @Test
    void listaDeOperacoesForaDoLayoutDevolve503ComACustodia() {
        gateway.registrar(custodia("SF") + "/contas/700000000000004/operacoes", 200, "002000000000910000031", 0);

        consultar("CONTA", "conta=700000000000004", "fp-lista-invalida-" + UUID.randomUUID())
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.custodias_indisponiveis[0]").isEqualTo("SF");
    }

    @Test
    void listasPaginadasDeVariasCustodiasSaoJuntadasEARepetidaFicaComAPrecedencia() {
        var cpf = "70000000744";
        var sf = custodia("SF") + "/clientes/" + cpf + "/operacoes";
        // SF em duas páginas: 910000071 e, na segunda, 910000072; NSF com 910000072 (repetida) e 910000073
        gateway.registrar(sf, 200, pagina(1, "SF000002", registroDaLista(910000071L, "2026-01-10")), 0);
        gateway.registrarPagina(sf, "SF000002", 200, pagina(1, null, registroDaLista(910000072L, "2026-02-10")));
        gateway.registrar(custodia("NSF") + "/clientes/" + cpf + "/operacoes", 200,
                pagina(2, null, registroDaLista(910000072L, "2026-02-10") + registroDaLista(910000073L, "2026-03-10")),
                0);
        gateway.registrarOperacao("SF", 910000071L, 0);
        gateway.registrarOperacao("SF", 910000072L, 0);
        gateway.registrarOperacao("NSF", 910000072L, 0);
        gateway.registrarOperacao("NSF", 910000073L, 0);
        var correlationId = "fp-paginas-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=" + cpf, correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(910000073, 910000072, 910000071))
                .jsonPath("$.data[*].operacao.custodia").isEqualTo(List.of("NSF", "NSF", "SF"));

        var comCorrelationId = Map.<String, Object>of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId));
        assertThat(contar(sf, comCorrelationId)).as("as duas páginas da SF").isEqualTo(2);
        assertThat(contar(custodia("SF") + "/operacoes/910000072/.*", comCorrelationId))
                .as("a repetida é detalhada só na custódia de maior precedência").isZero();
    }

    @Test
    void correlationIdChegaATodasAsChamadasFilhasDoFanOut() {
        var ids = List.of(910000041L, 910000042L, 910000043L, 910000044L, 910000045L);
        registrarLista(custodia("SF") + "/clientes/70000000400/operacoes", ids, "2026-04-01");
        ids.forEach(id -> gateway.registrarOperacao(id, 0));
        var correlationId = "fp-filhas-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=70000000400&expand=parcelas", correlationId)
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_CORRELATION_ID, correlationId);

        // A lista nas 4 custódias + 5 operações × 3 blocos
        var rotas = "/mainframe/custodias/[A-Z0-9]+/(clientes/70000000400/operacoes|operacoes/91000004[1-5]/blocos/.*)";
        assertThat(contar(rotas, Map.of())).isEqualTo(4 + 5 * 3);
        assertThat(contar(rotas, Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId))))
                .isEqualTo(4 + 5 * 3);
    }

    @Test
    void operacaoListadaQueNaoExisteMaisNaFontePrimariaFicaForaDaResposta() {
        registrarLista(custodia("SF") + "/clientes/70000000582/operacoes", List.of(910000052L, 910000051L),
                "2026-05-01");
        gateway.registrarOperacao(910000051L, 0);

        consultar("CLIENTE", "cpf_cnpj=70000000582&expand=parcelas", "fp-sumiu-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(910000051));
    }

    @Test
    void limiteOperacoesPegaAsMaisRecentesMesmoComAListaForaDeOrdem() {
        gateway.registrar(custodia("SF") + "/clientes/70000000663/operacoes", 200, pagina(3, null,
                registroDaLista(910000061L, "2026-01-10")
                        + registroDaLista(910000062L, "2026-06-10")
                        + registroDaLista(910000063L, "2026-03-10")), 0);
        List.of(910000061L, 910000062L, 910000063L).forEach(id -> gateway.registrarOperacao(id, 0));
        var correlationId = "fp-ordem-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=70000000663&limite_operacoes=2", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(910000062, 910000063));

        assertThat(contar("/mainframe/custodias/[A-Z0-9]+/operacoes/910000061/.*",
                Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId)))).isZero();
    }

    private RestTestClient.ResponseSpec consultar(String tipo, String query, String correlationId) {
        return client.get().uri(ROTA + "?" + query)
                .header("x-tipo-consulta", tipo)
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange();
    }

    /** Lista de operações do gateway em uma página, mais recente primeiro (as datas decrescem um dia). */
    private void registrarLista(String rota, List<Long> ids, String dataMaisRecente) {
        var registros = new StringBuilder();
        var data = LocalDate.parse(dataMaisRecente);
        for (var id : ids) {
            registros.append(registroDaLista(id, data.toString()));
            data = data.minusDays(1);
        }
        gateway.registrar(rota, 200, pagina(ids.size(), null, registros.toString()), 0);
    }

    private static String registroDaLista(long id, String data) {
        return "%018d%s".formatted(id, data);
    }

    private int contar(String urlPathPattern, Map<String, Object> headers) {
        var resposta = adminWireMock().post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", urlPathPattern, "headers", headers))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }

    /** Chamadas de bloco (não a lista) com o correlationId: início e fim no relógio do WireMock, em ms. */
    @SuppressWarnings("unchecked")
    private List<long[]> chamadasDeBloco(String correlationId) {
        var resposta = adminWireMock().post().uri("/__admin/requests/find")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", "/mainframe/custodias/[A-Z0-9]+/operacoes/.*",
                        "headers", Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId))))
                .retrieve()
                .body(Map.class);
        var intervalos = new ArrayList<long[]>();
        for (var requisicao : (List<Map<String, Object>>) resposta.get("requests")) {
            var inicio = ((Number) requisicao.get("loggedDate")).longValue();
            // O WireMock segura cada resposta pelo atraso fixo: a chamada ocupa ao menos [inicio, inicio + 150)
            intervalos.add(new long[] {inicio, inicio + 150});
        }
        return intervalos;
    }

    /** Maior número de intervalos {@code [inicio, fim)} abertos ao mesmo tempo. */
    private static int maximoDeChamadasSimultaneas(List<long[]> intervalos) {
        var maximo = 0;
        for (var intervalo : intervalos) {
            var abertos = (int) intervalos.stream()
                    .filter(outro -> outro[0] <= intervalo[0] && intervalo[0] < outro[1])
                    .count();
            maximo = Math.max(maximo, abertos);
        }
        return maximo;
    }

    private RestClient adminWireMock() {
        return RestClient.create(FontePrimariaContainers.urlWireMock(wireMock));
    }
}
