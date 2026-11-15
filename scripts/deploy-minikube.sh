#!/usr/bin/env bash
# Deploy QueueGuard to Minikube (or any kubectl context).
#   1) minikube start --memory=4096 --cpus=4
#   2) eval $(minikube docker-env) && ./scripts/build-images.sh
#   3) ./scripts/deploy-minikube.sh
#   4) minikube tunnel   (in another terminal) -> http://<EXTERNAL-IP>
set -euo pipefail
cd "$(dirname "$0")/.."

echo ">>> applying manifests"
for f in k8s/00-namespace.yaml k8s/01-secrets.yaml k8s/02-configmap.yaml \
         k8s/03-postgres.yaml k8s/04-redis.yaml \
         k8s/10-auth-service.yaml k8s/11-queue-service.yaml k8s/12-worker-service.yaml \
         k8s/13-rate-limit-service.yaml k8s/14-monitor-service.yaml k8s/15-api-gateway.yaml \
         k8s/16-queue-management-service.yaml \
         k8s/21-hpa-pdb.yaml k8s/22-network-policies.yaml; do
  kubectl apply -f "$f"
done

echo ">>> waiting for storage"
kubectl -n queueguard rollout status deploy/postgres --timeout=180s
kubectl -n queueguard rollout status deploy/redis --timeout=120s

echo ">>> waiting for microservices"
for d in auth-service queue-service worker-service rate-limit-service monitor-service queue-management-service api-gateway; do
  kubectl -n queueguard rollout status "deploy/${d}" --timeout=300s
done

echo ""
echo "QueueGuard is up. Get the gateway URL with either:"
echo "  minikube service api-gateway -n queueguard --url"
echo "  (or run 'minikube tunnel' and use the EXTERNAL-IP of svc/api-gateway)"
kubectl -n queueguard get svc api-gateway
