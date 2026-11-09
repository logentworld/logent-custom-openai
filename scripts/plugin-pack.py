#!/usr/bin/env python3
"""AAR의 classes.jar를 D8로 바꾸고 서명된 `.lpg` 플러그인 패키지를 만들어요."""

import argparse
import contextlib
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile


MAX_FILES = 4096
MAX_DEPENDENCIES = 32
MAX_MANIFEST = 1024 * 1024
MAX_FILE = 256 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024
MAX_AAR = 512 * 1024 * 1024
MAX_AAR_ENTRIES = 16384
MAX_CLASSES_JAR = 256 * 1024 * 1024
MAX_SIGNATURE = 256
MAX_ZIP = 512 * 1024 * 1024
MAX_PATH = 1024
CHUNK = 1024 * 1024
D8_TIMEOUT = 600
ID_PATTERN = re.compile(r"[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)*\Z", re.ASCII)
PLUGIN_KEY_PATTERN = re.compile(r"[a-z][a-z0-9-]*:[a-z][a-z0-9_-]*\Z", re.ASCII)
VERSION_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}\Z", re.ASCII)
ENTRY_PATTERN = re.compile(
    r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\Z", re.ASCII
)
SHA_PATTERN = re.compile(r"[0-9a-f]{64}\Z", re.ASCII)
DEX_MAGIC = b"dex\n"
FORBIDDEN_ASSET_SUFFIXES = {".dex", ".apk", ".jar", ".class", ".aar", ".aab"}
# id-ecPublicKey, prime256v1, 비압축 공개점으로 이루어진 DER SPKI 접두사예요.
P256_SPKI_PREFIX = bytes.fromhex(
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
)
FILE_FLAGS = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
READ_FLAGS = os.O_RDONLY | os.O_NONBLOCK


class PackError(Exception):
    pass


class FileEntry:
    __slots__ = ("path", "staged", "sha256", "size")

    def __init__(self, path, staged, sha256, size):
        self.path = path
        self.staged = staged
        self.sha256 = sha256
        self.size = size


