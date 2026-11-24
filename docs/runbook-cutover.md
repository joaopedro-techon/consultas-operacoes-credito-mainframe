# Runbook de cutover: imagem do serviço ECS `fonte-primaria`

Decisão: ADR-0017. O cutover em produção é **trocar a imagem do serviço ECS `fonte-primaria`**: sai a imagem
única `consultas-operacoes-credito` (com `consultas.rotas-ativas = fonte-primaria`) e entra a imagem
`consultas-operacoes-credito-mainframe`. O ALB, a regra `/v1/operacoes-credito/fonte-primaria*`, o target group e
o API Gateway não mudam. Para o consumidor, nada muda (mesmos paths, parâmetros, headers e corpo).

O que torna o cutover delicado é o **state do Terraform**, não a imagem. Os dois repositórios têm um
`infra/ecs` com os mesmos nomes e endereços:
- `var.nome`, padrão `consultas-operacoes-credito`;
- `var.prefixo_target_group`, padrão `consultas`;
- recursos em `for_each` com a chave `fonte-primaria`.

Hoje esses recursos estão no state do `infra/ecs` de `consultas-operacoes-credito`, que, depois da poda, só
declara o `visao-consolidada`.

> **Antes de qualquer `terraform apply` no `infra/ecs` de `consultas-operacoes-credito`**, rode lá o
> `terraform state rm` das instâncias `["fonte-primaria"]` (passo 2.4). Sem isso, o apply **destrói o serviço
> `fonte-primaria` em produção**: serviço ECS, task definition, target group, regra do listener, autoscaling e
> log group.

Convenções deste runbook:
- `$cluster` é o nome do cluster ECS (`cluster_name` do tfvars).
- `$servico` é o nome do serviço ECS. Com `nome` no padrão, é `consultas-operacoes-credito-fonte-primaria`;
  confirme no output `servicos` ou no state.
- Comandos em PowerShell. Endereços com aspas vão depois de `--%`, para que o PowerShell 5.1 e o 7 repassem as
  aspas ao `terraform` do mesmo jeito.

```powershell
$cluster = '<cluster_name>'
$servico = 'consultas-operacoes-credito-fonte-primaria'
New-Item -ItemType Directory -Force "$env:USERPROFILE\cutover-fonte-primaria" | Out-Null
$trabalho = "$env:USERPROFILE\cutover-fonte-primaria"   # fora dos repositórios: states e JSONs com dados sensíveis
```

## 1. Pré-requisitos

Faça cada verificação antes da janela e anote o resultado. Qualquer "não" adia o cutover.

### 1.1 Acesso da task nova

O serviço continua com a mesma task role (`task_role_arn`) e os mesmos security groups (`security_group_ids`):
o módulo do mainframe recebe os mesmos valores do tfvars de hoje. Se a role ou os SGs mudarem, confira:

- [ ] **SQS de eventos de consumo**: a task role tem `sqs:SendMessage` na fila de `CONSULTAS_EVENTOS_SQS_URL_FILA`.
  O SDK usa `SendMessageBatch`, que é autorizado pela mesma ação.

  ```powershell
  aws iam simulate-principal-policy --policy-source-arn <task_role_arn> --action-names sqs:SendMessage `
    --resource-arns <arn-da-fila> --query 'EvaluationResults[].EvalDecision'
  ```

- [ ] **Gateway do mainframe**: o SG das tasks tem saída para o host e a porta de `CONSULTAS_FONTE_PRIMARIA_URL`,
  e o gateway aceita a origem. Teste de uma task em execução do serviço atual (ECS Exec, se habilitado) ou de
  uma task avulsa na mesma subnet e no mesmo SG.
- [ ] **Valkey compartilhado** (`CONSULTAS_VALKEY_HOST`, porta 6379): o SG do ElastiCache aceita o SG das tasks.
  Os dois serviços usam a mesma chave `custodia:{id_operacao}` com TTL de 24 h (ADR-0012, ADR-0017), então o
  cache de custódia continua quente depois da troca.
- [ ] **ECR da imagem nova**: a execution role (`execution_role_arn`) tem `ecr:BatchGetImage` e
  `ecr:GetDownloadUrlForLayer` no repositório `consultas-operacoes-credito-mainframe`. É um repositório
  diferente do da imagem antiga; se a policy da role limita os repositórios, o pull falha e o deploy não sobe.
- [ ] **OTel Collector**: o mesmo `OTEL_EXPORTER_OTLP_ENDPOINT` é alcançável. O `service.name` muda para
  `consultas-operacoes-credito-mainframe`: alertas e painéis filtrados por `service_name` precisam aceitar os
  dois nomes durante o cutover. O painel do repositório filtra por `rota`, não por serviço.

### 1.2 Variáveis e segredos iguais aos do serviço atual

Guarde a task definition em uso hoje. Ela serve para comparar agora e para o rollback depois.

```powershell
$tdAtual = aws ecs describe-services --cluster $cluster --services $servico `
  --query 'services[0].taskDefinition' --output text
aws ecs describe-task-definition --task-definition $tdAtual --include TAGS `
  | Out-File -Encoding utf8 "$trabalho\td-antes-do-cutover.json"
