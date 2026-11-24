variable "aws_region" {
  description = "Regiao AWS do cluster ECS e do ALB."
  type        = string
}

variable "nome" {
  description = "Prefixo dos recursos (servicos, task definitions, log groups, politicas de escala)."
  type        = string
  default     = "consultas-operacoes-credito"
}

variable "prefixo_target_group" {
  description = "Prefixo curto dos target groups (o nome do target group aceita no maximo 32 caracteres)."
  type        = string
  default     = "consultas"

  validation {
    condition     = can(regex("^[A-Za-z0-9-]{1,28}$", var.prefixo_target_group))
    error_message = "prefixo_target_group aceita letras, digitos e '-', com no maximo 28 caracteres."
  }
}

# ---------------------------------------------------------------------------------------------------------
# Infraestrutura existente (nao criada aqui)
# ---------------------------------------------------------------------------------------------------------

variable "cluster_name" {
  description = "Nome do cluster ECS existente."
  type        = string
}

variable "vpc_id" {
  description = "VPC dos target groups (a mesma das subnets)."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets privadas das tasks (uma por AZ; use 3 AZs)."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "Informe ao menos duas subnets (AZs diferentes)."
  }
}

variable "security_group_ids" {
  description = "Security groups das tasks. Devem aceitar TCP 8080 vindo do security group do ALB e permitir saida para Valkey, SQS, gateway do mainframe e Collector."
  type        = list(string)
}

variable "alb_arn" {
  description = "ARN do Application Load Balancer existente (usado no resource_label do ALBRequestCountPerTarget)."
  type        = string
}

variable "alb_listener_arn" {
  description = "ARN do listener (HTTPS) do ALB onde as regras por path sao criadas."
  type        = string
}

variable "prioridade_regras" {
  description = "Prioridade da regra da fonte primaria no listener. A regra da visao consolidada (/v1/operacoes-credito*, no outro repositorio) precisa de um numero maior: a mais especifica (fonte primaria) vem antes."
  type        = number
  default     = 100

  validation {
    condition     = var.prioridade_regras >= 1 && var.prioridade_regras <= 49999
    error_message = "prioridade_regras deve estar entre 1 e 49999."
  }
}

variable "execution_role_arn" {
  description = "Role de execucao da task (pull da imagem, logs, leitura dos segredos de var.segredos)."
  type        = string
}

variable "task_role_arn" {
  description = "Role da aplicacao (SQS SendMessage)."
  type        = string
}

# ---------------------------------------------------------------------------------------------------------
# Aplicacao
# ---------------------------------------------------------------------------------------------------------

variable "imagem" {
  description = "Imagem consultas-operacoes-credito-mainframe, de preferencia por digest: <repo>@sha256:..."
  type        = string
}

variable "arquitetura_cpu" {
  description = "X86_64 ou ARM64 (Graviton, ~20% mais barato; exige imagem multi-arquitetura e nova medicao com o k6)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.arquitetura_cpu)
    error_message = "arquitetura_cpu deve ser X86_64 ou ARM64."
  }
}

variable "ambiente" {
  description = "Variaveis de ambiente do servico (AWS_REGION, CONSULTAS_VALKEY_HOST, CONSULTAS_FONTE_PRIMARIA_URL, CONSULTAS_EVENTOS_SQS_URL_FILA, OTEL_EXPORTER_OTLP_ENDPOINT...). A cota e preenchida pelo modulo."
  type        = map(string)
  default     = {}

  validation {
    condition     = length(setintersection(keys(var.ambiente), ["CONSULTAS_FONTE_PRIMARIA_COTA_CHAMADAS_POR_SEGUNDO", "CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS"])) == 0
    error_message = "A cota da fonte primaria e definida pelo modulo (var.fonte_primaria), nao em var.ambiente."
  }
}

variable "segredos" {
  description = "Segredos injetados como variavel de ambiente: nome => ARN no Secrets Manager ou SSM (ex.: CONSULTAS_EVENTOS_SEGREDO_HMAC)."
  type        = map(string)
  default     = {}
}

variable "retencao_logs_dias" {
  description = "Retencao dos log groups no CloudWatch."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------------------------------------------------
# Dimensionamento (docs/capacidade-ecs-fargate.md). Todos os numeros sao estimativas a validar com o k6.
# ---------------------------------------------------------------------------------------------------------

variable "fonte_primaria" {
  description = <<-EOT
    Servico da rota fonte primaria (gateway mainframe), I/O-bound e limitado pela cota.
    max_tasks tambem vira CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS: cada task limita-se a cota_chamadas_por_segundo / max_tasks
    (ADR-0015), entao aumentar max_tasks sem aumentar a cota NAO aumenta a vazao.
    alvo_cpu = null por padrao (a vazao e limitada pela cota, nao pela CPU).
  EOT
  type = object({
    cpu                              = optional(number, 1024)
    memory                           = optional(number, 2048)
    min_tasks                        = optional(number, 2)
    max_tasks                        = optional(number, 3)
    cota_chamadas_por_segundo        = optional(number, 1500)
    alvo_requisicoes_por_task_minuto = optional(number, 1500)
    alvo_cpu                         = optional(number)
    scale_out_cooldown               = optional(number, 60)
    scale_in_cooldown                = optional(number, 600)
    java_tool_options                = optional(string, "")
  })
  default = {}

  validation {
    condition     = var.fonte_primaria.min_tasks >= 1 && var.fonte_primaria.min_tasks <= var.fonte_primaria.max_tasks
    error_message = "fonte_primaria: min_tasks deve ser >= 1 e <= max_tasks."
  }

  validation {
    condition     = var.fonte_primaria.cota_chamadas_por_segundo >= var.fonte_primaria.max_tasks
    error_message = "fonte_primaria: a cota precisa dar ao menos 1 chamada/s por task (cota >= max_tasks)."
  }
}

variable "tags" {
  description = "Tags aplicadas a todos os recursos."
  type        = map(string)
  default = {
    app        = "consultas-operacoes-credito-mainframe"
    managed-by = "terraform"
  }
}
