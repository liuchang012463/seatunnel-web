import hashlib
import json
import os
from pathlib import Path
import re
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


def request(url, token, method="GET", data=None, headers=None):
    call = Request(url, data=data, headers=headers or {}, method=method)
    call.add_unredirected_header("Authorization", f"token {token}")
    with urlopen(call, timeout=120) as response:
        return None if response.status == 204 else json.load(response)


def publish():
    ref = os.environ["GITHUB_REF"]
    if not ref.startswith("refs/tags/release_"):
        raise SystemExit(f"需要 release_ 开头的 tag，收到 {ref}")
    tag = ref.removeprefix("refs/tags/")
    commit = os.environ["GITHUB_SHA"]
    token = os.environ["GITEA_TOKEN"]
    if not token:
        raise SystemExit("Gitea Runner 未提供 GITEA_TOKEN")
    api_url = os.environ["GITEA_RELEASE_API_URL"]
    repository = os.environ["GITHUB_REPOSITORY"]
    releases_url = f"{api_url}/repos/{repository}/releases"
    json_headers = {"Content-Type": "application/json"}

    archives = list(Path("seatunnel-web-dist/target").glob("seatunnel-web-*.tar.gz"))
    if len(archives) != 1 or not archives[0].stat().st_size:
        raise SystemExit("Maven 应生成一个非空的 SeaTunnel Web 发行包")
    archive = archives[0]
    # Git tag 可以包含斜杠等字符，Release 使用原始 tag，附件名使用可移植的字符。
    file_tag = re.sub(r"[^A-Za-z0-9._-]+", "-", tag)[:120]
    archive_name = f"{archive.name.removesuffix('.tar.gz')}-{file_tag}.tar.gz"
    checksum_name = "checksums.sha256"
    release_body = f"由 Gitea Actions 基于提交 {commit} 构建。"

    try:
        release = request(f"{releases_url}/tags/{quote(tag, safe='')}", token)
    except HTTPError as error:
        if error.code != 404:
            raise
        print(f"创建 Release 草稿 {tag}", flush=True)
        body = json.dumps({
            "tag_name": tag,
            "target_commitish": commit,
            "name": f"SeaTunnel Web {tag}",
            "body": release_body,
            "draft": True,
        }).encode()
        release = request(releases_url, token, "POST", body, json_headers)

    assets = release.get("assets") or []
    if not release["draft"]:
        if {archive_name, checksum_name}.issubset(asset["name"] for asset in assets):
            print(f"Release {tag} 已发布并包含所需附件", flush=True)
            return
        raise SystemExit(f"Release {tag} 已发布，但缺少本次构建所需的附件")
    if release["body"] != release_body:
        raise SystemExit(f"Release {tag} 的现有草稿由其他流程创建")

    release_url = f"{releases_url}/{release['id']}"
    assets_url = f"{release_url}/assets"
    digest = hashlib.sha256()
    with archive.open("rb") as package:
        for chunk in iter(lambda: package.read(1024 * 1024), b""):
            digest.update(chunk)
        package.seek(0)
        checksum = f"{digest.hexdigest()}  {archive_name}\n".encode()

        # 重跑时替换本工作流草稿中的附件，确保归档与校验值来自同一次构建。
        for asset in assets:
            if asset["name"] in (archive_name, checksum_name):
                request(f"{assets_url}/{asset['id']}", token, "DELETE")
        for name, data, size in (
            (archive_name, package, archive.stat().st_size),
            (checksum_name, checksum, len(checksum)),
        ):
            print(f"上传 {name}（{size} 字节）", flush=True)
            uploaded = request(f"{assets_url}?{urlencode({'name': name})}", token, "POST", data, {
                "Content-Type": "application/octet-stream",
                "Content-Length": str(size),
            })
            if uploaded["name"] != name or uploaded["size"] != size:
                raise SystemExit(f"Gitea 返回的附件信息与上传文件不一致：{name}")

    published = request(release_url, token, "PATCH", b'{"draft":false}', json_headers)
    if published["draft"]:
        raise SystemExit(f"Release {tag} 仍处于草稿状态")
    print(f"已发布 {published['html_url']}", flush=True)


if __name__ == "__main__":
    try:
        publish()
    except HTTPError as error:
        raise SystemExit(f"Gitea API 请求失败（HTTP {error.code}）：{error.read().decode(errors='replace')}") from error