def openssl(*arguments, data=None, pass_fds=()):
    result = subprocess.run(
        ["openssl", *arguments], input=data, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, pass_fds=pass_fds, check=False,
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise PackError(f"OpenSSL 실행에 실패했어요: {detail}")
    return result.stdout


def fingerprint(metadata):
    # ctime은 chmod로도 바뀌어 동시 패키징에서 오탐이 나요.
    return (
        metadata.st_dev, metadata.st_ino, metadata.st_mode, metadata.st_size,
        metadata.st_mtime_ns,
    )


def private_path(value):
    path = Path(value).expanduser().resolve()
    # worktree와 submodule의 .git 파일도 checkout 표시로 취급해요.
    for parent in (path.parent, *path.parent.parents):
        if os.path.lexists(parent / ".git"):
            raise PackError(f"개인키를 Git checkout 안에 둘 수 없어요: {path}")
    return path


def public_der(private_bytes):
    openssl("ec", "-check", "-noout", data=private_bytes)
    public = openssl(
        "ec", "-pubout", "-outform", "DER", "-conv_form", "uncompressed",
        "-param_enc", "named_curve", data=private_bytes,
    )
    if len(public) != 91 or not public.startswith(P256_SPKI_PREFIX):
        raise PackError("개인키는 ECDSA P-256 곡선을 사용해야 해요.")
    return public


def read_public_key(value):
    path = Path(value).expanduser().absolute()
    fd = os.open(path, READ_FLAGS)
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise PackError("공개키는 일반 파일이어야 해요.")
        with os.fdopen(fd, "rb") as stream:
            fd = None
            data = stream.read(4096)
    finally:
        if fd is not None:
            os.close(fd)
    if len(data) != 91 or not data.startswith(P256_SPKI_PREFIX):
        raise PackError("공개키는 ECDSA P-256 DER SPKI여야 해요.")
    return data


def safe_path(value):
    if not value or len(value) > MAX_PATH:
        return False
    if value.startswith("/") or "\\" in value or "\x00" in value:
        return False
    if any(ord(character) < 0x20 or ord(character) == 0x7f for character in value):
        return False
    return all(part not in ("", ".", "..") for part in value.split("/"))


def forbidden_asset(path):
    name = path.rsplit("/", 1)[-1].lower()
    return any(name.endswith(suffix) for suffix in FORBIDDEN_ASSET_SUFFIXES)


def validate_plugin_id(value):
    if len(value) > 128 or not ID_PATTERN.fullmatch(value) or any(not part for part in value.split(".")):
        raise PackError(
            r"id는 [a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)* 형식이며 점 사이에 빈 구간이 없어야 해요."
        )
    return value.rsplit(".", 1)[-1]


def validate_plugin_key(value):
    if len(value) > 128 or not PLUGIN_KEY_PATTERN.fullmatch(value):
        raise PackError("key는 128자 이하의 명시적인 publisher:name 형식이어야 해요.")
    return value


def validate_dependencies(plugin_id, dependencies, plugin_key=None):
    if len(dependencies) > MAX_DEPENDENCIES:
        raise PackError("필수 플러그인은 최대 32개까지 지정할 수 있어요.")
    for dependency in dependencies:
        if ":" in dependency:
            validate_plugin_key(dependency)
        else:
            validate_plugin_id(dependency)
        if dependency == plugin_id or dependency == plugin_key:
            raise PackError("플러그인은 자신을 필수 플러그인으로 지정할 수 없어요.")
    if len(set(dependencies)) != len(dependencies):
        raise PackError("필수 플러그인 참조가 중복됐어요.")
    return sorted(dependencies)


def validate_name(value):
    try:
        utf16 = value.encode("utf-16-le")
    except UnicodeEncodeError as error:
        raise PackError("name은 올바른 UTF-8이어야 해요.") from error
    # Kotlin의 String.length(UTF-16 단위)와 같은 상한을 써요.
    if not value.strip() or len(utf16) // 2 > 128:
        raise PackError("name은 공백이 아닌 128자 이하여야 해요.")
    if any(character == "\t" or ord(character) < 0x20
           or 0x7f <= ord(character) <= 0x9f for character in value):
        raise PackError("name에는 탭과 제어 문자를 넣을 수 없어요.")
    return value


def validate_version(value):
    if not VERSION_PATTERN.fullmatch(value):
        raise PackError("version은 영숫자로 시작하는 1~96자의 영숫자·점·밑줄·하이픈이어야 해요.")
    return value


def validate_entry(value):
    if not ENTRY_PATTERN.fullmatch(value):
        raise PackError("entry는 패키지가 있는 Java 클래스 이름이어야 해요.")
    return value


def validate_api_range(api_min, api_max):
    if api_min <= 0 or api_max < api_min:
        raise PackError("API 범위는 양의 정수이며 api-min이 api-max 이하여야 해요.")
    return api_min, api_max


def read_definition(value):
    def object_fields(pairs):
        fields = {}
        for key, item in pairs:
            if key in fields:
                raise PackError("플러그인 정의에 중복 필드가 있어요.")
            fields[key] = item
        return fields

    def invalid_constant(value):
        raise PackError("플러그인 정의는 올바른 JSON이어야 해요.")

    try:
        with Path(value).expanduser().open("r", encoding="utf-8") as source:
            definition = json.load(
                source, object_pairs_hook=object_fields, parse_constant=invalid_constant,
            )
    except (OSError, UnicodeError, ValueError, RecursionError) as error:
        raise PackError("플러그인 정의는 읽을 수 있는 UTF-8 JSON이어야 해요.") from error
    if not isinstance(definition, dict):
        raise PackError("플러그인 정의는 JSON 객체여야 해요.")
    required = {"id", "key", "name", "entry"}
    allowed = required | {"dependencies", "apiMin", "apiMax"}
    if definition.keys() - allowed:
        raise PackError("플러그인 정의에 알 수 없는 필드가 있어요.")
    if required - definition.keys():
        raise PackError("플러그인 정의에는 id·key·name·entry가 모두 필요해요.")
    if any(not isinstance(definition[key], str) for key in required):
        raise PackError("플러그인 정의의 id·key·name·entry는 문자열이어야 해요.")
    dependencies = definition.get("dependencies", [])
    if not isinstance(dependencies, list) or any(
        not isinstance(reference, str) for reference in dependencies
    ):
        raise PackError("플러그인 정의의 dependencies는 문자열 배열이어야 해요.")
    api_min = definition.get("apiMin", 4)
    api_max = definition.get("apiMax", 4)
    if type(api_min) is not int or type(api_max) is not int:
        raise PackError("플러그인 정의의 apiMin·apiMax는 정수여야 해요.")
    validate_plugin_id(definition["id"])
    plugin_key = validate_plugin_key(definition["key"])
    validate_api_range(api_min, api_max)
    return {
        "id": definition["id"],
        "plugin_key": plugin_key,
        "name": validate_name(definition["name"]),
        "entry": validate_entry(definition["entry"]),
        "requires": validate_dependencies(definition["id"], dependencies, plugin_key),
        "api_min": api_min,
        "api_max": api_max,
    }


def zip_info(name):
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = (stat.S_IFREG | 0o644) << 16
    entry.compress_type = zipfile.ZIP_DEFLATED
    return entry


def hash_file(path, limit):
    digest = hashlib.sha256()
    size = 0
    with open(path, "rb") as stream:
        while True:
            chunk = stream.read(CHUNK)
            if not chunk:
                break
            size += len(chunk)
            if size > limit:
                raise PackError(f"파일 크기가 허용 범위를 넘었어요: {path}")
            digest.update(chunk)
    return digest.hexdigest(), size


def copy_file(source, destination, limit):
    digest = hashlib.sha256()
    size = 0
    with open(source, "rb") as stream:
        while True:
            chunk = stream.read(CHUNK)
            if not chunk:
                break
            size += len(chunk)
            if size > limit:
                raise PackError(f"파일 크기가 허용 범위를 넘었어요: {source}")
            digest.update(chunk)
            destination.write(chunk)
    return digest.hexdigest(), size


def extract_entry(archive, info, destination, limit):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    fd = os.open(destination, flags, 0o600)
    digest = hashlib.sha256()
    size = 0
    try:
        with os.fdopen(fd, "wb") as output:
            fd = None
            with archive.open(info) as source:
                while True:
                    chunk = source.read(CHUNK)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > limit or size > info.file_size:
                        raise PackError(f"AAR 항목 크기가 선언값을 넘었어요: {info.filename}")
                    digest.update(chunk)
                    output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(destination)
        raise
    finally:
        if fd is not None:
            os.close(fd)
    if size != info.file_size:
        raise PackError(f"AAR 항목 크기가 선언값과 달라요: {info.filename}")
    return digest.hexdigest(), size


def allowed_asset_directory(name, prefix):
    return name in ("assets/", "assets/plugins/") or name == prefix or name.startswith(prefix)


def read_aar(value, plugin_id, staging):
    path = Path(value).expanduser().absolute()
    short = plugin_id.rsplit(".", 1)[-1]
    prefix = f"assets/plugins/{short}/"
    fd = os.open(path, READ_FLAGS)
    try:
        metadata = os.fstat(fd)
        if not stat.S_ISREG(metadata.st_mode):
            raise PackError("AAR은 일반 파일이어야 해요.")
        if metadata.st_size < 1 or metadata.st_size > MAX_AAR:
            raise PackError("AAR 크기는 1바이트 이상 512MiB 이하여야 해요.")
        expected = fingerprint(metadata)
        with os.fdopen(fd, "rb") as stream:
            fd = None
            result = read_aar_stream(stream, prefix, short, staging)
            if fingerprint(os.fstat(stream.fileno())) != expected:
                raise PackError("AAR이 읽는 중 바뀌었어요.")
            return result
    finally:
        if fd is not None:
            os.close(fd)


def read_aar_stream(stream, prefix, short, staging):
    assets = {}
    classes_jar = None
    total = 0
    with zipfile.ZipFile(stream) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_AAR_ENTRIES:
            raise PackError("AAR 항목이 너무 많아요.")
        seen = set()
        for info in entries:
            name = info.filename
            if name in seen:
                raise PackError(f"AAR에 중복 경로가 있어요: {name}")
            seen.add(name)
            if info.flag_bits & 0x1:
                raise PackError(f"암호화된 AAR 항목은 허용하지 않아요: {name}")
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode):
                raise PackError(f"AAR symlink 항목은 허용하지 않아요: {name}")
            directory = name.endswith("/")
            if not safe_path(name[:-1] if directory else name):
                raise PackError(f"안전하지 않은 AAR 경로예요: {name}")
            if directory:
                if name.startswith("assets/") and not allowed_asset_directory(name, prefix):
                    raise PackError(f"AAR 자산은 {prefix} 아래만 허용해요: {name}")
                continue
            if name == "classes.dex" or name.lower().endswith(".dex"):
                raise PackError(
                    "AAR에는 DEX를 넣을 수 없어요. classes.jar만 D8로 변환해요."
                )
            if name == "classes.jar":
                if classes_jar is not None:
                    raise PackError("AAR에 classes.jar가 중복됐어요.")
                if info.file_size < 1 or info.file_size > MAX_CLASSES_JAR:
                    raise PackError("classes.jar 크기는 1바이트 이상 256MiB 이하여야 해요.")
                classes_jar = info
                continue
            if not name.startswith("assets/"):
                continue
            if not name.startswith(prefix):
                raise PackError(f"AAR 자산은 {prefix} 아래만 허용해요: {name}")
            relative = name[len(prefix):]
            if not relative:
                raise PackError(f"AAR 자산 경로가 비어 있어요: {name}")
            if forbidden_asset(relative):
                raise PackError(f"실행 코드 컨테이너는 자산에 넣을 수 없어요: {name}")
            if len(name) > MAX_PATH:
                raise PackError(f"AAR 자산 경로는 {MAX_PATH}자 이하여야 해요: {name}")
            if info.file_size > MAX_FILE:
                raise PackError(f"파일 크기는 256MiB 이하여야 해요: {name}")
            if len(assets) >= MAX_FILES - 1:
                raise PackError(f"파일은 DEX를 포함해 최대 {MAX_FILES}개까지 넣을 수 있어요.")
            total += info.file_size
            if total > MAX_TOTAL:
                raise PackError("자산 전체 크기는 512MiB 이하여야 해요.")
            assets[relative] = info

        if classes_jar is None:
            raise PackError("AAR에 classes.jar가 없어요.")
        if total + classes_jar.file_size > MAX_TOTAL:
            raise PackError("AAR의 classes.jar와 자산 전체 크기는 512MiB 이하여야 해요.")

        jar_path = staging / "classes.jar"
        extract_entry(archive, classes_jar, jar_path, MAX_CLASSES_JAR)

        files = {}
        for relative in sorted(assets):
            info = assets[relative]
            destination = staging / "assets" / "plugins" / short / relative
            destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            digest, size = extract_entry(archive, info, destination, MAX_FILE)
            files[prefix + relative] = FileEntry(prefix + relative, destination, digest, size)
    return jar_path, files


