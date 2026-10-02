"""Reference questions for the household assistant against a running Aegis (issue #25, ADR-0025).

Commands (run on the Aegis VM; the member logs in with a credential file that never leaves the VM):

  prepare  uploads the synthetic reference documents and files them with a temporary folder tree and organization
           labels exactly as the reference set says, including the deliberately mislabeled and unlabeled documents
  probe    runs the application's own search with labels and with words (no AI) to show what each finds
  ask      asks every reference question in a new conversation, records answer, citations and run metrics and
           deletes the conversation; --mode names the retrieval the server runs (AEGIS_AI_CHAT_RETRIEVAL)
  cleanup  moves the reference documents to the trash and deletes the temporary labels and folders
  report   scores result files of `ask` or of the in-process live test and prints a Markdown summary

Raw results contain answer texts and stay local; the report holds only reference keys and numbers.
"""
import argparse
import http.cookiejar
import json
import ssl
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

REFERENCE_SET = Path(__file__).resolve().parents[2] / "fixtures" / "assistant-eval" / "reference-set.json"
OPEN_STATES = ("WAITING", "WORKING")
SEARCH_TOOLS = ("search_documents", "search_document_text")


class Client:
    def __init__(self, base, cafile):
        context = ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()
        context.check_hostname = False
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar),
                                                  urllib.request.HTTPSHandler(context=context))

    def call(self, method, path, body=None, raw=None, content_type=None, accepted=(200, 201, 204)):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", content_type or "application/json")
        token = next((cookie.value for cookie in self.jar if cookie.name == "XSRF-TOKEN"), None)
        if token and method not in ("GET", "HEAD"):
            request.add_header("X-XSRF-TOKEN", token)
        try:
            with self.opener.open(request, timeout=60) as response:
                status, payload = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, payload = error.code, error.read()
        result = json.loads(payload) if payload else None
        if accepted and status not in accepted:
            code = result.get("code") if isinstance(result, dict) else None
            raise RuntimeError(f"{method} {path.split('?')[0]} answered {status} {code or ''}".strip())
        return status, result

    def login(self, credentials):
        secret = json.loads(Path(credentials).read_text())
        self.call("GET", "/api/auth/session", accepted=None)
        self.call("POST", "/api/auth/login", {"username": secret["username"], "password": secret["password"]})

    def logout(self):
        self.call("POST", "/api/auth/logout", accepted=None)


def pdf(pages):
    """A born-digital PDF with Helvetica text in WinAnsi encoding, so umlauts reach the text layer."""
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", None,
               "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    kids = []
    for lines in pages:
        def escaped(line):
            return line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        text = "BT /F1 10 Tf 50 800 Td 14 TL " + " ".join("(" + escaped(line) + ") '" for line in lines) + " ET"
        stream = text.encode("cp1252")
        objects.append(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        content = len(objects)
        objects.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> "
                       "/Contents %d 0 R >>" % content)
        kids.append(len(objects))
    objects[1] = "<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % kid for kid in kids), len(kids))
    out = b"%PDF-1.4\n"
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(out))
        body = body if isinstance(body, bytes) else body.encode("cp1252")
        out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += "".join("%010d 00000 n \n" % offset for offset in offsets).encode()
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
    return out


def wrapped(lines, width=88):
    out = []
    for line in lines:
        while len(line) > width:
            cut = line.rfind(" ", 0, width)
            cut = cut if cut > 0 else width
            out.append(line[:cut])
            line = line[cut:].lstrip()
        out.append(line)
    return out


def upload(client, filename, data, category_id):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/pdf\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    query = "?categoryId=" + category_id if category_id else ""
    _, result = client.call("POST", "/api/documents" + query, raw=body,
                            content_type="multipart/form-data; boundary=" + boundary)
    return result["document"]


def save(path, state):
    Path(path).write_text(json.dumps(state, indent=2))


