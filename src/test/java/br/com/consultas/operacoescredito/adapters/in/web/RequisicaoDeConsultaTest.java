package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.TipoConsulta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequisicaoDeConsultaTest {

    @ParameterizedTest
    @CsvSource(textBlock = """
            '',     /v1/operacoes-credito/fonte-primaria,       true
            /app,   /app/v1/operacoes-credito/fonte-primaria,   true
            '',     /v1/operacoes-credito,                      false
            /app,   /app/v1/operacoes-credito,                  false
            '',     /v1/operacoes-credito/xyz,                  false
            '',     /v1/operacoes-credito/,                     false
            '',     /v1/operacoes-credito/fonte-primaria/,      false
            '',     /v1/operacoes-credito/fonte-primaria/x,     false
            '',     /v1/operacoes-credito/fonte-primariax,      false
            '',     /x/v1/operacoes-credito/fonte-primaria,     false
            '',     /actuator/health,                           false
            """)
    void soARotaExataDaFontePrimariaEDeConsulta(String contexto, String uri, boolean consulta) {
        var request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(contexto);

        assertThat(RequisicaoDeConsulta.ehRotaDeConsulta(request)).isEqualTo(consulta);
    }

    @Test
    void rotaEhSempreAFontePrimaria() {
        assertThat(RequisicaoDeConsulta.rota()).isEqualTo("fonte_primaria");
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            sistema-custodia,        sistema-custodia
            '  app.v2_X-1  ',        app.v2_X-1
            """)
    void consumidorNoFormatoVemSemEspacosNasPontas(String header, String esperado) {
        assertThat(RequisicaoDeConsulta.consumidor(comConsumidor(header))).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"com espaco", "a\nlinha-falsa", "nome{x}", "cpf=52998224725;", "ção"})
    void consumidorForaDoFormatoViraInvalido(String header) {
        assertThat(RequisicaoDeConsulta.consumidor(comConsumidor(header))).isEqualTo("invalido");
    }

    @Test
    void consumidorLongoDemaisViraInvalidoEAusenteViraDesconhecido() {
        assertThat(RequisicaoDeConsulta.consumidor(comConsumidor("a".repeat(65)))).isEqualTo("invalido");
        assertThat(RequisicaoDeConsulta.consumidor(comConsumidor("a".repeat(64)))).isEqualTo("a".repeat(64));
        assertThat(RequisicaoDeConsulta.consumidor(new MockHttpServletRequest())).isEqualTo("desconhecido");
        assertThat(RequisicaoDeConsulta.consumidor(comConsumidor("   "))).isEqualTo("desconhecido");
    }

    @Test
    void tipoDeConsultaValidoInvalidoOuAusente() {
        var valido = new MockHttpServletRequest();
        valido.addHeader("x-tipo-consulta", "CLIENTE");
        var invalido = new MockHttpServletRequest();
        invalido.addHeader("x-tipo-consulta", "cliente");

        assertThat(RequisicaoDeConsulta.tipoConsulta(valido)).isEqualTo("CLIENTE");
        assertThat(RequisicaoDeConsulta.tipoConsultaValido(valido)).contains(TipoConsulta.CLIENTE);
        assertThat(RequisicaoDeConsulta.tipoConsulta(invalido)).isEqualTo("invalido");
        assertThat(RequisicaoDeConsulta.tipoConsultaValido(invalido)).isEmpty();
        assertThat(RequisicaoDeConsulta.tipoConsulta(new MockHttpServletRequest())).isNull();
        assertThat(RequisicaoDeConsulta.tipoConsultaValido(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void blocosTrazemSempreOperacaoEOriginacaoSemRepeticao() {
        var request = new MockHttpServletRequest();
        request.addParameter("expand", "parcelas, operacao,,saldo");

        assertThat(RequisicaoDeConsulta.blocos(request)).containsExactly("operacao", "originacao", "parcelas", "saldo");
        assertThat(RequisicaoDeConsulta.blocos(new MockHttpServletRequest())).containsExactly("operacao", "originacao");
    }

    private static MockHttpServletRequest comConsumidor(String valor) {
        var request = new MockHttpServletRequest();
        request.addHeader("x-consumidor", valor);
        return request;
    }
}
