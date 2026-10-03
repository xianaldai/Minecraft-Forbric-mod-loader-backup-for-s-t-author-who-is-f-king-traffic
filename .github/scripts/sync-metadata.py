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
  * a comment upstream edited afterwards (the new text is quoted; the text from before the
    edit is the copy already sitting above), or one it deleted (noted with the author and
    time it was posted, so the copy here stands as the last text known to exist)
  * a comment a moderator collapsed -- readers get a stub where the text was -- or one shown
    again after that. Hiding changes what upstream shows without changing a word of it, so it
    is read from GitHub's own field for it rather than from any text
  * deletion, or a transfer that removes the issue from upstream's listing. An item the
    listing drops while upstream still serves it is neither of those and is not treated as
    one: it is read directly and compared like any other, so an omission in a listing -- the
    runner's listings have omitted two pull requests on every run so far -- cannot freeze an
    item with its closure and its later comments unrecorded
  * a gap in upstream's numbering -- an item that came and went between two runs, so that
    nothing here ever held a copy of it or a watermark to notice it by. The number itself is
    all there is to rebuild, and it gets an entry of its own saying so
  * a release deleted upstream, or a release asset withdrawn

That is the point of this repository: an upstream that quietly closes an
uncomfortable issue, rewrites what it said, or deletes the thread leaves a
trail here, next to the copy of what it said before.

Two things are deliberately not chased: reactions, which are counted elsewhere and say
nothing about what upstream showed, and review-thread state, kept out so each archive stays a
single readable page. A collapsed comment is not one of those: it is the text on the page
still, only hidden, which is precisely the kind of quiet change this repository exists to
notice.

An edit, a deletion and a collapse all move the item's updated_at, just as a new comment
does, so a run asks about a thread only when something actually happened to the item. The
exception is a watermark written before one of these was tracked: it holds no record of what
its thread said, or of which comments were hidden, so it is read once to learn that, and never
again. A collapse found on that first read is the one thing here reported as found rather than
as done -- the record it is being compared against cannot say whether the hiding is new, so it
says plainly that those comments were already collapsed when this fork began looking.

Watermarks for the change detection live in state.json on the mirror-state branch, not in this
repository's history. A run that finds nothing new writes nothing at all.

Limit: GitHub caps an issue body and a comment body at 65536 characters. An
event that outgrows one comment is posted as several, in order, each saying
which part it is, and a single quoted comment bigger than the whole limit is
cut with the cut marked in the archiver's own words -- so nothing is dropped
for being long. What that still leaves is an upstream issue whose body and
comments together exceed the limit on the day it is first archived: that one
fails to archive, the run says so, and later runs keep trying.
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

# How many requests this run made, and what the token has left. Both are printed at the end:
# with the sync now asked for whenever upstream changes, how much a run costs is the number
# that decides whether that is sustainable, and a token that runs out answers with failures.
CALLS = 0


def api(path, params=None, method="GET", body=None):
    global CALLS
    CALLS += 1
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

def comment_author(comment):
    """Upstream serves user: null once an account is deleted, so a login is never assumed."""
    return ((comment.get("user") or {}).get("login")) or "ghost"


def hidden_reason(comment):
    """Why a moderator collapsed this comment, or None when it reads as posted. GitHub serves
    {"reason": "off-topic"} (or "spam", "abuse", "outdated", ...) on a collapsed comment and
    null on every other, so the field itself says which of the two a comment is."""
    return (comment.get("minimized") or {}).get("reason")


def hidden_entry(comment):
    """One event line naming a comment upstream has collapsed, the reason, and when upstream
    last touched it -- that timestamp being the only evidence of when the hiding happened,
    since a collapse leaves the text itself untouched."""
    return ("- **@%s** commented on %s -- hidden as %s (upstream's last move on this comment: "
            "%s)" % (comment_author(comment), comment["created_at"],
                     hidden_reason(comment), comment["updated_at"]))


def comment_map(thread):
    """What this fork knows about each comment it has seen, so a later run can tell an edit
    from an addition -- and, when one disappears or is hidden, still say who posted it, when,
    and what became of it. updated_at is kept for the hidden case alone: a collapse leaves the
    text identical, so that timestamp is the only evidence of when it happened."""
    return {str(c["id"]): {"d": digest(c["body"]), "a": comment_author(c),
                           "t": c["created_at"], "m": hidden_reason(c),
                           "u": c["updated_at"]} for c in thread}


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


def cut_marker(count):
    """The archiver's own words, and marked as such, where a quoted comment is cut in two."""
    return ("**(quoted text cut here; it continues in the next part%s)**"
            % (", %d more" % count if count else ""))