def prepare(client, reference, state_path):
    if Path(state_path).exists():
        sys.exit(f"{state_path} exists: run cleanup first")
    run = uuid.uuid4().hex[:8]
    state = {"run": run, "folders": {}, "labels": {}, "documents": {}}
    try:
        _, categories = client.call("GET", "/api/categories")
        if any(c["name"] == reference["folderRoot"] and not c["parentId"] for c in categories["categories"]):
            raise RuntimeError(f"a top-level folder {reference['folderRoot']!r} exists already")
        _, root = client.call("POST", "/api/categories", {"name": reference["folderRoot"], "parentId": None})
        state["folders"][""] = root["id"]
        _, labels = client.call("GET", "/api/labels")
        builtin = {(label["facet"], label["builtinKey"]): label["id"] for label in labels if label["builtinKey"]}
        for document in reference["documents"]:
            parent = root["id"]
            for depth in range(len(document["folder"])):
                path = "/".join(document["folder"][:depth + 1])
                if path not in state["folders"]:
                    _, folder = client.call("POST", "/api/categories",
                                            {"name": document["folder"][depth], "parentId": parent})
                    state["folders"][path] = folder["id"]
                parent = state["folders"][path]
            label_ids = []
            for label in document["labels"]:
                if label.get("builtin"):
                    label_ids.append(builtin[(label["facet"], label["builtin"])])
                    continue
                key = label["facet"] + "/" + label["name"]
                if key not in state["labels"]:
                    status, created = client.call("POST", "/api/labels", {"facet": label["facet"],
                                                                          "name": label["name"]}, accepted=(201, 409))
                    if status == 409:
                        raise RuntimeError(f"the label {key!r} exists already; refusing to reuse household labels")
                    state["labels"][key] = created["id"]
                label_ids.append(state["labels"][key])
            pages = [wrapped(lines) for lines in document["pages"]]
            pages[-1] = pages[-1] + ["", f"Referenz {run}-{document['key']}"]
            folder_id = state["folders"]["/".join(document["folder"])] if document["folder"] else None
            stored = upload(client, document["title"] + ".pdf", pdf(pages), folder_id)
            state["documents"][document["key"]] = stored["id"]
            save(state_path, state)
            if stored["title"] != document["title"]:
                _, stored = client.call("PUT", f"/api/documents/{stored['id']}/title",
                                        {"title": document["title"], "expectedVersion": stored["version"]})
            if label_ids:
                client.call("PUT", f"/api/documents/{stored['id']}/labels",
                            {"labelIds": label_ids, "expectedVersion": stored["classification"]["version"]})
        for key, document_id in state["documents"].items():
            for _ in range(100):
                _, document = client.call("GET", "/api/documents/" + document_id)
                if document["processing"]["state"] == "ARCHIVED":
                    break
                time.sleep(3)
            else:
                raise RuntimeError(f"{key} was not archived in time")
        print(f"prepared run {run}: {len(state['documents'])} documents, {len(state['folders'])} folders, "
              f"{len(state['labels'])} labels")
    finally:
        save(state_path, state)


def probe(client, reference, state):
    """The application's search with labels and with words, without AI, for the mislabeled and unlabeled cases."""
    _, labels = client.call("GET", "/api/labels")
    builtin = {label["builtinKey"]: label["id"] for label in labels if label["builtinKey"]}
    own = {key.split("/", 1)[1]: label_id for key, label_id in state["labels"].items()}
    keys = {document_id: key for key, document_id in state["documents"].items()}
    probes = [
        ("organization Falkenried Versicherung", {"organization": own["Falkenried Versicherung"]}),
        ("words Falkenried", {"query": "Falkenried"}),
        ("type insurance policy + topic car", {"type": builtin["insurancePolicy"], "topic": builtin["car"]}),
        ("words MU-ST 123", {"query": "MU-ST 123"}),
        ("words Kfz Versicherung", {"query": "Kfz Versicherung"}),
        ("topic heating", {"topic": builtin["heating"]}),
        ("unsorted", {"category": "unsorted"}),
        ("words Thermo Brandt Wartung", {"query": "Thermo Brandt Wartung"}),
        ("words Wartung Heizung Thermo Brandt 2026", {"query": "Wartung Heizung Thermo Brandt 2026"}),
    ]
    results = []
    for name, params in probes:
        _, found = client.call("GET", "/api/documents?" + urllib.parse.urlencode(dict(params, size=100)))
        reference_hits = sorted(keys[item["id"]] for item in found["items"] if item["id"] in keys)
        results.append({"probe": name, "referenceDocuments": reference_hits,
                        "otherDocuments": found["matchingDocuments"] - len(reference_hits),
                        "contentSearch": found["contentSearch"]})
        print(f"{name:45} -> {', '.join(reference_hits) or '(none)'}"
              f" (+{found['matchingDocuments'] - len(reference_hits)} other)")
    return results


def run_metrics(run):
    tools = {}
    for step in run["steps"]:
        if step["kind"] == "TOOL_CALL":
            tools[step["name"]] = tools.get(step["name"], 0) + 1
    return {"modelCalls": run["modelCalls"], "toolCalls": run["toolCalls"], "tools": tools,
            "inputTokens": run["inputTokens"], "cacheReadTokens": run.get("cacheReadTokens"),
            "cacheWriteTokens": run.get("cacheWriteTokens"), "outputTokens": run["outputTokens"],
            "cost": run["cost"], "model": run["model"], "provider": run["provider"]}


