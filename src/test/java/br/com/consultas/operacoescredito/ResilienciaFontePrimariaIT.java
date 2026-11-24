package br.com.consultas.operacoescredito;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.UUID;

import static br.com.consultas.operacoescredito.GatewayMainframeSimulado.PAGINA_VAZIA;
import static br.com.consultas.operacoescredito.GatewayMainframeSimulado.bloco;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resiliência da fonte primária contra o WireMock com atrasos, falhas e cenários (ADR-0013/0014): retry só do
 * Resilience4j, só para falhas retentáveis e com prazo; falha de um bloco que não é {@code operacao} deixa só ele
 * indisponível (200 + {@code x-blocos-indisponiveis}); circuito por custódia × bloco, que abre e volta pelo
 * meio-aberto sem afetar os outros; custódia que não responde na descoberta (503 com as custódias); prazo da
 * requisição (504) e bulkhead por custódia. Contexto próprio, com prazos e limites pequenos; o timeout de resposta
 * do pool é alto para que quem corta a espera seja o prazo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.prazo.fonte-primaria.operacao=1s",
        "consultas.prazo.fonte-primaria.cliente=1500ms",
        "consultas.prazo.fonte-primaria.conta=1500ms",
        "consultas.fonte-primaria.retry.prazo-minimo-restante=300ms",
        "consultas.fonte-primaria.pool.timeout-resposta=5s",
        "resilience4j.circuitbreaker.instances.fontePrimaria.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.fontePrimaria.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.fontePrimaria.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.fontePrimaria.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.fontePrimaria.permitted-number-of-calls-in-half-open-state=2",
        "resilience4j.bulkhead.instances.fontePrimaria.max-concurrent-calls=" + ResilienciaFontePrimariaIT.BULKHEAD,
        "resilience4j.bulkhead.instances.fontePrimaria.max-wait-duration=0ms",
        "resilience4j.bulkhead.instances.fontePrimaria-cliente-conta.max-concurrent-calls="
                + ResilienciaFontePrimariaIT.TETO_CLIENTE_CONTA,
        "resilience4j.bulkhead.instances.fontePrimaria-cliente-conta.max-wait-duration=0ms"})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ResilienciaFontePrimariaIT {

    static final int BULKHEAD = 6;
    static final int TETO_CLIENTE_CONTA = 4;

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String BLOCOS_INDISPONIVEIS = "x-blocos-indisponiveis";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final GatewayMainframeSimulado gateway;
    private final CircuitBreakerRegistry circuitos;
    private final BulkheadRegistry bulkheads;

    @Autowired
    ResilienciaFontePrimariaIT(@LocalServerPort int porta, @Qualifier("wireMock") GenericContainer<?> wireMock,
                               CircuitBreakerRegistry circuitos, BulkheadRegistry bulkheads) {
        // Cliente sem retry automático: o do Apache HttpClient repetiria o 503 depois do Retry-After
        this.client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + porta)
                .build();
        this.gateway = new GatewayMainframeSimulado(wireMock);
        this.circuitos = circuitos;
        this.bulkheads = bulkheads;
    }

    @BeforeEach
    void circuitosFechados() {
        // Cada teste começa com os circuitos fechados e as janelas vazias, qualquer que seja a ordem de execução
        circuitos.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    void falha5xxPassageiraERecuperadaPeloRetry() {
        var id = 920000001L;
        gateway.registrarOperacao(id, 0);
        var parcelas = bloco("SF", id, "parcelas");
        gateway.registrarPassoDoCenario("retry-" + id, "Started", "falhou", parcelas, 500, "erro");
        gateway.registrarPassoDoCenario("retry-" + id, "falhou", null, parcelas, 200, PAGINA_VAZIA);
        var retry = "name:fontePrimaria-SF-parcelas";
        var retriesAntes = metrica("resilience4j.retry.calls", retry, "kind:successful_with_retry");

        consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectHeader().doesNotExist(BLOCOS_INDISPONIVEIS)
                .expectBody()
                .jsonPath("$.data[0].operacao.id_operacao").isEqualTo(id)
                .jsonPath("$.data[0].operacao.custodia").isEqualTo("SF")
                .jsonPath("$.data[0].parcelas").isArray();

        assertThat(gateway.contar(parcelas)).as("tentativas no bloco que falhou uma vez").isEqualTo(2);
        assertThat(metrica("resilience4j.retry.calls", retry, "kind:successful_with_retry"))
                .isEqualTo(retriesAntes + 1);
    }

    @Test
    void falha5xxPersistenteDeUmBlocoTemUmaTentativaExtraEODeixaIndisponivel() {
        var id = 920000002L;
        gateway.registrarOperacao(id, 0);
        var parcelas = bloco("SF", id, "parcelas");
        gateway.registrar(parcelas, 503, "fora do ar", 0);

        var resposta = consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectHeader().valueEquals(BLOCOS_INDISPONIVEIS, "parcelas")
                .expectBody()
                .jsonPath("$.data[0].operacao.id_operacao").isEqualTo(id)
                .jsonPath("$.data[0].originacao.canal_contratacao").isEqualTo("MOBILE")
                .jsonPath("$.data[0].parcelas").doesNotExist()
                .returnResult();

        assertThat(gateway.contar(parcelas)).as("1 tentativa + 1 extra").isEqualTo(2);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void erro4xxDeUmBlocoNaoERetentadoEODeixaIndisponivel() {
        var id = 920000003L;
        gateway.registrarOperacao(id, 0);
        var parcelas = bloco("SF", id, "parcelas");
        gateway.registrar(parcelas, 400, "requisicao invalida", 0);

        consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectHeader().valueEquals(BLOCOS_INDISPONIVEIS, "parcelas");

        assertThat(gateway.contar(parcelas)).isEqualTo(1);
    }

    @Test
    void semPrazoParaOutraTentativaNaoHaRetry() {
        var id = 920000004L;
        gateway.registrarOperacao(id, 0);
        var parcelas = bloco("SF", id, "parcelas");
        // Falha depois de 800 ms: dos 1 s de prazo restam menos que os 300 ms mínimos para tentar de novo
        gateway.registrar(parcelas, 500, "erro lento", 800);

        consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectHeader().valueEquals(BLOCOS_INDISPONIVEIS, "parcelas");

        assertThat(gateway.contar(parcelas)).isEqualTo(1);
    }

    @Test
    void falhaDoBlocoOperacaoNaCustodiaEm503ComACustodia() {
        var id = 920000006L;
        gateway.registrar(bloco("F5", id, "operacao"), 500, "erro", 0);

        var resposta = consultarOperacao(id, null)
                .expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.custodias_indisponiveis[0]").isEqualTo("F5")
                .jsonPath("$.custodias_indisponiveis.length()").isEqualTo(1)
                .returnResult();

        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void custodiaForaNaDescobertaNaoAfetaAOperacaoEncontradaEmOutra() {
        var id = 920000007L;
        gateway.registrarOperacao(id, 0);
        gateway.registrar(bloco("F5", id, "operacao"), 500, "erro", 0);

        consultarOperacao(id, null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.custodia").isEqualTo("SF");
    }

    @Test
    void operacaoEmDuasCustodiasFicaComADeMaiorPrecedencia() {
        var id = 920000008L;
        gateway.registrarOperacao("SF", id, 0);
        gateway.registrarOperacao("NSF", id, 0);

        consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.custodia").isEqualTo("NSF");

        assertThat(gateway.contar(bloco("SF", id, "parcelas"))).as("demais blocos só na vencedora").isZero();
        assertThat(gateway.contar(bloco("NSF", id, "parcelas"))).isEqualTo(1);
    }

    @Test
    void prazoDaConsultaOperacaoEstouradoDevolve504SemEsperarOMainframeNemRetentar() {
        var id = 920000005L;
        gateway.registrarOperacao(id, 0);
        var parcelas = bloco("SF", id, "parcelas");
        gateway.registrar(parcelas, 200, PAGINA_VAZIA, 4000);

        var inicio = System.nanoTime();
        var resposta = consultarOperacao(id, "parcelas")
                .expectStatus().isEqualTo(504)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.correlation_id").exists()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("limite_operacoes", "expand", "OPERACAO"))
                .returnResult();
        var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(duracaoMs).as("504 no prazo de 1 s da OPERACAO, não no atraso de 4 s").isBetween(900L, 2500L);
        assertThat(gateway.contar(parcelas)).as("timeout de resposta não é retentado").isEqualTo(1);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void prazoDaConsultaClienteValeTambemParaAListaDeOperacoes() {
        gateway.registrar(GatewayMainframeSimulado.custodia("SF") + "/clientes/70000000744/operacoes", 200,
                PAGINA_VAZIA, 4000);

        var inicio = System.nanoTime();
        var resposta = client.get().uri(ROTA + "?cpf_cnpj=70000000744")
                .header("x-tipo-consulta", "CLIENTE")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().returnResult();
        var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(duracaoMs).as("504 no prazo de 1,5 s do CLIENTE").isBetween(1400L, 3000L);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void circuitoDeUmaCustodiaEBlocoAbreSemChamarOGatewayENaoAfetaOsOutros() {
        var id = 920000070L;
        gateway.registrarOperacao(id, 0);
        var parcelas = bloco("SF", id, "parcelas");
        gateway.registrar(parcelas, 500, "erro", 0);

        abrirCircuitoDeParcelas(id);
        var chamadasComCircuitoAberto = gateway.contar(parcelas);

        var resposta = consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectHeader().valueEquals(BLOCOS_INDISPONIVEIS, "parcelas")
                .expectBody()
                .jsonPath("$.data[0].originacao.canal_contratacao").isEqualTo("MOBILE")
                .returnResult();
        ContratoOpenApi.assertConforme(resposta);

        assertThat(gateway.contar(parcelas)).as("circuito aberto não chama o gateway nem retenta")
                .isEqualTo(chamadasComCircuitoAberto);
        assertThat(metrica("resilience4j.circuitbreaker.state", "name:fontePrimaria-SF-parcelas", "state:open"))
                .isEqualTo(1.0);
        assertThat(circuitos.circuitBreaker("fontePrimaria-SF-operacao").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitos.circuitBreaker("fontePrimaria-SF-originacao").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitoMeioAbertoFechaQuandoOMainframeVolta() throws InterruptedException {
        var id = 920000080L;
        gateway.registrarOperacao(id, 0);
        var falhas = gateway.registrar(bloco("SF", id, "parcelas"), 500, "erro", 0);
        abrirCircuitoDeParcelas(id);

        gateway.remover(falhas);
        Thread.sleep(1200); // wait-duration-in-open-state = 1 s

        // Meio-aberto: as 2 chamadas permitidas dão certo e o circuito fecha
        consultarOperacao(id, "parcelas").expectStatus().isOk().expectHeader().doesNotExist(BLOCOS_INDISPONIVEIS);
        consultarOperacao(id, "parcelas").expectStatus().isOk().expectHeader().doesNotExist(BLOCOS_INDISPONIVEIS);
        assertThat(circuitos.circuitBreaker("fontePrimaria-SF-parcelas").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void timeoutsEncurtadosPeloPrazoNumaConsultaClienteNaoContamNoCircuito() {
        var cpf = "11144477735";
        gateway.registrar(GatewayMainframeSimulado.custodia("SF") + "/clientes/" + cpf + "/operacoes", 200,
                GatewayMainframeSimulado.pagina(1, null, "%018d2026-01-01".formatted(920000200L)), 0);
        // Blocos presos além do prazo de 1,5 s: o timeout de resposta de cada um é encurtado para o que resta dele
        gateway.registrarPadrao(GatewayMainframeSimulado.blocos(920000200L), 200, PAGINA_VAZIA, 4000);

        client.get().uri(ROTA + "?cpf_cnpj=" + cpf + "&expand=parcelas")
                .header("x-tipo-consulta", "CLIENTE")
                .exchange()
                .expectStatus().isEqualTo(504);

        circuitos.getAllCircuitBreakers().stream()
                .filter(circuito -> circuito.getName().startsWith("fontePrimaria-"))
                .forEach(circuito -> {
                    assertThat(circuito.getMetrics().getNumberOfFailedCalls())
                            .as("504 da aplicação não é falha do mainframe: " + circuito.getName()).isZero();
                    assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                });
    }

    @Test
    void clienteEContaSaturadosNaFontePrimariaNaoImpedemOperacao() {
        var bulkhead = bulkheads.bulkhead("fontePrimaria-cliente-conta");
        var id = 920000110L;
        gateway.registrarOperacao(id, 0);
        var ocupadas = 0;
        try {
            // Chamadas de consultas CLIENTE/CONTA em andamento ocupando todo o teto delas
            while (bulkhead.tryAcquirePermission()) {
                ocupadas++;
            }
            assertThat(ocupadas).isEqualTo(TETO_CLIENTE_CONTA);

            var cliente = client.get().uri(ROTA + "?cpf_cnpj=52998224725")
                    .header("x-tipo-consulta", "CLIENTE")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals("Retry-After", "1")
                    .expectBody().returnResult();
            var conta = client.get().uri(ROTA + "?conta=434115000065874")
                    .header("x-tipo-consulta", "CONTA")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody().returnResult();
            ContratoOpenApi.assertConforme(cliente);
            ContratoOpenApi.assertConforme(conta);
            consultarOperacao(id, null).expectStatus().isOk();
        } finally {
            for (int i = 0; i < ocupadas; i++) {
                bulkhead.onComplete();
            }
        }
        client.get().uri(ROTA + "?cpf_cnpj=52998224725&limite_operacoes=1")
                .header("x-tipo-consulta", "CLIENTE")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void bulkheadCheioDaCustodiaDeixaOsBlocosSemVagaIndisponiveis() {
        var id = 920000090L;
        gateway.registrarOperacao(id, 0);
        // Todos os blocos exceto operacao: 404 lento (bloco inexistente), ocupando a vaga por 500 ms
        var demais = "/mainframe/custodias/SF/operacoes/" + id + "/blocos/(?!operacao$).*";
        gateway.registrarPadrao(demais, 404, "", 500);

        // 8 blocos em paralelo (originacao + 7; teto do fan-out 16) contra um bulkhead de 6 chamadas sem espera
        consultarOperacao(id, "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.id_operacao").isEqualTo(id);

        assertThat(gateway.contar(demais)).as("só os blocos que conseguiram vaga chamam o gateway")
                .isEqualTo(BULKHEAD);
    }

    @Test
    void metricasDeResilienciaTemSoTagsDeBaixaCardinalidade() {
        var id = 920000100L;
        gateway.registrarOperacao(id, 0);
        consultarOperacao(id, null).expectStatus().isOk();

        for (var nome : new String[] {"resilience4j.circuitbreaker.state", "resilience4j.circuitbreaker.calls",
                "resilience4j.retry.calls", "resilience4j.bulkhead.available.concurrent.calls",
                "resilience4j.ratelimiter.available.permissions"}) {
            var tags = new ArrayList<String>();
            corpoDaMetrica(nome).path("availableTags").forEach(tag -> tags.add(tag.path("tag").asString()));
            assertThat(tags).as(nome).isSubsetOf("name", "kind", "state");
            assertThat(corpoDaMetrica(nome).path("availableTags").toString()).doesNotContain(Long.toString(id));
        }
    }

    /**
     * Falhas seguidas do bloco {@code parcelas} até o circuito {@code fontePrimaria-SF-parcelas} abrir (janela de
     * 10 chamadas, 50% de falhas; cada consulta faz 2 tentativas).
     */
    private void abrirCircuitoDeParcelas(long id) {
        var circuito = circuitos.circuitBreaker("fontePrimaria-SF-parcelas");
        for (int i = 0; i < 10 && circuito.getState() != CircuitBreaker.State.OPEN; i++) {
            consultarOperacao(id, "parcelas").expectStatus().isOk();
        }
        assertThat(circuito.getState()).as("circuito aberto depois de falhas seguidas")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    private RestTestClient.ResponseSpec consultarOperacao(long id, String expand) {
        var uri = ROTA + "?id_operacao=" + id + (expand == null ? "" : "&expand=" + expand);
        return client.get().uri(uri)
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", "resiliencia-" + UUID.randomUUID())
                .exchange();
    }

    private double metrica(String nome, String... tags) {
        var corpo = corpoDaMetrica(nome, tags);
        for (var medida : corpo.path("measurements")) {
            return medida.path("value").asDouble();
        }
        return 0.0;
    }

    private JsonNode corpoDaMetrica(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome);
        for (int i = 0; i < tags.length; i++) {
            uri.append(i == 0 ? "?" : "&").append("tag=").append(tags[i]);
        }
        var resultado = client.get().uri(uri.toString()).exchange().returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(resultado.getResponseBody());
    }
}