def build_tools_key(path):
    return tuple(
        int(part) if part.isdigit() else -1
        for part in re.split(r"[.-]", path.name)
    )


def sdk_roots():
    roots = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK"):
        value = os.environ.get(key)
        if value:
            roots.append(Path(value).expanduser())
    roots += [
        Path("/root/android-sdk"),
        Path.home() / "Android" / "Sdk",
        Path("/opt/android-sdk"),
        Path("/usr/lib/android-sdk"),
        Path("/usr/local/android-sdk"),
    ]
    unique = []
    seen = set()
    for root in roots:
        resolved = root.absolute()
        if resolved in seen:
            continue
        seen.add(resolved)
        unique.append(resolved)
    return unique


def find_d8(explicit):
    if explicit:
        path = Path(explicit).expanduser().absolute()
        if not path.is_file() or not os.access(path, os.X_OK):
            raise PackError(f"D8 실행 파일이 아니에요: {path}")
        parent = path.parent.parent
        root = parent.parent if parent.name == "build-tools" else None
        return path, root
    for root in sdk_roots():
        build_tools = root / "build-tools"
        try:
            versions = sorted(build_tools.iterdir(), key=build_tools_key, reverse=True)
        except (FileNotFoundError, NotADirectoryError, PermissionError):
            continue
        for version in versions:
            candidate = version / "d8"
            try:
                if version.is_dir() and candidate.is_file() and os.access(candidate, os.X_OK):
                    return candidate, root
            except PermissionError:
                continue
    found = shutil.which("d8")
    if found:
        return Path(found).resolve(), None
    raise PackError(
        "Android SDK build-tools의 d8을 찾지 못했어요. --d8로 실행 파일을 지정해 주세요."
    )