def ask(client, reference, state, mode, repetitions, only, out):
    _, overview = client.call("GET", "/api/chat/conversations")
    if not overview["available"]:
        sys.exit(f"the assistant is unavailable: {overview.get('unavailableReason')}")
    keys = {document_id: key for key, document_id in state["documents"].items()}
    runs = []
    for repetition in range(1, repetitions + 1):
        for question in reference["questions"]:
            if only and question["id"] not in only:
                continue
            conversation, turn = str(uuid.uuid4()), str(uuid.uuid4())
            started = time.monotonic()
            client.call("POST", "/api/chat/conversations", {"conversationId": conversation, "turnId": turn,
                                                             "question": question["question"],
                                                             "locale": question["locale"]})
            try:
                while True:
                    _, body = client.call("GET", "/api/chat/conversations/" + conversation)
                    latest = body["turns"][-1]
                    if latest["state"] not in OPEN_STATES or time.monotonic() - started > 600:
                        break
                    time.sleep(1.5)
                latency = time.monotonic() - started
                _, run = client.call("GET", "/api/ai/runs/" + latest["runId"])
            finally:
                client.call("DELETE", "/api/chat/conversations/" + conversation, accepted=None)
            answer = latest.get("answer") or {}
            used = set(run_metrics(run)["tools"])
            if mode == "TEXT_ONLY" and used & {"household_catalog", "search_documents"} or \
                    mode == "LABELS_AND_TEXT" and "search_document_text" in used:
                raise RuntimeError(f"the server does not run {mode}: the run used {sorted(used)}")
            result = {"harness": "live-app", "mode": mode, "repetition": repetition, "question": question["id"],
                      "state": "SUCCEEDED" if answer else run["state"], "failure": run.get("failureCode"),
                      "evidence": answer.get("evidence"), "answer": answer.get("text"),
                      "caveats": answer.get("caveats", []),
                      "citations": [{"source": keys.get(c["sourceId"], "other-document")
                                     if c["kind"] == "DOCUMENT" else c["kind"].lower(),
                                     "check": c["check"], "page": c.get("page")} for c in answer.get("citations", [])],
                      "latencySeconds": round(latency, 1)}
            result.update(run_metrics(run))
            runs.append(result)
            print(f"{mode} #{repetition} {question['id']:34} {result['state']} {result['evidence']} "
                  f"cited={[c['source'] for c in result['citations']]} {result['latencySeconds']}s "
                  f"calls={result['modelCalls']} tools={result['tools']}", flush=True)
            Path(out).write_text(json.dumps({"harness": "live-app", "runs": runs}, indent=2))
    return runs


def cleanup(client, state_path):
    """Unfiles and unlabels the reference documents before the trash, so the temporary labels and folders end unused
    and can be deleted instead of lingering in the household's vocabulary."""
    state = json.loads(Path(state_path).read_text())
    for document_id in state["documents"].values():
        status, document = client.call("GET", "/api/documents/" + document_id, accepted=None)
        if status != 200 or document.get("trash"):
            continue
        if any(chip["source"] == "OWN" for chip in document["classification"]["labels"]):
            _, document = client.call("PUT", f"/api/documents/{document_id}/labels",
                                      {"labelIds": [], "expectedVersion": document["classification"]["version"]})
        if document["classification"]["categoryId"]:
            _, document = client.call("PUT", f"/api/documents/{document_id}/category",
                                      {"categoryId": None, "expectedVersion": document["classification"]["version"]})
        client.call("POST", f"/api/documents/{document_id}/trash", {"expectedVersion": document["version"]})
    _, labels = client.call("GET", "/api/labels")
    versions = {label["id"]: label["version"] for label in labels}
    for key, label_id in state["labels"].items():
        if label_id in versions:
            client.call("DELETE", f"/api/labels/{label_id}?expectedVersion={versions[label_id]}")
    for path in sorted(state["folders"], key=lambda p: -len(p.split("/")) if p else 0):
        _, categories = client.call("GET", "/api/categories")
        versions = {category["id"]: category["version"] for category in categories["categories"]}
        folder_id = state["folders"][path]
        if folder_id in versions:
            client.call("DELETE", f"/api/categories/{folder_id}?expectedVersion={versions[folder_id]}")
    Path(state_path).unlink()
    print(f"cleaned up run {state['run']}")


# Scoring and report


