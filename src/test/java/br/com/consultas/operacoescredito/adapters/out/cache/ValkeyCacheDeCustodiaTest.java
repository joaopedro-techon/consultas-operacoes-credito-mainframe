package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Custodia;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache de custódia com um Valkey em memória que responde, atrasa ou falha como o teste mandar: chave, valor e TTL
 * do contrato, gravação fora da requisição em rodadas pequenas em pipeline, prazo curto sem tocar no Valkey e
 * fail-open com o circuit breaker.
 */
class ValkeyCacheDeCustodiaTest {

    private static final Duration TIMEOUT_COMANDO = Duration.ofMillis(500);
    private static final long TTL_CUSTODIA_MS = Duration.ofHours(24).toMillis();

    private final ValkeyFalso valkey = new ValkeyFalso();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("valkey", CircuitBreakerConfig.custom()
            .slidingWindowSize(100).minimumNumberOfCalls(100).build());
    private final List<Runnable> gravacoesAgendadas = new CopyOnWriteArrayList<>();

    @AfterEach
    void limpar() {
        PrazoDaRequisicao.encerrar();
    }

    @Test
    void leCustodiaPelaChaveDoContratoNumMgetDeUmaChave() {
        var cache = cache(Runnable::run, 500);
        valkey.guardar("custodia:100000001", "NSF");

        assertThat(cache.buscar(100000001L)).contains(Custodia.NSF);
        assertThat(valkey.tamanhosDosMget()).containsExactly(1);
    }

    @Test
    void custodiaAusenteOuForaDoFormatoVemVazia() {
        var cache = cache(Runnable::run, 500);
        valkey.guardar("custodia:100000002", "XPTO");

        assertThat(cache.buscar(100000001L)).isEmpty();
        assertThat(cache.buscar(100000002L)).isEmpty();
    }

    @Test
    void gravaOCodigoDaCustodiaComOTtlDoContrato() {
        var cache = cache(Runnable::run, 500);

        cache.gravar(100000001L, Custodia.F5);

        assertThat(valkey.valor("custodia:100000001")).isEqualTo("F5");
        assertThat(valkey.ttls()).containsExactly(TTL_CUSTODIA_MS);
    }

    @Test
    void gravacaoGrandeViraRodadasDeAteCemSetsEmPipeline() {
        var cache = cache(Runnable::run, 500);
        var custodias = new HashMap<Long, Custodia>();
        for (long id = 1; id <= 250; id++) {
            custodias.put(id, Custodia.SF);
        }

        cache.gravarTodas(custodias);

        assertThat(valkey.sets()).hasValue(250);
        assertThat(valkey.maximoPendentes()).as("SETs em pipeline, no máximo 100 por rodada").isEqualTo(100);
        assertThat(valkey.ttls()).containsOnly(TTL_CUSTODIA_MS);
    }

    @Test
    void gravacaoComCustodiasDemaisEDescartada() {
        var cache = cache(Runnable::run, 2);

        cache.gravarTodas(Map.of(1L, Custodia.SF, 2L, Custodia.OD, 3L, Custodia.F5));
        cache.gravarTodas(Map.of());

        assertThat(valkey.sets()).hasValue(0);
    }

    @Test
    void gravacaoERemocaoNaoRodamNaThreadDaRequisicao() {
        var cache = cache(gravacoesAgendadas::add, 500);
        valkey.guardar("custodia:100000003", "SF");

        cache.gravar(100000001L, Custodia.SF);

        assertThat(valkey.sets()).hasValue(0);
        assertThat(gravacoesAgendadas).hasSize(1);
        gravacoesAgendadas.getFirst().run();
        assertThat(valkey.sets()).hasValue(1);

        cache.remover(100000003L);
        assertThat(valkey.valor("custodia:100000003")).isEqualTo("SF");
        gravacoesAgendadas.get(1).run();
        assertThat(valkey.valor("custodia:100000003")).isNull();
    }

    @Test
    void semVagaDeGravacaoAGravacaoEDescartada() {
        var cache = cache(gravacoesAgendadas::add, 500);

        cache.gravar(1L, Custodia.SF);
        cache.gravar(2L, Custodia.SF);
        cache.remover(3L);

        // max-gravacoes-simultaneas = 1: só a primeira foi agendada
        assertThat(gravacoesAgendadas).hasSize(1);
    }

