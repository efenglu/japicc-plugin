#!/usr/bin/env bash -e

newVersion=$1
if [ -z "${newVersion}" ]; then
  echo "Missing new version"
  exit -1
fi

./mvnw versions:set -DnewVersion=${newVersion} -DgenerateBackupPoms=false
./mvnw clean install -Prelease
gpg --verify target/*.pom.asc
read -n 1 -p "Press enter to continue" continue

./mvnw clean deploy -Prelease

read -n 1 -p "Press enter to continue" continue

git add .
git commit -m "Release ${newVersion}"
git tag ${newVersion}
git push --tags
# Roll back all the changes so they don't get added to the main codebase
git reset HEAD~1
git checkout .
git clean -fd