def score(run, question):
    verified = [c["source"] for c in run["citations"] if c["check"] == "VERIFIED"]
    cited = [c["source"] for c in run["citations"]]
    expected = question["expectedSources"]
    allowed = set(expected) | set(question["acceptableSources"])
    answer = (run.get("answer") or "").lower()
    facts = all(any(alternative.lower() in answer for alternative in group) for group in question["expectedFacts"])
    missed = [key for key in expected if key not in verified]
    unrelated = sorted({source for source in cited
                        if source not in allowed and source not in ("contract", "task")})
    evidence = run.get("evidence") in question["expectedEvidence"]
    return {"answered": run["state"] == "SUCCEEDED", "recall": 1.0 if not expected
            else (len(expected) - len(missed)) / len(expected), "missed": missed, "unrelated": unrelated,
            "distractorCited": any(source in question["distractors"] for source in cited),
            "unverified": sum(1 for c in run["citations"] if c["check"] != "VERIFIED"), "facts": facts,
            "evidence": evidence,
            "passed": run["state"] == "SUCCEEDED" and not missed and facts and evidence and not unrelated}


def total_input(run):
    return run["inputTokens"] + (run.get("cacheReadTokens") or 0) + (run.get("cacheWriteTokens") or 0)


def mean(values):
    values = [value for value in values if value is not None]
    return statistics.fmean(values) if values else None


def number(value, digits=1):
    return "-" if value is None else f"{value:,.{digits}f}"


def report(reference, files):
    questions = {question["id"]: question for question in reference["questions"]}
    groups = {}
    for file in files:
        data = json.loads(Path(file).read_text())
        for run in data["runs"]:
            groups.setdefault((run["harness"], run["mode"]), []).append(run)
    lines = ["| Harness | Retrieval | Runs | Answered | Passed | Source recall | Missed sources | Unrelated citations "
             "| Latency median s | Model calls | Tool calls | Input tokens | Input chars | Output tokens | Cost USD |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    details = {}
    for (harness, mode), runs in sorted(groups.items()):
        scores = [score(run, questions[run["question"]]) for run in runs]
        missed = {}
        for run, result in zip(runs, scores):
            for key in result["missed"]:
                missed[key] = missed.get(key, 0) + 1
            details.setdefault(run["question"], {}).setdefault((harness, mode), []).append(result)
        lines.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
            harness, mode, len(runs), sum(s["answered"] for s in scores), sum(s["passed"] for s in scores),
            number(mean([s["recall"] for s in scores]), 2),
            ", ".join(f"{key} x{count}" for key, count in sorted(missed.items())) or "none",
            sum(len(s["unrelated"]) for s in scores),
            number(statistics.median(run["latencySeconds"] for run in runs)),
            number(mean([run["modelCalls"] for run in runs])), number(mean([run["toolCalls"] for run in runs])),
            number(mean([total_input(run) for run in runs]), 0), number(mean([run.get("inputCharacters")
                                                                             for run in runs]), 0),
            number(mean([run["outputTokens"] for run in runs]), 0), number(mean([float(run["cost"])
                                                                                for run in runs]), 3)))
    lines += ["", "| Question | Purpose | " + " | ".join(f"{h} {m}" for h, m in sorted(groups)) + " |",
              "|---|---|" + "---|" * len(groups)]
    for question_id, by_group in details.items():
        cells = []
        for group in sorted(groups):
            results = by_group.get(group, [])
            missed = sorted({key for result in results for key in result["missed"]})
            cells.append(f"{sum(r['passed'] for r in results)}/{len(results)}"
                         + (f" missed {', '.join(missed)}" if missed else ""))
        lines.append(f"| {question_id} | {', '.join(questions[question_id]['purpose'])} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("prepare", "probe", "ask", "cleanup", "report"))
    parser.add_argument("files", nargs="*", help="result files for report")
    parser.add_argument("--base", default="https://192.168.2.159")
    parser.add_argument("--cafile", default="/tmp/aegis-ca.crt")
    parser.add_argument("--credentials", default="/srv/aegis/deploy/secrets/aegis-dev-login.json")
    parser.add_argument("--reference-set", default=str(REFERENCE_SET))
    parser.add_argument("--state", default="assistant-eval-state.json")
    parser.add_argument("--mode", choices=("LABELS_AND_TEXT", "TEXT_ONLY"))
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--questions", default="")
    parser.add_argument("--out", default="assistant-eval-runs.json")
    args = parser.parse_args()
    reference = json.loads(Path(args.reference_set).read_text(encoding="utf-8"))
    if args.command == "report":
        print(report(reference, args.files))
        return
    client = Client(args.base, args.cafile)
    client.login(args.credentials)
    try:
        if args.command == "prepare":
            prepare(client, reference, args.state)
        elif args.command == "probe":
            print(json.dumps(probe(client, reference, json.loads(Path(args.state).read_text())), indent=2))
        elif args.command == "ask":
            if not args.mode:
                sys.exit("--mode is required")
            ask(client, reference, json.loads(Path(args.state).read_text()), args.mode, args.repetitions,
                {q for q in args.questions.split(",") if q}, args.out)
        elif args.command == "cleanup":
            cleanup(client, args.state)
    finally:
        client.logout()


if __name__ == "__main__":
    main()
