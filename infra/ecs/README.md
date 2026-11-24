# Serviço ECS Fargate `fonte-primaria` (referência) — IaC

Terraform de referência do serviço da fonte primária (ADR-0015, ADR-0017): a imagem `consultas-operacoes-credito-mainframe` roda como o serviço `fonte-primaria`, atrás de um ALB que já existe, com seu target group e seu autoscaling. O serviço `visao-consolidada`, a regra `/v1/operacoes-credito*` dele e o API Gateway ficam no repositório `consultas-operacoes-credito`. Os números padrão saem de [`docs/capacidade-ecs-fargate.md`](../../docs/capacidade-ecs-fargate.md) e são **estimativas**: recalibre com o k6 (`carga/README.md`) antes de produção.

## O que é criado

| Recurso | `fonte-primaria` |
| --- | --- |
| Task definition | 1 vCPU / 2 GB, `CONSULTAS_FONTE_PRIMARIA_COTA_CHAMADAS_POR_SEGUNDO` = cota, `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS` = `max_tasks` |
| Target group (`ip`, HTTP 8080) | least outstanding requests |
| Health check | `/actuator/health/readiness`, 10 s, 2 ok / 3 falhas |
| Regra no listener (path) | `/v1/operacoes-credito/fonte-primaria*` (prioridade `prioridade_regras`; a regra da visão consolidada precisa de um número maior) |
| Serviço ECS | deploy **100% / 50%**: nunca mais tasks que `max_tasks`, nem durante o deploy; circuit breaker com rollback |
| Autoscaling | 2–3 tasks; `ALBRequestCountPerTarget` alvo 1.500/min (25 req/s) |
| Log group | `/ecs/<nome>/fonte-primaria` |

Pontos de projeto:
- **`ALBRequestCountPerTarget` é a soma por minuto**: o alvo é RPS por task × 60.
- **`max_tasks` é também o divisor da cota.** Cada task limita-se a `cota ÷ max_tasks` chamadas/s (output `fonte_primaria_chamadas_por_segundo_por_task`). Subir `max_tasks` sem subir a cota não aumenta a vazão; só divide a mesma cota em fatias menores.
- Os recursos continuam num mapa com a chave `fonte-primaria` (`aws_ecs_service.this["fonte-primaria"]`...), os mesmos endereços de antes da separação dos repositórios.
- Sistema de arquivos da task somente leitura; `/tmp` (Tomcat, hsperfdata) fica num volume efêmero.
- `stopTimeout` 45 s > desligamento gracioso (30 s) > `deregistration_delay` (30 s) > maior prazo (6 s).

## State de antes da separação

Em produção, esses recursos já existem, criados pelo `infra/ecs` de `consultas-operacoes-credito` e no state
dele. **Não rode `apply` aqui com o state vazio**: o Terraform tentaria criar de novo o serviço, o target group, a
regra e o log group. Também não aponte este módulo para a mesma chave de state do outro repositório, porque
cada apply destruiria o que só o outro declara.

O state deste módulo fica numa chave própria, com os recursos `["fonte-primaria"]` movidos do state de lá
(`state pull` → `state mv` → `state push`), e lá eles saem com `terraform state rm`. O passo a passo, o que
conferir no `plan` e o rollback estão em [`docs/runbook-cutover.md`](../../docs/runbook-cutover.md).

O state fica em S3 (`backend "s3" {}` em `versions.tf`, lock nativo `use_lockfile`), na chave
`consultas-operacoes-credito-mainframe/ecs/terraform.tfstate` (modelo em `backend.hcl.example`). A chave de
`consultas-operacoes-credito` é `consultas-operacoes-credito/ecs/terraform.tfstate`, e as duas nunca podem ser a mesma.

## Fora do escopo (pré-requisitos)

Cluster ECS, VPC/subnets, ALB e listener HTTPS, security groups (tasks aceitando 8080 vindo do ALB), roles IAM (execução: ECR, logs e segredos; aplicação: SQS `SendMessage`), ElastiCache, fila SQS e Collector OTel. O módulo recebe tudo por variável.

## Aplicar

```powershell
cd infra/ecs
Copy-Item terraform.tfvars.example terraform.tfvars   # git-ignored; ajuste ARNs, imagem e ambiente
Copy-Item backend.hcl.example backend.hcl             # git-ignored; ajuste o bucket (a chave já é a deste repositório)
terraform init -backend-config=backend.hcl             # no cutover: a chave só recebe o state pelo runbook (state push)
terraform plan -out tfplan
terraform apply tfplan
terraform output
```

Validação local sem credenciais (a mesma da CI):

```powershell
terraform -chdir=infra/ecs fmt -check -recursive
terraform -chdir=infra/ecs init -backend=false -input=false
terraform -chdir=infra/ecs validate
```

## Antes de produção

- **Cotas da conta**: vCPU do Fargate On-Demand na região para as 3 tasks de 1 vCPU (o deploy a 100% não sobe tasks extras).
- **Timeout da integração do API Gateway** (no repositório da visão consolidada, `integration_timeout_ms`, padrão 1000 ms): a rota fonte primária tem prazos de 2,5 s e 6 s e precisa de pelo menos 6.500 ms, senão o gateway corta antes da aplicação.
- **Graviton** (`arquitetura_cpu = "ARM64"`): cerca de 20% mais barato por vCPU; exige imagem multi-arquitetura e nova medição.
- **`.terraform.lock.hcl`** ainda não versionado: gerar no primeiro `terraform init` e commitar.