def find_java(explicit):
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if not path.is_file() or not os.access(path, os.X_OK):
            raise PackError(f"Java 실행 파일이 아니에요: {path}")
        return path
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java"
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    found = shutil.which("java")
    if found:
        return Path(found).resolve()
    candidates = []
    for pattern in (
        "/usr/lib/jvm/*/bin/java",
        "/opt/android-studio/jbr/bin/java",
        str(Path.home() / "android-studio" / "jbr" / "bin" / "java"),
    ):
        candidates.extend(Path(item) for item in glob.glob(pattern))
    for candidate in sorted(candidates):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    raise PackError("Java를 찾지 못했어요. --java로 실행 파일을 지정해 주세요.")


def find_android_jar(sdk_root):
    roots = [sdk_root] if sdk_root is not None else []
    roots += sdk_roots()
    best = None
    best_key = None
    for root in roots:
        platforms = root / "platforms"
        try:
            directories = list(platforms.iterdir())
        except (FileNotFoundError, NotADirectoryError, PermissionError):
            continue
        for directory in directories:
            jar = directory / "android.jar"
            try:
                if not directory.is_dir() or not jar.is_file() or not os.access(jar, os.R_OK):
                    continue
            except PermissionError:
                continue
            suffix = directory.name.rsplit("-", 1)[-1]
            key = int(suffix) if suffix.isdigit() else -1
            if best_key is None or key > best_key:
                best = jar
                best_key = key
    return best


