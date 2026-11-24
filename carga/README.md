# Testes de carga (k6)

Cenários k6 para medir quanto **uma task** da fonte primária aguenta e validar as estimativas de [`docs/capacidade-ecs-fargate.md`](../docs/capacidade-ecs-fargate.md). Os testes rodam contra o docker-compose local (aplicação em `http://localhost:8080`), com a aplicação limitada à CPU e à memória da task Fargate.

| Script | Rota | Padrão |
| --- | --- | --- |
| `k6/fonte-primaria.js` | `GET /v1/operacoes-credito/fonte-primaria` (WireMock no lugar do mainframe) | 30 req/s, mix 50% OPERACAO / 25% CLIENTE / 25% CONTA |

O cenário da visão consolidada fica no repositório `consultas-operacoes-credito` (ADR-0017).

Todos os cenários usam chegada aberta (`constant-arrival-rate`, ou `ramping-arrival-rate` com `MODO=rampa`): a taxa não cai quando a aplicação fica lenta, como acontece em produção. A massa é a dos stubs do gateway em `wiremock/mappings`: operações 100000001..100000007 e 100000101..100000114, CPF/CNPJ 11222333000181, 52998224725, 12ABC34501DE35 e 11144477735, e as contas de `lib/comum.js`. O `expand` sorteia 2 blocos além de `operacao` e `originacao` (cerca de 4 por consulta, premissa do modelo).

## Variáveis

| Variável | Padrão | Uso |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Aplicação |
| `RPS` | 30 | Taxa total (somando os três tipos); na rampa, a taxa final |
| `MODO` | `constante` | `constante` ou `rampa` |
| `DURACAO` | `2m` | Duração no modo constante |
| `RPS_INICIAL`, `DEGRAUS`, `DURACAO_DEGRAU` | `RPS/DEGRAUS`, 6, `1m` | Rampa |
| `MIX_OPERACAO`, `MIX_CLIENTE`, `MIX_CONTA` | 0.5, 0.25, 0.25 | Fatia de cada tipo (0 desliga o cenário) |
| `LIMITE_OPERACOES` | (ausente) | `limite_operacoes` de CLIENTE/CONTA |
| `VUS`, `MAX_VUS` | 200, 2000 | VUs pré-alocados e máximo, divididos pelo mix |
| `MAX_429` | 0.01 | Fonte primária: taxa de 429 tolerada no threshold |
| `CONSUMIDOR` | `carga-k6` | Header `x-consumidor` |

Métricas próprias: `duracao_200` (latência só das respostas 200, com a tag `tipo_consulta`, usada nos thresholds), `respostas_429` e `taxa_429` (cota da fonte primária, fora de `http_req_failed`), `respostas_parciais` (200 com `x-blocos-indisponiveis`) e `respostas_5xx`.

Thresholds: p99 OPERACAO < 1,5 s, CLIENTE/CONTA < 4 s (prazos de 2,5 s e 6 s); erros fora o 429 < 1%; 429 abaixo de `MAX_429`; nenhuma iteração descartada.

## 1. Subir a stack com os limites da task

Na raiz do repositório, com o override `carga/docker-compose.carga.yml`:

```powershell
# Task de 1 vCPU / 2 GB com a cota inteira (MAX_TASKS=1 → 1.500 chamadas/s nesta task)
$env:APP_CPUS = "1"; $env:APP_MEMORIA = "2g"
$env:CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS = "1"
docker compose -f docker-compose.yml -f carga/docker-compose.carga.yml up -d --build
```

Sem compose, o equivalente para a aplicação já construída é `docker run --cpus=1 --memory=2g ...`. A JVM respeita esses limites: `ActiveProcessorCount` sai do `--cpus` e o heap fica em 75% da memória (`-XX:MaxRAMPercentage=75` no `Dockerfile`), como no Fargate. Com 1 CPU a JVM escolhe o SerialGC, como faria numa task de 1 vCPU.

Para a aplicação não disputar núcleos com LocalStack, Valkey, WireMock e o próprio k6, fixe os núcleos dela e deixe os outros para o resto:

```powershell
docker update --cpuset-cpus 0 consultas-operacoes-credito-mainframe-app-1
```

### Latência do mainframe no WireMock

Sem atraso, o WireMock responde em ~1 ms e o teste da fonte primária não diz nada sobre concorrência, bulkheads e pool. Para usar a premissa do modelo (p50 220 ms, p99 500 ms: lognormal com mediana 220 e sigma 0,35):

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8089/__admin/settings -ContentType application/json `
  -Body '{"delayDistribution": {"type": "lognormal", "median": 220, "sigma": 0.35}}'
# Voltar ao normal:
Invoke-RestMethod -Method Post -Uri http://localhost:8089/__admin/settings -ContentType application/json -Body '{"fixedDelay": 0}'
```

