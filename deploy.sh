#!/bin/bash

TAG_NAME=$1
if [ -n "$2" ]; then
  DESCRIPTION="$2"
fi

echo
echo "Preparing deploy $TAG_NAME"
echo

if [ "${TAG_NAME:0:4}" = "NMA-" ] || [ "${TAG_NAME:0:4}" = "dpl-" ]; then
  # sync tags with remote (delets locel not pushed tags!)
  # this is neaded because Travis deletes deploy-tags after deploying
  git tag -l | xargs git tag -d > /dev/null 2>&1
  git fetch --tags > /dev/null 2>&1

  # create annotated tag
  if ! git tag -a "$TAG_NAME" -m "$DESCRIPTION"
  then
    echo
    echo "Travis didn't yet finish the previous deploy or error occurred"
    echo
#    echo "You can delete deploy-tag (and cancel the deploy) by calling:"
#    printf '\n\tgit push --delete origin %s\n' "$TAG_NAME"
    while true; do
        read -r -p "Do you wish to force a new deploy (y/n)?" yn
        case $yn in
            [Yy]* ) git push --delete origin "$TAG_NAME"; break;;
            [Nn]* ) exit;;
        esac
    done
    fi
    # push deploy-tag to remote (it will be deleted by Travis after deploy)
    echo
    git push origin "$TAG_NAME"
else
  echo "Specify JIRA ticket \"NMA-XXX\" or the deploy name prefixed with \"dpl-\""
  echo "You can also add a description as a second parameter"
  echo "eg. deploy.sh dpl-RCv8.0.1 \"Minor bug fixes\""
fi

echo
read -n 1 -r -s -p "Press any key to continue..."