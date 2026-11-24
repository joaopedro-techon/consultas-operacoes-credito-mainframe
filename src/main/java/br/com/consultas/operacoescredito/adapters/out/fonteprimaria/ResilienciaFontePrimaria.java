package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.CotaDaFonteEsgotadaException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Custodia;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Proteção própria da fonte primária, separada por rota (ADR-0014):
 * <ul>
 *     <li>circuit breaker e retry por custódia × bloco (ou lista): {@code fontePrimaria-<CUSTODIA>-<rota>},
 *     criados na primeira chamada a partir da configuração base {@value #NOME} do {@code application.yml}. Uma
 *     instância com o nome exato declarada no {@code application.yml} sobrescreve a base para aquela rota;</li>
 *     <li>bulkhead por custódia ({@code fontePrimaria-<CUSTODIA>}): uma custódia lenta não ocupa as vagas das
 *     outras; mais o bulkhead {@value #BULKHEAD_CLIENTE_CONTA} das consultas CLIENTE/CONTA (ADR-0009);</li>
 *     <li>rate limiter único {@value #NOME}: a cota do mainframe inteiro dividida pelas instâncias (ADR-0015).</li>
 * </ul>
 * Cada chamada passa, de fora para dentro, por retry → circuit breaker → rate limiter → bulkhead de CLIENTE/CONTA
 * (só nessas consultas) → bulkhead da custódia. Assim cada tentativa conta no circuito e consome a cota, e circuito
 * aberto, cota esgotada e bulkheads recusam sem retry (e sem contar no circuito). A classificação das falhas vem de
 * {@link FalhasDoGateway}: o circuito conta só o que é culpa do mainframe e o retry só tenta o retentável, e só se
 * o prazo restante da requisição comportar outra tentativa.
 */
final class ResilienciaFontePrimaria {

    /** Configuração base das instâncias (e prefixo dos nomes, tag {@code name} das métricas {@code resilience4j.*}). */
    static final String NOME = "fontePrimaria";
    /** Bulkhead das chamadas das consultas CLIENTE e CONTA. */
    static final String BULKHEAD_CLIENTE_CONTA = "fontePrimaria-cliente-conta";

    private static final Duration TENTE_NOVAMENTE_SOBRECARGA = Duration.ofSeconds(1);

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final CircuitBreakerConfig circuitoBase;
    private final RetryConfig retryBase;
    private final RateLimiter rateLimiter;
    private final Map<Custodia, Bulkhead> bulkheads = new EnumMap<>(Custodia.class);
    private final Bulkhead bulkheadClienteConta;
    private final Map<String, Protecao> porRota = new ConcurrentHashMap<>();
    private final Duration prazoMinimoParaRetry;

    /**
     * @param prazoMinimoParaRetry só tenta de novo se ainda restar pelo menos isto do prazo da requisição
     * @param chamadasPorSegundo   cota desta instância (cota do mainframe ÷ máximo de instâncias)
     */
    ResilienciaFontePrimaria(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                             RateLimiter rateLimiter, BulkheadRegistry bulkheadRegistry,
                             Duration prazoMinimoParaRetry, int chamadasPorSegundo) {
        this.circuitBreakers = Objects.requireNonNull(circuitBreakers, "circuitBreakers");
        this.retries = Objects.requireNonNull(retries, "retries");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.circuitoBase = circuito(circuitBreakers.circuitBreaker(NOME).getCircuitBreakerConfig());
        this.retryBase = retry(retries.retry(NOME).getRetryConfig(), prazoMinimoParaRetry);
        if (chamadasPorSegundo < 1) {
            throw new IllegalArgumentException("A cota da fonte primária por instância deve ser ao menos 1/s");
        }
        rateLimiter.changeLimitForPeriod(
                limitePorPeriodo(chamadasPorSegundo, rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod()));
        var bulkheadBase = bulkheadRegistry.bulkhead(NOME).getBulkheadConfig();
        for (var custodia : Custodia.values()) {
            var nome = NOME + "-" + custodia.codigo();
            bulkheads.put(custodia, bulkheadRegistry.find(nome)
                    .orElseGet(() -> bulkheadRegistry.bulkhead(nome, BulkheadConfig.from(bulkheadBase).build())));
        }
        this.bulkheadClienteConta = bulkheadRegistry.bulkhead(BULKHEAD_CLIENTE_CONTA);
        this.prazoMinimoParaRetry = prazoMinimoParaRetry;
    }

    /**
     * Permissões por período do rate limiter para a cota por segundo: períodos curtos (ex.: 50 a cada 100 ms) evitam
     * que tasks fora de sincronia somem até o dobro da cota numa janela de 1 s. Ao menos 1.
     */
    static int limitePorPeriodo(int chamadasPorSegundo, Duration periodo) {
        var limite = Math.round(chamadasPorSegundo * (periodo.toNanos() / 1_000_000_000d));
        return (int) Math.max(1, limite);
    }

    /** Nome das instâncias de circuito e retry de uma rota, ex.: {@code fontePrimaria-F5-parcelas}. */
    static String nome(Custodia custodia, String rota) {
        return NOME + "-" + custodia.codigo() + "-" + rota;
    }

    /**
     * Executa a chamada protegida. Recusa da proteção (sem chamar o gateway) vira
     * {@link FonteDeOperacoesIndisponivelException} (circuito aberto, bulkhead cheio) ou
     * {@link CotaDaFonteEsgotadaException} (rate limiter); falhas da chamada saem como vieram, depois do retry.
     *
     * @param rota         nome canônico do bloco ou da lista ({@code lista_cliente}, {@code lista_conta})
     * @param clienteConta {@code true} nas chamadas de uma consulta CLIENTE ou CONTA (listas e blocos)
     */
    <T> T executar(Custodia custodia, String rota, boolean clienteConta, Supplier<T> chamada) {
        var protecao = porRota.computeIfAbsent(nome(custodia, rota), this::criar);
        Supplier<T> limitada = Bulkhead.decorateSupplier(bulkheads.get(custodia), chamada);
        if (clienteConta) {
            limitada = Bulkhead.decorateSupplier(bulkheadClienteConta, limitada);
        }
        var protegida = Retry.decorateSupplier(protecao.retry(),
                CircuitBreaker.decorateSupplier(protecao.circuitBreaker(),
                        RateLimiter.decorateSupplier(rateLimiter, limitada)));
        try {
            return protegida.get();
        } catch (CallNotPermittedException e) {
            throw new FonteDeOperacoesIndisponivelException("Circuit breaker " + protecao.circuitBreaker().getName()
                    + " aberto", esperaComCircuitoAberto(protecao.circuitBreaker()), e);
        } catch (RequestNotPermitted e) {
            throw new CotaDaFonteEsgotadaException("Cota de chamadas por segundo da fonte primária esgotada",
                    rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod(), e);
        } catch (BulkheadFullException e) {
            throw new FonteDeOperacoesIndisponivelException("Bulkhead da fonte primária cheio: " + e.getMessage(),
                    TENTE_NOVAMENTE_SOBRECARGA, e);
        }
    }

    /**
     * Circuito e retry de uma rota: a configuração da instância declarada com esse nome, se houver, senão a base;
     * em ambos os casos com a classificação de falhas do código. Uma instância declarada é substituída no registro
     * pela versão com a classificação (as métricas e os eventos acompanham a substituição).
     */
    private Protecao criar(String nome) {
        var circuito = circuitBreakers.find(nome)
                .map(declarado -> {
                    var comClassificacao = CircuitBreaker.of(nome, circuito(declarado.getCircuitBreakerConfig()),
                            declarado.getTags());
                    circuitBreakers.replace(nome, comClassificacao);
                    return comClassificacao;
                })
                .orElseGet(() -> circuitBreakers.circuitBreaker(nome, circuitoBase));
        var retry = retries.find(nome)
                .map(declarado -> {
                    var comClassificacao = Retry.of(nome, retry(declarado.getRetryConfig(), prazoMinimoParaRetry),
                            declarado.getTags());
                    retries.replace(nome, comClassificacao);
                    return comClassificacao;
                })
                .orElseGet(() -> retries.retry(nome, retryBase));
        return new Protecao(circuito, retry);
    }

    private static CircuitBreakerConfig circuito(CircuitBreakerConfig base) {
        return CircuitBreakerConfig.from(base)
                .recordException(FalhasDoGateway::falhaDaDependencia)
                .ignoreException(FalhasDoGateway::ignoradaPeloCircuito)
                .build();
    }

    private static RetryConfig retry(RetryConfig base, Duration prazoMinimo) {
        return RetryConfig.from(base)
                .retryOnException(erro -> FalhasDoGateway.retentavel(erro) && PrazoDaRequisicao.comporta(prazoMinimo))
                .build();
    }

    private static Duration esperaComCircuitoAberto(CircuitBreaker circuito) {
        return Duration.ofMillis(circuito.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
    }

    private record Protecao(CircuitBreaker circuitBreaker, Retry retry) {
    }
}
