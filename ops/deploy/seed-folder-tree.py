#!/usr/bin/env python3
"""Create or update a household's starting folder tree from tracked data (issue #72, ADR-0006).

The tree lives in `folder-tree.json`, not in this file. Seeding goes through the ordinary REST API, so it obeys
the same authorization, validation and versioning as a member working in the UI. It is idempotent: folders are
matched by normalized sibling name under the same parent, so a second run creates nothing and changes nothing.
Implied labels are given as built-in vocabulary keys and must resolve to active labels; a key that no longer
exists fails the run instead of producing a folder that never files anything automatically (ADR-0005).

Nothing is deleted by default. `--prune` removes folders the data file does not describe, deepest first, and
refuses any folder that holds documents or still has a kept subfolder. It therefore treats the data file as the
complete tree: pointing it at a partial file would propose deleting everything else. Read the plan before adding
`--apply`.

Usage (read-only plan first, then apply):

    python3 ops/deploy/seed-folder-tree.py --base-url https://192.168.2.159 \\
        --ca-cert /tmp/aegis-root.crt --credentials /srv/aegis/deploy/secrets/aegis-dev-login.json
    ... same command with --apply

Credentials are read from a JSON file with `username` and `password`, or from `--username` plus the
`AEGIS_PASSWORD` environment variable. They are never printed.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import ssl
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

SCHEMA_VERSION = 1
MAX_DEPTH = 8
MAX_NAME_LENGTH = 80
SUBJECT_KINDS = ("HOUSE", "HOUSEHOLD")


def match_key(name):
    """Normalized sibling identity as ADR-0002 defines it: NFKC, trimmed, whitespace collapsed, lower case."""
    return " ".join(unicodedata.normalize("NFKC", name).split()).casefold()


class TreeError(Exception):
    """The tracked tree data is unusable; nothing is sent to the household."""


def load_tree(path):
    """Validate the data file and return the folders in an order that lists every parent before its children."""
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise TreeError(f"Unsupported schemaVersion {document.get('schemaVersion')!r}; expected {SCHEMA_VERSION}")
    folders = []
    seen = set()
    for entry in document["folders"]:
        segments = tuple(entry["path"])
        if not segments or any(not isinstance(name, str) or not name.strip() for name in segments):
            raise TreeError(f"Folder path {list(segments)} has an empty segment")
        if len(segments) > MAX_DEPTH:
            raise TreeError(f"Folder path {' / '.join(segments)} is deeper than {MAX_DEPTH} levels")
        if any(len(name) > MAX_NAME_LENGTH for name in segments):
            raise TreeError(f"Folder path {' / '.join(segments)} has a name longer than {MAX_NAME_LENGTH}")
        key = tuple(match_key(name) for name in segments)
        if key in seen:
            raise TreeError(f"Folder path {' / '.join(segments)} collides with an earlier entry")
        if len(segments) > 1 and key[:-1] not in seen:
            raise TreeError(f"Folder path {' / '.join(segments)} appears before its parent")
        seen.add(key)
        subjects = list(entry.get("subjects", []))
        for kind in subjects:
            if kind not in SUBJECT_KINDS:
                raise TreeError(f"Folder path {' / '.join(segments)} implies unsupported subject {kind!r}; "
                                "people are subjects of a document, not of a default folder")
        folders.append({"path": segments, "labels": list(entry.get("labels", [])), "subjects": subjects})
    return folders


def resolve_labels(vocabulary, folders):
    """Map every built-in key the tree uses to an active label id, or explain which keys are unusable."""
    active = {label["builtinKey"]: label["id"] for label in vocabulary
              if label.get("builtinKey") and label.get("status") == "ACTIVE"}
    resolved, missing = {}, []
    for key in sorted({key for folder in folders for key in folder["labels"]}):
        if key in active:
            resolved[key] = active[key]
        else:
            missing.append(key)
    if missing:
        raise TreeError("These implied labels do not resolve to an active built-in label: "
                        + ", ".join(missing) + ". A folder whose implied label is missing would never file a "
                        "document automatically (ADR-0005); fix the key or the vocabulary first.")
    return resolved


def subject_set(subjects):
    """Compare implied subjects independently of order and of how a missing member id is spelled."""
    return {(subject["kind"], subject.get("memberId") or None) if isinstance(subject, dict) else (subject, None)
            for subject in subjects}


class Report:
    def __init__(self):
        self.created, self.remapped, self.pruned, self.refused = [], [], [], []
        self.unchanged = 0

    def lines(self, applied):
        verb = ("Created", "Remapped", "Deleted") if applied else ("Would create", "Would remap", "Would delete")
        for path in self.created:
            yield f"{verb[0]}: {path}"
        for path, detail in self.remapped:
            yield f"{verb[1]}: {path} -> {detail}"
        for path in self.pruned:
            yield f"{verb[2]}: {path}"
        for path, reason in self.refused:
            yield f"Kept (refused to delete): {path} - {reason}"
        yield f"Unchanged folders: {self.unchanged}"

    @property
    def changed(self):
        return bool(self.created or self.remapped or self.pruned)


def seed(catalog, folders, prune=False, apply=False):
    """Bring the household tree in line with the tracked data; without `apply` nothing is written."""
    labels = resolve_labels(catalog.labels(), folders)
    nodes = {node["id"]: dict(node) for node in catalog.categories()}
    index = {(node["parentId"], match_key(node["name"])): node["id"] for node in nodes.values()}
    report = Report()
    wanted = set()
    simulated = 0

    def register(node):
        nodes[node["id"]] = dict(node)
        index[(node["parentId"], match_key(node["name"]))] = node["id"]

    for folder in folders:
        parent_id, path = None, ""
        for name in folder["path"]:
            path = name if not path else f"{path} / {name}"
            node_id = index.get((parent_id, match_key(name)))
            if node_id is None:
                if apply:
                    node = catalog.create_category(parent_id, name)
                else:
                    simulated += 1
                    node = {"id": f"planned-{simulated}", "parentId": parent_id, "name": name,
                            "impliedLabelIds": [], "impliedSubjects": [], "documentCount": 0, "version": 0}
                register(node)
                node_id = node["id"]
                report.created.append(path)
            parent_id = node_id
        node = nodes[parent_id]
        wanted.add(parent_id)
        wanted_labels = {labels[key] for key in folder["labels"]}
        wanted_subjects = subject_set(folder["subjects"])
        if set(node["impliedLabelIds"]) != wanted_labels or subject_set(node["impliedSubjects"]) != wanted_subjects:
            detail = ", ".join(sorted(folder["labels"] + folder["subjects"])) or "nothing"
            subjects = [{"kind": kind, "memberId": None} for kind, _ in sorted(wanted_subjects)]
            if apply:
                register(catalog.set_implications(node["id"], node["version"], sorted(wanted_labels), subjects))
            else:
                node.update(impliedLabelIds=sorted(wanted_labels), impliedSubjects=subjects)
            report.remapped.append((path, f"implies {detail}"))
        else:
            report.unchanged += 1

    if prune:
        prune_extra(catalog, nodes, wanted, report, apply)
    return report


def prune_extra(catalog, nodes, wanted, report, apply):
    """Delete folders the data file does not describe, deepest first, never one that still holds something."""
    def depth(node_id):
        return 0 if nodes[node_id]["parentId"] is None else 1 + depth(nodes[node_id]["parentId"])

    def path_of(node_id):
        node = nodes[node_id]
        return node["name"] if node["parentId"] is None else f"{path_of(node['parentId'])} / {node['name']}"

    remaining = set(nodes)
    for node_id in sorted(set(nodes) - wanted, key=depth, reverse=True):
        node = nodes[node_id]
        children = [other for other in remaining if nodes[other]["parentId"] == node_id]
        if node["documentCount"]:
            report.refused.append((path_of(node_id), f"holds {node['documentCount']} document(s)"))
        elif children:
            report.refused.append((path_of(node_id), f"still has {len(children)} subfolder(s)"))
        else:
            if apply:
                catalog.delete_category(node_id, node["version"])
            remaining.discard(node_id)
            report.pruned.append(path_of(node_id))


class RestCatalog:
    """The household's catalog over the ordinary REST API, using a member session and the CSRF cookie."""

    def __init__(self, base_url, username, password, ca_cert=None):
        self.base_url = base_url.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        context = ssl.create_default_context(cafile=ca_cert) if ca_cert else None
        handlers = [urllib.request.HTTPCookieProcessor(self.jar)]
        if context is not None:
            handlers.append(urllib.request.HTTPSHandler(context=context))
        self.opener = urllib.request.build_opener(*handlers)
        # The session endpoint answers 401 while anonymous; its only job here is to hand out the CSRF cookie.
        self._request("GET", "/api/auth/session", tolerate=(401,))
        self._request("POST", "/api/auth/login", {"username": username, "password": password})

    def _request(self, method, path, payload=None, tolerate=()):
        headers = {"X-XSRF-TOKEN": cookie.value for cookie in self.jar if cookie.name == "XSRF-TOKEN"}
        data = None if payload is None else json.dumps(payload).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, headers=headers, data=data, method=method)
        try:
            with self.opener.open(request, timeout=60) as response:
                body = response.read()
        except urllib.error.HTTPError as error:
            if error.code in tolerate:
                return None
            detail = error.read().decode("utf-8", "replace")[:300]
            raise SystemExit(f"{method} {path} failed with HTTP {error.code}: {detail}") from None
        return json.loads(body) if body else None

    def labels(self):
        return self._request("GET", "/api/labels")

    def categories(self):
        return self._request("GET", "/api/categories")["categories"]

    def create_category(self, parent_id, name):
        return self._request("POST", "/api/categories", {"parentId": parent_id, "name": name})

    def set_implications(self, category_id, expected_version, label_ids, subjects):
        return self._request("PUT", f"/api/categories/{category_id}/implications",
                             {"labelIds": label_ids, "subjects": subjects, "expectedVersion": expected_version})

    def delete_category(self, category_id, expected_version):
        query = urllib.parse.urlencode({"expectedVersion": expected_version})
        return self._request("DELETE", f"/api/categories/{category_id}?{query}")


