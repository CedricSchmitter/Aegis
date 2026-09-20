"""Folder tree seeding tests. Everything runs against an in-memory catalog; no household is contacted."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("seed_folder_tree", Path(__file__).with_name("seed-folder-tree.py"))
seeder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seeder)

TREE_FILE = Path(__file__).with_name("folder-tree.json")
MIGRATION = (Path(__file__).resolve().parents[2] / "backend/documents/adapters/src/main/resources/db/migration"
             / "documents/V2_2__document_catalog.sql")


def builtin_label_keys():
    """The product vocabulary a fresh installation ships, read from the migration that seeds it."""
    body = MIGRATION.read_text(encoding="utf-8").split("INSERT INTO builtin_label_seed VALUES", 1)[1].split(";", 1)[0]
    return set(re.findall(r"'[0-9a-f-]{36}',\s*'[A-Z_]+',\s*'([A-Za-z]+)'", body))


class FakeCatalog:
    """Enough of the catalog API to exercise the script: stable ids, versions and sibling uniqueness."""

    def __init__(self, labels, categories=()):
        self._labels = [dict(label) for label in labels]
        self._categories = {node["id"]: dict(node) for node in categories}
        self._next = 0
        self.writes = 0

    def labels(self):
        return copy.deepcopy(self._labels)

    def categories(self):
        return copy.deepcopy(list(self._categories.values()))

    def create_category(self, parent_id, name):
        self.writes += 1
        self._next += 1
        node = {"id": f"cat-{self._next}", "parentId": parent_id, "name": name, "impliedLabelIds": [],
                "impliedSubjects": [], "documentCount": 0, "version": 0}
        self._categories[node["id"]] = node
        return dict(node)

    def set_implications(self, category_id, expected_version, label_ids, subjects):
        self.writes += 1
        node = self._categories[category_id]
        assert node["version"] == expected_version, "stale version"
        node.update(impliedLabelIds=list(label_ids), impliedSubjects=list(subjects), version=node["version"] + 1)
        return dict(node)

    def delete_category(self, category_id, expected_version):
        self.writes += 1
        node = self._categories.pop(category_id)
        assert node["version"] == expected_version, "stale version"
        return {"deletedId": category_id}


def catalog_for(folders, categories=()):
    keys = sorted({key for folder in folders for key in folder["labels"]})
    labels = [{"id": f"label-{key}", "facet": "TOPIC", "builtinKey": key, "status": "ACTIVE"} for key in keys]
    return FakeCatalog(labels, categories)


class TrackedTreeTest(unittest.TestCase):
    def test_implied_labels_resolve_to_shipped_builtin_keys(self):
        """A renamed built-in key must fail here, not silently produce a folder that never files anything."""
        shipped = builtin_label_keys()
        self.assertIn("broadcastingFee", shipped, "migration parsing broke; the check below would be vacuous")
        used = {key for folder in seeder.load_tree(TREE_FILE) for key in folder["labels"]}
        self.assertTrue(used, "the tracked tree implies no labels at all")
        self.assertEqual(used - shipped, set())

    def test_tracked_tree_matches_the_household_skeleton(self):
        folders = seeder.load_tree(TREE_FILE)
        self.assertEqual(len(folders), 33)
        paths = {" / ".join(folder["path"]) for folder in folders}
        self.assertIn("Wohnen / Energie / Strom", paths)
        self.assertEqual(max(len(folder["path"]) for folder in folders), 3)

    def test_grouping_folders_imply_nothing_so_they_never_gate_their_children(self):
        implied = {" / ".join(f["path"]): f["labels"] for f in seeder.load_tree(TREE_FILE)}
        for grouping in ("Wohnen", "Wohnen / Energie", "Finanzen", "Arbeit & Bildung", "Persönliche Dokumente"):
            self.assertEqual(implied[grouping], [], grouping)
        self.assertEqual(implied["Versicherungen"], ["insurance"])


class TreeValidationTest(unittest.TestCase):
    def write(self, document):
        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        with handle as out:
            json.dump(document, out)
        self.addCleanup(Path(handle.name).unlink)
        return handle.name

    def test_rejects_unsupported_schema_child_before_parent_and_sibling_collision(self):
        cases = {
            "schemaVersion": {"schemaVersion": 2, "folders": []},
            "before its parent": {"schemaVersion": 1, "folders": [{"path": ["A", "B"]}]},
            "collides": {"schemaVersion": 1, "folders": [{"path": ["A"]}, {"path": [" a "]}]},
            "empty segment": {"schemaVersion": 1, "folders": [{"path": ["  "]}]},
            "unsupported subject": {"schemaVersion": 1, "folders": [{"path": ["A"], "subjects": ["MEMBER"]}]},
        }
        for expected, document in cases.items():
            with self.assertRaises(seeder.TreeError) as raised:
                seeder.load_tree(self.write(document))
            self.assertIn(expected, str(raised.exception))

    def test_missing_builtin_key_fails_before_anything_is_written(self):
        folders = [{"path": ("Wohnen",), "labels": ["renamedAway"], "subjects": []}]
        catalog = FakeCatalog([{"id": "l1", "builtinKey": "housing", "status": "ACTIVE"}])
        with self.assertRaises(seeder.TreeError) as raised:
            seeder.seed(catalog, folders, apply=True)
        self.assertIn("renamedAway", str(raised.exception))
        self.assertEqual(catalog.writes, 0)

    def test_retired_builtin_label_is_not_accepted(self):
        folders = [{"path": ("Wohnen",), "labels": ["housing"], "subjects": []}]
        catalog = FakeCatalog([{"id": "l1", "builtinKey": "housing", "status": "RETIRED"}])
        with self.assertRaises(seeder.TreeError):
            seeder.seed(catalog, folders, apply=True)


class SeedingTest(unittest.TestCase):
    def setUp(self):
        self.folders = seeder.load_tree(TREE_FILE)

    def test_seeding_an_empty_household_creates_the_tree_and_sets_implications(self):
        catalog = catalog_for(self.folders)
        report = seeder.seed(catalog, self.folders, apply=True)
        self.assertEqual(len(report.created), 33)
        self.assertEqual(len(catalog.categories()), 33)
        by_path = {}
        nodes = {node["id"]: node for node in catalog.categories()}
        for node in nodes.values():
            path, walk = [], node
            while walk is not None:
                path.insert(0, walk["name"])
                walk = nodes.get(walk["parentId"])
            by_path[" / ".join(path)] = node
        self.assertEqual(by_path["Wohnen / Energie / Strom"]["impliedLabelIds"], ["label-electricity"])
        self.assertEqual(by_path["Wohnen"]["impliedSubjects"], [{"kind": "HOUSE", "memberId": None}])
        self.assertEqual(by_path["Wohnen"]["impliedLabelIds"], [])

    def test_second_run_changes_nothing(self):
        catalog = catalog_for(self.folders)
        seeder.seed(catalog, self.folders, apply=True)
        catalog.writes = 0
        report = seeder.seed(catalog, self.folders, apply=True)
        self.assertEqual((report.created, report.remapped, report.unchanged), ([], [], 33))
        self.assertEqual(catalog.writes, 0)

    def test_plan_without_apply_writes_nothing_but_reports_the_whole_tree(self):
        catalog = catalog_for(self.folders)
        report = seeder.seed(catalog, self.folders, apply=False)
        self.assertEqual(len(report.created), 33)
        self.assertEqual(catalog.writes, 0)
        self.assertEqual(catalog.categories(), [])

    def test_existing_folder_is_reused_across_case_whitespace_and_unicode_form(self):
        """Never create a sibling that differs from an existing one only by case, whitespace or form (ADR-0005)."""
        existing = [{"id": "old-wohnen", "parentId": None, "name": "wohnen", "impliedLabelIds": [],
                     "impliedSubjects": [], "documentCount": 4, "version": 7},
                    {"id": "old-energie", "parentId": "old-wohnen", "name": "  Energie ", "impliedLabelIds": [],
                     "impliedSubjects": [], "documentCount": 0, "version": 1},
                    {"id": "old-personal", "parentId": None, "name": "Persönliche Dokumente",
                     "impliedLabelIds": [], "impliedSubjects": [], "documentCount": 2, "version": 1}]
        catalog = catalog_for(self.folders, existing)
        report = seeder.seed(catalog, self.folders, apply=True)
        self.assertEqual(len(report.created), 30)
        self.assertNotIn("Wohnen", report.created)
        self.assertNotIn("Persönliche Dokumente", report.created)
        nodes = {node["id"]: node for node in catalog.categories()}
        self.assertEqual(len(nodes), 33)
        self.assertLessEqual({"old-wohnen", "old-energie", "old-personal"}, set(nodes))
        self.assertEqual(nodes["old-wohnen"]["impliedSubjects"], [{"kind": "HOUSE", "memberId": None}])
        self.assertEqual(nodes["old-wohnen"]["name"], "wohnen", "an existing folder keeps the household's spelling")
        strom = [node for node in nodes.values() if node["name"] == "Strom"]
        self.assertEqual([node["parentId"] for node in strom], ["old-energie"], "children hang under the reuse")

    def test_implications_are_corrected_when_they_drifted(self):
        existing = [{"id": "old", "parentId": None, "name": "Gesundheit", "impliedLabelIds": ["label-taxes"],
                     "impliedSubjects": [], "documentCount": 0, "version": 3}]
        catalog = catalog_for(self.folders, existing)
        seeder.seed(catalog, self.folders, apply=True)
        node = next(node for node in catalog.categories() if node["id"] == "old")
        self.assertEqual(node["impliedLabelIds"], ["label-health"])


class PruneTest(unittest.TestCase):
    def setUp(self):
        self.folders = [{"path": ("Wohnen",), "labels": [], "subjects": []}]

    def build(self, extra):
        catalog = catalog_for(self.folders, extra)
        return catalog, seeder.seed(catalog, self.folders, prune=True, apply=True)

    def test_without_prune_an_unlisted_folder_is_left_alone(self):
        extra = [{"id": "x", "parentId": None, "name": "Test", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 0, "version": 0}]
        catalog = catalog_for(self.folders, extra)
        report = seeder.seed(catalog, self.folders, apply=True)
        self.assertEqual(report.pruned, [])
        self.assertIn("x", {node["id"] for node in catalog.categories()})

    def test_prune_removes_only_the_empty_unlisted_folder(self):
        extra = [{"id": "x", "parentId": None, "name": "Test", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 0, "version": 0}]
        catalog, report = self.build(extra)
        self.assertEqual(report.pruned, ["Test"])
        self.assertEqual({node["name"] for node in catalog.categories()}, {"Wohnen"})

    def test_prune_refuses_a_folder_that_holds_documents(self):
        extra = [{"id": "x", "parentId": None, "name": "Test", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 2, "version": 0}]
        catalog, report = self.build(extra)
        self.assertEqual(report.pruned, [])
        self.assertEqual(report.refused, [("Test", "holds 2 document(s)")])
        self.assertIn("x", {node["id"] for node in catalog.categories()})

    def test_prune_refuses_a_parent_whose_subfolder_was_kept(self):
        extra = [{"id": "x", "parentId": None, "name": "Alt", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 0, "version": 0},
                 {"id": "y", "parentId": "x", "name": "Rechnungen", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 3, "version": 0}]
        catalog, report = self.build(extra)
        self.assertEqual(report.pruned, [])
        self.assertEqual(sorted(report.refused), [("Alt", "still has 1 subfolder(s)"),
                                                  ("Alt / Rechnungen", "holds 3 document(s)")])
        self.assertEqual({node["id"] for node in catalog.categories()} & {"x", "y"}, {"x", "y"})

    def test_prune_removes_an_empty_branch_from_the_leaf_up(self):
        extra = [{"id": "x", "parentId": None, "name": "Alt", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 0, "version": 0},
                 {"id": "y", "parentId": "x", "name": "Leer", "impliedLabelIds": [], "impliedSubjects": [],
                  "documentCount": 0, "version": 0}]
        catalog, report = self.build(extra)
        self.assertEqual(report.pruned, ["Alt / Leer", "Alt"])
        self.assertEqual({node["name"] for node in catalog.categories()}, {"Wohnen"})


if __name__ == "__main__":
    unittest.main()
