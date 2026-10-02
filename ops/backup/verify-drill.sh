#!/bin/sh
# Verifies a restored Aegis instance against the export it was restored from:
# every exported document exists with the same id, becomes ARCHIVED (new Paperless id assigned by the isolated
# archive), its original matches the exported checksum and media type (a photo stays a photo), every task keeps
# its source document link, every exported folder placement and label comes back on the document, and a document
# created by the page editor keeps where its pages came from (a source that did not come back is shown as deleted).
# Export schema 1 files carry no classification; files before schema 11 carry no page ranges.
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
from datetime import datetime
base, jar, source = sys.argv[1:4]

def get(path, raw=False):
    out = subprocess.run(["curl", "-fs", "-b", jar, base + path], capture_output=True, check=True).stdout
    return out if raw else json.loads(out)

documents = json.load(open(f"{source}/data/documents.json"))["documents"]
tasks = json.load(open(f"{source}/data/tasks.json"))["tasks"]
manifest = json.load(open(f"{source}/manifest.json"))
problems = []
types = {}
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
    if current.get("contentType") != d.get("contentType"):
        problems.append(f"document {d['id']} came back as {current.get('contentType')}, exported as "
                        f"{d.get('contentType')}")
    types[d.get("contentType")] = types.get(d.get("contentType"), 0) + 1
classified = 0
for d in documents:
    exported = d.get("classification") or {}
    if not exported.get("folderId") and not exported.get("labels"):
        continue
    classified += 1
    restored = get(f"/api/documents/{d['id']}").get("classification") or {}
    if exported.get("folderId") and restored.get("categoryId") != exported["folderId"]:
        problems.append(f"document {d['id']} lost its folder")
    own = {c["labelId"] for c in restored.get("labels", []) if c["source"] == "OWN"}
    for a in exported.get("labels", []):
        if a["reviewState"] in ("CONFIRMED", "APPLIED") and a["labelId"] not in own:
            problems.append(f"document {d['id']} lost label {a['labelId']}")
def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))

restored_ids = {d["id"] for d in documents if d.get("originalPath") is not None}
derived = 0
for d in documents:
    exported = d.get("derivedFrom")
    if not exported or d["id"] not in restored_ids:
        continue
    derived += 1
    origin = get(f"/api/documents/{d['id']}/derivations").get("origin")
    if origin is None:
        problems.append(f"document {d['id']} lost where its pages came from")
        continue
    if origin["pageEditId"] != exported["pageEditId"] or instant(origin["createdAt"]) != instant(
            exported["createdAt"]):
        problems.append(f"document {d['id']} page edit or creation time differs")
    expected = []
    for r in exported["ranges"]:
        source = r.get("sourceDocumentId")
        kept = source if source in restored_ids else None
        expected.append((r["firstPage"], r["lastPage"], r["sourceKind"], kept, r["firstSourcePage"],
                         r["lastSourcePage"], r["rotation"],
                         None if r["sourceKind"] == "SCAN" else ("DELETED" if kept is None else "present")))
    actual = [(r["firstPage"], r["lastPage"], r["sourceKind"], r.get("sourceDocumentId"), r["firstSourcePage"],
               r["lastSourcePage"], r["rotation"],
               r.get("sourceState") if r.get("sourceState") in (None, "DELETED") else "present")
              for r in origin["ranges"]]
    if actual != expected:
        problems.append(f"document {d['id']} page ranges differ: {actual} != {expected}")
for t in tasks:
    current = get(f"/api/tasks/{t['id']}")
    if t.get("sourceDocumentId") and (current.get("source") or {}).get("documentId") != t["sourceDocumentId"]:
        problems.append(f"task {t['id']} lost its source link")
    if current["status"] != t["status"] or current.get("dueDate") != t.get("dueDate"):
        problems.append(f"task {t['id']} state differs")
print(f"verified {len(documents)} documents ({classified} with a folder or labels, {derived} created by the "
      f"page editor) and {len(tasks)} tasks "
      f"from generation {manifest['generation']} (schema {manifest['schemaVersion']}); media types {types}")
if problems:
    print("\n".join(problems))
    sys.exit(1)
EOF
