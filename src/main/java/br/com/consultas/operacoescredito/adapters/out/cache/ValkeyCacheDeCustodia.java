package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Custodia;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Cache de custódia no Valkey: chave {@code custodia:{id_operacao}}, valor = código da custódia, TTL
 * {@code ttl-custodia} (ADR-0012). Chave, valor e TTL são contrato com o serviço da visão consolidada, que lê e
 * grava as mesmas chaves no mesmo Valkey (ADR-0017).
 * <ul>
 *     <li>Leitura, na requisição: um {@code MGET} da chave, esperado no máximo pelo timeout de comando
 *     ({@code spring.data.redis.timeout}). Não lê com menos de {@code prazo-minimo-restante} do prazo da
 *     requisição.</li>
 *     <li>Gravação e remoção, fora da requisição (virtual thread, no máximo {@code max-gravacoes-simultaneas}):
 *     um {@code SET ... PX} por custódia, em rodadas de até {@code chaves-por-comando} comandos em pipeline, cada
 *     rodada dentro do timeout de comando; um {@code DEL} por remoção. A resposta da consulta não espera.</li>
 * </ul>
 * Só comandos de uma chave e {@code MGET} (sem cluster mode). Fail-open: toda leitura e cada gravação passam pelo
 * circuit breaker {@value CacheConfig#CIRCUIT_BREAKER}; falha ou circuito aberto viram "não sei" (a consulta refaz
 * a descoberta) e "não gravado".
 * <p>
 * Cada comando é uma observação {@code consultas.dependencia} ({@code dependencia=valkey},
 * {@code operacao=MGET|SET|DEL}).
 */
class ValkeyCacheDeCustodia implements CacheDeCustodia {

    static final String DEPENDENCIA = "valkey";

    private static final Logger log = LoggerFactory.getLogger(ValkeyCacheDeCustodia.class);

    private final ComandosValkey comandos;
    private final CircuitBreaker circuitBreaker;
    private final ObservationRegistry observationRegistry;
    private final Duration timeoutComando;
    private final long ttlCustodiaEmMilissegundos;
    private final int chavesPorComando;
    private final int maxChavesPorConsulta;
    private final Duration prazoMinimoRestante;
    private final Semaphore vagasDeGravacao;
    private final Executor gravacoes;

    ValkeyCacheDeCustodia(ComandosValkey comandos, CircuitBreaker circuitBreaker,
                          ObservationRegistry observationRegistry, CacheProperties properties,
                          Duration timeoutComando, Executor gravacoes) {
        this.comandos = comandos;
        this.circuitBreaker = circuitBreaker;
        this.observationRegistry = observationRegistry;
        this.timeoutComando = timeoutComando;
        this.ttlCustodiaEmMilissegundos = properties.ttlCustodia().toMillis();
        this.chavesPorComando = properties.chavesPorComando();
        this.maxChavesPorConsulta = properties.maxChavesPorConsulta();
        this.prazoMinimoRestante = properties.prazoMinimoRestante();
        this.vagasDeGravacao = new Semaphore(properties.maxGravacoesSimultaneas());
        this.gravacoes = gravacoes;
    }

    @Override
    public Optional<Custodia> buscar(long idOperacao) {
        if (!PrazoDaRequisicao.comporta(prazoMinimoRestante)) {
            return Optional.empty();
        }
        var chave = new byte[][] {bytes(CacheDeCustodia.chave(idOperacao))};
        return executar("MGET", null, () -> mget(chave))
                .map(valores -> valores.get(0))
                // Valor fora do formato (outra versão, edição manual) é tratado como ausente
                .flatMap(valor -> Custodia.porCodigo(new String(valor, StandardCharsets.UTF_8)));
    }

    @Override
    public void gravar(long idOperacao, Custodia custodia) {
        gravarTodas(Map.of(idOperacao, custodia));
    }