aws ecs describe-task-definition --task-definition $tdAtual `
  --query 'taskDefinition.containerDefinitions[0].[image, environment, secrets]'
```

- [ ] O `terraform.tfvars` do `infra/ecs` deste repositório tem os mesmos valores do tfvars de produção do
  `infra/ecs` de `consultas-operacoes-credito`: `nome`, `prefixo_target_group`, `cluster_name`, `vpc_id`,
  `subnet_ids`, `security_group_ids`, `alb_arn`, `alb_listener_arn`, `prioridade_regras`, `execution_role_arn`,
  `task_role_arn`, `arquitetura_cpu`, `retencao_logs_dias`, `ambiente`, `segredos` e `fonte_primaria`. Copie
  daquele arquivo; não redigite.
  - **`nome` e `prefixo_target_group` diferentes mudam o nome do serviço e do target group, e o Terraform os
    substituiria** (destroy + create).
  - **`prioridade_regras`** precisa ser o mesmo valor nos dois tfvars: a regra da fonte primária usa
    `prioridade_regras` e a da visão consolidada usa `prioridade_regras + 1`.
- [ ] `ambiente` e `segredos` dão, na task definition nova, as mesmas variáveis da atual. As exceções:
  - sai `CONSULTAS_ROTAS_ATIVAS`;
  - variáveis só da visão consolidada (por exemplo `CONSULTAS_DYNAMODB_TABELA`) podem sair, porque a imagem
    nova as ignora;
  - `CONSULTAS_FONTE_PRIMARIA_COTA_*` são preenchidas pelo módulo.
- [ ] `CONSULTAS_EVENTOS_SEGREDO_HMAC` aponta para o mesmo segredo. Um segredo diferente muda os HMACs de
  CPF/CNPJ e conta nos eventos de consumo.

### 1.3 Máximo de tasks e cota local iguais (ADR-0015)

Cada task limita-se a `cota ÷ max_tasks` chamadas/s ao mainframe. O `max_tasks` do autoscaling e o
`CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS` das tasks precisam ser o mesmo número, e não podem mudar no cutover.

```powershell
aws application-autoscaling describe-scalable-targets --service-namespace ecs `
  --resource-ids "service/$cluster/$servico" --query 'ScalableTargets[0].[MinCapacity, MaxCapacity]'
aws ecs describe-task-definition --task-definition $tdAtual `
  --query "taskDefinition.containerDefinitions[0].environment[?starts_with(name, 'CONSULTAS_FONTE_PRIMARIA_COTA')]"
```

- [ ] `MaxCapacity` = `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS` = `fonte_primaria.max_tasks` do tfvars novo.
- [ ] `CONSULTAS_FONTE_PRIMARIA_COTA_CHAMADAS_POR_SEGUNDO` = `fonte_primaria.cota_chamadas_por_segundo` do
  tfvars novo (1.500 é a cota do mainframe para todas as tasks).
