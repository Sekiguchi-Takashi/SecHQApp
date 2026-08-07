#!/bin/bash
set -e

cd "$(dirname "$0")"

REPO=SecHQApp
USER=Sekiguchi-Takashi
MSG="${1:-update}"
TOKEN=$(git config --global github.token)

if [ -z "$TOKEN" ]; then
  printf '%s\n' "github.token is not set: git config --global github.token ghp_XXXX"
  exit 1
fi

curl -s -o /dev/null -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/repos \
  -d "{\"name\":\"$REPO\",\"private\":true}" || true

if [ ! -d .git ]; then
  git init
fi

git branch -M main || true
git remote remove origin 2>/dev/null || true
git remote add origin "https://$USER:$TOKEN@github.com/$USER/$REPO.git"

git add -A
git commit -m "$MSG" || true
git push -u origin main --force

printf '%s\n' "pushed: $REPO / $MSG"
