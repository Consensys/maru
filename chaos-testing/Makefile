include chaos-mesh.mk
include helm.mk
include k3s.mk

.PHONY: chaos-full-reload \
	chaos-mesh-install-with-curl \
	chaos-mesh-install \
	chaos-mesh-uninstall \
	chaos-mesh-run-network-experiment \
	chaos-mesh-reload \
	helm-clean-releases \
	helm-redeploy-besu \
	helm-redeploy-maru \

chaos-redeploy-chaos-mesh-and-linea:
	@set -e; \
	$(MAKE) chaos-mesh-reinstall & pid1=$$!; \
	$(MAKE) helm-redeploy-linea maru_image=$(if $(maru_image),$(maru_image)) & pid2=$$!; \
	wait $$pid1 || exit 1; \
	wait $$pid2 || exit 1; \

chaos-reload-k3s-and-chaos-mesh:
	@$(MAKE) k3s-reload
	@$(MAKE) chaos-mesh-install

chaos-full-reload:
	@$(MAKE) k3s-reload
	@$(MAKE) chaos-redeploy-chaos-mesh-and-linea maru_image=$(if $(maru_image),$(maru_image))
	@$(MAKE) chaos-experiment-workflow-and-wait # runs workflow-linea-resilience.yaml and waits for it to finish
	@$(MAKE) chaos-health-check

chaos-full-reload-with-local-maru-image:
	@$(MAKE) chaos-full-reload maru_image=consensys/maru:local

chaos-health-check:
	@echo "Running health check for chaos testing environment"
	@echo "Creating port-forward besu and maru nodes"
	-@$(MAKE) port-forward-stop-all
	@$(MAKE) port-forward-all component=besu port=8545
	@$(MAKE) port-forward-all component=maru port=5060
	@cd .. && ./gradlew :chaos-testing:health-check:acceptanceTest

chaos-redeploy-linea-and-run-experiment:
	@$(MAKE) helm-redeploy-linea maru_image=$(if $(maru_image),$(maru_image))
	@$(MAKE) chaos-experiment-workflow-and-wait # runs workflow-linea-resilience.yaml and waits for it to finish
	@$(MAKE) chaos-health-check

chaos-experiment-workflow-and-health-check:
	@$(MAKE) chaos-experiment-workflow-and-wait
	@$(MAKE) chaos-health-check
