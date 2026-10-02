#!/usr/bin/env python3
"""Mirror upstream releases and issues/PRs into this backup fork, append-only.

Contract, same as the git half of the sync: this script only ever ADDS things
to this repository and never edits, closes, reopens, or deletes anything --
not even its own past output. If upstream rewrites or deletes one of its
issues or releases, the already-mirrored copy here stays exactly as it was.

Shape of the mirror:

  * Releases -> real GitHub releases on this fork (same tag, title, notes,
    asset files). The tags themselves arrive via the git sync.
  * Issues   -> one read-only archive issue per upstream issue, titled with
    a [mirrored] prefix, body carrying the original author, text, state, and
    a link back to the source. Comments are folded into the same body so each
    archive is one self-contained object. Pull requests are archived the same
    way, as plain issues (PRs opened on this fork pointing at it would be the
    wrong shape for an archive).
  * Labels   -> created on this fork when first needed, so mirrored issues
    can wear their original labels.

Once an archive exists, upstream's later behaviour is recorded rather than
followed. Nothing already written is ever rewritten; instead a new comment is
added to the archive issue:

  * "closed" / "reopened" / state changes of any kind
  * title edits and body edits (the new text is quoted in the comment)
  * comments upstream added after the archive was taken (quoted in full, so the thread keeps
    growing here even though the archived body only ever shows it as far as it ran)
  * deletion, or a transfer that removes the issue from upstream's listing
  * a release deleted upstream, or a release asset withdrawn

That is the point of this repository: an upstream that quietly closes an
uncomfortable issue, rewrites what it said, or deletes the thread leaves a
trail here, next to the copy of what it said before.

Two things are deliberately not chased: reactions and review-thread state
(kept out so each archive stays a single readable page), and changes to a
comment's own text after it was written. A new comment is recorded; an edit of
an old one, or an old one deleted, moves the issue's updated_at and leaves
nothing else behind, so it shows up only as a watermark advancing.

Watermarks for the change detection live in state.json on the mirror-state
branch, not in this repository's history. A run that finds nothing new writes
nothing at all.

Limit: GitHub caps issue bodies at 65536 characters. An upstream issue with
a very long comment thread would fail to archive; the run reports it and
carries on (each archive attempt is independent).
"""

import base64
import datetime
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM_REPO = os.environ.get("UPSTREAM_REPO", "Ray-T-r/Minecraft-Forbric-mod-loader")
FORK_REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ["GH_TOKEN"]

MARKER = "<!-- forbric-backup-mirror"
EVENT = "<!-- forbric-backup-event"
MIRROR_TAG = "[mirrored]"
EVENTS_TITLE = MIRROR_TAG + " upstream metadata events"

STATE_BRANCH = "mirror-state"
STATE_PATH = "state.json"

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


# ------------------------------------------------- watermarks for change detection

def load_state():
    """Read state.json from the mirror-state branch. Missing branch or file = {}."""
    blob = api("/repos/%s/contents/%s" % (FORK_REPO, STATE_PATH), {"ref": STATE_BRANCH})
    if not blob:
        return {}
    try:
        return json.loads(base64.b64decode(blob["content"]).decode())
    except Exception as e:
        print("::warning::state.json on %s is unreadable (%s); starting from what the "
              "archive itself already shows" % (STATE_BRANCH, e))
        return {}


def save_state(state):
    """Write state.json back on the mirror-state branch, creating the branch if needed."""
    existing = api("/repos/%s/contents/%s" % (FORK_REPO, STATE_PATH), {"ref": STATE_BRANCH})
    payload = {
        "message": "Mirror watermarks after the run at %s" % stamp(),
        "content": base64.b64encode(
            json.dumps(state, indent=1, sort_keys=True).encode()).decode(),
        "branch": STATE_BRANCH,
    }
    if existing:
        payload["sha"] = existing["sha"]
    elif not api("/repos/%s/git/ref/heads/%s" % (FORK_REPO, STATE_BRANCH)):
        # First run: branch the state off the default branch, then drop the file in.
        default = api("/repos/%s" % FORK_REPO)["default_branch"]
        head = api("/repos/%s/git/ref/heads/%s" % (FORK_REPO, default))
        api("/repos/%s/git/refs" % FORK_REPO, method="POST",
            body={"ref": "refs/heads/" + STATE_BRANCH, "sha": head["object"]["sha"]})
    api("/repos/%s/contents/%s" % (FORK_REPO, STATE_PATH), method="PUT", body=payload)


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def digest(text):
    return hashlib.sha1((text or "").encode()).hexdigest()[:12]


def fingerprint(item):
    return {
        "state": item.get("state"),
        "title": item.get("title"),
        "body": digest(item.get("body")),
        "updated_at": item.get("updated_at"),
    }


