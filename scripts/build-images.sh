#!/usr/bin/env bash
# Build all QueueGuard service images.
# Minikube users: run `eval $(minikube docker-env)` first so images land
# inside the cluster's Docker daemon (no registry needed).
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${VERSION:-1.0.0}"
for svc in api-gateway auth-service queue-service worker-service rate-limit-service monitor-service queue-management-service; do
  echo ">>> building queueguard/${svc}:${VERSION}"
  docker build -f deploy/Dockerfile \
    --build-arg SERVICE_NAME="${svc}" \
    -t "queueguard/${svc}:${VERSION}" .
done
echo "All images built."
