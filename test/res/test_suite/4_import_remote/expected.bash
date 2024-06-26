#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P || exit 1)"

# 1. Here we import by @github token:

# shellcheck source=test/res/test_suite/4_import_remote/.github_deps/rynkowsg/sosh@main/test/res/test_suite/4_import_remote/test_lib/test_log.bash
# source "${SCRIPT_DIR}/.github_deps/rynkowsg/sosh@main/test/res/test_suite/4_import_remote/test_lib/test_log.bash" # BEGIN
#!/usr/bin/env bash

# This is a sample library that entry.bash refers to.
# To make it easier to maintain, I put it here, as you can see in entry.bash it is not sourced directly.
# It is sourced first with @github token and then with @https token.

printf "%s\n" "Log from library"
# source "${SCRIPT_DIR}/.github_deps/rynkowsg/sosh@main/test/res/test_suite/4_import_remote/test_lib/test_log.bash" # END

# 2. And here by @https token:

# shellcheck source=test/res/test_suite/4_import_remote/.https_deps/raw.githubusercontent.com/rynkowsg/sosh/main/test/res/test_suite/4_import_remote/test_lib/test_log.bash
# source "${SCRIPT_DIR}/.https_deps/raw.githubusercontent.com/rynkowsg/sosh/main/test/res/test_suite/4_import_remote/test_lib/test_log.bash" # BEGIN
#!/usr/bin/env bash

# This is a sample library that entry.bash refers to.
# To make it easier to maintain, I put it here, as you can see in entry.bash it is not sourced directly.
# It is sourced first with @github token and then with @https token.

printf "%s\n" "Log from library"
# source "${SCRIPT_DIR}/.https_deps/raw.githubusercontent.com/rynkowsg/sosh/main/test/res/test_suite/4_import_remote/test_lib/test_log.bash" # END

printf "${GREEN}%s${NC}\n" "All good"
