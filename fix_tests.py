import os

files = [
    "src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/WorkspaceRootPathsBehaviorTest.kt",
    "src/test/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/navigation/AnonymousImplementationMetadataBehaviorTest.kt"
]

for file in files:
    with open(file, 'r') as f:
        content = f.read()

    # We need to insert `put("compact", false)` wherever `put("query", ...)` or `put("name", ...)` is.
    # Actually, let's just insert it after `buildJsonObject {` if it's not there.
    # Or just replace `buildJsonObject {` with `buildJsonObject {\n            put("compact", false)`

    new_content = content.replace('buildJsonObject {\n', 'buildJsonObject {\n            put("compact", false)\n')
    new_content = new_content.replace('buildJsonObject { ', 'buildJsonObject { put("compact", false); ')

    with open(file, 'w') as f:
        f.write(new_content)
    print(f"Updated {file}")
