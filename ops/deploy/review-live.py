"""LAN review; optional --export publishes a fresh generation. Never prints credentials or document content."""
import hashlib
import http.cookiejar
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost"
seed = Path("/srv/aegis/deploy/secrets/initial-members.json")
members = json.loads(seed.read_text())["members"]


def client():
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def request(path, payload=None):
        headers = {"Content-Type": "application/json"}
        headers.update({"X-XSRF-TOKEN": cookie.value for cookie in jar if cookie.name == "XSRF-TOKEN"})
        req = urllib.request.Request(base + path, headers=headers,
                                     data=None if payload is None else json.dumps(payload).encode())
        try:
            with opener.open(req, timeout=30) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()
    return request


anonymous = client()
for endpoint in ("/api/documents", "/api/tasks", "/api/exports", "/api/status", "/api/members"):
    assert anonymous(endpoint)[0] == 401, endpoint
print("Anonymous document/task/export/status/member access: rejected")
collections = []
for member in members:
    request = client()
    request("/api/auth/session")
    status, _ = request("/api/auth/login", {"username": member["username"], "password": member["initialPassword"]})
    assert status == 200, "Initial credential no longer valid; use an explicit secure credential source"
    status, body = request("/api/documents")
    assert status == 200
    documents = json.loads(body)["items"]
    collections.append({item["id"] for item in documents})
    for document in documents:
        status, original = request("/api/documents/" + document["id"] + "/original")
        assert status == 200 and hashlib.sha256(original).hexdigest() == document["checksumSha256"]
    status, tasks = request("/api/tasks?includeDone=true")
    assert status == 200
    malformed, _ = request("/api/documents/not-a-uuid")
    print(f"{member['username']}: login OK; {len(documents)} originals checksum verified; "
          f"{len(json.loads(tasks))} tasks; malformed UUID HTTP {malformed}")
    if "--export" in sys.argv and member == members[-1]:
        status, body = request("/api/exports", {})
        assert status == 201
        generation = json.loads(body)
        assert not generation["missingOriginals"]
        print("Published export generation: " + generation["generation"])
    status, _ = request("/api/auth/logout", {})
    assert status == 204 and request("/api/documents")[0] == 401
assert collections[0] == collections[1]
print("Shared collection identical; logout rejects further access")
