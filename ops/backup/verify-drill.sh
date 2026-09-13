#!/bin/sh
# Verifies a restored Aegis instance against the export it was restored from:
# every exported document exists with the same id, becomes ARCHIVED (new Paperless id assigned by the isolated
# archive), its original matches the exported checksum, and every task keeps its source document link.
# Usage: verify-drill.sh <base-url> <members-file> <export-generation-dir>
set -eu
BASE="$1"
MEMBERS_FILE="$2"
SOURCE="$3"
JAR="$(mktemp -t aegis-cookies-XXXXXX)"
trap 'rm -f "$JAR"' EXIT

USERNAME="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['members'][0]['username'])" "$MEMBERS_FILE")"
PASSWORD="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['members'][0]['initialPassword'])" "$MEMBERS_FILE")"

curl -fs -c "$JAR" -b "$JAR" "$BASE/api/auth/session" >/dev/null || true
XSRF="$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")"
curl -fs -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" "$BASE/api/auth/login" >/dev/null

python3 - "$BASE" "$JAR" "$SOURCE" <<'EOF'
import hashlib, json, subprocess, sys, time
base, jar, source = sys.argv[1:4]

def get(path, raw=False):
    out = subprocess.run(["curl", "-fs", "-b", jar, base + path], capture_output=True, check=True).stdout
    return out if raw else json.loads(out)

documents = json.load(open(f"{source}/data/documents.json"))["documents"]
tasks = json.load(open(f"{source}/data/tasks.json"))["tasks"]
manifest = json.load(open(f"{source}/manifest.json"))
problems = []
for d in documents:
    if d.get("originalPath") is None:
        continue
    for attempt in range(120):
        current = get(f"/api/documents/{d['id']}")
        if current["processing"]["state"] == "ARCHIVED":
            break
        time.sleep(5)
    if current["processing"]["state"] != "ARCHIVED":
        problems.append(f"document {d['id']} not archived: {current['processing']}")
        continue
    if current["processing"]["paperlessDocumentId"] == d.get("paperlessDocumentId"):
        print(f"note: document {d['id']} kept the same external id {d.get('paperlessDocumentId')} (fresh archive)")
    original = get(f"/api/documents/{d['id']}/original", raw=True)
    if hashlib.sha256(original).hexdigest() != d["checksumSha256"]:
        problems.append(f"document {d['id']} original checksum mismatch")
for t in tasks:
    current = get(f"/api/tasks/{t['id']}")
    if t.get("sourceDocumentId") and (current.get("source") or {}).get("documentId") != t["sourceDocumentId"]:
        problems.append(f"task {t['id']} lost its source link")
    if current["status"] != t["status"] or current.get("dueDate") != t.get("dueDate"):
        problems.append(f"task {t['id']} state differs")
print(f"verified {len(documents)} documents and {len(tasks)} tasks from generation {manifest['generation']}")
if problems:
    print("\n".join(problems))
    sys.exit(1)
EOF
