package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Evento de consumo (seção 11 da especificação, ADR-0004) observado na fila SQS do LocalStack depois de consultas
 * reais pela API HTTP: um evento JSON plano em snake_case por requisição, CPF/CNPJ e conta só como HMAC-SHA256,
 * {@code rota = fonte_primaria}, os contadores de cache por bloco sempre vazios (o cache de blocos é só da visão
 * consolidada) e a fila em memória drenada no encerramento gracioso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import({FontePrimariaContainers.class, SqsContainers.class, EventosDeConsumoContainers.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventoDeConsumoIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String CPF = "52998224725";
    private static final String CNPJ_ALFANUMERICO = "12ABC34501DE35";
    private static final String CONTA = "434115000065874";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final LocalStackContainer localStack;
    private final SqsClient sqs;
    private final String urlFila;
    private final GenericContainer<?> wireMock;
    /** Corpo cru de cada evento lido da fila, por correlation_id (a fila é compartilhada pelos testes). */
    private final Map<String, String> eventos = new ConcurrentHashMap<>();

    @Autowired
    EventoDeConsumoIT(RestTestClient client, LocalStackContainer localStack,
                      @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.localStack = localStack;
        this.sqs = EventosDeConsumoContainers.sqs(localStack);
        this.urlFila = sqs.getQueueUrl(b -> b.queueName(EventosDeConsumoContainers.FILA)).queueUrl();
        this.wireMock = wireMock;
    }

    @AfterAll
    void fechar() {
        sqs.close();
    }

    @Test
    void consultaPorOperacaoPublicaUmEventoPlanoComOContexto() {
        var correlationId = novoCorrelationId();
        var inicio = Instant.now();

        client.get().uri(ROTA + "?id_operacao=100000001&expand=parcelas,saldo"
                        + "&fields=operacao.situacao_operacao_credito,parcelas.numero_parcela_amortizacao")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", "sistema-custodia")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("versao_evento").asInt()).isEqualTo(1);
        assertThat(Instant.parse(evento.path("timestamp").asString()))
                .isBetween(inicio.minusSeconds(1), Instant.now());
        assertThat(evento.path("correlation_id").asString()).isEqualTo(correlationId);
        assertThat(evento.path("consumidor").asString()).isEqualTo("sistema-custodia");
        assertThat(evento.path("rota").asString()).isEqualTo("fonte_primaria");
        assertThat(evento.path("tipo_consulta").asString()).isEqualTo("OPERACAO");
        assertThat(evento.path("id_operacao").isNumber()).isTrue();
        assertThat(evento.path("id_operacao").asLong()).isEqualTo(100000001L);
        assertThat(textos(evento, "blocos_solicitados")).containsExactly("operacao", "originacao", "parcelas", "saldo");
        assertThat(textos(evento, "campos_solicitados"))
                .containsExactly("operacao.situacao_operacao_credito", "parcelas.numero_parcela_amortizacao");
        assertThat(evento.path("quantidade_operacoes_retornadas").asInt()).isEqualTo(1);
        assertThat(textos(evento, "blocos_indisponiveis")).isEmpty();
        assertThat(evento.path("status_http").asInt()).isEqualTo(200);
        assertThat(evento.path("duracao_ms").isNumber()).isTrue();
        assertThat(evento.path("duracao_ms").asLong()).isNotNegative();
        assertThat(evento.has("codigo_erro")).isFalse();
        assertThat(evento.has("cpf_cnpj_hmac")).isFalse();
        assertThat(evento.has("conta_hmac")).isFalse();
        assertThat(evento.has("filtro")).isFalse();
        assertThat(evento.has("limite_operacoes")).isFalse();
        assertThat(evento.path("cache_hits").isObject()).isTrue();
        assertThat(evento.path("cache_misses").isObject()).isTrue();
        // JSON plano: nenhum objeto aninhado além dos contadores de cache por bloco
        evento.properties().forEach(campo -> {
            assertThat(campo.getKey()).matches("[a-z][a-z0-9_]*");
            if (!campo.getKey().startsWith("cache_")) {
                assertThat(campo.getValue().isObject()).as(campo.getKey()).isFalse();
            }
        });
    }

    @Test
    void consultaPorClienteLevaCpfSoComoHmac() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?cpf_cnpj=" + CPF + "&limite_operacoes=2")
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("cpf_cnpj_hmac").asString()).isEqualTo(EventosDeConsumoContainers.hmac(CPF))
                .matches("[0-9a-f]{64}");
        assertThat(evento.has("conta_hmac")).isFalse();
        assertThat(evento.has("id_operacao")).isFalse();
        assertThat(evento.path("tipo_consulta").asString()).isEqualTo("CLIENTE");
        assertThat(evento.path("consumidor").asString()).isEqualTo("desconhecido");
        assertThat(evento.path("limite_operacoes").asInt()).isEqualTo(2);
        assertThat(evento.path("quantidade_operacoes_retornadas").asInt()).isEqualTo(2);
        assertThat(eventos.get(correlationId)).doesNotContain(CPF);
    }

    @Test
    void cnpjAlfanumericoTambemVaiSoComoHmac() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?cpf_cnpj=" + CNPJ_ALFANUMERICO)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("cpf_cnpj_hmac").asString())
                .isEqualTo(EventosDeConsumoContainers.hmac(CNPJ_ALFANUMERICO));
        assertThat(evento.path("quantidade_operacoes_retornadas").asInt()).isEqualTo(2);
        assertThat(eventos.get(correlationId)).doesNotContain(CNPJ_ALFANUMERICO);
    }

    @Test
    void consultaPorContaLevaContaSoComoHmac() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?conta=" + CONTA)
                .header("x-tipo-consulta", "CONTA")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("conta_hmac").asString()).isEqualTo(EventosDeConsumoContainers.hmac(CONTA));
        assertThat(evento.has("cpf_cnpj_hmac")).isFalse();
        assertThat(evento.path("quantidade_operacoes_retornadas").asInt()).isEqualTo(2);
        assertThat(eventos.get(correlationId)).doesNotContain(CONTA);
    }

    @Test
    void cpfDentroDoFiltroVaiComoHmac() {
        var correlationId = novoCorrelationId();
        var filtro = "operacao.cpf_cnpj==" + CPF + ";operacao.situacao_operacao_credito==ATIVA";

        client.get().uri(ROTA + "?cpf_cnpj=" + CPF + "&filter={filtro}", filtro)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("filtro").asString()).isEqualTo("operacao.cpf_cnpj=="
                + EventosDeConsumoContainers.hmac(CPF) + ";operacao.situacao_operacao_credito==ATIVA");
        assertThat(eventos.get(correlationId)).doesNotContain(CPF);
    }

    @Test
    void documentoComPontuacaoDentroDoFiltroNuncaVaiEmClaroEmNenhumaForma() {
        var correlationId = novoCorrelationId();
        var cpfPontuado = "529.982.247-25";
        var cnpjPontuado = "12.ABC.345/01DE-35";
        var filtro = "operacao.cpf_cnpj==" + cpfPontuado + ",originacao.dependencia_contabil==" + cnpjPontuado;

        client.get().uri(ROTA + "?cpf_cnpj=" + CPF + "&filter={filtro}", filtro)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        // Sem a pontuação, o HMAC do filtro casa com o cpf_cnpj_hmac
        assertThat(evento.path("filtro").asString()).isEqualTo("operacao.cpf_cnpj==" + EventosDeConsumoContainers.hmac(CPF)
                + ",originacao.dependencia_contabil==" + EventosDeConsumoContainers.hmac(CNPJ_ALFANUMERICO));
        assertThat(evento.path("cpf_cnpj_hmac").asString()).isEqualTo(EventosDeConsumoContainers.hmac(CPF));
        assertThat(eventos.get(correlationId)).doesNotContain(CPF, cpfPontuado, "529.982", "982.247", "247-25",
                CNPJ_ALFANUMERICO, cnpjPontuado, "ABC.345", "345/01DE", "345\\/01DE");
    }

    @Test
    void consumidorForaDoFormatoVaiComoInvalidoNoEvento() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", "consumidor com espaco e cpf " + CPF)
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("consumidor").asString()).isEqualTo("invalido");
        assertThat(eventos.get(correlationId)).doesNotContain(CPF);
    }

    @Test
    void parametroRepetidoE400ComEvento() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001&expand=parcelas&expand=saldo")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isBadRequest();

        var evento = evento(correlationId);
        assertThat(evento.path("status_http").asInt()).isEqualTo(400);
        assertThat(evento.path("codigo_erro").asString()).isEqualTo("requisicao_invalida");
    }

    @Test
    void operacaoInexistenteGeraEventoComStatusECodigoDeErro() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=999999999")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isNotFound();

        var evento = evento(correlationId);
        assertThat(evento.path("status_http").asInt()).isEqualTo(404);
        assertThat(evento.path("codigo_erro").asString()).isEqualTo("operacao_nao_encontrada");
        assertThat(evento.path("quantidade_operacoes_retornadas").asInt()).isZero();
    }

    @Test
    void requisicaoInvalidaGeraEventoSemDadoPessoalEmClaro() {
        var correlationId = novoCorrelationId();
        var cpfInvalido = "52998224700";

        client.get().uri(ROTA + "?cpf_cnpj=" + cpfInvalido + "&expand=parcelas," + CPF + "&fields=" + CPF)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isBadRequest();

        var evento = evento(correlationId);
        assertThat(evento.path("status_http").asInt()).isEqualTo(400);
        assertThat(evento.path("codigo_erro").asString()).isEqualTo("requisicao_invalida");
        assertThat(evento.path("cpf_cnpj_hmac").asString()).isEqualTo(EventosDeConsumoContainers.hmac(cpfInvalido));
        // Só blocos e campos bem formados entram no evento; o resto não é dado de negócio
        assertThat(textos(evento, "blocos_solicitados")).containsExactly("operacao", "originacao", "parcelas");
        assertThat(textos(evento, "campos_solicitados")).isEmpty();
        assertThat(eventos.get(correlationId)).doesNotContain(CPF).doesNotContain(cpfInvalido);
    }

    @Test
    void blocosIndisponiveisVaoNoEvento() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000003&expand=parcelas,saldo,plano")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        assertThat(textos(evento(correlationId), "blocos_indisponiveis")).containsExactly("plano", "saldo");
    }

    @Test
    void consultaNaFontePrimariaIndicaARotaESemCacheDeBlocos() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001&expand=saldo")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var evento = evento(correlationId);
        assertThat(evento.path("rota").asString()).isEqualTo("fonte_primaria");
        assertThat(evento.path("cache_hits").properties()).isEmpty();
        assertThat(evento.path("cache_misses").properties()).isEmpty();
    }

    @Test
    void eventoLevaOTraceIdDaRequisicao() {
        var correlationId = novoCorrelationId();
        var traceId = UUID.randomUUID().toString().replace("-", "");

        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isOk();

        assertThat(evento(correlationId).path("trace_id").asString()).isEqualTo(traceId);
    }

    @Test
    void eventosAindaNaFilaEmMemoriaSaoEnviadosNoEncerramentoGracioso() throws Exception {
        // Outra instância da aplicação, com lote grande e intervalo longo: nada sai antes do encerramento
        var argumentos = new String[]{
                "--server.port=0",
                "--consultas.fonte-primaria.url=" + FontePrimariaContainers.urlWireMock(wireMock),
                "--consultas.eventos.habilitado=true",
                "--consultas.eventos.segredo-hmac=" + EventosDeConsumoContainers.SEGREDO_HMAC,
                "--consultas.eventos.fila.intervalo-envio=1h",
                "--consultas.eventos.sqs.endpoint=" + localStack.getEndpoint(),
                "--consultas.eventos.sqs.url-fila=" + EventosDeConsumoContainers.urlFila(localStack),
                "--consultas.eventos.sqs.regiao=" + localStack.getRegion(),
                "--consultas.eventos.sqs.credenciais.access-key-id=" + localStack.getAccessKey(),
                "--consultas.eventos.sqs.credenciais.secret-access-key=" + localStack.getSecretKey(),
        };
        var correlationIds = List.of(novoCorrelationId(), novoCorrelationId(), novoCorrelationId());
        var aplicacao = new SpringApplicationBuilder(ConsultasOperacoesCreditoApplication.class).run(argumentos);
        try (var http = HttpClient.newHttpClient()) {
            var porta = aplicacao.getEnvironment().getProperty("local.server.port");
            for (var correlationId : correlationIds) {
                var resposta = http.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + porta + ROTA + "?id_operacao=100000001"))
                        .header("x-tipo-consulta", "OPERACAO")
                        .header("x-correlationid", correlationId)
                        .build(), HttpResponse.BodyHandlers.discarding());
                assertThat(resposta.statusCode()).isEqualTo(200);
            }
            lerFila();
            assertThat(eventos).doesNotContainKeys(correlationIds.toArray(String[]::new));
        } finally {
            aplicacao.close();
        }

        correlationIds.forEach(this::evento);
    }

    private JsonNode evento(String correlationId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            lerFila();
            return eventos.containsKey(correlationId);
        });
        return JSON.readTree(eventos.get(correlationId));
    }

    /** Lê (e apaga) o que houver na fila, guardando cada evento pelo correlation_id. */
    private void lerFila() {
        List<Message> mensagens;
        do {
            mensagens = sqs.receiveMessage(b -> b.queueUrl(urlFila).maxNumberOfMessages(10).waitTimeSeconds(1))
                    .messages();
            for (var mensagem : mensagens) {
                eventos.put(JSON.readTree(mensagem.body()).path("correlation_id").asString(), mensagem.body());
            }
            if (!mensagens.isEmpty()) {
                var lidas = mensagens;
                sqs.deleteMessageBatch(b -> b.queueUrl(urlFila).entries(lidas.stream()
                        .map(m -> DeleteMessageBatchRequestEntry.builder()
                                .id(m.messageId()).receiptHandle(m.receiptHandle()).build())
                        .toList()));
            }
        } while (!mensagens.isEmpty());
    }

    private static List<String> textos(JsonNode evento, String campo) {
        assertThat(evento.path(campo).isArray()).as(campo).isTrue();
        return evento.path(campo).valueStream().map(JsonNode::asString).toList();
    }

    private static String novoCorrelationId() {
        return "teste-" + UUID.randomUUID();
    }
}
