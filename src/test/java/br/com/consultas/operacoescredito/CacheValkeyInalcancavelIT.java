package br.com.consultas.operacoescredito;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valkey que aceita a conexão e nunca responde (Toxiproxy {@code timeout} sem prazo: os bytes somem), com um prazo
 * de OPERACAO curto: o cache de custódia custa no máximo o timeout de conexão/comando por consulta, que refaz a
 * descoberta de custódia no mainframe dentro do prazo, e o circuito do Valkey abre. Diferente de {@code CacheIT}, onde o proxy desligado
 * recusa a conexão na hora.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.prazo.fonte-primaria.operacao=" + CacheValkeyInalcancavelIT.PRAZO_OPERACAO_MS + "ms",
        "resilience4j.circuitbreaker.instances.valkey.wait-duration-in-open-state=60s"})
@AutoConfigureRestTestClient
@Import({FontePrimariaContainers.class, CacheContainers.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheValkeyInalcancavelIT {

    static final int PRAZO_OPERACAO_MS = 300;
    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria?id_operacao=100000001&expand=parcelas";

    private final RestTestClient client;
    private final Proxy proxyValkey;
    private final CircuitBreakerRegistry circuitBreakers;
    private final LettuceConnectionFactory conexoes;

    @Autowired
    CacheValkeyInalcancavelIT(RestTestClient client, Proxy proxyValkey, CircuitBreakerRegistry circuitBreakers,
                              LettuceConnectionFactory conexoes) {
        this.client = client;
        this.proxyValkey = proxyValkey;
        this.circuitBreakers = circuitBreakers;
        this.conexoes = conexoes;
    }

    @BeforeAll
    void aquecer() {
        // Primeiras chamadas (classes, conexões com o gateway e o Valkey) fora das medições de tempo. Frias, elas
        // podem passar do prazo curto (504): o aquecimento não é medição, então só a última precisa ser 200.
        for (int i = 0; i < 10; i++) {
            client.get().uri(ROTA)
                    .header("x-tipo-consulta", "OPERACAO")
                    .exchange()
                    .expectBody().returnResult();
        }
        consultar();
    }

    @AfterEach
    void valkeyDeVolta() throws IOException {
        for (var toxic : proxyValkey.toxics().getAll()) {
            toxic.remove();
        }
        conexoes.resetConnection();
        circuitBreakers.circuitBreaker("valkey").reset();
    }

    @Test
    void valkeyQueNaoRespondeNaoEstouraOPrazoEAbreOCircuito() throws IOException {
        proxyValkey.toxics().timeout("valkey-buraco-negro-ida", ToxicDirection.UPSTREAM, 0);
        proxyValkey.toxics().timeout("valkey-buraco-negro-volta", ToxicDirection.DOWNSTREAM, 0);
        // A próxima consulta abre uma conexão nova, que passa pelo buraco negro
        conexoes.resetConnection();

        var duracoesMs = new ArrayList<Long>();
        for (int i = 0; i < 15; i++) {
            var inicio = System.nanoTime();
            consultar();
            duracoesMs.add((System.nanoTime() - inicio) / 1_000_000);
        }

        assertThat(duracoesMs).as("cada consulta no prazo de OPERACAO, mesmo com o Valkey mudo")
                .allSatisfy(duracao -> assertThat(duracao).isLessThan(PRAZO_OPERACAO_MS));
        assertThat(circuitBreakers.circuitBreaker("valkey").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void consultar() {
        client.get().uri(ROTA)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk();
    }
}
