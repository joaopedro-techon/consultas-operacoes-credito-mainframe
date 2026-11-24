package br.com.consultas.operacoescredito;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cache de custódia no Valkey observado pela API HTTP (ADR-0012): chave {@code custodia:{id_operacao}}, valor = código
 * da custódia, TTL de 24 h, o contrato com o serviço da visão consolidada (ADR-0017). Nenhum dado de operação vai ao
 * cache (ADR-0002). O Valkey é inspecionado direto pela porta do container; a aplicação fala com ele por um
 * Toxiproxy, que o teste derruba ou atrasa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({FontePrimariaContainers.class, CacheContainers.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";
    private static final long TTL_CUSTODIA_MS = Duration.ofHours(24).toMillis();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final Proxy proxyValkey;
    private final CircuitBreakerRegistry circuitBreakers;
    private final LettuceConnectionFactory conexoesDaAplicacao;
    private final GenericContainer<?> wireMock;
    private final RedisClient valkeyClient;
    private final StatefulRedisConnection<byte[], byte[]> valkey;

    @Autowired
    CacheIT(RestTestClient client, Proxy proxyValkey, CircuitBreakerRegistry circuitBreakers,
            LettuceConnectionFactory conexoesDaAplicacao, @Qualifier("valkey") GenericContainer<?> valkeyContainer,
            @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.proxyValkey = proxyValkey;
        this.circuitBreakers = circuitBreakers;
        this.conexoesDaAplicacao = conexoesDaAplicacao;
        this.wireMock = wireMock;
        this.valkeyClient = RedisClient.create(CacheContainers.uriDireta(valkeyContainer));
        this.valkey = valkeyClient.connect(ByteArrayCodec.INSTANCE);
    }

    @BeforeEach
    void valkeyDisponivelEVazio() throws IOException, InterruptedException {
        // Gravações no cache saem fora da requisição: as do teste anterior terminam (ou desistem no timeout de
        // comando, 10 ms) antes de limpar o Valkey
        Thread.sleep(100);
        for (var toxic : proxyValkey.toxics().getAll()) {
            toxic.remove();
        }
        proxyValkey.enable();
        // Reconexão imediata (sem esperar o backoff do Lettuce) depois de um teste com o Valkey fora
        conexoesDaAplicacao.resetConnection();
        // Abre já a conexão compartilhada da aplicação: sem isto, o primeiro comando do teste paga a conexão com o
        // connect-timeout de 20 ms e, num runner lento, falha (fail-open). Em CLIENTE/CONTA o primeiro comando é o
        // SET da custódia, que se perderia
        await().atMost(Duration.ofSeconds(5)).ignoreExceptions().until(() -> {
            try (var conexao = conexoesDaAplicacao.getConnection()) {
                return "PONG".equals(conexao.ping());
            }
        });
        circuitBreakers.circuitBreaker("valkey").reset();
        valkey.sync().flushall();
    }

    @AfterAll
    void fecharValkey() {
        valkey.close();
        valkeyClient.shutdown();
    }

    @Test
    void custodiaEhGravadaComAChaveOValorEOTtlDoContratoESemNenhumDadoDaOperacao() {
        consultarOperacao("100000001", TODOS_OS_BLOCOS);

        aguardarNoCache("custodia:100000001");
        assertThat(valkey.sync().get(chave("custodia:100000001"))).asString(StandardCharsets.UTF_8).isEqualTo("SF");
        assertThat(valkey.sync().pttl(chave("custodia:100000001")))
                .isBetween(TTL_CUSTODIA_MS - 60_000, TTL_CUSTODIA_MS);
        // Só o endereço da operação: nenhum bloco em cache (ADR-0002)
        assertThat(valkey.sync().dbsize()).isEqualTo(1);
    }

    @Test
    void custodiaEmCachePulaADescobertaNasQuatroCustodias() {
        consultarOperacao("100000002", "parcelas");
        aguardarNoCache("custodia:100000002");
        var chamadasAntes = chamadasAoGateway("100000002");

        consultarOperacao("100000002", "parcelas,saldo");

        // Um GET por bloco só na custódia lembrada (operacao, originacao, parcelas e saldo), sem a descoberta
        assertThat(chamadasAoGateway("100000002") - chamadasAntes).isEqualTo(4);
        assertThat(valkey.sync().dbsize()).isEqualTo(1);
    }

    @Test
    void custodiaEmCacheQueNaoTemMaisAOperacaoEEsquecidaERedescoberta() {
        // A operação "migrou": o cache aponta a F5, mas ela está na SF
        valkey.sync().set(chave("custodia:100000002"), chave("F5"));

        consultarOperacao("100000002", "parcelas")
                .expectBody().jsonPath("$.data[0].operacao.custodia").isEqualTo("SF");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                "SF".equals(new String(valkey.sync().get(chave("custodia:100000002")), StandardCharsets.UTF_8)));
    }

    @Test
    void valorDeCustodiaForaDoFormatoEhTratadoComoAusente() {
        valkey.sync().set(chave("custodia:100000004"), chave("XPTO"));

        consultarOperacao("100000004", "")
                .expectBody().jsonPath("$.data[0].operacao.custodia").isEqualTo("OD");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                "OD".equals(new String(valkey.sync().get(chave("custodia:100000004")), StandardCharsets.UTF_8)));
    }

    @Test
    void consultaPorClienteGravaACustodiaDeCadaOperacaoDaLista() {
        consultarLista("CLIENTE", "cpf_cnpj", "52998224725", "");

        aguardarNoCache("custodia:100000001", "custodia:100000002", "custodia:100000003");
        assertThat(valkey.sync().get(chave("custodia:100000001"))).asString(StandardCharsets.UTF_8).isEqualTo("SF");
        assertThat(valkey.sync().get(chave("custodia:100000002"))).asString(StandardCharsets.UTF_8).isEqualTo("SF");
        assertThat(valkey.sync().get(chave("custodia:100000003"))).asString(StandardCharsets.UTF_8).isEqualTo("F5");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"OPERACAO, id_operacao, 100000001", "CLIENTE, cpf_cnpj, 52998224725", "CONTA, conta, 434115000065874"})
    void valkeyForaNaoAlteraAResposta(String tipo, String parametro, String valor) throws IOException {
        var esperada = consultarLista(tipo, parametro, valor, TODOS_OS_BLOCOS).returnResult(String.class);

        proxyValkey.disable();
        var resposta = consultarLista(tipo, parametro, valor, TODOS_OS_BLOCOS).returnResult(String.class);

        assertThat(resposta.getResponseBody()).isEqualTo(esperada.getResponseBody());
        assertThat(resposta.getResponseHeaders().get(HEADER_INDISPONIVEIS))
                .isEqualTo(esperada.getResponseHeaders().get(HEADER_INDISPONIVEIS));
    }

    @Test
    void valkeyLentoNaoAlteraARespostaNemSegueOAtraso() throws IOException {
        var esperada = consultarOperacao("100000001", TODOS_OS_BLOCOS).returnResult(String.class).getResponseBody();
        aguardarNoCache("custodia:100000001");

        proxyValkey.toxics().latency("valkey-lento", ToxicDirection.DOWNSTREAM, 3_000);
        var inicio = System.nanoTime();
        var resposta = consultarOperacao("100000001", TODOS_OS_BLOCOS).returnResult(String.class).getResponseBody();
        var duracao = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(resposta).isEqualTo(esperada);
        assertThat(duracao).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void comOValkeyForaOCircuitBreakerAbreEAsConsultasParamDeTentarOValkey() throws Exception {
        proxyValkey.disable();
        for (int i = 0; i < 10; i++) {
            consultarOperacao("100000001", "parcelas");
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> circuitBreakers.circuitBreaker("valkey").getState()
                == CircuitBreaker.State.OPEN);
        // Gravações que ainda estavam saindo (fora da requisição) terminam antes da contagem
        Thread.sleep(200);
        var chamadasAoValkey = chamadasAoValkey();

        for (int i = 0; i < 5; i++) {
            consultarOperacao("100000001", "parcelas");
        }

        assertThat(chamadasAoValkey()).isEqualTo(chamadasAoValkey);
        assertThat(metrica("resilience4j.circuitbreaker.state", "name:valkey", "state:open")).isEqualTo(1.0);
    }

    /** Espera as chaves aparecerem no Valkey: a gravação no cache sai fora da requisição. */
    private void aguardarNoCache(String... chaves) {
        var bytes = java.util.Arrays.stream(chaves).map(CacheIT::chave).toArray(byte[][]::new);
        await().atMost(Duration.ofSeconds(5)).until(() -> valkey.sync().exists(bytes) == chaves.length);
    }

    private RestTestClient.ResponseSpec consultarOperacao(String idOperacao, String expand) {
        return consultarLista("OPERACAO", "id_operacao", idOperacao, expand);
    }

    private RestTestClient.ResponseSpec consultarLista(String tipo, String parametro, String valor, String expand) {
        var resposta = client.get().uri(ROTA + "?" + parametro + "={valor}&expand={expand}", valor, expand)
                .header("x-tipo-consulta", tipo)
                .exchange();
        resposta.expectStatus().isOk();
        return resposta;
    }

    private static byte[] chave(String chave) {
        return chave.getBytes(StandardCharsets.UTF_8);
    }

    /** Chamadas ao Valkey feitas pela aplicação ({@code consultas.dependencia}, {@code dependencia=valkey}). */
    private double chamadasAoValkey() {
        return metrica("consultas.dependencia", "dependencia:valkey");
    }

    /** Primeira medida (COUNT ou VALUE) da métrica no Actuator; 0 se a métrica ainda não existe. */
    private double metrica(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome);
        for (int i = 0; i < tags.length; i++) {
            uri.append(i == 0 ? '?' : '&').append("tag=").append(tags[i]);
        }
        var resultado = client.get().uri(uri.toString()).exchange().returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return 0;
        }
        return JSON.readTree(resultado.getResponseBody()).get("measurements").get(0).get("value").asDouble();
    }

    /** Requisições recebidas pelo gateway simulado para a operação (journal do WireMock). */
    private int chamadasAoGateway(String idOperacao) {
        var resposta = RestClient.create(FontePrimariaContainers.urlWireMock(wireMock))
                .post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern",
                        "/mainframe/custodias/[A-Z0-9]+/operacoes/" + idOperacao + "/blocos/.*"))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }
}