    /**
     * Agenda a gravação e volta na hora. Descartada (sem erro) com chaves demais, sem vaga de gravação ou com o
     * circuito aberto.
     */
    @Override
    public void gravarTodas(Map<Long, Custodia> custodias) {
        if (custodias.isEmpty() || custodias.size() > maxChavesPorConsulta) {
            return;
        }
        var valores = new ArrayList<Gravacao>(custodias.size());
        custodias.forEach((id, custodia) -> valores.add(
                new Gravacao(bytes(CacheDeCustodia.chave(id)), bytes(custodia.codigo()))));
        agendar("SET", valores.size(), () -> set(valores));
    }

    @Override
    public void remover(long idOperacao) {
        var chave = bytes(CacheDeCustodia.chave(idOperacao));
        agendar("DEL", 1, () -> aguardar(List.of(comandos.del(chave)), timeoutComando));
    }

    /** Executa {@code comando} fora da requisição, se houver vaga de gravação; senão descarta. */
    private void agendar(String comando, int chaves, Runnable acao) {
        if (!vagasDeGravacao.tryAcquire()) {
            log.debug("Gravações no Valkey todas em andamento; {} chaves não gravadas ({})", chaves, comando);
            return;
        }
        var pai = observationRegistry.getCurrentObservation();
        try {
            gravacoes.execute(() -> {
                try {
                    executar(comando, pai, () -> {
                        acao.run();
                        return Boolean.TRUE;
                    });
                } finally {
                    vagasDeGravacao.release();
                }
            });
        } catch (RejectedExecutionException e) {
            vagasDeGravacao.release();
        }
    }

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    /** Um {@code SET chave valor PX ttl-custodia}. */
    private record Gravacao(byte[] chave, byte[] valor) {
    }

    private List<byte[]> mget(byte[][] chaves) {
        var futuro = comandos.mget(chaves);
        aguardar(List.of(futuro), PrazoDaRequisicao.limitar(timeoutComando));
        var valores = futuro.join();
        if (valores.size() != chaves.length) {
            throw new IllegalStateException("MGET devolveu " + valores.size() + " valores para " + chaves.length
                    + " chaves");
        }
        return valores;
    }

    /** {@code SET ... PX} em rodadas de até {@code chaves-por-comando} comandos em pipeline. */
    private void set(List<Gravacao> gravacoes) {
        for (int inicio = 0; inicio < gravacoes.size(); inicio += chavesPorComando) {
            var futuros = new ArrayList<CompletableFuture<?>>();
            for (var gravacao : gravacoes.subList(inicio, Math.min(inicio + chavesPorComando, gravacoes.size()))) {
                futuros.add(comandos.set(gravacao.chave(), gravacao.valor(), ttlCustodiaEmMilissegundos));
            }
            aguardar(futuros, timeoutComando);
        }
    }

    /** Espera todos os futuros por até {@code timeout} no total; o primeiro que falhar derruba a espera. */
    private static void aguardar(List<? extends CompletableFuture<?>> futuros, Duration timeout) {
        try {
            CompletableFuture.allOf(futuros.toArray(CompletableFuture[]::new))
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            futuros.forEach(futuro -> futuro.cancel(false));
            throw new RedisCommandTimeoutException("Valkey sem resposta em " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new IllegalStateException("Falha no Valkey", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisCommandInterruptedException(e);
        }
    }

    /** Executa a chamada sob o circuit breaker; qualquer falha vira {@code Optional.empty()} (fail-open). */
    private <T> Optional<T> executar(String operacao, Observation pai, Supplier<T> chamada) {
        try {
            return Optional.ofNullable(circuitBreaker.executeSupplier(() ->
                    Observacoes.dependencia(observationRegistry, DEPENDENCIA, operacao)
                            .parentObservation(pai)
                            .observe(chamada)));
        } catch (CallNotPermittedException e) {
            log.debug("Circuit breaker do Valkey aberto; {} ignorado, consulta segue sem o cache", operacao);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Falha no Valkey ({}); consulta segue sem o cache: {}", operacao, e.toString());
            return Optional.empty();
        }
    }
}
