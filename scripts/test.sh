#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_classes="$project_root/build/test-classes"
mkdir -p "$test_classes"
java -m jdk.compiler/com.sun.tools.javac.Main --source 17 --target 17 -d "$test_classes" \
    "$project_root/app/src/main/java/io/oxide/mobile/ServerProcess.java" \
    "$project_root/tests/FakeServer.java" "$project_root/tests/ServerProcessTest.java" \
    "$project_root/app/src/main/java/io/oxide/mobile/ServerProfile.java" \
    "$project_root/app/src/main/java/io/oxide/mobile/FileAccess.java" "$project_root/tests/ProfileFilesTest.java" \
    "$project_root/app/src/main/java/io/oxide/mobile/ServerFolders.java" "$project_root/tests/ServerFoldersTest.java"
java -ea -cp "$test_classes" io.oxide.mobile.ServerProcessTest
java -ea -cp "$test_classes" io.oxide.mobile.ProfileFilesTest
java -ea -cp "$test_classes" io.oxide.mobile.ServerFoldersTest
python3 -m unittest discover -s "$project_root/tests" -p 'test_*.py'
