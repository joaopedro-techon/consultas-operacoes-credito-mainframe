package br.com.consultas.operacoescredito;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Corpos de resposta de referência ({@code src/test/resources/respostas-esperadas}), gravados byte a byte da
 * visão consolidada antes de ela sair deste repositório (ADR-0017). Até então a fonte primária era comparada com
 * a visão consolidada na mesma execução; agora é comparada com estas respostas, que são o contrato.
 */
final class RespostasEsperadas {

    private RespostasEsperadas() {
    }

    /** Corpo esperado, ex.: {@code operacao/padrao-100000001}. */
    static String corpo(String nome) {
        var caminho = "respostas-esperadas/" + nome + ".json";
        try (var entrada = RespostasEsperadas.class.getClassLoader().getResourceAsStream(caminho)) {
            if (entrada == null) {
                throw new IllegalArgumentException("Resposta esperada inexistente: " + caminho);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Corpo recebido, decodificado dos bytes exatos da resposta. */
    static String recebido(byte[] corpo) {
        return new String(corpo, StandardCharsets.UTF_8);
    }
}
