package br.com.consultas.operacoescredito.adapters.out.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Configuração inválida do cache falha na subida (não alcançável pela API HTTP); formato da chave. */
class CachePropertiesTest {

    @Test
    void ttlDeCustodiaPositivoEAceito() {
        assertThat(propriedades(Duration.ofHours(24)).ttlCustodia()).isEqualTo(Duration.ofHours(24));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void ttlDeCustodiaZeroOuNegativoERejeitado(String ttl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> propriedades(Duration.parse(ttl)))
                .withMessageContaining("consultas.cache.ttl-custodia");
    }

    @Test
    void padroesLimitamComandosEGravacoesGrandes() {
        var padrao = propriedades(Duration.ofHours(24));

        assertThat(padrao.chavesPorComando()).isEqualTo(100);
        assertThat(padrao.maxChavesPorConsulta()).isEqualTo(500);
        assertThat(padrao.prazoMinimoRestante()).isEqualTo(Duration.ofMillis(20));
        assertThat(padrao.maxGravacoesSimultaneas()).isEqualTo(32);
    }

    @Test
    void limitesMenoresQueUmSaoRejeitados() {
        var prazo = Duration.ofMillis(20);
        var ttl = Duration.ofHours(24);
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(true, 0, 500, prazo, 32, ttl))
                .withMessageContaining("chaves-por-comando");
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(true, 100, 0, prazo, 32, ttl))
                .withMessageContaining("max-chaves-por-consulta");
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(true, 100, 500, prazo, 0, ttl))
                .withMessageContaining("max-gravacoes-simultaneas");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheProperties(true, 100, 500, Duration.ofMillis(-1), 32, ttl))
                .withMessageContaining("prazo-minimo-restante");
    }

    @Test
    void chaveDaCustodiaEhContratoComAVisaoConsolidada() {
        assertThat(CacheDeCustodia.chave(100000001L)).isEqualTo("custodia:100000001");
    }

    static CacheProperties propriedades(Duration ttlCustodia) {
        return new CacheProperties(true, 100, 500, Duration.ofMillis(20), 32, ttlCustodia);
    }
}
