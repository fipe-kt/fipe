"""Run the contract and React checks against an installed npm tarball."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

here = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--pack-directory", type=Path, default=here / "artifacts")
args = parser.parse_args()
destination = args.pack_directory.resolve()
destination.mkdir(parents=True, exist_ok=True)


def run(*command, cwd=here, **options):
    return subprocess.run(command, cwd=cwd, check=True, text=True, **options)


run("npm", "run", "build")
packed = json.loads(
    run("npm", "pack", "--json", "--ignore-scripts", "--pack-destination", destination,
        capture_output=True).stdout
)[0]
tarball = destination / packed["filename"]
with tempfile.TemporaryDirectory(prefix="fipe-state-consumer-") as directory:
    consumer = Path(directory)
    (consumer / "package.json").write_text('{"name":"fipe-state-consumer","private":true}')
    run("npm", "install", "--ignore-scripts", "--offline", "--legacy-peer-deps", "--no-fund",
        "--no-audit", tarball, cwd=consumer)
    for name in ("react", "react-test-renderer"):
        (consumer / "node_modules" / name).symlink_to(here / "node_modules" / name, target_is_directory=True)

    shutil.copyfile(here / "tests/contract.ts", consumer / "contract.ts")
    shutil.copyfile(here / "tests/react.cjs", consumer / "react.cjs")
    run("node", here / "node_modules/typescript/bin/tsc", "--strict", "--noUnusedLocals",
        "--noUnusedParameters", "--noImplicitReturns", "--target", "ES2020", "--module",
        "Node16", "--lib", "ES2020,DOM", "contract.ts", cwd=consumer)
    run("node", "-e", """
const { verify } = require("./contract.js");
const timeout = setTimeout(() => { throw Error("Workflow checks timed out"); }, 10000);
verify(console.log).catch(error => {
    console.error(error);
    process.exitCode = 1;
}).finally(() => clearTimeout(timeout));
""", cwd=consumer, timeout=15)
    run("node", "react.cjs", cwd=consumer, timeout=15)

print(f"Verified installed package {tarball}")