def java_environment(java):
    environment = dict(os.environ)
    environment["PATH"] = str(java.parent) + os.pathsep + environment.get("PATH", "")
    try:
        if os.path.samefile(java, java.parent.parent / "bin" / "java"):
            environment["JAVA_HOME"] = str(java.parent.parent)
        else:
            environment.pop("JAVA_HOME", None)
    except OSError:
        environment.pop("JAVA_HOME", None)
    return environment


def run_d8(d8, java, android_jar, jar_path, output_directory, min_api):
    output_directory.mkdir(mode=0o700)
    command = [
        str(d8), "--release", "--min-api", str(min_api),
        "--output", str(output_directory),
    ]
    if android_jar is not None:
        command.extend(["--lib", str(android_jar)])
    command.append(str(jar_path))
    try:
        result = subprocess.run(
            command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=java_environment(java), timeout=D8_TIMEOUT, check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise PackError(f"D8 실행이 {D8_TIMEOUT}초 안에 끝나지 않았어요.") from error
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()[-4096:]
        raise PackError(f"D8 실행에 실패했어요(exit {result.returncode}): {detail}")


def d8_dex(staging, d8, java, android_jar, jar_path, min_api):
    output = staging / "dex"
    run_d8(d8, java, android_jar, jar_path, output, min_api)
    entries = sorted(output.iterdir(), key=lambda entry: entry.name)
    names = [entry.name for entry in entries]
    if names != ["classes.dex"]:
        raise PackError(f"D8 산출물은 classes.dex 하나여야 해요: {names}")
    dex = entries[0]
    if dex.is_symlink() or not dex.is_file():
        raise PackError("D8 산출물 classes.dex가 일반 파일이 아니에요.")
    with open(dex, "rb") as stream:
        if stream.read(4) != DEX_MAGIC:
            raise PackError("D8 산출물이 dex\\n magic으로 시작하지 않아요.")
    digest, size = hash_file(dex, MAX_FILE)
    if size < 1:
        raise PackError("D8 산출물 classes.dex가 비어 있어요.")
    return FileEntry("classes.dex", dex, digest, size)


def render_manifest(arguments, files):
    if not 1 <= len(files) <= MAX_FILES:
        raise PackError(f"플러그인 파일은 최대 {MAX_FILES}개까지 넣을 수 있어요.")
    total = sum(entry.size for entry in files.values())
    if total > MAX_TOTAL:
        raise PackError("DEX와 자산 전체 크기는 512MiB 이하여야 해요.")
    lines = [
        "logent-plugin-v1\n",
        f"id={arguments.id}\n",
        f"name={arguments.name}\n",
        f"version={arguments.version}\n",
        f"apiMin={arguments.api_min}\n",
        f"apiMax={arguments.api_max}\n",
        f"entry={arguments.entry}\n",
    ]
    if arguments.plugin_key is not None:
        lines.append(f"key={validate_plugin_key(arguments.plugin_key)}\n")
    dependencies = validate_dependencies(
        arguments.id, arguments.requires, arguments.plugin_key,
    )
    lines.extend(f"requires={dependency}\n" for dependency in dependencies)
    for path in sorted(files):
        entry = files[path]
        lines.append(f"{entry.sha256}\t{entry.size}\t{path}\n")
    manifest = "".join(lines).encode("utf-8")
    if len(manifest) > MAX_MANIFEST:
        raise PackError("manifest.txt는 1MiB 이하여야 해요.")
    return manifest


def verify_signature(manifest, signature, public, workspace):
    public_path = workspace / "verify-public.der"
    signature_path = workspace / "verify-manifest.sig"
    public_path.write_bytes(public)
    signature_path.write_bytes(signature)
    try:
        openssl(
            "dgst", "-sha256", "-verify", str(public_path), "-keyform", "DER",
            "-signature", str(signature_path), data=manifest,
        )
    finally:
        with contextlib.suppress(FileNotFoundError):
            public_path.unlink()
        with contextlib.suppress(FileNotFoundError):
            signature_path.unlink()


def sign_manifest(manifest, key_fd, public, workspace):
    # 같은 파일 서술자를 OpenSSL에 넘겨 개인키가 읽는 중 바뀌지 않게 해요.
    os.lseek(key_fd, 0, os.SEEK_SET)
    signature = openssl(
        "dgst", "-sha256", "-sign", f"/dev/fd/{key_fd}",
        data=manifest, pass_fds=(key_fd,),
    )
    if not signature or len(signature) > MAX_SIGNATURE:
        raise PackError("DER 서명은 1바이트 이상 256바이트 이하여야 해요.")
    verify_signature(manifest, signature, public, workspace)
    return signature


def write_package(staged, files, manifest, signature):
    total = len(manifest) + len(signature) + sum(entry.size for entry in files.values())
    if total > MAX_TOTAL:
        raise PackError("manifest와 서명을 포함한 전체 크기는 512MiB 이하여야 해요.")
    with open(staged, "w+b") as stream:
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(zip_info("manifest.txt"), manifest)
            archive.writestr(zip_info("manifest.sig"), signature)
            for path in sorted(files):
                entry = files[path]
                with archive.open(zip_info(path), "w") as destination:
                    digest, size = copy_file(entry.staged, destination, entry.size)
                if digest != entry.sha256 or size != entry.size:
                    raise PackError(f"원본과 ZIP의 SHA256 또는 크기가 달라요: {path}")
        stream.flush()
        os.fsync(stream.fileno())
    if os.path.getsize(staged) > MAX_ZIP:
        raise PackError("압축 ZIP 크기는 512MiB 이하여야 해요.")


def read_entry_bytes(archive, info, limit):
    if info.file_size < 0 or info.file_size > limit:
        raise PackError(f"ZIP 항목 크기가 허용 범위를 벗어났어요: {info.filename}")
    with archive.open(info) as source:
        data = source.read(limit + 1)
    if len(data) > limit or len(data) != info.file_size:
        raise PackError(f"ZIP 항목 크기가 선언값과 달라요: {info.filename}")
    return data


def parse_manifest(data):
    if not data or data[-1] != 0x0A:
        raise PackError("manifest는 마지막 줄바꿈이 필요해요.")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PackError("manifest는 UTF-8이어야 해요.") from error
    lines = text[:-1].split("\n")
    if not 8 <= len(lines) <= MAX_FILES + MAX_DEPENDENCIES + 8:
        raise PackError("manifest 줄 수가 허용 범위를 벗어났어요.")
    if lines[0] != "logent-plugin-v1":
        raise PackError("manifest 첫 줄은 logent-plugin-v1이어야 해요.")

    def field(index, key):
        prefix = f"{key}="
        if not lines[index].startswith(prefix):
            raise PackError(f"manifest {key} 필드가 올바르지 않아요.")
        return lines[index][len(prefix):]

    plugin_id = field(1, "id")
    short = validate_plugin_id(plugin_id)
    validate_name(field(2, "name"))
    validate_version(field(3, "version"))
    try:
        api_min = int(field(4, "apiMin"))
        api_max = int(field(5, "apiMax"))
    except ValueError as error:
        raise PackError("manifest API 범위가 정수가 아니에요.") from error
    validate_api_range(api_min, api_max)
    validate_entry(field(6, "entry"))
    file_start = 7
    plugin_key = None
    if lines[file_start].startswith("key="):
        plugin_key = validate_plugin_key(lines[file_start][len("key="):])
        file_start += 1
    dependencies = []
    while file_start < len(lines) and lines[file_start].startswith("requires="):
        dependencies.append(lines[file_start][len("requires="):])
        file_start += 1
    if dependencies != validate_dependencies(plugin_id, dependencies, plugin_key):
        raise PackError("manifest 필수 플러그인 행은 참조 문자열순으로 정렬해야 해요.")
    if not 1 <= len(lines) - file_start <= MAX_FILES:
        raise PackError("manifest 파일 수가 허용 범위를 벗어났어요.")
    prefix = f"assets/plugins/{short}/"
    files = []
    paths = []
    for line in lines[file_start:]:
        parts = line.split("\t")
        if len(parts) != 3 or not SHA_PATTERN.fullmatch(parts[0]):
            raise PackError("manifest 파일 행이 올바르지 않아요.")
        if not parts[1].isdigit() or str(int(parts[1])) != parts[1]:
            raise PackError("manifest 파일 크기가 올바르지 않아요.")
        size = int(parts[1])
        path = parts[2]
        if size > MAX_FILE:
            raise PackError("manifest 파일 크기가 허용 범위를 벗어났어요.")
        if not safe_path(path) or (path != "classes.dex" and not path.startswith(prefix)):
            raise PackError(f"플러그인 소유 경로가 아니에요: {path}")
        if path != "classes.dex" and forbidden_asset(path):
            raise PackError(f"실행 코드 컨테이너는 자산에 넣을 수 없어요: {path}")
        files.append(FileEntry(path, None, parts[0], size))
        paths.append(path)
    if len(set(paths)) != len(paths):
        raise PackError("manifest 파일 경로가 중복됐어요.")
    if paths != sorted(paths):
        raise PackError("manifest 파일 행은 경로순으로 정렬해야 해요.")
    if "classes.dex" not in paths:
        raise PackError("manifest에 classes.dex가 없어요.")
    return files


def verify_package(path, public, workspace):
    path = Path(path)
    if not path.is_file():
        raise PackError(f"완성된 패키지가 없어요: {path}")
    if not 1 <= path.stat().st_size <= MAX_ZIP:
        raise PackError("압축 ZIP 크기는 512MiB 이하여야 해요.")
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_FILES + 2:
            raise PackError("패키지 항목이 너무 많아요.")
        names = []
        declared_total = 0
        for info in infos:
            name = info.filename
            if info.is_dir() or not safe_path(name):
                raise PackError(f"패키지 ZIP 경로가 올바르지 않아요: {name}")
            if info.flag_bits & 0x1:
                raise PackError(f"암호화된 ZIP 항목은 허용하지 않아요: {name}")
            if name in names:
                raise PackError(f"패키지에 중복 경로가 있어요: {name}")
            if info.file_size < 0 or info.file_size > MAX_FILE:
                raise PackError(f"패키지 파일 크기가 허용 범위를 벗어났어요: {name}")
            declared_total += info.file_size
            if declared_total > MAX_TOTAL:
                raise PackError("패키지 전체 크기가 너무 커요.")
            names.append(name)
        if "manifest.txt" not in names or "manifest.sig" not in names:
            raise PackError("패키지에 manifest.txt와 manifest.sig가 필요해요.")
        manifest = read_entry_bytes(
            archive, archive.getinfo("manifest.txt"), MAX_MANIFEST,
        )
        signature = read_entry_bytes(
            archive, archive.getinfo("manifest.sig"), MAX_SIGNATURE,
        )
        verify_signature(manifest, signature, public, workspace)
        parsed = parse_manifest(manifest)
        expected = {"manifest.txt", "manifest.sig"} | {
            entry.path for entry in parsed
        }
        if set(names) != expected or len(names) != len(expected):
            raise PackError("서명 목록과 패키지 ZIP 파일 목록이 달라요.")
        actual_total = len(manifest) + len(signature)
        has_dex = False
        for entry in parsed:
            info = archive.getinfo(entry.path)
            if info.file_size != entry.size:
                raise PackError(f"패키지 파일 크기가 manifest와 달라요: {entry.path}")
            digest = hashlib.sha256()
            size = 0
            prefix = b""
            with archive.open(info) as source:
                while True:
                    chunk = source.read(CHUNK)
                    if not chunk:
                        break
                    size += len(chunk)
                    actual_total += len(chunk)
                    if size > entry.size or actual_total > MAX_TOTAL:
                        raise PackError("패키지 실제 크기가 허용 범위를 벗어났어요.")
                    if len(prefix) < 4:
                        prefix += chunk[:4 - len(prefix)]
                    digest.update(chunk)
            if size != entry.size or digest.hexdigest() != entry.sha256:
                raise PackError(f"패키지 파일 SHA-256이 달라요: {entry.path}")
            if entry.path == "classes.dex":
                if prefix != DEX_MAGIC:
                    raise PackError("classes.dex가 dex\\n magic으로 시작하지 않아요.")
                has_dex = True
            elif entry.path.lower().endswith(".dex"):
                raise PackError("classes.dex 외의 DEX는 허용하지 않아요.")
        if not has_dex:
            raise PackError("패키지에 classes.dex가 없어요.")


def build(arguments):
    vars(arguments).update(read_definition(arguments.definition))
    short = validate_plugin_id(arguments.id)
    arguments.requires = validate_dependencies(
        arguments.id, arguments.requires, arguments.plugin_key,
    )
    arguments.name = validate_name(arguments.name)
    arguments.version = validate_version(arguments.version)
    arguments.entry = validate_entry(arguments.entry)
    arguments.api_min, arguments.api_max = validate_api_range(
        arguments.api_min, arguments.api_max,
    )
    if arguments.min_api <= 0:
        raise PackError("D8 min-api는 양의 정수여야 해요.")
    output = Path(arguments.output).expanduser().absolute()
    if not output.parent.is_dir():
        raise PackError(f"출력 디렉터리가 없어요: {output.parent}")
    if output.is_dir():
        raise PackError(f"출력 경로가 디렉터리예요: {output}")
    aar = Path(arguments.aar).expanduser().absolute()
    key = private_path(arguments.key)
    public_path = Path(arguments.public_key).expanduser().absolute()
    if output.resolve() in (aar.resolve(), key, public_path.resolve()):
        raise PackError("출력 경로는 AAR·개인키·공개키와 달라야 해요.")

    key_fd = os.open(key, FILE_FLAGS)
    try:
        if not stat.S_ISREG(os.fstat(key_fd).st_mode):
            raise PackError("개인키는 일반 파일이어야 해요.")
        os.fchmod(key_fd, 0o600)
        key_metadata = fingerprint(os.fstat(key_fd))
        provided_public = read_public_key(public_path)
        with os.fdopen(key_fd, "rb") as key_stream:
            key_fd = None
            private = key_stream.read(1024 * 1024 + 1)
            if len(private) > 1024 * 1024:
                raise PackError("개인키 파일은 1MiB 이하여야 해요.")
            public = public_der(private)
            if provided_public != public:
                raise PackError("지정한 DER SPKI 공개키가 개인키와 일치하지 않아요.")
            if fingerprint(os.fstat(key_stream.fileno())) != key_metadata:
                raise PackError("개인키가 읽는 중 바뀌었어요.")

            staging = Path(tempfile.mkdtemp(prefix=".logent-plugin-", dir=output.parent))
            try:
                jar_path, files = read_aar(aar, arguments.id, staging)
                d8, sdk_root = find_d8(arguments.d8)
                java = find_java(arguments.java)
                android_jar = find_android_jar(sdk_root)
                files["classes.dex"] = d8_dex(
                    staging, d8, java, android_jar, jar_path, arguments.min_api,
                )
                manifest = render_manifest(arguments, files)
                # OpenSSL은 파일 서술자의 현재 위치를 쓰므로 방금 읽은 키를 되감아요.
                key_stream.seek(0)
                signature = sign_manifest(
                    manifest, key_stream.fileno(), public, staging,
                )
                if fingerprint(os.fstat(key_stream.fileno())) != key_metadata:
                    raise PackError("개인키가 서명 중 바뀌었어요.")
                staged = staging / "package.lpg"
                write_package(staged, files, manifest, signature)
                verify_package(staged, public, staging)
                os.replace(staged, output)
                try:
                    directory_fd = os.open(output.parent, os.O_RDONLY | os.O_DIRECTORY)
                except OSError:
                    directory_fd = None
                if directory_fd is not None:
                    try:
                        with contextlib.suppress(OSError):
                            os.fsync(directory_fd)
                    finally:
                        os.close(directory_fd)
            finally:
                shutil.rmtree(staging, ignore_errors=True)
    finally:
        if key_fd is not None:
            os.close(key_fd)

    dex = files["classes.dex"]
    print(f"서명된 플러그인 패키지를 만들었어요: {output}")
    print(f"id={arguments.id} short={short} files={len(files)} dex={dex.size}바이트")
    print(f"공개키 SHA256: {hashlib.sha256(public).hexdigest()}")


def parser():
    result = argparse.ArgumentParser(
        description="AAR을 D8로 변환하고 서명된 .lpg 플러그인 패키지를 만들어요.",
    )
    commands = result.add_subparsers(dest="command", required=True, help="실행할 작업을 골라 주세요.")
    package = commands.add_parser("build", help="AAR의 classes.jar와 자산을 서명 패키지로 만들어요.")
    package.add_argument("--aar", required=True, help="Gradle이 만든 AAR 파일을 지정해 주세요.")
    package.add_argument("--definition", required=True, help="logent-plugin.json 정의 파일을 지정해 주세요.")
    package.add_argument("--version", required=True, help="플러그인 버전을 지정해 주세요.")
    package.add_argument("--key", required=True, help="Git checkout 밖의 PEM 개인키를 지정해 주세요.")
    package.add_argument("--public-key", required=True, help="개인키와 일치해야 하는 DER SPKI 공개키를 지정해 주세요.")
    package.add_argument("--output", required=True, help="완성된 .lpg 출력 경로를 지정해 주세요. 실패하면 기존 파일을 보존해요.")
    package.add_argument("--min-api", type=int, default=26, help="D8 Android min API예요. 기본값은 26이에요.")
    package.add_argument("--d8", help="Android SDK build-tools의 d8 실행 파일을 직접 지정해 주세요.")
    package.add_argument("--java", help="D8을 실행할 Java 실행 파일을 직접 지정해 주세요.")
    package.set_defaults(action=build)
    return result


def main():
    arguments = parser().parse_args()
    try:
        arguments.action(arguments)
    except (
        PackError, OSError, ValueError, RuntimeError, EOFError, struct.error,
        zipfile.BadZipFile, zipfile.LargeZipFile, subprocess.SubprocessError,
    ) as error:
        print(f"플러그인 패키지 작업에 실패했어요: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
