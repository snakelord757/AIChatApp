# AIChatApp

AIChatApp is a Kotlin/JVM command-line chat client for the DeepSeek API. It can run as a JVM CLI during development and can also be built as a standalone native executable with GraalVM Native Image.

## Configuration

Create `local.properties` in the project root:

```properties
DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-v4-flash
DEEPSEEK_TOKEN_PRICE_PER_1M_USD=0.28
AI_CHAT_SUMMARY_INTERVAL=20
AI_CHAT_CONTEXT_STRATEGY=sliding
AI_CHAT_CONTEXT_WINDOW_MESSAGES=20
```

`local.properties` is ignored by Git. Do not commit real API keys.

If the file or API key is missing, the app starts in the existing local demonstration mode.

## CLI Usage

Show help:

```powershell
.\gradlew.bat run --args="--help"
```

Show version:

```powershell
.\gradlew.bat run --args="--version"
```

Start the interactive chat on the JVM:

```powershell
.\gradlew.bat run
```

You can also start chat explicitly:

```powershell
.\gradlew.bat run --args="chat"
```

Inside chat mode, the available commands are `/help`, `/settings`, `/summary`, `/facts`, `/checkpoint`, `/branch create <name>`, `/branch list`, `/branch switch <name>`, `/clear`, and `/exit`.

## Context Management

AIChatApp supports three context strategies:

- `sliding` sends the system prompt plus the latest context-window messages, with the current summary included when one exists.
- `facts` sends the system prompt, the current summary when one exists, a sticky facts system block, and the latest context-window messages.
- `branching` sends only the active branch dialog so branches do not mix.

Automatic summary creation is a base option, not a context strategy. It runs when `AI_CHAT_SUMMARY_INTERVAL` is greater than `0` and enough main-timeline messages have accumulated. Disable automatic summaries with:

```properties
AI_CHAT_SUMMARY_INTERVAL=0
```

In `/settings`, use `set contextStrategy <sliding|facts|branching>`, `set contextWindow <number>`, and `set summaryInterval <number>`.

Sticky facts are collected from explicit message markers such as `goal:`, `constraint:`, `preference:`, `decision:`, and `agreement:`. Branching starts with `/checkpoint`, then `/branch create <name>` creates an independent continuation from that checkpoint or from the current dialog if no checkpoint exists.

## JVM Distribution

Build an installable JVM distribution:

```powershell
.\gradlew.bat installDist
```

The generated command scripts are written to:

```text
build/app/aichat/bin/
```

Run the installed JVM CLI on Windows:

```powershell
.\build\app\aichat\bin\aichat.bat --help
.\build\app\aichat\bin\aichat.bat chat
```

On Unix-like systems, use:

```bash
./build/app/aichat/bin/aichat --help
./build/app/aichat/bin/aichat chat
```

## Native Binary

Install a GraalVM JDK with Native Image support and run Gradle with that JDK. For example, set `JAVA_HOME` to your GraalVM installation before building.

On Windows, also install Visual Studio 2022 17.6 or newer with the C++ build tools. GraalVM Native Image needs the Windows native compiler toolchain to produce `.exe` files.

The native-image build uses the GraalVM installation selected by `JAVA_HOME` or `GRAALVM_HOME`.

Build the native executable:

```powershell
.\gradlew.bat nativeCompile
```

The binary is produced at:

```text
build/native/nativeCompile/aichat.exe
```

On Unix-like systems the binary is:

```text
build/native/nativeCompile/aichat
```

Run the native CLI directly:

```powershell
.\build\native\nativeCompile\aichat.exe --help
.\build\native\nativeCompile\aichat.exe --version
.\build\native\nativeCompile\aichat.exe chat
```

By default, non-console streams use UTF-8 and attached consoles use the JVM-reported console charset. You can override the charset explicitly when needed:

```powershell
$env:AICHAT_CHARSET="UTF-8"
.\build\native\nativeCompile\aichat.exe chat
```

## GitHub Release Builds

GitHub Actions can build native binaries and upload them to a GitHub Release. The workflow is defined in:

```text
.github/workflows/release-native-binaries.yml
```

Create and push a version tag to publish release binaries:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds and uploads:

```text
aichat-windows-x64.zip
aichat-linux-x64.tar.gz
aichat-macos-arm64.tar.gz
```

You can also start the workflow manually from GitHub:

```text
Actions -> Release native binaries -> Run workflow
```

Manual runs require a release tag name such as `v1.0.0`.

## Development Verification

Run tests:

```powershell
.\gradlew.bat test
```

Compile the JVM app:

```powershell
.\gradlew.bat assemble
```

Build the native binary:

```powershell
.\gradlew.bat nativeCompile
```