def changes_between(old, new):
    """What upstream did to an item we already archived, in words."""
    out = []
    if old.get("state") != new.get("state"):
        out.append("state changed: %s -> %s" % (old.get("state"), new.get("state")))
    if old.get("title") != new.get("title"):
        out.append("title edited\n\n  was: %s\n  now: %s"
                   % (old.get("title"), new.get("title")))
    if old.get("body") != new.get("body"):
        out.append("body edited; the text upstream shows now is quoted below")
    return out


# The way this script writes a comment into an archive body. Used to read an archive back and
# find how far its thread runs, for archives taken before comments were tracked.
COMMENT_STAMP = re.compile(r"\*\*@[^*]+\*\* commented on (\S+):")


def archived_comment_through(mirror_number):
    """The newest comment timestamp already rendered into an archived body, or "" when that
    archive holds no comments at all. None when the body could not be read -- the caller then
    leaves the watermark alone rather than guess a baseline and re-post comments already
    visible above."""
    issue = api("/repos/%s/issues/%d" % (FORK_REPO, mirror_number))
    if issue is None:
        return None
    stamps = COMMENT_STAMP.findall(issue.get("body") or "")
    return max(stamps) if stamps else ""


# ------------------------------------------------- what the archive already holds

def existing_mirrors():
    """Scan this fork's issues for mirror markers -> {upstream number: fork issue number}."""
    seen = {}
    for issue in paged("/repos/%s/issues" % FORK_REPO, {"state": "all"}):
        body = issue.get("body") or ""
        if MARKER in body and issue["title"].startswith(MIRROR_TAG):
            for line in body.splitlines():
                if line.startswith("Upstream number: "):
                    seen[int(line.rsplit(" ", 1)[-1])] = issue["number"]
                    break
    return seen


def existing_release_tags():
    return {r["tag_name"]: r for r in paged("/repos/%s/releases" % FORK_REPO)}


def events_issue():
    """The one issue that collects events with no archive of their own (releases)."""
    for issue in paged("/repos/%s/issues" % FORK_REPO, {"state": "all"}):
        if issue["title"] == EVENTS_TITLE:
            return issue["number"]
    created = api("/repos/%s/issues" % FORK_REPO, method="POST", body={
        "title": EVENTS_TITLE,
        "body": "%s events %s -->\n\nAppend-only log of things that happened upstream to "
                "content this fork mirrors outside of a single issue: a release deleted, an "
                "asset withdrawn. New entries are comments; nothing here is ever rewritten.\n"
                % (MARKER, UPSTREAM_REPO),
    })
    print("  opened the upstream events issue #%d" % created["number"])
    return created["number"]


def post_event(issue_number, lines):
    """Append one event to an archive issue. Never edits what is already there."""
    body = "%s at %s -->\n\n%s" % (EVENT, stamp(), "\n\n".join(lines))
    api("/repos/%s/issues/%d/comments" % (FORK_REPO, issue_number), method="POST",
        body={"body": body})
    print("    recorded an upstream change on #%d" % issue_number)


# ------------------------------------------------- writers

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


def upstream_tag_commit(tag):
    """The commit an upstream tag names, peeling annotated tags. None if it is not a tag of
    commits, or upstream will not say."""
    ref = api("/repos/%s/git/ref/tags/%s"
              % (UPSTREAM_REPO, urllib.parse.quote(tag, safe="")))
    obj = (ref or {}).get("object") or {}
    # A tag of a tag of a commit is legal, so peel in a loop -- bounded, so a cycle upstream
    # somehow managed to create cannot hang the run forever.
    for _ in range(5):
        if obj.get("type") == "commit":
            return obj.get("sha")
        if obj.get("type") != "tag":
            return None
        parent = api("/repos/%s/git/tags/%s" % (UPSTREAM_REPO, obj.get("sha")))
        obj = (parent or {}).get("object") or {}
    return None


def mirror_release(rel):
    """Re-publish one upstream release on this fork, assets included."""
    tag = rel["tag_name"]
    body = (rel.get("body") or "") + (
        "\n\n---\n%s release %d %s -->\n"
        "Mirrored from [upstream release](https://github.com/%s/releases/tag/%s). "
        "Assets re-uploaded verbatim."
        % (MARKER, rel["id"], UPSTREAM_REPO, UPSTREAM_REPO,
           urllib.parse.quote(tag, safe="")))
    payload = {
        "tag_name": tag,
        "name": rel.get("name") or tag,
        "body": body,
        "draft": False,
        "prerelease": bool(rel.get("prerelease")),
    }
    # Creating a release without this makes GitHub mint the tag at whatever this fork's
    # default branch happens to point at that minute: a tag carrying an upstream release's
    # name, marking a commit with nothing to do with that release. All four of the first
    # mirrored releases came out that way. Named only when this fork actually has the commit,
    # since GitHub rejects a target it cannot see, and a release with a merely wrong tag beats
    # no release at all.
    target = upstream_tag_commit(tag)
    if target and api("/repos/%s/git/commit/%s" % (FORK_REPO, target)) is not None:
        payload["target_commitish"] = target
    created = api("/repos/%s/releases" % FORK_REPO, method="POST", body=payload)
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


