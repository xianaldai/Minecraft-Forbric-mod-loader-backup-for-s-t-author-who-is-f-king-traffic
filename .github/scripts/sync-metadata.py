#!/usr/bin/env python3
"""Mirror upstream releases and issues/PRs into this backup fork, append-only.

Contract, same as the git half of the sync: this script only ever ADDS things
to this repository and never edits, closes, reopens, or deletes anything --
not even its own past output. If upstream rewrites or deletes one of its
issues or releases, the already-mirrored copy here stays exactly as it was.

Shape of the mirror:

  * Releases -> real GitHub releases on this fork (same tag, title, notes,
    asset files). The tags themselves arrive via the git sync. An upstream
    edit to release notes after mirroring is NOT picked up: append-only
    beats fresher-notes.
  * Issues   -> one read-only archive issue per upstream issue, titled with
    a [mirrored] prefix, body carrying the original author, text, state, and
    a link back to the source. Comments are folded into the same body so each
    archive is one self-contained object. Pull requests are archived the same
    way, as plain issues (PRs opened on this fork pointing at it would be the
    wrong shape for an archive).
  * Labels   -> created on this fork when first needed, so mirrored issues
    can wear their original labels.

State lives in the archive itself: before writing anything we list this
repo's issues and releases looking for the mirror marker; whatever is already
there tells us what upstream objects have been mirrored. No upstream changes
and everything already mirrored makes a run a no-op.

Things upstream can do that this deliberately does not chase:
  * bodies and comments edited after the mirroring run (the archive keeps
    the text as first seen)
  * issues deleted upstream (our copy survives; GitHub itself makes deleted
    ones unreachable)
  * reactions, review threads, cross-reference events (kept out on purpose,
    so each archive stays a single readable page)

Limit: GitHub caps issue bodies at 65536 characters. An upstream issue with
a very long comment thread would fail to archive; the run reports it and
carries on (each archive attempt is independent).
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM_REPO = os.environ.get("UPSTREAM_REPO", "Ray-T-r/Minecraft-Forbric-mod-loader")
FORK_REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ["GH_TOKEN"]

MARKER = "<!-- forbric-backup-mirror"
MIRROR_TAG = "[mirrored]"

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def api(path, params=None, method="GET", body=None):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            payload = resp.read()
    except urllib.error.HTTPError as e:
        # 404 and 410 mean gone or never existed -- expected shapes of the
        # world for a backup script, not crashes.
        if e.code in (404, 410):
            return None
        print("::error::GitHub API %s %s -> HTTP %d: %s"
              % (method, path, e.code, e.read().decode(errors="replace")[:500]))
        raise
    return json.loads(payload) if payload else None


def paged(path, params=None):
    params = dict(params or {})
    params.setdefault("per_page", "100")
    page = 1
    while True:
        params["page"] = str(page)
        batch = api(path, params)
        if not batch:
            return
        yield from batch
        if len(batch) < 100:
            return
        page += 1


def existing_mirrors():
    """The archive is the database: scan this fork's issues for mirror markers."""
    seen = set()
    for issue in paged("/repos/%s/issues" % FORK_REPO, {"state": "all"}):
        body = issue.get("body") or ""
        if MARKER in body and issue["title"].startswith(MIRROR_TAG):
            for line in body.splitlines():
                if line.startswith("Upstream number: "):
                    seen.add(int(line.rsplit(" ", 1)[-1]))
                    break
    return seen


def existing_release_tags():
    return {r["tag_name"] for r in paged("/repos/%s/releases" % FORK_REPO)}


def create_issue(title, body, labels):
    created = api("/repos/%s/issues" % FORK_REPO, method="POST",
                  body={"title": title, "body": body, "labels": labels})
    print("  archived issue #%d: %s" % (created["number"], title))
    return created


def ensure_labels(names):
    """Create on this fork any label a mirrored issue wears. Existing labels
    are never updated even if upstream changed them -- append-only."""
    have = {l["name"] for l in paged("/repos/%s/labels" % FORK_REPO)}
    for name in names:
        if name not in have:
            src = api("/repos/%s/labels/%s"
                      % (UPSTREAM_REPO, urllib.parse.quote(name, safe="")))
            if src:
                api("/repos/%s/labels" % FORK_REPO, method="POST",
                    body={"name": name, "color": src["color"],
                          "description": src.get("description") or ""})
                have.add(name)


