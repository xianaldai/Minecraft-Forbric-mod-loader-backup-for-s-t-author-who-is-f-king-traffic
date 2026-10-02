#!/usr/bin/env bash
# Merge upstream's branch into the current one without rewriting history, and without letting
# upstream's version of this fork's own automation arrive.
#
# The rule this implements: upstream owns the code, this fork owns .github/workflows and
# .github/scripts. A merge takes everything upstream has except those two directories, which are
# restored to this fork's copies -- and any file that exists only upstream in them is dropped, so
# a merge cannot hand this fork a workflow it does not already own, nor rewrite one it does. Why:
# build.yml here is deliberately diverged (the Windows leg was dropped), the sync and keepalive
# workflows live only here, and this repository exists precisely to not be at upstream's mercy.
#
# Exit codes:
#   0   main is in the wanted state (already contained, fast-forwarded, or merged)
#   20  upstream conflicts with something outside the fork-owned directories; main is untouched
#   *   an unexpected failure; main is untouched
#
# Usage: merge-upstream.sh <remote> <branch>
set -euo pipefail

remote=${1:?upstream remote name, e.g. upstream}
branch=${2:?branch name, e.g. main}

# Being run outside a clone is not a conflict, and must not be reported as one: exit 20 opens
# the "upstream cannot be merged automatically" issue, which would blame upstream for a script
# that was simply pointed at the wrong directory.
if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "::error::merge-upstream.sh was run outside a git repository; nothing was touched"
  exit 21
fi

fork_owned=(.github/workflows .github/scripts)

if git merge-base --is-ancestor "$remote/$branch" HEAD; then
  echo "upstream/$branch is already contained; nothing to sync"
  exit 0
fi

if git merge-base --is-ancestor HEAD "$remote/$branch"; then
  # This branch has no commits upstream lacks: a pure fast-forward, and with nothing
  # fork-owned to protect (a branch that has not diverged has not diverged its workflows).
  git merge --ff-only "$remote/$branch"
  exit 0
fi

# Diverged. Stage the merge without committing it, so the fork-owned directories can be put
# back before the merge commit exists. A merge commit adds a commit and erases none, so both
# lineages stay reachable and the backup never loses ground.
#
# There is deliberately no rebase flag here: `git merge` cannot rebase, whatever pull.rebase
# says -- that setting only ever affects `git pull`. Passing --no-rebase anyway is worse than
# redundant: it is an unknown option to `git merge`, and it fails the merge outright.
git merge --no-commit "$remote/$branch" || true

if [ ! -e .git/MERGE_HEAD ]; then
  # The merge did not start (unrelated histories, a missing ref, a tree it would clobber).
  # Nothing changed. Filed with the conflicts rather than as a failure because a run every 5
  # minutes must not turn one standing condition into a failure notification storm.
  echo "::warning::git merge did not start; main left untouched"
  exit 20
fi

for owned in "${fork_owned[@]}"; do
  # Restore this fork's copy of the directory as of HEAD...
  git cat-file -e "HEAD:${owned}" 2>/dev/null || continue
  git checkout HEAD -- "$owned"
  # ...and drop anything under it that only upstream has.
  while read -r f; do
    git cat-file -e "HEAD:${f}" 2>/dev/null || git rm -q -f -- "$f"
  done < <(git ls-files -- "$owned" | sort -u)
done

if [ -n "$(git diff --name-only --diff-filter=U)" ]; then
  # A conflict outside the fork-owned directories is a real one: back out and ask a human.
  git merge --abort || true
  exit 20
fi

git commit -q \
  -m "Merge upstream $branch, keeping this fork's own workflows" \
  -m "Upstream head: $(git rev-parse "$remote/$branch")"
exit 0
