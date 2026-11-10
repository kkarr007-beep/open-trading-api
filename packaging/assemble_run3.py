#!/usr/bin/env python3
"""Build a RID package with upstream license files copied byte for byte."""
import hashlib
import json
import os
import shutil
import stat
import zipfile
from pathlib import Path

ROOT = Path("/workspace")
NUGET = Path.home() / ".nuget" / "packages"
SETTINGS = {
    "Logging": {"LogLevel": {"Default": "Information", "Microsoft.AspNetCore": "Warning"}},
    "Audit": {
        "Mode": "Production",
        "Root": "..",
        "Urls": "http://127.0.0.1:47321",
        "AllowInsecureHttp": False,
        "FilesShareUnc": "\\\\audit-server\\audit-files",
        "BackupAllowRoot": "",
        "WorkerSeconds": 60,
        "CertificatePath": "",
        "BindAddress": "",
        "Port": 0,
    },
}
NUGET_PACKAGES = [
    ("microsoft.data.sqlite", "10.0.14", "Microsoft.Data.Sqlite.nuspec"),
    ("microsoft.data.sqlite.core", "10.0.14", "Microsoft.Data.Sqlite.Core.nuspec"),
    ("microsoft.extensions.hosting.windowsservices", "10.0.14", "Microsoft.Extensions.Hosting.WindowsServices.nuspec"),
    ("system.serviceprocess.servicecontroller", "10.0.14", "System.ServiceProcess.ServiceController.nuspec"),
    ("sqlitepclraw.bundle_e_sqlite3", "2.1.13", "SQLitePCLRaw.bundle_e_sqlite3.nuspec"),
    ("sqlitepclraw.core", "2.1.13", "SQLitePCLRaw.core.nuspec"),
    ("sqlitepclraw.provider.e_sqlite3", "2.1.13", "SQLitePCLRaw.provider.e_sqlite3.nuspec"),
    ("sqlitepclraw.lib.e_sqlite3", "2.1.13", "SQLitePCLRaw.lib.e_sqlite3.nuspec"),
]
RUNTIME_PACKS = {
    "win-x64": [
        ("microsoft.netcore.app.runtime.win-x64", "10.0.14", "Microsoft.NETCore.App.Runtime.win-x64"),
        ("microsoft.aspnetcore.app.runtime.win-x64", "10.0.14", "Microsoft.AspNetCore.App.Runtime.win-x64"),
    ],
    "linux-x64": [
        ("microsoft.netcore.app.runtime.linux-x64", "10.0.14", "Microsoft.NETCore.App.Runtime.linux-x64"),
        ("microsoft.aspnetcore.app.runtime.linux-x64", "10.0.14", "Microsoft.AspNetCore.App.Runtime.linux-x64"),
    ],
}


def nupkg(package_id: str, version: str) -> zipfile.ZipFile:
    matches = list((NUGET / package_id / version).glob("*.nupkg"))
    if len(matches) != 1:
        raise SystemExit(f"expected one nupkg for {package_id} {version}, found {matches}")
    return zipfile.ZipFile(matches[0])


def license_members(package: zipfile.ZipFile) -> list[str]:
    return [
        name for name in package.namelist()
        if any(token in name.upper() for token in ("LICENSE", "NOTICE", "COPYING"))
    ]


def write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def build_sbom(publish: Path, rid: str, license_index: list[str]) -> dict:
    deps = json.loads((publish / "AuditRecord.App.deps.json").read_text(encoding="utf-8"))
    target = deps["runtimeTarget"]["name"]
    if rid not in target:
        raise SystemExit(f"deps target {target} does not match {rid}")
    components = []
    for key, meta in deps["libraries"].items():
        if meta.get("type") == "project":
            continue
        name, version = key.split("/", 1)
        kind = meta.get("type")
        components.append({
            "name": name,
            "version": version,
            "depsType": kind,
            "scope": "runtime-pack" if kind == "runtimepack" else "nuget",
        })
    native = []
    target_libs = deps["targets"][target]
    for key, meta in target_libs.items():
        for asset in meta.get("native", {}):
            base = Path(asset).name
            file_path = publish / base
            native.append({
                "depsKey": key,
                "depsPath": asset,
                "packagedFile": f"app/{base}" if file_path.is_file() else None,
                "bytes": file_path.stat().st_size if file_path.is_file() else None,
            })
    return {
        "bomFormat": "application-inventory",
        "specVersion": "1",
        "name": "audit-record",
        "metadata": {
            "tools": ["dotnet publish 10.0.403"],
            "runtime": "10.0.14",
            "rid": rid,
            "depsRuntimeTarget": target,
            "note": (
                f"Summary inventory from AuditRecord.App.deps.json of the {rid} "
                "self-contained publish. Not a certified CycloneDX document and not a "
                "complete list of every third-party notice inside the runtime packs. "
                "Those texts are the copied LICENSE and THIRD-PARTY-NOTICES files."
            ),
            "licenseIndex": license_index,
        },
        "components": components,
        "nativeAssets": native,
    }