def mirror_issue(issue, comments):
    n = issue["number"]
    is_pr = bool(issue.get("pull_request"))
    kind = "Pull request" if is_pr else "Issue"
    closed_note = ""
    if issue.get("closed_at"):
        closed_note = "\nclosed upstream at %s" % issue["closed_at"]

    src_url = "https://github.com/%s/%s/%d" % (
        UPSTREAM_REPO, "pull" if is_pr else "issues", n)
    lines = [
        "%s issue %d %s -->" % (MARKER, n, UPSTREAM_REPO),
        "Upstream number: %d" % n,
        "",
        "> %s **#%d** from [%s](%s)" % (kind, n, UPSTREAM_REPO, src_url),
        "> Author: @%s - opened %s - state: **%s**%s"
        % (issue["user"]["login"], issue["created_at"], issue["state"], closed_note),
        "",
        "## " + (issue["title"] or ""),
        "",
        issue["body"] or "_(no body)_",
    ]
    for c in comments:
        lines += [
            "",
            "---",
            "**@%s** commented on %s:" % (c["user"]["login"], c["created_at"]),
            "",
            c["body"] or "_(no body)_",
        ]
    if is_pr:
        pr = api("/repos/%s/pulls/%d" % (UPSTREAM_REPO, n))
        if pr:
            tick = chr(96)
            lines += [
                "",
                "---",
                "PR metadata: base %s%s%s <- head %s%s%s - merged: %s"
                % (tick, pr["base"]["ref"], tick, tick, pr["head"]["ref"], tick,
                   pr.get("merged_at") or "no"),
            ]

    title = "%s upstream #%d: %s" % (MIRROR_TAG, n, issue["title"] or "")
    labels = [l["name"] for l in issue.get("labels", [])]
    ensure_labels(labels)
    return create_issue(title, "\n".join(lines), labels)


def mirror_release(rel):
    """Re-publish one upstream release on this fork, assets included."""
    tag = rel["tag_name"]
    body = (rel.get("body") or "") + (
        "\n\n---\n%s release %d %s -->\n"
        "Mirrored from [upstream release](https://github.com/%s/releases/tag/%s). "
        "Assets re-uploaded verbatim."
        % (MARKER, rel["id"], UPSTREAM_REPO, UPSTREAM_REPO,
           urllib.parse.quote(tag, safe="")))
    created = api("/repos/%s/releases" % FORK_REPO, method="POST", body={
        "tag_name": tag,
        "name": rel.get("name") or tag,
        "body": body,
        "draft": False,
        "prerelease": bool(rel.get("prerelease")),
    })
    print("  mirrored release %s -> fork release id %d" % (tag, created["id"]))
    for asset in rel.get("assets", []):
        req = urllib.request.Request(asset["url"])
        req.add_header("Authorization", "Bearer " + TOKEN)
        req.add_header("Accept", "application/octet-stream")
        with urllib.request.urlopen(req) as resp:
            data = resp.read()
        up = urllib.request.Request(
            "%s/repos/%s/releases/%d/assets?name=%s"
            % (UPLOADS, FORK_REPO, created["id"],
               urllib.parse.quote(asset["name"], safe="")),
            data=data, method="POST")
        up.add_header("Authorization", "Bearer " + TOKEN)
        up.add_header("Content-Type", "application/octet-stream")
        with urllib.request.urlopen(up) as resp:
            out = json.loads(resp.read())
        print("    asset %s (%d bytes -> %d bytes)"
              % (asset["name"], asset["size"], out["size"]))
    return created


def main():
    # The issues endpoint returns PRs too; both get archived. Pull requests are ALSO
    # fetched from the pulls endpoint and merged in: the issues listing has, more than
    # once, omitted PRs for some tokens/edge caches, and a backup prefers two sources
    # over one.
    upstream_issues = list(paged("/repos/%s/issues" % UPSTREAM_REPO, {"state": "all"}))
    pulls = list(paged("/repos/%s/pulls" % UPSTREAM_REPO, {"state": "all"}))
    upstream_releases = list(paged("/repos/%s/releases" % UPSTREAM_REPO))

    by_number = {i["number"]: i for i in upstream_issues}
    for pr in pulls:
        if pr["number"] not in by_number:
            # The pulls payload carries the same issue-facing fields (title, body,
            # user, labels, state, timestamps) the archive renders.
            by_number[pr["number"]] = pr
    upstream_issues = [by_number[n] for n in sorted(by_number)]

    print("sources: issues endpoint %d, pulls endpoint %d, merged %d"
          % (len(upstream_issues) - len(pulls) if len(pulls) <= len(upstream_issues) else len(upstream_issues),
             len(pulls), len(upstream_issues)))

    done = existing_mirrors()
    tags_done = existing_release_tags()

    new_issues = [i for i in upstream_issues if i["number"] not in done]
    new_rels = [r for r in upstream_releases if r["tag_name"] not in tags_done]

    print("upstream: %d issues/PRs, %d releases; fork already mirrors %d issues"
          " and %d release tags"
          % (len(upstream_issues), len(upstream_releases), len(done), len(tags_done)))

    for rel in new_rels:
        print("mirroring release %s" % rel["tag_name"])
        try:
            mirror_release(rel)
        except Exception as e:
            print("::warning::release %s failed: %s" % (rel["tag_name"], e))

    for issue in sorted(new_issues, key=lambda i: i["number"]):
        print("mirroring issue #%d" % issue["number"])
        comments = list(paged("/repos/%s/issues/%d/comments"
                              % (UPSTREAM_REPO, issue["number"])))
        try:
            mirror_issue(issue, comments)
        except Exception as e:
            print("::warning::issue #%d failed: %s" % (issue["number"], e))

    if not new_issues and not new_rels:
        print("nothing new to mirror")
    return 0


if __name__ == "__main__":
    sys.exit(main())
