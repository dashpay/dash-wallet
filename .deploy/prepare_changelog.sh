#!/bin/bash
version_code=$(awk -F': ' '/versionCode/ {print $NF}' ./wallet/build.gradle | tr -d '}')

if [[ -z "$version_code" ]]; then
  echo "Failed to parse versionCode from build.gradle. Please check the file and try again."
  exit 1
fi

echo -e "# Fill in the changelog for version $version_code\n# en-US\n\n# es-ES\n\n# de-DE\n\n# ko-KR\n\n# fr-FR" > changelog.txt
nano changelog.txt

current_lang=""
while IFS= read -r line
do
  if [[ $line == "#"* ]]
  then
    current_lang="${line:2}"
  fi

  if [[ $line != "#"* && -n $line ]]
  then
    mkdir -p ./fastlane/metadata/android/$current_lang/changelogs/
    echo "$line" >> ./fastlane/metadata/android/$current_lang/changelogs/$version_code.txt
  fi
done < changelog.txt


es_filename="./fastlane/metadata/android/es-ES/changelogs/$version_code.txt"
if [ -f "$es_filename" ]; then
    mkdir -p ./fastlane/metadata/android/es-US/changelogs/
    cp $es_filename ./fastlane/metadata/android/es-US/changelogs/$version_code.txt
fi

# clean up
rm changelog.txt
