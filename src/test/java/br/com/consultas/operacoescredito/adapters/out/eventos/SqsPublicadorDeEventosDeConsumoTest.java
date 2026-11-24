package br.com.consultas.operacoescredito.adapters.out.eventos;

import br.com.consultas.operacoescredito.application.port.out.EventoDeConsumo;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SqsPublicadorDeEventosDeConsumoTest {

    private static final String URL_FILA = "http://sqs.local/000000000000/eventos";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private final SqsFalso sqs = new SqsFalso();
    private SqsPublicadorDeEventosDeConsumo publicador;

    @AfterEach
    void parar() {
        sqs.liberar();
        if (publicador != null) {
            publicador.stop();
        }
    }

    @Test
    void enviaEmLotesDeAteDezEventos() {
        publicador = iniciar(100, Duration.ofSeconds(1), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));
        sqs.pausar();
        for (int i = 0; i < 25; i++) {
            publicador.publicar(evento("c-" + i));
        }
        sqs.liberar();

        await().atMost(Duration.ofSeconds(10)).until(() -> sqs.mensagens().size() == 25);
        assertThat(sqs.chamadas()).allSatisfy(lote -> {
            assertThat(lote.queueUrl()).isEqualTo(URL_FILA);
            assertThat(lote.entries()).hasSizeBetween(1, 10);
        });
        assertThat(sqs.chamadas().stream().filter(lote -> lote.entries().size() == 10).count()).isGreaterThanOrEqualTo(2);
        assertThat(contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS)).isEqualTo(25);
    }

    @Test
    void loteIncompletoSaiDepoisDoIntervalo() {
        publicador = iniciar(100, Duration.ofMillis(50), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));

        publicador.publicar(evento("a"));
        publicador.publicar(evento("b"));
        publicador.publicar(evento("c"));

        await().atMost(Duration.ofSeconds(5)).until(() -> sqs.mensagens().size() == 3);
        assertThat(sqs.chamadas()).hasSize(1);
    }

    @Test
    void mensagemEJsonPlanoEmSnakeCase() {
        publicador = iniciar(100, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));

        publicador.publicar(evento("json"));

        await().atMost(Duration.ofSeconds(5)).until(() -> sqs.mensagens().size() == 1);
        var corpo = JSON.readTree(sqs.mensagens().getFirst());
        assertThat(corpo.path("versao_evento").asInt()).isEqualTo(1);
        assertThat(corpo.path("correlation_id").asString()).isEqualTo("json");
        assertThat(corpo.path("cache_hits").path("parcelas").asLong()).isEqualTo(2);
    }

    @Test
    void filaCheiaDescartaSemBloquear() {
        publicador = iniciar(2, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"), bulkhead(1, 60_000));
        sqs.pausar();

        var inicio = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            publicador.publicar(evento("c-" + i));
        }

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
        assertThat(descartados("fila_cheia")).isPositive();
    }

    @Test
    void falhaDoSqsDescartaOLoteEConta() {
        publicador = iniciar(100, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));
        sqs.responder(requisicao -> {
            throw SdkClientException.create("SQS fora");
        });

        publicador.publicar(evento("x"));

        await().atMost(Duration.ofSeconds(5)).until(() -> descartados("falha_envio") == 1);
        assertThat(contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS)).isZero();
    }

    @Test
    void mensagensRecusadasNoLoteSaoContadasComoDescartadas() {
        publicador = iniciar(100, Duration.ofMillis(50), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));
        sqs.responder(requisicao -> SendMessageBatchResponse.builder()
                .successful(SendMessageBatchResultEntry.builder().id("0").messageId("m0").build())
                .failed(BatchResultErrorEntry.builder().id("1").code("InternalError").senderFault(false).build())
                .build());

        publicador.publicar(evento("ok"));
        publicador.publicar(evento("recusado"));

        await().atMost(Duration.ofSeconds(5)).until(() -> descartados("falha_envio") == 1);
        assertThat(contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS)).isEqualTo(1);
    }

    @Test
    void circuitoAbertoDescartaSemChamarOSqs() {
        var circuito = CircuitBreaker.ofDefaults("sqs");
        circuito.transitionToOpenState();
        publicador = iniciar(100, Duration.ofMillis(10), circuito, bulkhead(4, 1000));

        publicador.publicar(evento("x"));

        await().atMost(Duration.ofSeconds(5)).until(() -> descartados("circuito_aberto") == 1);
        assertThat(sqs.chamadas()).isEmpty();
    }

    @Test
    void bulkheadSemVagaDescartaOLote() {
        publicador = iniciar(100, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"), bulkhead(1, 0));
        sqs.pausar();

        publicador.publicar(evento("primeiro"));
        await().atMost(Duration.ofSeconds(5)).until(() -> sqs.emAndamento() == 1);
        publicador.publicar(evento("segundo"));

        await().atMost(Duration.ofSeconds(5)).until(() -> descartados("bulkhead_cheio") == 1);
    }

    @Test
    void encerramentoEnviaOQueRestouNaFila() {
        publicador = iniciar(100, Duration.ofHours(1), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));
        // Menos que um lote: o drenador fica esperando completar o lote até o intervalo (1 h)
        for (int i = 0; i < 9; i++) {
            publicador.publicar(evento("c-" + i));
        }
        assertThat(sqs.chamadas()).isEmpty();

        publicador.stop();

        assertThat(sqs.mensagens()).hasSize(9);
        assertThat(publicador.isRunning()).isFalse();
    }

    @Test
    void depoisDoEncerramentoOEventoEDescartado() {
        publicador = iniciar(100, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));
        publicador.stop();

        publicador.publicar(evento("tarde"));

        assertThat(descartados("encerramento")).isEqualTo(1);
    }

    @Test
    void encerramentoComSqsTravadoRespeitaOTempoMaximoEContaOQueNaoSaiu() {
        publicador = iniciar(100, Duration.ofHours(1), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000),
                Duration.ofMillis(300));
        sqs.pausar();
        for (int i = 0; i < 35; i++) {
            publicador.publicar(evento("c-" + i));
        }

        var inicio = System.nanoTime();
        publicador.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
        assertThat(contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS) + todosOsDescartados())
                .as("todo evento enviado ou contado").isEqualTo(35);
        assertThat(descartados("encerramento")).isPositive();
    }

    @Test
    void eventosPublicadosDuranteOEncerramentoSaoEnviadosOuContados() throws InterruptedException {
        publicador = iniciar(10_000, Duration.ofMillis(5), CircuitBreaker.ofDefaults("sqs"), bulkhead(4, 1000));
        var publicados = new AtomicInteger();
        var publicadores = new java.util.ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            publicadores.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 2_000; i++) {
                    publicador.publicar(evento("c-" + i));
                    publicados.incrementAndGet();
                }
            }));
        }
        Thread.sleep(5);

        publicador.stop();
        for (var thread : publicadores) {
            thread.join();
        }

        assertThat(contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS) + todosOsDescartados())
                .as("nenhum evento perdido sem contagem").isEqualTo(publicados.get());
    }

    @Test
    void drenadorEsperandoVagaNoBulkheadEInterrompidoPeloEncerramentoSemPerderOLote() throws InterruptedException {
        publicador = iniciar(100, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"), bulkhead(1, 60_000),
                Duration.ofMillis(500));
        sqs.pausar();
        publicador.publicar(evento("primeiro"));
        await().atMost(Duration.ofSeconds(5)).until(() -> sqs.emAndamento() == 1);
        // O drenador fica esperando a única vaga do bulkhead para o segundo lote
        publicador.publicar(evento("segundo"));
        Thread.sleep(200);

        publicador.stop();

        assertThat(contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS) + todosOsDescartados()).isEqualTo(2);
        assertThat(descartados("encerramento")).isEqualTo(2);
    }

    @Test
    void falhaInesperadaAoDespacharDescartaOLoteEODrenadorContinua() {
        publicador = iniciar(100, Duration.ofMillis(10), CircuitBreaker.ofDefaults("sqs"),
                falhaNaPrimeiraPermissao(bulkhead(4, 1000)));

        publicador.publicar(evento("perdido"));
        await().atMost(Duration.ofSeconds(5)).until(() -> descartados("falha_envio") == 1);
        publicador.publicar(evento("enviado"));

        await().atMost(Duration.ofSeconds(5)).until(() -> contador(SqsPublicadorDeEventosDeConsumo.METRICA_ENVIADOS) == 1);
        assertThat(descartados("fila_cheia")).isZero();
    }

    /** Bulkhead real, exceto a primeira {@code acquirePermission}, que falha com um erro inesperado. */
    private static Bulkhead falhaNaPrimeiraPermissao(Bulkhead real) {
        var primeira = new java.util.concurrent.atomic.AtomicBoolean(true);
        return (Bulkhead) java.lang.reflect.Proxy.newProxyInstance(Bulkhead.class.getClassLoader(),
                new Class<?>[]{Bulkhead.class}, (proxy, metodo, argumentos) -> {
                    if (metodo.getName().equals("acquirePermission") && primeira.getAndSet(false)) {
                        throw new IllegalStateException("inesperada");
                    }
                    try {
                        return metodo.invoke(real, argumentos);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private double todosOsDescartados() {
        return metricas.get(SqsPublicadorDeEventosDeConsumo.METRICA_DESCARTADOS).counters().stream()
                .mapToDouble(contador -> contador.count()).sum();
    }

    private SqsPublicadorDeEventosDeConsumo iniciar(int capacidade, Duration intervalo, CircuitBreaker circuito,
                                                    Bulkhead bulkhead) {
        return iniciar(capacidade, intervalo, circuito, bulkhead, Duration.ofSeconds(5));
    }

    private SqsPublicadorDeEventosDeConsumo iniciar(int capacidade, Duration intervalo, CircuitBreaker circuito,
                                                    Bulkhead bulkhead, Duration tempoMaximoDrenagem) {
        var properties = new EventosSqsProperties(true,
                new EventosSqsProperties.Fila(capacidade, 10, intervalo, tempoMaximoDrenagem),
                new EventosSqsProperties.Sqs(URL_FILA, null, "sa-east-1", null, Duration.ofSeconds(1),
                        Duration.ofSeconds(3)));
        var novo = new SqsPublicadorDeEventosDeConsumo(sqs, properties, circuito, bulkhead,
                ObservationRegistry.NOOP, metricas);
        novo.start();
        return novo;
    }

    private static Bulkhead bulkhead(int maximo, long esperaMs) {
        return Bulkhead.of("sqs", BulkheadConfig.custom()
                .maxConcurrentCalls(maximo)
                .maxWaitDuration(Duration.ofMillis(esperaMs))
                .build());
    }

    private double contador(String nome) {
        return metricas.get(nome).counter().count();
    }

    private double descartados(String motivo) {
        return metricas.get(SqsPublicadorDeEventosDeConsumo.METRICA_DESCARTADOS).tag("motivo", motivo).counter().count();
    }

    private static EventoDeConsumo evento(String correlationId) {
        return new EventoDeConsumo(EventoDeConsumo.VERSAO, Instant.parse("2026-11-23T10:15:30Z"), correlationId,
                null, "teste", "fonte_primaria", "OPERACAO", 100000001L, null, null,
                List.of("operacao", "originacao", "parcelas"), List.of(), null, null, 1, List.of(),
                Map.of("parcelas", 2L), Map.of(), 200, null, 12);
    }

    /** SQS em memória: aceita tudo (ou responde como o teste mandar) e pode segurar os envios. */
    private static final class SqsFalso implements SqsClient {

        private final List<SendMessageBatchRequest> chamadas = new CopyOnWriteArrayList<>();
        private volatile Function<SendMessageBatchRequest, SendMessageBatchResponse> resposta = SqsFalso::sucesso;
        private volatile CountDownLatch pausa = new CountDownLatch(0);
        private final AtomicInteger emAndamento = new AtomicInteger();

        @Override
        public SendMessageBatchResponse sendMessageBatch(SendMessageBatchRequest requisicao) {
            emAndamento.incrementAndGet();
            try {
                pausa.await(30, TimeUnit.SECONDS);
                chamadas.add(requisicao);
                return resposta.apply(requisicao);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw SdkClientException.create("interrompido");
            } finally {
                emAndamento.decrementAndGet();
            }
        }

        void pausar() {
            pausa = new CountDownLatch(1);
        }

        void liberar() {
            pausa.countDown();
        }

        void responder(Function<SendMessageBatchRequest, SendMessageBatchResponse> resposta) {
            this.resposta = resposta;
        }

        int emAndamento() {
            return emAndamento.get();
        }

        List<SendMessageBatchRequest> chamadas() {
            return chamadas;
        }

        List<String> mensagens() {
            return chamadas.stream().flatMap(lote -> lote.entries().stream()).map(e -> e.messageBody()).toList();
        }

        private static SendMessageBatchResponse sucesso(SendMessageBatchRequest requisicao) {
            return SendMessageBatchResponse.builder()
                    .successful(requisicao.entries().stream()
                            .map(e -> SendMessageBatchResultEntry.builder().id(e.id()).messageId("m-" + e.id()).build())
                            .toList())
                    .build();
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
            // nada a fechar
        }
    }
}
