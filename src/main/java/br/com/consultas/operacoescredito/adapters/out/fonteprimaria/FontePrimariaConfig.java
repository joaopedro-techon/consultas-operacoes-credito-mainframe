package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeCustodia;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.config.observabilidade.MetricasDeCustodia;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.PrecedenciaDeCustodia;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.function.Resolver;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Fonte primária: {@code @HttpExchange} sobre {@code RestClient} + Apache HttpClient 5 com pool próprio, e
 * resiliência própria ({@link ResilienciaFontePrimaria}).
 */
@Configuration(proxyBeanMethods = false)
class FontePrimariaConfig {

    /** Nome do bean da porta de saída da fonte primária. */
    static final String FONTE_PRIMARIA = "fontePrimaria";
    private static final String HTTP_CLIENT = "fontePrimariaHttpClient";

    @Bean(name = HTTP_CLIENT, defaultCandidate = false)
    CloseableHttpClient fontePrimariaHttpClient(FontePrimariaProperties properties) {
        var pool = properties.pool();
        var gerenciador = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(pool.maxConexoes())
                .setMaxConnPerRoute(pool.maxConexoesPorRota())
                .setConnectionConfigResolver(conexaoNoPrazo(pool))
                .build();
        var requisicao = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(pool.timeoutObterConexao()))
                .setResponseTimeout(Timeout.of(pool.timeoutResposta()))
                .build();
        return HttpClients.custom()
                .setConnectionManager(gerenciador)
                .setDefaultRequestConfig(requisicao)
                .setKeepAliveStrategy((resposta, contexto) -> TimeValue.of(pool.keepAlive()))
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.of(pool.keepAlive()))
                // Uma camada de retry por dependência: a da fonte primária é a do Resilience4j
                .disableAutomaticRetries()
                .build();
    }

    /**
     * Rate limiter {@value ResilienciaFontePrimaria#NOME} já com a cota desta task: o do {@code application.yml} é
     * substituído no registro (métricas e eventos acompanham), porque mudar o limite de um existente só vale a
     * partir do próximo período, e o primeiro sairia com o {@code limit-for-period} do yml.
     */
    static RateLimiter cotaDaTask(RateLimiterRegistry registro, int chamadasPorSegundo) {
        var declarado = registro.rateLimiter(ResilienciaFontePrimaria.NOME);
        var config = declarado.getRateLimiterConfig();
        var comCota = RateLimiter.of(ResilienciaFontePrimaria.NOME, RateLimiterConfig.from(config)
                .limitForPeriod(ResilienciaFontePrimaria.limitePorPeriodo(chamadasPorSegundo,
                        config.getLimitRefreshPeriod()))
                .build(), declarado.getTags());
        registro.replace(ResilienciaFontePrimaria.NOME, comCota);
        return comCota;
    }

    /**
     * A classificação das falhas (o circuito conta só falhas do mainframe, ADR-0009; o retry só tenta o retentável
     * e só se o prazo comportar) é aplicada pela {@link ResilienciaFontePrimaria} a cada instância por custódia e
     * rota (ADR-0014); números de tentativas, janelas e bulkheads ficam no {@code application.yml}.
     */
    @Bean(name = FONTE_PRIMARIA)
    FonteDeOperacoes fontePrimaria(FontePrimariaProperties properties, RestClient.Builder restClientBuilder,
                                   @Qualifier(HTTP_CLIENT) CloseableHttpClient httpClient,
                                   ResourceLoader resourceLoader, ObservationRegistry observationRegistry,
                                   RetryRegistry retryRegistry, CircuitBreakerRegistry circuitBreakerRegistry,
                                   RateLimiterRegistry rateLimiterRegistry, BulkheadRegistry bulkheadRegistry,
                                   CacheDeCustodia cacheDeCustodia, PrecedenciaDeCustodia precedencia,
                                   CatalogoDeCampos catalogo, MeterRegistry meterRegistry) {
        Assert.hasText(properties.url(), "consultas.fonte-primaria.url é obrigatório");
        var restClient = restClientBuilder
                .baseUrl(properties.url())
                .requestFactory(requisicoesNoPrazo(httpClient, properties.pool()))
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
        var gateway = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GatewayMainframeClient.class);
        var resiliencia = new ResilienciaFontePrimaria(circuitBreakerRegistry, retryRegistry,
                cotaDaTask(rateLimiterRegistry, properties.cota().porTask()), bulkheadRegistry,
                properties.retry().prazoMinimoRestante(), properties.cota().porTask());
        var layouts = LayoutsTrancode.carregar(resourceLoader, properties.layouts());
        layouts.validarContra(catalogo);
        return new FontePrimariaTrancodeAdapter(gateway, layouts, observationRegistry, resiliencia, cacheDeCustodia,
                precedencia, new MetricasDeCustodia(meterRegistry, FontePrimariaTrancodeAdapter.ROTA),
                properties.fanOut().maxChamadasSimultaneas(), properties.paginacao().maxPaginas());
    }

    /**
     * Configuração de cada conexão nova, resolvida na thread da requisição: o timeout para abrir a conexão TCP
     * também nunca passa do que resta do prazo. Com o padrão (50 ms) bem abaixo do menor prazo (OPERACAO,
     * 150 ms), um host inalcançável estoura o timeout de conexão (falha do mainframe, conta no circuito e é
     * retentada) antes de o prazo cortar a chamada.
     */
    static Resolver<HttpRoute, ConnectionConfig> conexaoNoPrazo(FontePrimariaProperties.Pool pool) {
        return rota -> ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(PrazoDaRequisicao.limitar(pool.timeoutConexao())))
                .setTimeToLive(TimeValue.of(pool.ttl()))
                .build();
    }

    /**
     * Fábrica de requisições em que a espera por conexão do pool e pela resposta de cada tentativa nunca passa do
     * que resta do prazo da requisição: o 504 sai no prazo mesmo com o mainframe lento.
     */
    static HttpComponentsClientHttpRequestFactory requisicoesNoPrazo(CloseableHttpClient httpClient,
                                                                     FontePrimariaProperties.Pool pool) {
        var fabrica = new HttpComponentsClientHttpRequestFactory(httpClient);
        fabrica.setHttpContextFactory((metodo, uri) -> {
            if (PrazoDaRequisicao.restante().isEmpty()) {
                return null;
            }
            var contexto = HttpClientContext.create();
            contexto.setRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.of(PrazoDaRequisicao.limitar(pool.timeoutObterConexao())))
                    .setResponseTimeout(Timeout.of(PrazoDaRequisicao.limitar(pool.timeoutResposta())))
                    .build());
            return contexto;
        });
        return fabrica;
    }
}
