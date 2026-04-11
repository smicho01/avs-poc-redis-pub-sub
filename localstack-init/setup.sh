#!/bin/bash

echo ">>> Setting up LocalStack resources..."

AWS="aws --endpoint-url=http://localhost:4566 --region us-east-1"

# S3 buckets
$AWS s3 mb s3://file-storage
echo ">>> Created S3 bucket: file-storage"

$AWS s3 mb s3://external-malware-scanner-inbox
echo ">>> Created S3 bucket: external-malware-scanner-inbox"

$AWS s3 mb s3://external-malware-scanner-healthy
echo ">>> Created S3 bucket: external-malware-scanner-healthy"
$AWS s3 mb s3://external-malware-scanner-quarantine
echo ">>> Created S3 bucket: external-malware-scanner-quarantine"


# SQS queue for scan results
$AWS sqs create-queue --queue-name scan-results
echo ">>> Created SQS queue: scan-results"

QUEUE_URL=$($AWS sqs get-queue-url --queue-name scan-results --query 'QueueUrl' --output text)
QUEUE_ARN=$($AWS sqs get-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)
echo ">>> Queue ARN: $QUEUE_ARN"

# SNS topic for external malware scanner outcome
TOPIC_ARN=$($AWS sns create-topic --name external-malware-scanner-outcome --query 'TopicArn' --output text)
echo ">>> Created SNS topic: $TOPIC_ARN"

# SQS policy to allow SNS to send messages to the queue
POLICY_FILE=$(mktemp)
cat > "$POLICY_FILE" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": "*",
      "Action": "sqs:SendMessage",
      "Resource": "$QUEUE_ARN",
      "Condition": {
        "ArnEquals": {
          "aws:SourceArn": "$TOPIC_ARN"
        }
      }
    }
  ]
}
EOF

$AWS sqs set-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attributes "Policy=file://$POLICY_FILE"
rm "$POLICY_FILE"
echo ">>> Applied SQS policy for SNS"

# Subscribe SQS to SNS topic
$AWS sns subscribe \
  --topic-arn "$TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "$QUEUE_ARN"
echo ">>> Subscribed SQS to SNS topic"

echo ""
echo ">>> LocalStack setup complete"
echo ">>> file-storage bucket                  : s3://file-storage"
echo ">>> external-malware-scanner-inbox bucket: s3://external-malware-scanner-inbox"
echo ">>> SQS queue URL        : $QUEUE_URL"
echo ">>> SNS topic ARN        : $TOPIC_ARN"