def split_for_limit(lines, limit):
    """Group event lines into comment-sized chunks, cutting a line that is too big on its own.

    GitHub refuses a comment body over 65536 characters, and thirty upstream comments quoted in
    full blow past that easily. Before this, the post failed whole: nothing was recorded, the
    watermark did not move, and every later run failed the same way on the same thread. A cut
    line closes its code fence, says so in the archiver's own voice, and reopens the fence, so
    the text is all still there in order, inside fences that still balance.
    """
    chunks = []
    current = []
    size = 0
    for line in lines:
        # The blank line and the "\n\n" join are part of the size; approximate with slack, and
        # leave room for the cut marker and fences so a cut line's pieces stay in one chunk
        # instead of pushing each piece into a comment of its own.
        budget = limit - 400
        if len(line) > budget:
            piece_count = (len(line) + budget - 1) // budget
            pieces = [line[i * budget:(i + 1) * budget] for i in range(piece_count)]
            expanded = []
            for index, piece in enumerate(pieces):
                expanded.append(piece)
                if index + 1 < len(pieces):
                    expanded += ["", "````", cut_marker(piece_count - index - 1), "````"]
            line = None
            todo = expanded
        else:
            todo = [line]
        for item in todo:
            if current and size + len(item) + 2 > limit:
                chunks.append(current)
                current = []
                size = 0
            current.append(item)
            size += len(item) + 2
    if current:
        chunks.append(current)
    return chunks