def assemble(rid: str, publish: Path, destination_root: Path) -> None:
    if destination_root.exists():
        shutil.rmtree(destination_root)
    app = destination_root / "app"
    shutil.copytree(publish, app)
    dev = app / "appsettings.Development.json"
    if dev.exists():
        dev.unlink()
    web_config = app / "web.config"
    if web_config.exists():
        web_config.unlink()
    (app / "appsettings.json").write_text(
        json.dumps(SETTINGS, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    note = "이 폴더는 비어 있는 시작 상태입니다. 운영 자료를 미리 넣지 않습니다.\n"
    for name in ("data", "files", "backup"):
        folder = destination_root / name
        folder.mkdir()
        (folder / "README.txt").write_text(note, encoding="utf-8")
    for folder, source in (
        ("config", ROOT / "packaging" / "config"),
        ("scripts", ROOT / "packaging" / "scripts"),
        ("guide", ROOT / "packaging" / "guide"),
    ):
        target = destination_root / folder
        target.mkdir()
        for item in source.iterdir():
            if item.is_file():
                shutil.copy(item, target / item.name)
    if rid == "linux-x64":
        script = (ROOT / "packaging" / "linux" / "run.sh").read_bytes()
        if b"\r" in script:
            raise SystemExit("run.sh still contains CR")
        target = destination_root / "run.sh"
        target.write_bytes(script)
        target.chmod(0o755)
        (app / "AuditRecord.App").chmod(0o755)

    licenses = destination_root / "licenses"
    index_lines = [
        f"RID: {rid}",
        "Files below were copied from the stated upstream package or URL.",
        "No NOTICE file was created for a package whose nupkg has no NOTICE member.",
        "",
    ]
    license_index = []
    for package_id, version, _nuspec_name in NUGET_PACKAGES:
        package = nupkg(package_id, version)
        members = license_members(package)
        dest = licenses / "nuget" / package_id / version
        nuspec_name = next(name for name in package.namelist() if name.endswith(".nuspec"))
        write_bytes(dest / Path(nuspec_name).name, package.read(nuspec_name))
        index_lines.append(
            f"nuget {package_id} {version}: nuspec copied; license-like members = {members or 'NONE'}"
        )
        for member in members:
            write_bytes(dest / Path(member).name, package.read(member))
            rel = f"licenses/nuget/{package_id}/{version}/{Path(member).name}"
            license_index.append(rel)
        license_index.append(f"licenses/nuget/{package_id}/{version}/{Path(nuspec_name).name}")
        package.close()

    for package_id, version, folder_name in RUNTIME_PACKS[rid]:
        package = nupkg(package_id, version)
        dest = licenses / "runtime" / rid / folder_name / version
        for member in license_members(package):
            data = package.read(member)
            write_bytes(dest / Path(member).name, data)
            rel = f"licenses/runtime/{rid}/{folder_name}/{version}/{Path(member).name}"
            license_index.append(rel)
            index_lines.append(f"runtime {package_id} {version}: copied {member} ({len(data)} bytes) from the nupkg")
        package.close()

    apache = (ROOT / "packaging" / "licenses" / "Apache-2.0.txt").read_bytes()
    write_bytes(licenses / "Apache-2.0.txt", apache)
    index_lines.append(
        "Apache-2.0.txt: byte copy of https://www.apache.org/licenses/LICENSE-2.0.txt . "
        "The SQLitePCLRaw 2.1.13 nupkgs contain license expression Apache-2.0 and no LICENSE or NOTICE member."
    )
    commit_license = (ROOT / "packaging" / "licenses" / "dotnet-0427b9ab-LICENSE.TXT").read_bytes()
    write_bytes(licenses / "dotnet-0427b9ab-LICENSE.TXT", commit_license)
    index_lines.append(
        "dotnet-0427b9ab-LICENSE.TXT: byte copy of "
        "https://raw.githubusercontent.com/dotnet/dotnet/0427b9abbead3fa64c6655eb4290c537397131fa/LICENSE.TXT . "
        "That commit is the repository commit in the Microsoft.Data.Sqlite 10.0.14 nuspec. "
        "The same bytes are at src/efcore/LICENSE.txt on that commit. "
        "Microsoft.Data.Sqlite and Microsoft.Data.Sqlite.Core nupkgs contain no LICENSE member; "
        "their nuspec copyright element is included in the copied nuspec."
    )
    (licenses / "INDEX.txt").write_text("\n".join(index_lines) + "\n", encoding="utf-8")
    sbom = build_sbom(app, rid, license_index)
    (licenses / "sbom.json").write_text(json.dumps(sbom, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    shutil.copy(ROOT / "packaging" / "limitations.md", destination_root / "limitations.md")
    normalize_owned(destination_root)

    lines = []
    for path in sorted(destination_root.rglob("*")):
        if path.is_file():
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            lines.append(f"{digest}  {path.relative_to(destination_root).as_posix()}")
    (destination_root / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def to_lf(data: bytes) -> bytes:
    text = data.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    return text.encode("utf-8")


def to_crlf(data: bytes, bom: bool) -> bytes:
    text = data.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    body = text.replace("\n", "\r\n").encode("utf-8")
    return (b"\xef\xbb\xbf" + body) if bom else body


def normalize_owned(root: Path) -> None:
    """LF for our text. CRLF only for .cmd and .ps1. .ps1 is UTF-8 with BOM.
    Upstream license bytes under licenses/ are left unchanged."""
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        suffix = path.suffix.lower()
        if rel.startswith("licenses/") and path.name not in {"INDEX.txt", "sbom.json"}:
            continue
        if rel.startswith("app/") and path.name != "appsettings.json" and not rel.startswith("app/wwwroot/"):
            continue
        if rel.startswith("app/wwwroot/") and suffix not in {".css", ".js"}:
            continue
        if path.name == "run.sh" or suffix == ".sh":
            path.write_bytes(to_lf(path.read_bytes()))
            path.chmod(0o755)
        elif suffix == ".ps1":
            path.write_bytes(to_crlf(path.read_bytes(), bom=True))
        elif suffix == ".cmd":
            path.write_bytes(to_crlf(path.read_bytes(), bom=False))
        elif suffix in {".md", ".json", ".css", ".js", ".txt"}:
            path.write_bytes(to_lf(path.read_bytes()))


def zip_tree(source: Path, zip_path: Path) -> None:
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob("*")):
            rel = path.relative_to(source).as_posix()
            info = zipfile.ZipInfo(rel + ("/" if path.is_dir() else ""))
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = path.stat().st_mode
            info.external_attr = (mode & 0xFFFF) << 16
            if path.is_dir():
                continue
            data = path.read_bytes()
            if path.suffix == ".sh" and b"\r" in data:
                raise SystemExit(f"refusing to zip {rel} with CR")
            if path.suffix == ".ps1" and (not data.startswith(b"\xef\xbb\xbf") or b"\n" not in data or b"\r\n" not in data):
                raise SystemExit(f"refusing to zip {rel} without UTF-8 BOM and CRLF")
            if path.suffix == ".cmd" and b"\r\n" not in data:
                raise SystemExit(f"refusing to zip {rel} without CRLF")
            if path.suffix == ".md" and b"\r" in data:
                raise SystemExit(f"refusing to zip {rel} with CR")
            archive.writestr(info, data)


if __name__ == "__main__":
    stage = Path(os.environ["RUN3_STAGE"])
    assemble(os.environ["RUN3_RID"], Path(os.environ["RUN3_PUBLISH"]), stage)
    # Zip the parent so the archive root is the 감사기록 directory.
    zip_tree(stage.parent, Path(os.environ["RUN3_ZIP"]))
    print("wrote", os.environ["RUN3_ZIP"])
