# AWS — OpenTelemetry Collector configs

Three configs for AWS backends. Use them independently or together.

| Config | Backend | Signal | When to use |
|--------|---------|--------|-------------|
| `collector-cloudwatch.yaml` | Amazon CloudWatch | Metrics | CloudWatch alarms + dashboards |
| `collector-xray.yaml` | AWS X-Ray | Traces | X-Ray trace search + service map |
| `collector-s3.yaml` | Amazon S3 | Logs | Long-term log archival (compliance) |

---

## IAM permissions required

### CloudWatch (`collector-cloudwatch.yaml`)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData",
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogGroups",
        "logs:DescribeLogStreams"
      ],
      "Resource": "*"
    }
  ]
}
```

### X-Ray (`collector-xray.yaml`)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "xray:PutTraceSegments",
        "xray:PutTelemetryRecords",
        "xray:GetSamplingRules",
        "xray:GetSamplingTargets"
      ],
      "Resource": "*"
    }
  ]
}
```

### S3 (`collector-s3.yaml`)

```json
{
  "Effect": "Allow",
  "Action": ["s3:PutObject"],
  "Resource": "arn:aws:s3:::YOUR_BUCKET_NAME/*"
}
```

---

## Credential setup

**Local / EC2:** Set `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` as env vars.

**EKS (recommended):** Use IRSA (IAM Roles for Service Accounts). Annotate the Collector's ServiceAccount:

```yaml
annotations:
  eks.amazonaws.com/role-arn: arn:aws:iam::ACCOUNT_ID:role/otel-collector-role
```

No credentials needed in the pod — the SDK uses the projected token automatically.

---

## Cost notes

**CloudWatch:** Custom metrics cost $0.30/metric/month. OTEL metrics can have high cardinality (each unique label combination is a separate metric). Use `filter` and `transform` processors to drop high-cardinality labels before exporting. Check your metric count in CloudWatch before enabling billing alerts.

**X-Ray:** $5.00 per million traces recorded beyond the free tier (100k/month). Use sampling to control cost in high-throughput services.

**S3:** $0.023/GB stored + $0.005/1000 PUT requests. Logs from high-volume services accumulate fast — add a lifecycle policy to transition to S3 Glacier after 90 days.