# ------------------------------------------------- main

def main():
    # A 404 from the repo endpoint means upstream is gone, renamed or private. Say that once
    # instead of the misleading "0 issues, 0 releases, nothing new to mirror" the empty
    # listings below would otherwise print. The git job is where the alert issue is raised;
    # this job only stays quiet and honest.
    if api("/repos/%s" % UPSTREAM_REPO) is None:
        print("::warning::upstream %s is not visible; nothing to mirror" % UPSTREAM_REPO)
        return 0

    # The issues endpoint returns PRs too; both get archived. Pull requests are ALSO
    # fetched from the pulls endpoint and merged in: the issues listing has, more than
    # once, omitted PRs for some tokens/edge caches, and a backup prefers two sources
    # over one.
    raw_issues = list(paged("/repos/%s/issues" % UPSTREAM_REPO, {"state": "all"}))
    pulls = list(paged("/repos/%s/pulls" % UPSTREAM_REPO, {"state": "all"}))
    upstream_releases = list(paged("/repos/%s/releases" % UPSTREAM_REPO))

    by_number = {i["number"]: i for i in raw_issues}
    for pr in pulls:
        if pr["number"] not in by_number:
            # The pulls payload carries the same issue-facing fields (title, body, user,
            # labels, state, timestamps) that the archive renders.
            by_number[pr["number"]] = pr
    upstream_issues = [by_number[n] for n in sorted(by_number)]
    print("sources: issues endpoint %d, pulls endpoint %d, merged %d"
          % (len(raw_issues), len(pulls), len(upstream_issues)))

    state = load_state()
    known = state.setdefault("issues", {})
    rel_state = state.setdefault("releases", {})
    mirrored = existing_mirrors()
    tags_done = existing_release_tags()
    dirty = False

    # A watermark can be missing for something that is already archived: the first run after
    # this script learned to keep watermarks, or a state branch that was lost. Adopt what
    # upstream currently shows as the watermark rather than archiving it a second time -- a
    # duplicate is not a backup, and the archive issue itself already records that the item
    # was mirrored. Nothing is reported as a change here: there is no earlier watermark to
    # have changed from, and inventing one would put a false event in the log.
    for issue in upstream_issues:
        n = str(issue["number"])
        if issue["number"] in mirrored and n not in known:
            print("adopting the archive of upstream #%d as its watermark" % issue["number"])
            known[n] = fingerprint(issue)
            dirty = True
    for rel in upstream_releases:
        if rel["tag_name"] in tags_done and rel["tag_name"] not in rel_state:
            rel_state[rel["tag_name"]] = {
                "assets": sorted(a["name"] for a in rel.get("assets", [])),
                "updated_at": rel.get("updated_at")}
            dirty = True
    for number in sorted(mirrored):
        n = str(number)
        if n in known or number in by_number:
            continue
        # Archived, but upstream does not list it and no watermark exists: gone before the
        # watermarks started. Say so once, without pretending to know when.
        if api("/repos/%s/issues/%d" % (UPSTREAM_REPO, number)) is None:
            post_event(mirrored[number], [
                "**Upstream no longer serves this item: deleted, or moved somewhere a plain "
                "lookup cannot follow.**",
                "The copy above is the last snapshot taken while it still existed. "
                "(Noted after this fork began keeping watermarks, so the date it went is "
                "unknown.)",
            ])
            known[n] = {"state": None, "title": None, "body": None, "updated_at": None,
                        "deleted": True}
            dirty = True

    # ---- new issues, and any archive that has gone missing from this fork
    for issue in upstream_issues:
        n = str(issue["number"])
        if n in known:
            if issue["number"] in mirrored:
                continue
            # The archive issue itself is gone from this fork. Re-archive rather than keep a
            # watermark pointing at nothing.
            print("archive for upstream #%d is gone from this fork; re-archiving" % issue["number"])
        print("mirroring issue #%d" % issue["number"])
        comments = list(paged("/repos/%s/issues/%d/comments"
                              % (UPSTREAM_REPO, issue["number"])))
        try:
            mirror_issue(issue, comments)
        except Exception as e:
            # Do not record a watermark for something that was not archived.
            print("::warning::issue #%d failed: %s" % (issue["number"], e))
            continue
        known[n] = fingerprint(issue)
        # The body just written carries the thread as far as it runs today, so this is where a
        # later run starts recording comments from.
        known[n]["comments_through"] = max((c["created_at"] for c in comments), default="")
        dirty = True

    # ---- what upstream did to what we already archived
    for n_str, old in sorted(known.items(), key=lambda kv: int(kv[0])):
        number = int(n_str)
        mirror_number = mirrored.get(number)
        if mirror_number is None:
            continue
        now = by_number.get(number)
        if now is None:
            # Gone from the listing. Confirm directly before calling it deleted: the listing
            # has been seen to drop items for some tokens, and a false "deleted" here would be
            # exactly the kind of lie this archive exists to avoid.
            if api("/repos/%s/issues/%d" % (UPSTREAM_REPO, number)) is None and \
               not (old.get("deleted")):
                archive_url = "https://github.com/%s/issues/%d" % (UPSTREAM_REPO, number)
                post_event(mirror_number, [
                    "**Upstream no longer serves this item: deleted, or moved somewhere a "
                    "plain lookup cannot follow.**",
                    "The copy above is the last snapshot taken while it still existed. "
                    "Source: %s" % archive_url,
                ])
                old["deleted"] = True
                dirty = True
            continue
        if now.get("updated_at") == old.get("updated_at"):
            continue
        new = fingerprint(now)
        described = changes_between(old, new)

        # Comments upstream posted after this fork archived the item. The archived body shows
        # the thread only as far as it ran when the archive was taken, and nothing else here
        # would ever hold the rest of it. Only items whose updated_at moved are asked for their
        # comments, which is every item a comment could have been added to.
        through = old.get("comments_through")
        if through is None:
            through = archived_comment_through(mirror_number)
            if through is None:
                continue  # unreadable archive body; leave the watermark and retry next run
            new["comments_through"] = through
            dirty = True
        thread = list(paged("/repos/%s/issues/%d/comments" % (UPSTREAM_REPO, number)))
        added = [c for c in thread if c["created_at"] > through]
        newest = max((c["created_at"] for c in thread), default="")
        if newest > through:
            new["comments_through"] = newest
            dirty = True

        if described or added:
            lines = []
            if described:
                lines += ["**Upstream changed this item after it was archived** "
                          "(%s):" % now.get("updated_at"), "- " + "\n- ".join(described)]
                if old.get("body") != new.get("body"):
                    lines += ["", "Upstream's current text:", "",
                              "````", now.get("body") or "_(no body)_", "````"]
            if added:
                lines += ["", "**%d comment%s upstream added after this fork archived the "
                          "item**, quoted in full:" % (len(added),
                                                       "" if len(added) == 1 else "s")]
                for comment in added:
                    lines += ["", "---", "**@%s** commented on %s:"
                              % (comment["user"]["login"], comment["created_at"]), "",
                              "````", comment["body"] or "_(no body)_", "````"]
            try:
                post_event(mirror_number, lines)
            except Exception as e:
                print("::warning::could not record the change to #%d: %s" % (number, e))
                continue
        known[n_str] = new
        dirty = True

    # ---- releases: mirrored already, but upstream can withdraw them
    upstream_tags = {r["tag_name"]: r for r in upstream_releases}
    for rel in upstream_releases:
        tag = rel["tag_name"]
        assets = sorted(a["name"] for a in rel.get("assets", []))
        now_fp = {"assets": assets, "updated_at": rel.get("updated_at")}
        if tag in tags_done:
            # Asset withdrawal is the quiet way to unpublish a download.
            had = rel_state.get(tag, {}).get("assets")
            if had is not None and set(had) != set(assets):
                events = events_issue()
                post_event(events, [
                    "**Assets changed on release `%s`**" % tag,
                    "was: %s" % (", ".join(had) or "none"),
                    "now: %s" % (", ".join(assets) or "none"),
                    "The fork's copy of the release still holds what it was given.",
                ])
            if rel_state.get(tag) != now_fp:
                rel_state[tag] = now_fp
                dirty = True
        else:
            print("mirroring release %s" % tag)
            try:
                mirror_release(rel)
            except Exception as e:
                print("::warning::release %s failed: %s" % (tag, e))
                continue
            rel_state[tag] = now_fp
            dirty = True

    for tag in list(rel_state):
        if tag not in upstream_tags and not rel_state[tag].get("deleted"):
            events = events_issue()
            post_event(events, [
                "**Upstream release `%s` is gone** (deleted, or turned back into a draft)."
                % tag,
                "This fork keeps its own copy of that release and its assets.",
            ])
            rel_state[tag]["deleted"] = True
            dirty = True

    if dirty:
        save_state(state)
    else:
        print("nothing new to mirror")
    return 0


if __name__ == "__main__":
    sys.exit(main())