- [ ] O serviço está com `maximumPercent=100` e `minimumHealthyPercent=50`, e assim continua:

  ```powershell
  aws ecs describe-services --cluster $cluster --services $servico `
    --query 'services[0].deploymentConfiguration'
  ```

### 1.4 Imagens no registry

- [ ] A imagem nova está publicada no ECR por digest:
  `<conta>.dkr.ecr.<regiao>.amazonaws.com/consultas-operacoes-credito-mainframe@sha256:...`. Ela vem do CI verde
  da `main`, com a verificação de contrato passando.
- [ ] A **imagem antiga** (a `image` de `td-antes-do-cutover.json`) existe e continua existindo até o fim do
  período de rollback. O rollback depende dela: **não a apague**, e confira se nenhuma lifecycle policy do ECR a
  expira. Uma regra "manter as N últimas" expira essa imagem quando o outro repositório publicar a imagem podada
  no mesmo repositório.

  ```powershell
  aws ecr describe-images --repository-name consultas-operacoes-credito --image-ids imageDigest=<sha256:digest-antigo>
  aws ecr get-lifecycle-policy --repository-name consultas-operacoes-credito
  ```

  Se a lifecycle policy puder alcançá-la, dê à imagem uma tag fora das regras (por exemplo
  `rollback-fonte-primaria`) ou ajuste a policy antes do cutover.

### 1.5 Congelamento

- [ ] Ninguém roda `terraform apply` no `infra/ecs` de nenhum dos dois repositórios do passo 2 até o fim do
  passo 3.
- [ ] Nenhum deploy do serviço `visao-consolidada` durante a janela (veja a seção 6).

## 2. Separar o state do Terraform

### 2.1 Por que

Os dois módulos declaram os mesmos endereços (`aws_ecs_service.this["fonte-primaria"]` e os demais) com os
mesmos nomes na AWS. Hoje, esses recursos estão no state do `infra/ecs` de `consultas-operacoes-credito`.

- **Apply lá, sem tirar os recursos do state**: o Terraform destrói o `fonte-primaria`, que não está mais na
  configuração.
- **Apply aqui, com state vazio**: o Terraform tenta criar tudo de novo. O apply falha em recursos que já
  existem (log group, target group, prioridade da regra), ou cria um segundo serviço com o mesmo nome.
- **Os dois módulos apontando para o mesmo state** (mesmo bucket e mesma `key`): cada apply destrói o que só o
  outro declara. **Nunca** faça isso.

O caminho correto: o state deste repositório fica numa chave **própria**, com os recursos `["fonte-primaria"]`
**movidos** para ela, e o state do outro repositório perde esses recursos com `terraform state rm`. Nada é
recriado na AWS.

**Backend.** Os dois módulos declaram `backend "s3" {}` (configuração parcial) e usam o mesmo bucket com
chaves diferentes. Cada um tem um `backend.hcl.example`, e o `backend.hcl` real fica fora do git:

| Repositório | `key` |
|---|---|
| `consultas-operacoes-credito` | `consultas-operacoes-credito/ecs/terraform.tfstate` |
| `consultas-operacoes-credito-mainframe` (este) | `consultas-operacoes-credito-mainframe/ecs/terraform.tfstate` |

Se o state do `infra/ecs` de `consultas-operacoes-credito` ainda é **local** (antes do backend S3), migre-o
primeiro para a chave dele: `terraform init -backend-config=backend.hcl -migrate-state`, no checkout de lá.
Depois disso, nenhum dos dois repositórios usa state local.

### 2.2 Backup e inventário (no `infra/ecs` de `consultas-operacoes-credito`)

Use o checkout e o backend com que esse módulo é aplicado em produção.

```powershell
cd <consultas-operacoes-credito>\infra\ecs
terraform init -backend-config=backend.hcl       # chave consultas-operacoes-credito/ecs/...
# cmd /c grava os bytes como vieram (o pipe do PowerShell 5.1 recodifica a saída)
cmd /c "terraform state pull > `"$trabalho\visao-consolidada-antes.tfstate`""   # backup
Copy-Item "$trabalho\visao-consolidada-antes.tfstate" "$trabalho\origem.tfstate"
terraform state list | Select-String 'fonte-primaria'
```

A lista esperada tem 7 endereços:

