output "servicos" {
  description = "Nome do servico ECS (so fonte-primaria)."
  value       = { for nome, s in aws_ecs_service.this : nome => s.name }
}

output "task_definitions" {
  description = "ARN (com revisao) da task definition por servico."
  value       = { for nome, t in aws_ecs_task_definition.this : nome => t.arn }
}

output "target_groups" {
  description = "ARN do target group por servico."
  value       = { for nome, tg in aws_lb_target_group.this : nome => tg.arn }
}

output "log_groups" {
  description = "Log group do CloudWatch por servico."
  value       = { for nome, lg in aws_cloudwatch_log_group.this : nome => lg.name }
}

output "fonte_primaria_chamadas_por_segundo_por_task" {
  description = "Cota local de cada task da fonte primaria (cota / max_tasks, ADR-0015). E o limit-for-period efetivo do rate limiter fontePrimaria por segundo."
  value       = floor(var.fonte_primaria.cota_chamadas_por_segundo / var.fonte_primaria.max_tasks)
}
