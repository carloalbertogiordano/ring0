#!/usr/bin/env bash
# One-time setup of release signing for Ring0. Run it yourself: the signing key is created here, goes straight into
# encrypted GitHub secrets and into an encrypted backup, and is never printed or kept in plain text.
#
#   ./scripts/setup-release-signing.sh [backup-file]      (default backup: ~/ring0-release-signing-backup.tar.gpg)
#
# Needs: gh (logged in, admin rights on the repo), keytool (JDK), openssl, gpg, tar, base64.
set -euo pipefail
umask 077

BACKUP="${1:-$HOME/ring0-release-signing-backup.tar.gpg}"
ENV_NAME="release"
ALIAS="ring0"

for tool in gh keytool openssl gpg tar base64; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "gh is not logged in (gh auth login)" >&2; exit 1; }
[ ! -e "$BACKUP" ] || { echo "refusing to overwrite $BACKUP" >&2; exit 1; }

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
OWNER_ID="$(gh api user -q .id)"
echo "Repository: $REPO"
echo "This creates a new APK signing key. Whoever has it can publish updates that your installed app accepts."
read -r -p "Continue? [y/N] " answer
[ "$answer" = "y" ] || { echo "aborted"; exit 1; }

# the work directory lives in RAM and is wiped on exit
WORK="$(mktemp -d /dev/shm/ring0-signing.XXXXXX)"
cleanup() { find "$WORK" -type f -exec shred -u {} + 2>/dev/null || true; rm -rf "$WORK"; unset STORE_PASS BACKUP_PASS BACKUP_PASS2; }
trap cleanup EXIT

STORE_PASS="$(openssl rand -base64 36 | tr -d '\n=+/')"
export STORE_PASS

keytool -genkeypair -keystore "$WORK/release.p12" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Ring0, O=Ring0 project" \
  -storepass:env STORE_PASS -keypass:env STORE_PASS >/dev/null
echo "Signing key created (RSA 4096, PKCS12, 30 years)."

# 1. encrypted offline backup: losing this key means no more updates for installed apps
read -r -s -p "Passphrase for the backup file: " BACKUP_PASS; echo
read -r -s -p "Repeat it: " BACKUP_PASS2; echo
[ "$BACKUP_PASS" = "$BACKUP_PASS2" ] && [ "${#BACKUP_PASS}" -ge 12 ] || { echo "passphrases differ or shorter than 12 characters" >&2; exit 1; }
printf 'alias=%s\nstore_and_key_password=%s\nrepository=%s\n' "$ALIAS" "$STORE_PASS" "$REPO" > "$WORK/credentials.txt"
tar -C "$WORK" -cf "$WORK/backup.tar" release.p12 credentials.txt
printf '%s' "$BACKUP_PASS" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 \
  --symmetric --cipher-algo AES256 --s2k-mode 3 --s2k-digest-algo SHA512 --s2k-count 65011712 \
  -o "$BACKUP" "$WORK/backup.tar"
echo "Encrypted backup written to $BACKUP (copy it to offline storage and keep the passphrase in your password manager)."

# 2. protected GitHub environment: you must approve every release, and only v* tags may use it
if gh api -X PUT "repos/$REPO/environments/$ENV_NAME" \
     -F "reviewers[][type]=User" -F "reviewers[][id]=$OWNER_ID" \
     -F "deployment_branch_policy[protected_branches]=false" -F "deployment_branch_policy[custom_branch_policies]=true" >/dev/null 2>&1; then
  gh api -X POST "repos/$REPO/environments/$ENV_NAME/deployment-branch-policies" -f name='v*' -f type=tag >/dev/null 2>&1 || true
  echo "Environment '$ENV_NAME' created: required reviewer = you, allowed only for tags v*."
else
  echo "WARNING: could not create the protected environment (private repositories need a paid GitHub plan for protection rules)." >&2
  echo "The secrets will still be stored in the '$ENV_NAME' environment if it exists, otherwise as repository secrets." >&2
  gh api -X PUT "repos/$REPO/environments/$ENV_NAME" >/dev/null 2>&1 || true
fi

# 3. secrets, passed through stdin so they never appear in a process list or a log
set_secret() {
  if gh secret set "$1" --env "$ENV_NAME" --repo "$REPO" >/dev/null 2>&1; then return 0; fi
  echo "falling back to a repository secret for $1" >&2
  gh secret set "$1" --repo "$REPO" >/dev/null
}
base64 -w0 "$WORK/release.p12" | set_secret KEYSTORE_BASE64
printf '%s' "$STORE_PASS" | set_secret KEYSTORE_PASSWORD
printf '%s' "$STORE_PASS" | set_secret KEY_PASSWORD
printf '%s' "$ALIAS" | set_secret KEY_ALIAS
echo "GitHub secrets stored: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS."

echo
echo "Done. To release: git tag v0.2.0 && git push origin v0.2.0, then approve the 'release' environment on GitHub."
echo "Certificate fingerprint (publish it so users can verify the APK):"
keytool -list -v -keystore "$WORK/release.p12" -alias "$ALIAS" -storepass:env STORE_PASS | grep -E 'SHA256:' | head -1
