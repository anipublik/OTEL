# Contributing to otel-starter

Configs for new stacks and fixes to existing ones welcome. The bar is simple: if it passes the Collector's own validator and every non-trivial line has a comment, it belongs here.

---

## Rules for new config contributions

**One directory per app stack.** Every new app stack lives under `configs/<stack-name>/`. Include a `README.md`, a `collector.yaml`, and a `sdk-bootstrap.<ext>` if the stack has an SDK.

**Must pass `otelcol-contrib validate` before opening a PR.** Run the validator yourself:

```bash
docker run --rm \
  -v $(pwd)/configs/mystack/collector.yaml:/config.yaml \
  otel/opentelemetry-collector-contrib:latest \
  validate --config /config.yaml
```

The CI will run this same check. If it fails there it will fail in CI.

**Comment every non-trivial line.** A "non-trivial line" is any line where a reader unfamiliar with OTEL would reasonably wonder "why is this here" or "what does this do." The reviewer will reject uncommented non-trivial fields. When in doubt, add the comment.

**No new Docker images in the infra stack.** The five services in `infra/docker/docker-compose.yml` are the observability stack: Collector, Prometheus, Tempo, Loki, Grafana. If your config requires a sixth service (e.g. a mock app to generate telemetry), put it in a separate `infra/custom/` directory with its own README explaining why it can't fit in the existing stack.

**Semantic conventions only.** Do not invent attribute names. If you're naming a span attribute or metric, check [opentelemetry.io/docs/specs/semconv/](https://opentelemetry.io/docs/specs/semconv/) first. Use the standard name if one exists.

**Replace deprecated fields — do not keep them.** If you see `k8snode` in an existing config, replace it with `k8s_api`. If you see `jaegerreceiver` or `jaegerexporter` anywhere, remove it — Jaeger is being removed from collector-contrib in December 2026 and should not be added to new configs. Send a fix PR if you spot a deprecated field in an existing file.

**No hardcoded secrets.** Every token, password, API key, or connection string must use the `${ENV_VAR_NAME}` syntax with a comment explaining where to get the value. The CI will reject configs that contain obvious credential patterns.

---

## Pull request checklist

- [ ] `otelcol-contrib validate` passes locally
- [ ] Every non-trivial config line has a `#` comment
- [ ] No deprecated receivers or exporters (`jaegerreceiver`, `jaegerexporter`, `k8snode` detector)
- [ ] No hardcoded secrets — env var references only
- [ ] Directory has a `README.md` with setup steps
- [ ] Attribute names follow OTel semantic conventions
- [ ] If adding a new stack, the top-level `README.md` "Pick your stack" table is updated

---

## Local validation

To run the same checks CI runs:

```bash
# Lint all YAML files
pip install yamllint
yamllint -d '{extends: relaxed, rules: {line-length: {max: 120}}}' .

# Validate all collector.yaml files
find . -name "collector.yaml" | while read f; do
  echo "Validating $f..."
  docker run --rm \
    -v "$(pwd)/$f:/config.yaml" \
    otel/opentelemetry-collector-contrib:latest \
    validate --config /config.yaml
done
```

---

## Questions

Open a GitHub Discussion. Don't open an issue unless it's a concrete bug (broken config, validator regression, etc.).
