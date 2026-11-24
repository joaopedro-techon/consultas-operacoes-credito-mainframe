package br.com.consultas.operacoescredito.adapters.out.cache;

import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Os dois comandos que o cache usa, assíncronos: cada chamada só envia o comando e devolve o futuro da resposta,
 * então vários comandos seguidos vão em pipeline pela mesma conexão. Só comandos de uma chave ou {@code MGET}
 * (que o cliente de cluster do Lettuce divide por slot): nada de script ou transação com várias chaves.
 */
interface ComandosValkey {

    /** {@code MGET} das chaves; a lista tem um valor por chave, na mesma ordem, {@code null} para ausente. */
    CompletableFuture<List<byte[]>> mget(byte[][] chaves);

    /** {@code SET chave valor PX ttl}. */
    CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos);

    /** {@code DEL chave} (uma chave). */
    CompletableFuture<?> del(byte[] chave);

    /**
     * Comandos sobre a conexão nativa compartilhada do Lettuce ({@code spring.data.redis}, standalone ou cluster).
     * Obter a conexão pode abrir a conexão TCP (limitada por {@code spring.data.redis.connect-timeout}); a resposta
     * de cada comando, pelo timeout de comando do Lettuce ({@code spring.data.redis.timeout}).
     */
    static ComandosValkey lettuce(RedisConnectionFactory conexoes) {
        return new ComandosValkey() {

            @Override
            public CompletableFuture<List<byte[]>> mget(byte[][] chaves) {
                try (var conexao = conexoes.getConnection()) {
                    return nativa(conexao).mget(chaves).toCompletableFuture()
                            .thenApply(valores -> valores.stream().map(ComandosValkey::valor).toList());
                }
            }

            @Override
            public CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos) {
                try (var conexao = conexoes.getConnection()) {
                    return nativa(conexao).set(chave, valor, SetArgs.Builder.px(ttlMilissegundos))
                            .toCompletableFuture();
                }
            }

            @Override
            public CompletableFuture<?> del(byte[] chave) {
                try (var conexao = conexoes.getConnection()) {
                    return nativa(conexao).del(chave).toCompletableFuture();
                }
            }
        };
    }

    private static byte[] valor(KeyValue<byte[], byte[]> chaveValor) {
        return chaveValor.hasValue() ? chaveValor.getValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static RedisClusterAsyncCommands<byte[], byte[]> nativa(RedisConnection conexao) {
        if (conexao.getNativeConnection() instanceof RedisClusterAsyncCommands<?, ?> comandos) {
            return (RedisClusterAsyncCommands<byte[], byte[]>) comandos;
        }
        throw new IllegalStateException("Cache por bloco exige o cliente Lettuce (spring.data.redis)");
    }
}