O override já liga as respostas assíncronas do WireMock (`--async-response-enabled`), para que o atraso não prenda uma thread do Jetty por requisição e o WireMock não vire o gargalo.

## 2. Rodar

```powershell
# Ponto fixo, com a latência configurada acima
k6 run -e RPS=30 -e DURACAO=3m carga/k6/fonte-primaria.js
# Rampa para achar o joelho (6 degraus de 1 min até 120 req/s)
k6 run -e MODO=rampa -e RPS=120 carga/k6/fonte-primaria.js
# Acima da cota: espera-se 429, então afrouxe o threshold
k6 run -e RPS=150 -e MAX_429=0.9 carga/k6/fonte-primaria.js
```

Rode o k6 de outra máquina, ou em núcleos que não sejam os da aplicação. Acompanhe no Grafana (http://localhost:3000): latência, status, chamadas ao mainframe × cota, bulkheads e rate limiters.

## 3. Medir o RPS máximo por task

1. **Aqueça** por 1–2 minutos a ~30% da taxa esperada (JIT e pools). Descarte esse trecho.
2. **Faça a rampa** e anote o último degrau em que todos os thresholds passam. O joelho aparece como:
   - p99 subindo rápido;
   - `dropped_iterations > 0` (faltou VU porque as respostas demoraram);
   - CPU do container perto do limite (`docker stats`, ou no Prometheus `process_cpu_usage * system_cpu_count`);
   - 503 de bulkhead/rate limiter, ou 504.
3. **Confirme no ponto fixo**: 5 minutos a 100% e depois a 70% do joelho. O **RPS-alvo por task** (o que entra no autoscaling) é cerca de 70% do joelho, para que a task absorva o crescimento da carga enquanto o autoscaling reage.
4. **Calcule o custo de CPU por requisição**, que é o número que se leva para o Fargate:

   ```
   ms de CPU por requisição = uso de CPU (em núcleos) × 1000 / RPS
   ex.: 1,3 núcleo a 600 req/s → 2,2 ms/req
   ```

5. **Cache de custódia.** Com a massa local, depois do aquecimento toda consulta OPERACAO acha a custódia no Valkey. Meça também com `CONSULTAS_CACHE_HABILITADO=false` (toda consulta refaz a descoberta nas 4 custódias) para ter o pior caso de chamadas ao mainframe.
6. **Cota.** O limite costuma ser a cota (rate limiter), não a CPU. Meça:
   - chamadas ao mainframe por consulta (painel "Chamadas ao mainframe × cota" ÷ RPS), para comparar com o modelo (≈ 5,9 OPERACAO, ≈ 29 CLIENTE/CONTA);
   - CPU por chamada ao mainframe (CPU ÷ chamadas/s);
   - vagas livres nos bulkheads `fontePrimaria-<custodia>` e `fontePrimaria-cliente-conta` quando a vazão está na cota: elas não podem chegar a zero com o mainframe saudável;
   - o custo do 429: rode bem acima da cota (`RPS=150`) e veja a CPU gasta só para recusar.

## 4. Extrapolar para o Fargate

Um vCPU do Fargate é um hyperthread de um processador de servidor e costuma ser **mais lento** que um núcleo de desktop recente. Use um fator de conversão (premissa: 1,3) até medir na AWS:

```
RPS-alvo por task ≈ (vCPU da task × 1000 × 0,6) / (ms de CPU por requisição medido × 1,3)
ex.: 1 vCPU, 8 ms/req local → 1000 × 0,6 / (8 × 1,3) ≈ 58 req/s (na fonte primária, a cota costuma limitar antes)
alvo do ALBRequestCountPerTarget = RPS-alvo × 60
tasks no pico = RPS do pico ÷ RPS-alvo
```

O WireMock só imita a latência do mainframe e roda na mesma máquina. Por isso, a latência medida localmente **não vale** para produção, só o custo de CPU da aplicação. A validação final é em uma conta AWS:
1. Suba uma única task (`min_tasks = max_tasks = 1` no `infra/ecs`).
2. Rode o k6 de uma EC2 na mesma VPC, contra o ALB, com a mesma rampa.
3. Ajuste `alvo_requisicoes_por_task_minuto` e `min_tasks` com o joelho medido (o `max_tasks` vem da cota, ADR-0015).
4. Registre os números no documento de capacidade.
