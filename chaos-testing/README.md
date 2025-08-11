
# Maru test network topology

- 1 maru validator: `maru-validator` -> `besu-sequencer` (starts with Clique then switch to QBFT)
- 4 maru followers:
  - `maru-follower-0-0` -> `besu-follower-0` (will also work as bootnode)
  - `maru-follower-1-0` -> `besu-follower-1`
  - `maru-follower-2-0` -> `besu-follower-2`
  - `maru-follower-3-0` -> [`besu-follower-3`, `besu-follower-4`]
     - `besu-follower-3` is the primary EL client
     - `besu-follower-4` is just EL client replica

# Quick start

### Full provisioning

```bash
export KUBECONFIG=~/.kube/k3s-server
make chaos-full-reload
```

- deploys K3S kubernetes cluster
- deploys Chaos-Mesh into `chaos-mesh` namespace
- deploys Maru and Besu network into `default` namespace

### Other helpful commands

- `make helm-redeploy-maru-and-besu` - redeploys Maru + Besu network from genesis
- - `make chaos-experiment-podkill-besu-nodes` - runs Besu downtime experiment of 60s
- `make chaos-redeploy-and-run-experiment` - combines above targets