def credentials(args):
    if args.credentials:
        stored = json.loads(Path(args.credentials).read_text(encoding="utf-8"))
        return stored["username"], stored["password"]
    password = os.environ.get("AEGIS_PASSWORD")
    if not args.username or not password:
        raise SystemExit("Provide --credentials, or --username together with AEGIS_PASSWORD.")
    return args.username, password


def main(argv=None):
    parser = argparse.ArgumentParser(description="Seed a household folder tree from tracked data.")
    parser.add_argument("--base-url", required=True, help="Household endpoint, for example https://192.168.2.159")
    parser.add_argument("--tree", default=str(Path(__file__).with_name("folder-tree.json")))
    parser.add_argument("--ca-cert", help="Certificate authority file for the household's HTTPS endpoint")
    parser.add_argument("--credentials", help="JSON file with username and password")
    parser.add_argument("--username", help="Member to act as; the password comes from AEGIS_PASSWORD")
    parser.add_argument("--prune", action="store_true", help="Delete empty folders the data file does not list")
    parser.add_argument("--apply", action="store_true", help="Write the changes; without it only a plan is printed")
    args = parser.parse_args(argv)

    try:
        folders = load_tree(args.tree)
    except TreeError as error:
        raise SystemExit(f"Refusing to seed: {error}") from None
    username, password = credentials(args)
    catalog = RestCatalog(args.base_url, username, password, args.ca_cert)
    try:
        report = seed(catalog, folders, prune=args.prune, apply=args.apply)
    except TreeError as error:
        raise SystemExit(f"Refusing to seed: {error}") from None
    for line in report.lines(args.apply):
        print(line)
    if not args.apply and report.changed:
        print("Plan only; re-run with --apply to write these changes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
