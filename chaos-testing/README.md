
# Maru test network topology

- 1 validator: `maru-validator` + `besu-sequencer`
- 2 followers:
  - `maru-follower-0-0` + `besu-follower-0`,
  - `maru-follower-1-0` + `besu-follower-1`

# Quick start

### Full provisioning

```bash
make chaos-full-reload
```

- deploys K3S kubernetes cluster
- deploys Chaos-Mesh into `chaos-mesh` namespace
- deploys Maru and Besu network into `default` namespace

### Other helpful commands

- `make helm-redeploy-maru-and-besu` - redeploys Maru + Besu network from genesis
- - `make chaos-experiment-podkill-besu-nodes` - runs Besu downtime experiment of 60s
- `make chaos-redeploy-and-run-experiment` - combines above targets