```
aws_appautoscaling_policy.requisicoes["fonte-primaria"]
aws_appautoscaling_target.this["fonte-primaria"]
aws_cloudwatch_log_group.this["fonte-primaria"]
aws_ecs_service.this["fonte-primaria"]
aws_ecs_task_definition.this["fonte-primaria"]
aws_lb_listener_rule.this["fonte-primaria"]
aws_lb_target_group.this["fonte-primaria"]
```

Há um 8º, `aws_appautoscaling_policy.cpu["fonte-primaria"]`, se `fonte_primaria.alvo_cpu` foi definido no
tfvars (o padrão é `null`). Mova e remova **todos** os que a lista mostrar.

### 2.3 Montar o state deste repositório

Faça o `state mv` num diretório vazio, sem `.tf` e sem backend. Assim, `-state` e `-state-out` agem só sobre os
arquivos indicados, e nenhum state remoto é tocado.

```powershell
New-Item -ItemType Directory -Force "$trabalho\mover" | Out-Null
cd "$trabalho\mover"
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_cloudwatch_log_group.this[\"fonte-primaria\"] aws_cloudwatch_log_group.this[\"fonte-primaria\"]
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_ecs_task_definition.this[\"fonte-primaria\"] aws_ecs_task_definition.this[\"fonte-primaria\"]
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_lb_target_group.this[\"fonte-primaria\"] aws_lb_target_group.this[\"fonte-primaria\"]
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_lb_listener_rule.this[\"fonte-primaria\"] aws_lb_listener_rule.this[\"fonte-primaria\"]
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_ecs_service.this[\"fonte-primaria\"] aws_ecs_service.this[\"fonte-primaria\"]
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_appautoscaling_target.this[\"fonte-primaria\"] aws_appautoscaling_target.this[\"fonte-primaria\"]
terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_appautoscaling_policy.requisicoes[\"fonte-primaria\"] aws_appautoscaling_policy.requisicoes[\"fonte-primaria\"]
# só se apareceu na lista do passo 2.2:
# terraform state mv "-state=..\origem.tfstate" "-state-out=..\fonte-primaria.tfstate" --% aws_appautoscaling_policy.cpu[\"fonte-primaria\"] aws_appautoscaling_policy.cpu[\"fonte-primaria\"]
terraform state list "-state=..\fonte-primaria.tfstate"
```

A última lista precisa ter exatamente os endereços do passo 2.2. O `origem.tfstate` alterado é descartável: o
state remoto do outro repositório é alterado só no passo 2.4, com `state rm`.

Envie o state para a chave deste repositório. A chave precisa estar vazia: não rode `apply` aqui antes disso.

```powershell
cd <consultas-operacoes-credito-mainframe>\infra\ecs
Copy-Item <tfvars-de-producao-ajustado> terraform.tfvars   # passo 1.2
terraform init -backend-config=backend.hcl
terraform state list                       # sem state: "No state file was found!"
terraform state push "$trabalho\fonte-primaria.tfstate"
terraform state list                       # os mesmos 7 (ou 8) endereços
```

Confirme que este módulo assumiu os recursos **sem mudar nada que importe**. Para isso, o `terraform.tfvars` ainda
descreve a task definition de hoje:
- `imagem` com a imagem **antiga**, por digest;
- `CONSULTAS_ROTAS_ATIVAS = "fonte-primaria"` em `ambiente` (o módulo aceita essa variável ali).

```powershell
terraform plan -out tfplan
terraform show -json tfplan | ConvertFrom-Json |
  Select-Object -ExpandProperty resource_changes |
  Where-Object { $_.change.actions -contains 'delete' } |
  ForEach-Object { $_.address }            # tem que sair vazio
```

O plano esperado só tem `update in-place` de tags: o `default_tags` muda `app` para
`consultas-operacoes-credito-mainframe`. Qualquer `delete` ou `replace` significa um tfvars diferente do de
produção ou um endereço que faltou: **pare**, corrija e planeje de novo. Aplicar esse plano é opcional. Se ele
só muda tags, aplicá-lo antes da janela deixa o plano do cutover menor.

### 2.4 Tirar os recursos do state de `consultas-operacoes-credito`

Só depois do passo 2.3 (o state deste repositório já tem os recursos):

