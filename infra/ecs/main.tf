locals {
  container_name = "app"
  container_port = 8080

  # So o servico da fonte primaria (ADR-0017); o da visao consolidada e a regra dele no ALB
  # (/v1/operacoes-credito*) ficam no repositorio consultas-operacoes-credito. O mapa com uma entrada mantem os
  # enderecos dos recursos (this["fonte-primaria"]) de antes da separacao: o cutover e so trocar a imagem.
  servicos = {
    "fonte-primaria" = {
      config        = var.fonte_primaria
      sigla         = "fp"
      path_patterns = ["/v1/operacoes-credito/fonte-primaria*"]
      prioridade    = var.prioridade_regras
      # Requisicoes longas (centenas de ms a segundos): menos requisicoes pendentes equilibra melhor.
      # O ALB nao aceita slow start com least_outstanding_requests.
      algoritmo  = "least_outstanding_requests"
      slow_start = 0
      # Nunca mais tasks que max_tasks, nem durante o deploy: cada task tem cota / max_tasks (ADR-0015)
      deployment_maximum_percent         = 100
      deployment_minimum_healthy_percent = 50
      ambiente = {
        CONSULTAS_FONTE_PRIMARIA_COTA_CHAMADAS_POR_SEGUNDO = tostring(var.fonte_primaria.cota_chamadas_por_segundo)
        CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS            = tostring(var.fonte_primaria.max_tasks)
      }
    }
  }

  # Combinacoes validas de CPU (unidades) e memoria (MiB) do Fargate
  memorias_fargate = tomap({
    "256"   = [512, 1024, 2048]
    "512"   = range(1024, 4097, 1024)
    "1024"  = range(2048, 8193, 1024)
    "2048"  = range(4096, 16385, 1024)
    "4096"  = range(8192, 30721, 1024)
    "8192"  = range(16384, 61441, 4096)
    "16384" = range(32768, 122881, 8192)
  })

  ambiente_por_servico = {
    for nome, s in local.servicos : nome => merge(
      var.ambiente,
      s.ambiente,
      { for k, v in { JAVA_TOOL_OPTIONS = s.config.java_tool_options } : k => v if v != "" },
    )
  }
}

data "aws_ecs_cluster" "this" {
  cluster_name = var.cluster_name
}

data "aws_lb" "this" {
  arn = var.alb_arn
}

resource "aws_cloudwatch_log_group" "this" {
  for_each = local.servicos

  name              = "/ecs/${var.nome}/${each.key}"
  retention_in_days = var.retencao_logs_dias
}

resource "aws_ecs_task_definition" "this" {
  for_each = local.servicos

  family                   = "${var.nome}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(each.value.config.cpu)
  memory                   = tostring(each.value.config.memory)
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.arquitetura_cpu
  }

  # Sistema de arquivos somente leitura; o /tmp (Tomcat) fica num volume efemero da task
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name                   = local.container_name
      image                  = var.imagem
      essential              = true
      readonlyRootFilesystem = true
      portMappings = [
        { containerPort = local.container_port, protocol = "tcp" }
      ]
      environment = [for k, v in local.ambiente_por_servico[each.key] : { name = k, value = v }]
      secrets     = [for k, v in var.segredos : { name = k, valueFrom = v }]
      mountPoints = [
        { sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }
      ]
      # SIGTERM -> readiness DOWN e desligamento gracioso (spring.lifecycle.timeout-per-shutdown-phase = 30s)
      stopTimeout = 45
      ulimits = [
        { name = "nofile", softLimit = 65536, hardLimit = 65536 }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this[each.key].name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = local.container_name
          # Log nao bloqueia a aplicacao se o CloudWatch ficar lento
          "mode"            = "non-blocking"
          "max-buffer-size" = "25m"
        }
      }
    }
  ])

  lifecycle {
    precondition {
      condition     = try(contains(local.memorias_fargate[tostring(each.value.config.cpu)], each.value.config.memory), false)
      error_message = "${each.key}: cpu ${each.value.config.cpu} com memory ${each.value.config.memory} nao e uma combinacao valida do Fargate."
    }
  }
}

resource "aws_lb_target_group" "this" {
  for_each = local.servicos

  name                          = "${var.prefixo_target_group}-${each.value.sigla}"
  port                          = local.container_port
  protocol                      = "HTTP"
  target_type                   = "ip"
  vpc_id                        = var.vpc_id
  load_balancing_algorithm_type = each.value.algoritmo
  slow_start                    = each.value.slow_start
  # Menor que o stopTimeout (45 s) e maior que o maior prazo da aplicacao (6 s)
  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each = local.servicos

  listener_arn = var.alb_listener_arn
  priority     = each.value.prioridade

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }

  condition {
    path_pattern {
      values = each.value.path_patterns
    }
  }
}

resource "aws_ecs_service" "this" {
  for_each = local.servicos

  name                               = "${var.nome}-${each.key}"
  cluster                            = data.aws_ecs_cluster.this.arn
  task_definition                    = aws_ecs_task_definition.this[each.key].arn
  desired_count                      = each.value.config.min_tasks
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  deployment_maximum_percent         = each.value.deployment_maximum_percent
  deployment_minimum_healthy_percent = each.value.deployment_minimum_healthy_percent
  # Subida da JVM + aquecimento antes de o ALB poder derrubar a task por health check
  health_check_grace_period_seconds = 90
  enable_ecs_managed_tags           = true
  propagate_tags                    = "SERVICE"
  wait_for_steady_state             = false

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this[each.key].arn
    container_name   = local.container_name
    container_port   = local.container_port
  }

  # Quem decide a quantidade de tasks e o autoscaling
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener_rule.this]
}

resource "aws_appautoscaling_target" "this" {
  for_each = local.servicos

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${data.aws_ecs_cluster.this.cluster_name}/${aws_ecs_service.this[each.key].name}"
  min_capacity       = each.value.config.min_tasks
  max_capacity       = each.value.config.max_tasks
}

# Politica principal: requisicoes por task no ALB. A metrica e a soma POR MINUTO (alvo = RPS por task x 60).
resource "aws_appautoscaling_policy" "requisicoes" {
  for_each = local.servicos

  name               = "${var.nome}-${each.key}-alb-requisicoes"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = each.value.config.alvo_requisicoes_por_task_minuto
    scale_out_cooldown = each.value.config.scale_out_cooldown
    scale_in_cooldown  = each.value.config.scale_in_cooldown
    disable_scale_in   = false

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${data.aws_lb.this.arn_suffix}/${aws_lb_target_group.this[each.key].arn_suffix}"
    }
  }

  depends_on = [aws_lb_listener_rule.this]
}

# Rede de seguranca: se o custo de CPU por requisicao for maior que o estimado, a CPU escala antes da latencia
# degradar. Com duas politicas, o ECS sobe se qualquer uma pedir e so desce se todas permitirem.
resource "aws_appautoscaling_policy" "cpu" {
  for_each = { for nome, s in local.servicos : nome => s if s.config.alvo_cpu != null }

  name               = "${var.nome}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = each.value.config.alvo_cpu
    scale_out_cooldown = each.value.config.scale_out_cooldown
    scale_in_cooldown  = each.value.config.scale_in_cooldown

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
