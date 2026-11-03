import os
import re

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Match pattern like: something.javaClass.getMethod("name"...)
    # Replace with: ReflectionCache.getMethod(something.javaClass, "name"...)

    # We need to make sure ReflectionCache is imported if we use it.

    pattern = r'([a-zA-Z0-9_]+)\.javaClass\.getMethod\('

    new_content = re.sub(pattern, r'ReflectionCache.getMethod(\1.javaClass, ', content)

    if new_content != content:
        # Check if ReflectionCache is imported
        if 'import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ReflectionCache' not in new_content:
            # Add import after package declaration
            new_content = re.sub(
                r'(package [^\n]+)',
                r'\1\n\nimport com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ReflectionCache',
                new_content
            )

        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

for root, dirs, files in os.walk('src/main'):
    for file in files:
        if file.endswith('.kt'):
            process_file(os.path.join(root, file))
