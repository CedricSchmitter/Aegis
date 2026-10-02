import json
import re
import tempfile
import unittest
from pathlib import Path

import chat_reference_check as check

REFERENCE = json.loads(check.REFERENCE_SET.read_text(encoding="utf-8"))
QUESTIONS = {question["id"]: question for question in REFERENCE["questions"]}


def run(question, citations, answer, evidence="COMPLETE", state="SUCCEEDED", mode="LABELS_AND_TEXT"):
    return {"harness": "live-app", "mode": mode, "repetition": 1, "question": question, "state": state,
            "evidence": evidence, "answer": answer,
            "citations": [{"source": source, "check": status, "page": None} for source, status in citations],
            "latencySeconds": 20.0, "modelCalls": 3, "toolCalls": 4, "inputTokens": 100, "cacheReadTokens": 900,
            "cacheWriteTokens": 0, "outputTokens": 300, "cost": 0.04}


class ReferenceSetTest(unittest.TestCase):
    def test_every_named_source_is_a_reference_document_and_both_hard_cases_are_asked(self):
        keys = [document["key"] for document in REFERENCE["documents"]]
        self.assertEqual(len(keys), len(set(keys)))
        for question in REFERENCE["questions"]:
            for field in ("expectedSources", "acceptableSources", "distractors"):
                self.assertTrue(set(question[field]) <= set(keys), (question["id"], field))
            self.assertTrue(question["expectedEvidence"])
        expected = {key for question in REFERENCE["questions"] for key in question["expectedSources"]}
        for labeling in ("MISLABELED", "UNLABELED"):
            hard = [d["key"] for d in REFERENCE["documents"] if d["labeling"] == labeling]
            self.assertTrue(hard and set(hard) <= expected, labeling)

    def test_questions_name_fictional_entities_only(self):
        for question in REFERENCE["questions"]:
            self.assertRegex(question["question"],
                             "Nordlicht|Falkenried|MU-ST 123|Thermo Brandt|Lindqvist|Wellenreiter|Lindenhof|Funkwelle")


class ScoreTest(unittest.TestCase):
    def test_an_answer_citing_every_expected_source_with_the_facts_passes(self):
        result = check.score(run("q04-insurer-overview", [("falkenried-car-policy", "VERIFIED"),
                                                          ("falkenried-contents-policy", "VERIFIED")],
                                 "Kfz-Versicherung [1] und Hausratversicherung [2]."), QUESTIONS["q04-insurer-overview"])
        self.assertTrue(result["passed"])
        self.assertEqual(1.0, result["recall"])

    def test_a_source_cited_with_an_unverified_quote_counts_as_missed(self):
        result = check.score(run("q04-insurer-overview", [("falkenried-car-policy", "NOT_IN_SOURCE"),
                                                          ("falkenried-contents-policy", "VERIFIED")],
                                 "Kfz und Hausrat"), QUESTIONS["q04-insurer-overview"])
        self.assertFalse(result["passed"])
        self.assertEqual(["falkenried-car-policy"], result["missed"])
        self.assertEqual(0.5, result["recall"])
        self.assertEqual(1, result["unverified"])

    def test_citing_an_unrelated_household_document_fails_and_a_distractor_is_flagged(self):
        result = check.score(run("q02-current-energy-price", [("nordlicht-price-change", "VERIFIED"),
                                                              ("other-document", "VERIFIED"),
                                                              ("nordlicht-magazine", "VERIFIED")],
                                 "29,80 Cent/kWh"), QUESTIONS["q02-current-energy-price"])
        self.assertFalse(result["passed"])
        self.assertEqual(["nordlicht-magazine", "other-document"], result["unrelated"])
        self.assertTrue(result["distractorCited"])

    def test_a_question_without_sources_passes_only_when_nothing_was_found(self):
        question = QUESTIONS["q10-not-in-records"]
        self.assertTrue(check.score(run(question["id"], [], "Dazu finde ich nichts.", "NOT_FOUND"), question)["passed"])
        self.assertFalse(check.score(run(question["id"], [], "Im März.", "COMPLETE"), question)["passed"])

    def test_missing_facts_or_a_failed_run_do_not_pass(self):
        question = QUESTIONS["q03-mislabeled-car-insurance"]
        cited = [("falkenried-car-policy", "VERIFIED")]
        self.assertFalse(check.score(run(question["id"], cited, "Etwa 500 EUR."), question)["passed"])
        self.assertFalse(check.score(run(question["id"], cited, "486,20 EUR", state="FAILED"), question)["passed"])

    def test_the_report_compares_the_retrievals_per_question(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "runs.json"
            file.write_text(json.dumps({"runs": [
                run("q03-mislabeled-car-insurance", [("falkenried-car-policy", "VERIFIED")], "486,20 EUR"),
                run("q03-mislabeled-car-insurance", [], "Nicht gefunden.", "NOT_FOUND", mode="TEXT_ONLY")]}))
            text = check.report(REFERENCE, [file])
        self.assertIn("| live-app | LABELS_AND_TEXT | 1 | 1 | 1 | 1.00 | none | 0 |", text)
        self.assertIn("| live-app | TEXT_ONLY | 1 | 1 | 0 | 0.00 | falkenried-car-policy x1 | 0 |", text)
        self.assertIn("| q03-mislabeled-car-insurance | mislabeled | 1/1 | 0/1 missed falkenried-car-policy |", text)
        self.assertIn("| 1,000 |", text)


class PdfTest(unittest.TestCase):
    def test_text_keeps_umlauts_and_parentheses_and_the_cross_reference_points_at_its_table(self):
        data = check.pdf([["Kündigung (Textform) § 4 \\ Ende"], ["Seite zwei"]])
        self.assertIn("Kündigung \\(Textform\\) § 4 \\\\ Ende".encode("cp1252"), data)
        self.assertIn(b"/Count 2", data)
        offset = int(re.search(rb"startxref\n(\d+)", data).group(1))
        self.assertTrue(data[offset:].startswith(b"xref"))
        for number, position in enumerate(re.findall(rb"(\d{10}) 00000 n", data), 1):
            self.assertTrue(data[int(position):].startswith(b"%d 0 obj" % number))

    def test_long_lines_wrap_at_word_boundaries(self):
        lines = check.wrapped(["wort " * 30 + "ende"], width=40)
        self.assertTrue(all(len(line) <= 40 for line in lines))
        self.assertEqual(" ".join(lines), ("wort " * 30 + "ende").strip())


if __name__ == "__main__":
    unittest.main()
