#!/usr/bin/env bash
# =============================================================================
# Applies StockFlow's bucket settings to a bucket that was created by hand (the console), so it ends
# up configured exactly like the one media-cloudfront.yaml creates. Run it once, then deploy
# media-cloudfront.yaml with UseExistingBucket=true for the bucket policy, CloudFront and the IAM policy.
#
#   ./configure-existing-bucket.sh <bucket> <region> <frontend-origins>
#   ./configure-existing-bucket.sh stockflow-123456789012-ap-southeast-2-an ap-southeast-2 \
#       "https://app.example.com,http://localhost:5173"
#
# Needs the AWS CLI v2 signed in as an administrator of the account (not the application's user).
# Idempotent: every call replaces the setting with the same value.
#
# IRREVERSIBLE: enabling Object Lock on a bucket cannot be undone. It sets no default retention, so
# nothing is locked until the application locks an object (a confirmed design, a supplier invoice).
#
# Keep in step with MediaBucket in media-cloudfront.yaml: the two describe the same bucket.
# =============================================================================
set -euo pipefail

BUCKET="${1:?usage: $0 <bucket> <region> <comma-separated frontend origins>}"
REGION="${2:?usage: $0 <bucket> <region> <comma-separated frontend origins>}"
ORIGINS="${3:?usage: $0 <bucket> <region> <comma-separated frontend origins>}"

s3api() { aws s3api --region "$REGION" "$@"; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Public access block: all four on"
s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

echo "Object ownership: bucket owner enforced (ACLs off)"
s3api put-bucket-ownership-controls --bucket "$BUCKET" \
  --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'

echo "Default encryption: SSE-S3"
s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

echo "Versioning: enabled (Object Lock requires it)"
s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled

echo "Object Lock: enabled, no default retention"
s3api put-object-lock-configuration --bucket "$BUCKET" \
  --object-lock-configuration '{"ObjectLockEnabled":"Enabled"}'

cat > "$work/lifecycle.json" <<'JSON'
{
  "Rules": [
    { "ID": "AbortIncompleteMultipartUploads", "Status": "Enabled", "Filter": {},
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 } },
    { "ID": "RetainNoncurrentVersionsForRecovery", "Status": "Enabled", "Filter": {},
      "NoncurrentVersionExpiration": { "NoncurrentDays": 90 } },
    { "ID": "ExpireUnclaimedUploads", "Status": "Enabled", "Filter": { "Prefix": "incoming/" },
      "Expiration": { "Days": 1 } },
    { "ID": "ExpireGeneratedExports", "Status": "Enabled", "Filter": { "Prefix": "exports/" },
      "Expiration": { "Days": 7 } }
  ]
}
JSON
echo "Lifecycle: incoming/ 1 day, exports/ 7 days, noncurrent versions 90 days"
s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration "file://$work/lifecycle.json"

origins_json=""
IFS=',' read -ra origin_list <<< "$ORIGINS"
for origin in "${origin_list[@]}"; do
  origin="$(echo "$origin" | xargs)"
  [ -n "$origin" ] && origins_json="${origins_json:+$origins_json,}\"$origin\""
done
[ -n "$origins_json" ] || { echo "No frontend origin given" >&2; exit 1; }
cat > "$work/cors.json" <<JSON
{
  "CORSRules": [
    { "AllowedOrigins": [$origins_json], "AllowedMethods": ["GET", "PUT", "HEAD"],
      "AllowedHeaders": ["*"], "ExposeHeaders": ["ETag"], "MaxAgeSeconds": 3000 }
  ]
}
JSON
echo "CORS: $ORIGINS"
s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration "file://$work/cors.json"

echo "Done. Next: deploy media-cloudfront.yaml in $REGION with BucketName=$BUCKET UseExistingBucket=true."