def post_event(issue_number, lines):
    """Append one event to an archive issue. Never edits what is already there.

    An event too big for one comment arrives as several, in order, each saying which part it is.
    """
    # Room left for the marker line, the date, and the part header.
    chunks = split_for_limit(lines, 60000)
    for index, chunk in enumerate(chunks, 1):
        head = ""
        if len(chunks) > 1:
            head = "**(part %d of %d)**\n\n" % (index, len(chunks))
        body = "%s at %s -->\n\n%s%s" % (EVENT, stamp(), head, "\n\n".join(chunk))
        api("/repos/%s/issues/%d/comments" % (FORK_REPO, issue_number), method="POST",
            body={"body": body})
    print("    recorded an upstream change on #%d%s"
          % (issue_number, " (%d comments)" % len(chunks) if len(chunks) > 1 else ""))


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
        ]
        # Said here as well as in later events, so a reader of this page is never left thinking
        # a collapsed comment came from upstream that way. The stamp line above is untouched:
        # archived_comment_through reads these stamps back to find how far a thread ran.
        if hidden_reason(c):
            lines += ["", "_(collapsed upstream as %s: readers see a stub until they expand "
                      "it)_" % hidden_reason(c)]
        lines += ["", c["body"] or "_(no body)_"]
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

    # ---- numbers upstream has retired
    # Issues and pull requests share one counter per repository, and GitHub never reuses a
    # number: deleting an item, or transferring it away, retires its number and later listings
    # simply skip it. So a hole below the highest number is evidence that something was there
    # and is not served any more. Every other check in this file starts from a watermark, which
    # means the item that matters most here -- one that appeared and vanished between two runs,
    # leaving this fork nothing to have a watermark for -- used to leave no trace at all. A
    # hole with no watermark is exactly that item.
    highest = max(by_number, default=0)
    unrecorded = 0
    for number in range(1, highest + 1):
        n = str(number)
        if number in by_number or n in known:
            continue
        # A short page is not proof of a deletion, the same way it is not proof for an item this
        # fork already holds: ask for the thing itself, in both the shapes it could have had, and
        # believe the hole only when every lookup comes back empty.
        if api("/repos/%s/issues/%d" % (UPSTREAM_REPO, number)) is not None:
            continue
        if api("/repos/%s/pulls/%d" % (UPSTREAM_REPO, number)) is not None:
            continue
        if unrecorded >= 25:
            # A bulk deletion is not worth 400 API calls in one run; the rest are still holes
            # next run, when this loop starts over from the same place.
            print("::warning::more than 25 retired numbers to record; the rest wait for the "
                  "next run")
            break
        unrecorded += 1
        lower = [k for k in by_number if k < number]
        higher = [k for k in by_number if k > number]
        if lower and higher:
            neighbours = "between upstream #%d and #%d, which this fork does hold" \
                % (max(lower), min(higher))
        else:
            neighbours = "at the edge of upstream's numbering"
        print("upstream #%d was retired before this fork could read it" % number)
        create_issue(
            "%s upstream #%d: retired before this fork could read it" % (MIRROR_TAG, number),
            "\n".join([
                MARKER,
                "Upstream number: %d" % number,
                "-->",
                "",
                "> **There is nothing to read here.** Upstream's numbering says something was, "
                "and this fork never saw it while it was alive.",
                "",
                "Issues and pull requests in a repository share one counter, and GitHub never "
                "reuses a number: deleting an item, or transferring it to another repository, "
                "retires its number and later listings skip it. This run read upstream's issues "
                "and pull requests as far as #%d, and #%d is in neither. Asking for it directly "
                "answers 404 as an issue and as a pull request alike, so it existed and it is "
                "gone." % (highest, number),
                "",
                "No title, body or comment could be kept: it appeared and was retired between "
                "two syncs, and the sync before this one had nothing to compare against. All "
                "that survives is the fact of it, %s." % neighbours,
                "",
                "Source: https://github.com/%s/issues/%d (404 at the time of writing)"
                % (UPSTREAM_REPO, number),
                "",
                "Detected %s." % stamp(),
            ]),
            [])
        known[n] = {"state": None, "title": None, "body": None, "updated_at": None,
                    "deleted": True, "never_seen": True}
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
        # ...and what each of those comments said, so an edit or a deletion after today is
        # visible as a difference rather than lost.
        known[n]["comments"] = comment_map(comments)
        dirty = True

    # ---- what upstream did to what we already archived
    for n_str, old in sorted(known.items(), key=lambda kv: int(kv[0])):
        number = int(n_str)
        mirror_number = mirrored.get(number)
        if mirror_number is None:
            continue
        now = by_number.get(number)
        if now is None:
            if old.get("deleted"):
                # Recorded on an earlier run. There is nothing left to confirm, and one lookup
                # per already-known deletion adds up on a sync that runs often.
                continue
            # Gone from the listing, which is not the same as gone from upstream. Upstream
            # number 36 and 37, both pull requests whose pull-side record answers 404 while
            # their issue-side record is served as normal, appear in neither of the runner's
            # listings -- they are in the pulls listing only for other tokens, and the runner's
            # issues listing leaves PRs out. Ask for the item itself: a 404 is the real thing
            # and is recorded as such below, anything else IS the item, and this run carries on
            # with it exactly as if the listing had handed it over. Stopping at "it exists"
            # instead would freeze such an item for good -- its closure, its edits and every
            # comment after all of them would go unrecorded, which is the one failure this
            # repository exists to prevent.
            now = api("/repos/%s/issues/%d" % (UPSTREAM_REPO, number))
            if now is None:
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
        # Skip only when both halves hold: nothing upstream touched, and this fork already
        # knows the shape of the thread. A watermark written before comment edits were tracked
        # has no record of what each comment said, so without that second half the next edit to
        # one would be invisible -- the item moves, this run looks, and finds nothing to compare
        # against. Learning the shape costs one comments call per item, once ever.
        untouched = now.get("updated_at") == old.get("updated_at")
        if untouched and old.get("comments") is not None:
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
        seen = old.get("comments") or {}
        present = comment_map(thread)
        # Four ways a thread can differ from what was recorded, and they are not
        # interchangeable: an id nobody has seen is new, an id whose text digest moved was
        # edited in place, an id that was recorded but is no longer served was deleted, and an
        # id that a moderator collapsed (or stopped collapsing) is hidden from readers while
        # still saying exactly what it said. That last one moves nothing but a timestamp, so a
        # digest cannot see it -- GitHub's own field for the comment is what tells them apart.
        added = [c for c in thread
                 if str(c["id"]) not in seen and c["created_at"] > through]
        edited = [c for c in thread if str(c["id"]) in seen
                  and seen[str(c["id"])]["d"] != present[str(c["id"])]["d"]]
        gone = [(cid, seen[cid]) for cid in seen if cid not in present]
        if gone:
            # The same care the issue listing gets: a page that came back short must not turn
            # into an invented deletion, so read the thread once more and keep only what is
            # missing from both. This costs an extra call only when something looks deleted.
            thread = list(paged("/repos/%s/issues/%d/comments" % (UPSTREAM_REPO, number)))
            present = comment_map(thread)
            gone = [(cid, meta) for cid, meta in gone if cid not in present]
        # Read after the second look, so the hidden/served split is taken from the thread this
        # run finally believes. An entry with no "m" of its own is a watermark written before
        # collapses were tracked. For a comment that is shown now that means nothing to say --
        # shown is the default, and there is no transition to report. For one that is hidden it
        # is the opposite: the hiding is real and belongs on the record, but calling it new
        # would be a guess, since it may have been collapsed before this fork ever read it. So
        # it is recorded once, in its own words, as what was found rather than what happened.
        hidden = []
        shown = []
        learned = []
        for c in thread:
            was = seen.get(str(c["id"]))
            if was is None:
                continue
            now_hidden = present[str(c["id"])]["m"]
            if "m" not in was:
                if now_hidden is not None:
                    learned.append(c)
                continue
            if was["m"] == now_hidden:
                continue
            (shown if now_hidden is None else hidden).append(c)
        newest = max((c["created_at"] for c in thread), default="")
        if newest > through:
            through = newest
            dirty = True
        # Written on every pass, not only when it grows. A watermark that is left out whenever
        # the thread did not grow does not stay left out: the next run finds no watermark,
        # rebuilds one from the archived body -- which knows the thread only as far as it ran
        # the day the archive was taken -- and the run after that drops it again. The value is
        # never wrong, since what protects the thread from being re-reported is the comment map
        # below and not this stamp, but a watermark that moves backwards is not a watermark.
        new["comments_through"] = through
        new["comments"] = present

        if described or added or edited or gone or hidden or shown or learned:
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
                              % (comment_author(comment), comment["created_at"]), "",
                              "````", comment["body"] or "_(no body)_", "````"]
            if edited:
                # The text from before is not repeated: it is already in the copy above, in
                # the archived body or in the event comment that first carried it.
                lines += ["", "**%d comment%s upstream edited after this fork recorded %s** "
                          "-- the earlier text is the one already above; this is what %s now:"
                          % (len(edited), "" if len(edited) == 1 else "s",
                             "it" if len(edited) == 1 else "they",
                             "it says" if len(edited) == 1 else "they say")]
                for comment in edited:
                    lines += ["", "---", "**@%s** commented on %s:"
                              % (comment_author(comment), comment["created_at"]), "",
                              "````", comment["body"] or "_(no body)_", "````"]
            if hidden:
                # The text is not repeated here either: hiding a comment does not change it,
                # so the copy above is still exactly what a reader would see on expanding it.
                lines += ["", "**%d comment%s upstream hid after this fork recorded %s** -- a "
                          "moderator collapsed %s, so readers get a stub where the text was; "
                          "the text itself is the cop%s already above:"
                          % (len(hidden), "" if len(hidden) == 1 else "s",
                             "it" if len(hidden) == 1 else "them",
                             "it" if len(hidden) == 1 else "them",
                             "y" if len(hidden) == 1 else "ies")]
                lines += [hidden_entry(c) for c in hidden]
            if shown:
                lines += ["", "**%d comment%s upstream shows again** -- a moderator unhid %s, "
                          "so it is back on the page as the text above:"
                          % (len(shown), "" if len(shown) == 1 else "s",
                             "it" if len(shown) == 1 else "them")]
                for comment in shown:
                    lines += ["- **@%s** commented on %s -- no longer hidden (was %s; "
                              "upstream's last move on this comment: %s)"
                              % (comment_author(comment), comment["created_at"],
                                 seen[str(comment["id"])]["m"], comment["updated_at"])]
            if learned:
                # Said once per archive, the first time this fork reads a thread whose
                # watermarks predate the field: not "upstream hid these now" -- that is not
                # known -- but "these were already hidden when this fork started looking".
                lines += ["", "**%d comment%s upstream had already collapsed when this fork "
                          "began recording which comments are hidden** -- a moderator hid %s, "
                          "so readers there get a stub; the text is the cop%s above:"
                          % (len(learned), "" if len(learned) == 1 else "s",
                             "it" if len(learned) == 1 else "them",
                             "y" if len(learned) == 1 else "ies")]
                lines += [hidden_entry(c) for c in learned]
            if gone:
                lines += ["", "**%d comment%s this fork recorded %s gone from upstream.** "
                          "The copy above is the last text of %s known to exist."
                          % (len(gone), "" if len(gone) == 1 else "s",
                             "has" if len(gone) == 1 else "have",
                             "it" if len(gone) == 1 else "them")]
                for cid, meta in gone:
                    lines += ["- **@%s** commented on %s (id %s)"
                              % (meta.get("a"), meta.get("t"), cid)]
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
    status = main()
    # The rate-limit read is itself one request, and it is only worth making when the run
    # reached main() at all -- an upstream that is not visible returns early on purpose.
    try:
        core = (api("/rate_limit") or {}).get("resources", {}).get("core", {})
        left = "%s of %s left this hour" % (core.get("remaining"), core.get("limit"))
    except Exception as e:  # never let a diagnostic fail the run
        left = "rate limit unknown (%s)" % e
    print("this run made %d GitHub API calls; %s" % (CALLS, left))
    sys.exit(status)