```powershell
cd <consultas-operacoes-credito>\infra\ecs
terraform state rm --% aws_cloudwatch_log_group.this[\"fonte-primaria\"]
terraform state rm --% aws_ecs_task_definition.this[\"fonte-primaria\"]
terraform state rm --% aws_lb_target_group.this[\"fonte-primaria\"]
terraform state rm --% aws_lb_listener_rule.this[\"fonte-primaria\"]
terraform state rm --% aws_ecs_service.this[\"fonte-primaria\"]
terraform state rm --% aws_appautoscaling_target.this[\"fonte-primaria\"]
terraform state rm --% aws_appautoscaling_policy.requisicoes[\"fonte-primaria\"]
# só se apareceu na lista do passo 2.2:
# terraform state rm --% aws_appautoscaling_policy.cpu[\"fonte-primaria\"]
terraform state list | Select-String 'fonte-primaria'   # vazio
terraform plan -out tfplan
terraform show -json tfplan | ConvertFrom-Json |
  Select-Object -ExpandProperty resource_changes |
  Where-Object { $_.change.actions -contains 'delete' } |
  ForEach-Object { $_.address }            # tem que sair vazio
```

`terraform state rm` só esquece os recursos: nada é apagado na AWS. Se o plano de lá ainda tiver `delete`,
**não aplique**: falta algum endereço, ou o tfvars de lá não é o de produção.

O `visao-consolidada-antes.tfstate` do passo 2.2 é o ponto de volta: se algo der errado neste passo,
`terraform state push -force` desse arquivo restaura o state de antes. Use só nesse caso, porque o `-force`
ignora as verificações de lineage e serial.

### 2.5 Alternativa: importar em vez de mover

Se não for possível ler o state antigo, importe os recursos neste repositório com blocos `import` num arquivo
local, fora do commit. Os IDs vêm da AWS:

| Endereço | ID do import |
| --- | --- |
| `aws_cloudwatch_log_group.this["fonte-primaria"]` | nome do log group (`/ecs/<nome>/fonte-primaria`) |
| `aws_ecs_task_definition.this["fonte-primaria"]` | ARN da task definition em uso (`$tdAtual`) |
| `aws_lb_target_group.this["fonte-primaria"]` | ARN do target group |
| `aws_lb_listener_rule.this["fonte-primaria"]` | ARN da regra `/v1/operacoes-credito/fonte-primaria*` |
| `aws_ecs_service.this["fonte-primaria"]` | `<cluster>/<servico>` |
| `aws_appautoscaling_target.this["fonte-primaria"]` | `service-namespace/resource-id/scalable-dimension`: `ecs/service/<cluster>/<servico>/ecs:service:DesiredCount` |
| `aws_appautoscaling_policy.requisicoes["fonte-primaria"]` | `ecs/service/<cluster>/<servico>/ecs:service:DesiredCount/<nome>-fonte-primaria-alb-requisicoes` |

O critério é o mesmo do passo 2.3: o plano não pode ter `delete` nem `replace`. Depois, siga o passo 2.4 do
mesmo jeito.

## 3. Cutover: trocar a imagem

