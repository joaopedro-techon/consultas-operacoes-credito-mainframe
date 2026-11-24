package br.com.consultas.operacoescredito;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.SimpleValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.ExchangeResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida respostas da rota da fonte primária contra o contrato HTTP de {@code consultas-operacoes-credito}
 * (ADR-0017): status declarado, content type, headers obrigatórios ({@code x-correlationid}, {@code Retry-After})
 * e corpo pelo schema. Toda divergência é erro (nível default {@code ERROR}); nenhuma vira só aviso.
 * <p>
 * Os arquivos do contrato ficam no diretório da propriedade de sistema {@value #PROPRIEDADE_DIRETORIO}: no CI, a
 * cópia baixada da {@code main} do outro repositório antes dos testes; sem ela (build local, IDE), a cópia
 * versionada em {@value #DIRETORIO_PADRAO}. Código copiado do repositório da visão consolidada, não compartilhado.
 */
final class ContratoOpenApi {

    /** Propriedade de sistema com o diretório do {@code openapi.yaml} e do {@code catalogo-campos.yml}. */
    static final String PROPRIEDADE_DIRETORIO = "contrato.dir";
    /** Cópia versionada do contrato, usada quando a propriedade não está definida. */
    static final String DIRETORIO_PADRAO = "src/test/resources/contrato";

    static final Path OPENAPI = arquivo(System.getProperty(PROPRIEDADE_DIRETORIO), "openapi.yaml");
    static final Path CATALOGO = arquivo(System.getProperty(PROPRIEDADE_DIRETORIO), "catalogo-campos.yml");

    private static final OpenApiInteractionValidator VALIDADOR = validador(OPENAPI);

    private ContratoOpenApi() {
    }

    /**
     * Arquivo {@code nome} do contrato no {@code diretorio} (ou em {@value #DIRETORIO_PADRAO}, se nulo ou em
     * branco); falha se não existir, para que o teste nunca passe sem contrato.
     */
    static Path arquivo(String diretorio, String nome) {
        var base = diretorio == null || diretorio.isBlank() ? Path.of(DIRETORIO_PADRAO) : Path.of(diretorio);
        var arquivo = base.resolve(nome);
        if (!Files.isRegularFile(arquivo)) {
            throw new IllegalStateException("Arquivo do contrato não encontrado: " + arquivo.toAbsolutePath()
                    + " (propriedade " + PROPRIEDADE_DIRETORIO + ")");
        }
        return arquivo;
    }

    /** Validador do contrato no {@code arquivo}, com toda divergência como erro. */
    static OpenApiInteractionValidator validador(Path arquivo) {
        return OpenApiInteractionValidator.createForSpecificationUrl(arquivo.toUri().toString())
                .withLevelResolver(LevelResolver.create().withDefaultLevel(ValidationReport.Level.ERROR).build())
                .build();
    }

    /** Falha o teste se a resposta real divergir do contrato, listando as divergências. */
    static void assertConforme(ExchangeResult resultado) {
        var relatorio = validar(VALIDADOR, resultado.getUrl().getRawPath(), resultado.getStatus().value(),
                resultado.getResponseHeaders(), resultado.getResponseBodyContent());
        assertThat(relatorio.hasErrors())
                .as("Resposta de %s %s (%s) diverge de %s:%n%s%nOnde: %s", resultado.getMethod(), resultado.getUrl(),
                        resultado.getStatus(), OPENAPI, SimpleValidationReportFormat.getInstance().apply(relatorio),
                        ondeDiverge(relatorio))
                .isFalse();
    }

    /** Ponteiros (no corpo e no schema) de cada divergência, que a mensagem do validador não traz. */
    private static String ondeDiverge(ValidationReport relatorio) {
        return relatorio.getMessages().stream()
                .map(mensagem -> mensagem.getContext()
                        .flatMap(ValidationReport.MessageContext::getPointers)
                        .map(ponteiros -> "corpo " + ponteiros.getInstance() + ", schema " + ponteiros.getSchema())
                        .orElse(mensagem.getKey()))
                .toList()
                .toString();
    }

    /** Divergências de uma resposta a {@code GET caminho} em relação ao contrato do {@code validador}. */
    static ValidationReport validar(OpenApiInteractionValidator validador, String caminho, int status,
                                    HttpHeaders headers, byte[] corpo) {
        var resposta = SimpleResponse.Builder.status(status);
        headers.forEach(resposta::withHeader);
        var contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null) {
            resposta.withContentType(contentType);
        }
        if (corpo != null && corpo.length > 0) {
            resposta.withBody(corpo);
        }
        return validador.validateResponse(caminho, Request.Method.GET, resposta.build());
    }
}
