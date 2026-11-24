package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Começa a contar o prazo da requisição ({@link PrazoDaRequisicao}) conforme o tipo de consulta, logo depois do
 * filtro de contexto e antes de qualquer processamento, e o remove ao final. Sem tipo de consulta válido não há
 * prazo: a requisição termina em 400 antes de ir a qualquer fonte.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class PrazoDaRequisicaoFilter extends OncePerRequestFilter {

    private final PrazoProperties prazos;

    PrazoDaRequisicaoFilter(PrazoProperties prazos) {
        this.prazos = prazos;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var tipo = RequisicaoDeConsulta.tipoConsultaValido(request);
        if (tipo.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        PrazoDaRequisicao.iniciar(prazos.de(tipo.get()));
        try {
            chain.doFilter(request, response);
        } finally {
            PrazoDaRequisicao.encerrar();
        }
    }
}