1. Confirme que o serviço está estável e anote a linha de base das métricas da seção 4 (a última hora e a
   mesma hora do dia anterior):

   ```powershell
   aws ecs describe-services --cluster $cluster --services $servico `
     --query 'services[0].[taskDefinition, runningCount, desiredCount, deployments[].rolloutState]'
   ```

2. No `terraform.tfvars` deste repositório, troque `imagem` pelo digest da imagem
   `consultas-operacoes-credito-mainframe` e tire `CONSULTAS_ROTAS_ATIVAS` de `ambiente`.
3. Planeje e confira:

   ```powershell
   cd <consultas-operacoes-credito-mainframe>\infra\ecs
   terraform plan -out tfplan
   ```

   - `aws_ecs_task_definition.this["fonte-primaria"]` é substituída. Toda mudança de container gera uma revisão
     nova, e o Terraform **desregistra a revisão anterior** (ela fica `INACTIVE`). Isso afeta o rollback
     (seção 5).
   - `aws_ecs_service.this["fonte-primaria"]` tem `update in-place` só de `task_definition`, com
     `deployment_maximum_percent = 100` e `deployment_minimum_healthy_percent = 50`.
   - Nenhum `delete` em target group, regra, serviço, autoscaling ou log group (use o filtro do passo 2.3; a
     única substituição aceita é a da task definition).

4. Aplique:

   ```powershell
   terraform apply tfplan
   aws ecs wait services-stable --cluster $cluster --services $servico
   aws ecs describe-services --cluster $cluster --services $servico `
     --query 'services[0].deployments[].[status, rolloutState, taskDefinition, runningCount]'
   ```

   O deploy com `maximumPercent=100` não sobe tasks a mais: o ECS para até metade das tasks, sobe as novas e
   repete. Assim, as chamadas ao mainframe não dobram durante o deploy. Com o mínimo de 2 tasks, a capacidade
   cai pela metade por alguns minutos, e o 429 (`Retry-After`) pode subir nesse intervalo. Faça o cutover fora
   do pico.

   Se as tasks novas não ficarem saudáveis (`rolloutState = FAILED`), o circuit breaker do deploy
   (`rollback = true`) tenta voltar à revisão anterior. Se o serviço não ficar estável nela (a revisão está
   `INACTIVE`, veja a seção 5), faça o rollback da seção 5.1. Em qualquer caso, siga a seção 5.2 para deixar o
   state coerente.

## 4. Verificação

Painel: **"Consultas de Operações de Crédito"** (Grafana, uid `consultas-operacoes-credito`, de
`observabilidade/grafana/dashboards/consultas-operacoes-credito.json`), com `rota = fonte_primaria`. Compare com
a linha de base do passo 3.1 durante pelo menos uma hora, e de novo no primeiro pico depois do cutover.

| O que | Onde | Esperado |
| --- | --- | --- |
| Taxa de 2xx / 429 / 5xx | "Requisições por status", "Taxa de erro (5xx) por tipo de consulta", "Respostas 429 por rota" | 5xx no nível da linha de base; 429 só no intervalo do deploy |
| p95 por tipo de consulta | PromQL abaixo (o painel tem p50 e p99) | Dentro da linha de base e dos prazos (`OPERACAO`, `CLIENTE`, `CONTA`) |
| Hits do cache de custódia | "Descoberta de custódia por rota, custódia e resultado", `resultado = cache` | Mesma proporção de `cache` sobre o total que antes; o Valkey e a chave são os mesmos |
| Eventos de consumo | `eventos_consumo_enviados_total`, `eventos_consumo_descartados_total`; Athena | Enviados acompanham as requisições; descartados perto de zero; eventos com `rota = 'fonte_primaria'` chegando |
| Chamadas ao mainframe dentro da cota | "Chamadas ao mainframe (todas as tasks) × cota", "Permissões livres nos rate limiters (mínimo entre tasks)" | Soma ≤ 1.500/s (a cota); nunca acima, nem durante o deploy |
| Circuitos e bulkheads | "Circuitos abertos agora", "Vagas livres nos bulkheads" | Iguais à linha de base |

p95 da rota por tipo de consulta:

```promql
histogram_quantile(0.95, sum by (le, tipo_consulta) (
  rate(http_server_requests_seconds_bucket{rota="fonte_primaria", uri="/v1/operacoes-credito/fonte-primaria"}[5m])))
```

Eventos de consumo no Athena (tabela do pipeline Firehose → S3, fora deste repositório; campos em
`docs/eventos/evento-consumo.md`):

```sql
SELECT status_http, count(*) AS eventos
FROM <tabela_eventos_consumo>
WHERE rota = 'fonte_primaria' AND from_iso8601_timestamp("timestamp") >= <inicio_do_cutover>
GROUP BY status_http
ORDER BY status_http;
```

Conferências diretas:
- **A imagem nova está em todas as tasks.** O `taskDefinition` de todas as tasks em execução é a revisão nova:
  `aws ecs list-tasks --cluster $cluster --service-name $servico`, depois `aws ecs describe-tasks`.
- **Fumaça pela rota pública** (pelo API Gateway, com uma API key de teste): `OPERACAO` de uma operação conhecida
  responde 200, com o mesmo corpo de antes do cutover.
