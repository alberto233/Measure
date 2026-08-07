#!/usr/bin/env python3
"""Print every test failure message found in Gradle's JUnit XML.

Gradle names the failing test on the console and puts the reason in an HTML report,
which on CI is a file nobody can open without downloading an artifact. Reading it has
twice cost a round trip through the whole pipeline to learn one sentence. This prints
the sentence.

Deliberately reads the XML rather than relying on Gradle's `testLogging`: the XML is
written for every module whether or not that module configured logging, so a module
added later cannot silently fall out of this.
"""

import glob
import sys
import xml.etree.ElementTree as ElementTree

LIMIT = 1200


def main() -> int:
    paths = sorted(glob.glob("**/build/test-results/**/*.xml", recursive=True))
    failures = 0

    for path in paths:
        try:
            root = ElementTree.parse(path).getroot()
        except ElementTree.ParseError:
            # A half-written file from an interrupted run says nothing useful, and
            # crashing here would hide the failures in every other file.
            continue

        for case in root.iter("testcase"):
            problems = list(case.findall("failure")) + list(case.findall("error"))
            for problem in problems:
                failures += 1
                print(f"\n{case.get('classname')} > {case.get('name')}")
                message = (problem.get("message") or "").strip()
                if message:
                    print(f"  {message[:LIMIT]}")
                body = (problem.text or "").strip()
                if body:
                    # The first few frames are where the assertion actually lives; the
                    # rest is the runner's own stack and says nothing about the test.
                    for line in body.splitlines()[:12]:
                        print(f"  {line}")

    if failures == 0:
        print("(no failing test cases found in any test-results XML)")
    else:
        print(f"\n{failures} failing test case(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
