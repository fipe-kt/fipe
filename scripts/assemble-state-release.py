"""Assemble the Fipe State beta from verified installable artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

VERSION = "1.0.0-beta.2"
ROOT = Path(__file__).resolve().parent.parent


def verify_tarball(path):
    with tarfile.open(path) as archive:
        package = json.load(archive.extractfile("package/package.json"))
        if (package["name"], package["version"]) != ("fipe-state", VERSION):
            raise ValueError("npm package coordinate mismatch")
        if not any(name.endswith(".d.ts") for name in archive.getnames()):
            raise ValueError("npm archive is missing TypeScript declarations")


def write_repository(maven, path):
    with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
        for source in sorted(maven.rglob("*")):
            if source.is_file():
                entry = ZipInfo(source.relative_to(maven).as_posix())
                entry.external_attr = 0o644 << 16
                archive.writestr(entry, source.read_bytes(), compress_type=ZIP_DEFLATED)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--maven", type=Path, required=True)
    parser.add_argument("--npm", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--ref", default="HEAD")
    args = parser.parse_args()

    verify_tarball(args.npm)
    modules = list(args.maven.glob(f"io/github/fipe-kt/fipe-state/{VERSION}/*.pom"))
    if len(modules) != 1:
        raise ValueError("Maven repository is missing the expected Fipe State version")
    pom = ET.parse(modules[0]).getroot()
    fields = ("groupId", "artifactId", "version")
    coordinates = tuple(pom.findtext(f"{{*}}{field}") for field in fields)
    if coordinates != ("io.github.fipe-kt", "fipe-state", VERSION):
        raise ValueError("Maven package coordinate mismatch")
    for suffix in (".jar", "-sources.jar"):
        if not (modules[0].parent / f"fipe-state-{VERSION}{suffix}").is_file():
            raise ValueError(f"Maven repository is missing {suffix}")

    args.out.mkdir(parents=True, exist_ok=True)
    maven_archive = args.out / f"fipe-state-maven-{VERSION}.zip"
    npm_archive = args.out / f"fipe-state-{VERSION}.tgz"
    source_archive = args.out / f"fipe-state-source-{VERSION}.zip"
    write_repository(args.maven, maven_archive)
    shutil.copyfile(args.npm, npm_archive)
    subprocess.run(["git", "archive", "--format=zip", f"--prefix=fipe-state-{VERSION}/",
                    "-o", str(source_archive.resolve()), args.ref], cwd=ROOT, check=True)
    with ZipFile(source_archive) as archive:
        if f"fipe-state-{VERSION}/Package.swift" not in archive.namelist():
            raise ValueError("The selected Git revision is missing Package.swift")

    outputs = (maven_archive, npm_archive, source_archive)
    sums = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}" for path in outputs]
    (args.out / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
    print("\n".join(str(path) for path in outputs))

if __name__ == "__main__":
    main()