- **Logs** (`/ecs/<nome>/fonte-primaria`): `service.name` = `consultas-operacoes-credito-mainframe`; sem `ERROR`
  novos.

Critério para encerrar: uma hora sem desvio da linha de base e um pico sem 5xx acima dela. Mantenha a imagem
antiga no registry (passo 1.4) pelo menos até o fim do período de rollback combinado com o time.

## 5. Rollback

Rollback é voltar a task definition anterior, com a **imagem antiga e `consultas.rotas-ativas = fonte-primaria`**
(`CONSULTAS_ROTAS_ATIVAS`). **Isso só funciona enquanto essa imagem existir no registry**: se ela foi apagada ou
expirada (passo 1.4), não há para onde voltar.

### 5.1 Rápido, pela AWS CLI

O apply do passo 3 deixou a revisão anterior `INACTIVE`. Um serviço não pode ser atualizado para uma revisão
`INACTIVE`, então registre de novo a definição salva no passo 1.2:

```powershell
$salvo = Get-Content -Raw "$trabalho\td-antes-do-cutover.json" | ConvertFrom-Json
$td = $salvo.taskDefinition
aws ecs describe-task-definition --task-definition $td.taskDefinitionArn --query 'taskDefinition.status'
# ACTIVE: use $td.taskDefinitionArn direto no update-service abaixo. INACTIVE: registre de novo
# Só os campos aceitos pelo register-task-definition, sem nulos (a CLI recusa null)
$entrada = [ordered]@{}
foreach ($campo in 'family', 'taskRoleArn', 'executionRoleArn', 'networkMode', 'containerDefinitions', 'volumes',
                   'placementConstraints', 'requiresCompatibilities', 'cpu', 'memory', 'runtimePlatform') {
  if ($null -ne $td.$campo) { $entrada[$campo] = $td.$campo }
}
if ($salvo.tags) { $entrada['tags'] = $salvo.tags }
[IO.File]::WriteAllText("$trabalho\td-rollback.json", ($entrada | ConvertTo-Json -Depth 32))
$tdRollback = aws ecs register-task-definition --cli-input-json "file://$trabalho\td-rollback.json" `
  --query 'taskDefinition.taskDefinitionArn' --output text
aws ecs update-service --cluster $cluster --service $servico --task-definition $tdRollback
aws ecs wait services-stable --cluster $cluster --services $servico
```

Depois verifique como na seção 4: o `service.name` volta a `consultas-operacoes-credito`.

### 5.2 Deixar o Terraform coerente

Depois do 5.1, o state deste repositório ainda aponta para a revisão nova, e o próximo `apply` faria o cutover
de novo. Para alinhar, ponha no `terraform.tfvars` a imagem antiga e `CONSULTAS_ROTAS_ATIVAS = "fonte-primaria"`
em `ambiente`, e rode `terraform plan` / `terraform apply`. Esse também é o caminho de rollback sem a CLI, um
pouco mais lento. Volte o tfvars para a imagem nova só quando a causa estiver corrigida.

O state continua neste repositório; o rollback não desfaz o passo 2. Devolver os recursos ao state do outro
repositório só faz sentido se o ADR-0017 for revertido. Nesse caso, use o caminho inverso: `state mv` deste
state para o de lá, e `state rm` aqui.

## 6. Ordem com `consultas-operacoes-credito`

- O serviço `visao-consolidada` só recebe a imagem podada (joaopedro-techon/consultas-operacoes-credito#18)
  **depois do cutover** do `fonte-primaria`, ou a qualquer momento, desde que essa imagem **nunca vá para o
  serviço `fonte-primaria`**. A imagem podada não tem a rota da fonte primária, e no `fonte-primaria` toda
  consulta viraria 404.
- O deploy da imagem podada no `visao-consolidada` é um `terraform apply` no `infra/ecs` de lá, e só pode
  acontecer **depois do passo 2.4**. Antes dele, o apply destrói o `fonte-primaria`.
- Na mesma janela, só um dos dois serviços troca de imagem por vez: se algo degradar, fica claro qual mudança
  voltar.
- A imagem antiga (passo 1.4) continua no registry mesmo depois de o `visao-consolidada` receber a podada: ela
  é o rollback do `fonte-primaria`.
