"""Compare installed-package evidence with the shared behavior contract."""
from collections import Counter
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parent.parent
contract = json.loads((root / "conformance/state-contract.json").read_text())
expected = Counter(case["output"] for case in contract)
if len(sys.argv) != 4:
    raise SystemExit("Supply the Kotlin, React Native, and Swift verification logs")
for environment, filename in zip(("Kotlin", "React Native", "Swift"), sys.argv[1:]):
    lines = Path(filename).read_text().splitlines()
    observed = Counter(line.strip() for line in lines if line.strip().startswith("PASS "))
    missing = expected - observed
    repeated = {line: observed[line] for line in expected if observed[line] > 1}
    if missing or repeated:
        raise SystemExit(f"{environment}: missing={list(missing)} repeats={repeated}")
    print(f"{environment}: {sum(expected.values())} common cases passed")