    @Test
    void poucoPrazoRestanteNaoTocaNoValkey() {
        var cache = cache(Runnable::run, 500);
        valkey.guardar("custodia:100000001", "SF");
        PrazoDaRequisicao.iniciar(Duration.ofMillis(5));

        assertThat(cache.buscar(100000001L)).isEmpty();
        assertThat(valkey.tamanhosDosMget()).isEmpty();
    }

    @Test
    void valkeyForaViraNaoSeiEFalhaNoCircuito() {
        var cache = cache(Runnable::run, 500);
        valkey.falharComConexao();

        assertThat(cache.buscar(100000001L)).isEmpty();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void circuitoAbertoNaoChamaOValkey() {
        var cache = cache(Runnable::run, 500);
        valkey.guardar("custodia:100000001", "SF");
        circuitBreaker.transitionToOpenState();

        assertThat(cache.buscar(100000001L)).isEmpty();
        cache.gravar(100000002L, Custodia.SF);

        assertThat(valkey.tamanhosDosMget()).isEmpty();
        assertThat(valkey.sets()).hasValue(0);
    }

    @Test
    void valkeySemRespostaDesisteNoTimeoutDeComando() {
        var cache = cache(Runnable::run, 500);
        valkey.naoResponder();

        var inicio = System.nanoTime();
        assertThat(cache.buscar(100000001L)).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private ValkeyCacheDeCustodia cache(Executor gravacoes, int maxChavesPorConsulta) {
        // Uma gravação em andamento por vez
        var properties = new CacheProperties(true, 100, maxChavesPorConsulta, Duration.ofMillis(20), 1,
                Duration.ofHours(24));
        return new ValkeyCacheDeCustodia(valkey, circuitBreaker, ObservationRegistry.NOOP, properties,
                TIMEOUT_COMANDO, gravacoes);
    }

    private static byte[] bytes(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Valkey em memória. Cada resposta sai {@link #DEMORA} depois do comando, então os comandos enviados em
     * sequência sem esperar (pipeline) ficam pendentes juntos: o máximo de pendentes mostra o tamanho do pipeline.
     */
    private static final class ValkeyFalso implements ComandosValkey {

        private static final long DEMORA = 30;

        private final Map<String, byte[]> dados = new ConcurrentHashMap<>();
        private final List<Integer> tamanhosDosMget = new CopyOnWriteArrayList<>();
        private final List<Long> ttls = new CopyOnWriteArrayList<>();
        private final AtomicInteger sets = new AtomicInteger();
        private final AtomicInteger pendentes = new AtomicInteger();
        private final AtomicInteger maximoPendentes = new AtomicInteger();
        private volatile boolean semConexao;
        private volatile boolean semResposta;

        void guardar(String chave, String valor) {
            dados.put(chave, bytes(valor));
        }

        String valor(String chave) {
            var valor = dados.get(chave);
            return valor == null ? null : new String(valor, StandardCharsets.UTF_8);
        }

        void falharComConexao() {
            semConexao = true;
        }

        void naoResponder() {
            semResposta = true;
        }

        List<Integer> tamanhosDosMget() {
            return tamanhosDosMget;
        }

        int maximoPendentes() {
            return maximoPendentes.get();
        }

        AtomicInteger sets() {
            return sets;
        }

        List<Long> ttls() {
            return ttls;
        }

        @Override
        public CompletableFuture<List<byte[]>> mget(byte[][] chaves) {
            if (semConexao) {
                throw new RedisConnectionFailureException("Valkey fora");
            }
            tamanhosDosMget.add(chaves.length);
            var valores = new ArrayList<byte[]>();
            for (var chave : chaves) {
                valores.add(dados.get(new String(chave, StandardCharsets.UTF_8)));
            }
            return responder(valores);
        }

        @Override
        public CompletableFuture<?> del(byte[] chave) {
            dados.remove(new String(chave, StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(1L);
        }

        @Override
        public CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos) {
            sets.incrementAndGet();
            ttls.add(ttlMilissegundos);
            dados.put(new String(chave, StandardCharsets.UTF_8), valor);
            return responder(Boolean.TRUE);
        }

        private <T> CompletableFuture<T> responder(T valor) {
            var futuro = new CompletableFuture<T>();
            maximoPendentes.accumulateAndGet(pendentes.incrementAndGet(), Math::max);
            if (!semResposta) {
                CompletableFuture.delayedExecutor(DEMORA, TimeUnit.MILLISECONDS).execute(() -> {
                    pendentes.decrementAndGet();
                    futuro.complete(valor);
                });
            }
            return futuro;
        }
    }
}
