package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.application.port.in.Consumo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ContextoRequisicaoFilterTest {

    private final List<Consumo> consumos = new ArrayList<>();
    private final ContextoRequisicaoFilter filtro = new ContextoRequisicaoFilter(semResolvedor(),
            new RegistroDeConsumoHttp(provedor(() -> consumos::add), provedor(() -> null)));

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void aoFinalRemoveSoAsChavesQuePosNoMdc() throws Exception {
        MDC.put("traceId", "abc123");
        MDC.put("spanId", "def456");
        var duranteARequisicao = new HashMap<String, String>();
        var cadeia = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
        }, (request, response, chain) -> duranteARequisicao.putAll(MDC.getCopyOfContextMap()));

        filtro.doFilter(consulta("sistema-custodia"), new MockHttpServletResponse(), cadeia);

        assertThat(duranteARequisicao).containsEntry("consumidor", "sistema-custodia")
                .containsEntry("rota", "fonte_primaria")
                .containsEntry("tipo_consulta", "OPERACAO")
                .containsKey("correlation_id");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("traceId", "abc123", "spanId", "def456"));
    }

    @Test
    void consumidorForaDoFormatoVaiNormalizadoAoMdcEAoEvento() throws Exception {
        var duranteARequisicao = new HashMap<String, String>();
        var cadeia = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
        }, (request, response, chain) -> duranteARequisicao.putAll(MDC.getCopyOfContextMap()));

        filtro.doFilter(consulta("x\n{\"falso\":1}"), new MockHttpServletResponse(), cadeia);

        assertThat(duranteARequisicao).containsEntry("consumidor", "invalido");
        assertThat(consumos).singleElement().extracting(Consumo::consumidor).isEqualTo("invalido");
    }

    @Test
    void rotaQueNaoEDeConsultaNaoGeraEvento() throws Exception {
        var request = new MockHttpServletRequest("GET", "/v1/operacoes-credito/xyz");

        filtro.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(consumos).isEmpty();
    }

    private static MockHttpServletRequest consulta(String consumidor) {
        var request = new MockHttpServletRequest("GET", "/v1/operacoes-credito/fonte-primaria");
        request.addHeader("x-consumidor", consumidor);
        request.addHeader("x-tipo-consulta", "OPERACAO");
        request.addParameter("id_operacao", "1");
        return request;
    }

    private static HandlerExceptionResolver semResolvedor() {
        return (request, response, handler, ex) -> null;
    }

    private static <T> ObjectProvider<T> provedor(Supplier<T> fornecedor) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return fornecedor.get();
            }

            @Override
            public T getObject(Object... args) {
                return fornecedor.get();
            }

            @Override
            public T getIfAvailable() {
                return fornecedor.get();
            }

            @Override
            public T getIfUnique() {
                return fornecedor.get();
            }
        };
    }
}
