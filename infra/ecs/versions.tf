terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # State remoto em S3 (configuração parcial): bucket, chave e região vêm do backend.hcl, fora do git
  # (modelo em backend.hcl.example). A chave é própria deste repositório, nunca a de consultas-operacoes-credito.
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = var.tags
  }
}
