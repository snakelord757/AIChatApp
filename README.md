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
AI_CHAT_ALLOW_CLARIFYING_QUESTIONS=false
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

Inside chat mode, the available commands are `/help`, `/settings`, `/summary`, `/facts`, `/memory`, `/edit invariants`, `/pause`, `/resume`, `/checkpoint`, `/branch create <name>`, `/branch list`, `/branch switch <name>`, `/clear`, and `/exit`.

Use `/pause` to mark the active task as paused. If a model request is already in flight, the CLI records a pause flag and does not start the next task stage after the current request returns. When input ends or the CLI closes while a task is active, the task state is also saved as paused so the next run can show that it was interrupted.

Use `/resume` to continue a paused task. `/resume <extra context>` stores the extra text as task clarification instead of starting a new task. A plain chat message such as `continue task` or `продолжи задачу` also resumes the paused task when one exists.

## Context Management

AIChatApp supports two context strategies:

- `sliding` sends the system prompt plus the latest context-window messages, with the current summary included when one exists.
- `facts` asks the model to extract durable memory from the current sticky facts plus the latest `AI_CHAT_CONTEXT_WINDOW_MESSAGES` messages, stores the resulting key-value facts, then sends the system prompt, the current summary when one exists, the sticky facts system block, and the latest context-window messages.

Automatic summary creation is a base option, not a context strategy. It runs when `AI_CHAT_SUMMARY_INTERVAL` is greater than `0` and enough messages have accumulated in the active chat. Main chat and every branch keep their own independent summary. Disable automatic summaries with:

```properties
AI_CHAT_SUMMARY_INTERVAL=0
```

In `/settings`, use `set contextStrategy <sliding|facts>`, `set contextWindow <number>`, and `set summaryInterval <number>`.

Clarifying questions are disabled by default. Enable them in `local.properties` only when you want the task orchestrator to ask the user for missing information before continuing:

```properties
AI_CHAT_ALLOW_CLARIFYING_QUESTIONS=true
```

## Task Orchestration

User tasks are executed by a task orchestrator as a strict finite-state machine:

```text
PLANNING -> EXECUTION -> VALIDATION -> COMPLETION
```

`VALIDATION` can send the task back to `EXECUTION` when it finds problems. `COMPLETION` is final, and code-level transition checks reject skipped stages such as `PLANNING -> VALIDATION`.

Each task stage has its own stage-agent role:

- `PlanningAgent`
- `ExecutionAgent`
- `ValidationAgent`
- `CompletionAgent`

Stage agents keep their own message history. Their private stage history is not written into the shared chat history. The orchestrator owns shared chat history, permanent memory, personal memory, and working memory; stage agents receive only the user task, the previous stage result, accumulated stage summaries, clarifications approved by the orchestrator, and allowed working context. Context strategies such as `sliding` and `facts` are evaluated only by the orchestrator, which passes a prepared context snapshot to stage agents instead of exposing strategy settings or repositories to them.

Each stage returns a structured `StageResult` with the stage, success flag, summary, output, issues, requested changes or retry reason, and token usage when available. The orchestrator passes each result to the next legal stage and writes the final completion output into the public chat history.

The latest task lifecycle is stored next to `chat-history.json` as `task-state.json`. Lifecycle status is separate from task stage and can be `ACTIVE`, `PAUSED`, `DONE`, or `FAILED`.

When validation repeatedly fails, the task is paused instead of cycling forever between `EXECUTION` and `VALIDATION`. The pause reason is saved in `task-state.json`, and the user can provide missing details with `/resume <details>`.

Stage-call diagnostics are stored separately as `task-stage-audit.jsonl` next to `chat-history.json`. This audit file records each stage prompt, raw response, parsed `StageResult`, attempt number, timestamp, and token usage without adding those messages to the public chat history or rendering them in normal chat output.

Sticky facts can still be collected immediately from explicit message markers such as `goal:`, `constraint:`, `preference:`, `decision:`, and `agreement:`. With the `facts` strategy enabled, the app also sends a small extraction prompt before the main assistant response so the model can turn natural user messages into stored facts.

Branching is a chat feature, not a context strategy. Use `/checkpoint`, then `/branch create <name>` to create an independent continuation from that checkpoint or from the current dialog if no checkpoint exists. Use `/branch switch <name>` to enter a branch and `/branch switch main` to return to the main chat. Each branch keeps independent messages, sticky facts, and summary state. The selected context strategy and automatic summary option apply only to the active chat or branch.

## Markdown Memory

AIChatApp also keeps Markdown memory in files next to `chat-history.json`. The app directory uses the same lookup order as chat history:

```text
-Daichat.history.dir
APP_HOME
%LOCALAPPDATA%/AIChatApp on Windows
$HOME/.aichat on Unix-like systems
```

Memory files are always active and do not have `local.properties` settings:

```text
<app-dir>/memory/permanent.md
<app-dir>/memory/personal.md
<app-dir>/memory/work/main.md
<app-dir>/memory/work/<branch>.md
```

The three memory types are:

- Permanent memory: global Markdown instructions and constraints. It is read as a system block and is never changed automatically by the model.
- Personal memory: durable user communication and workflow preferences. The real DeepSeek agent updates it with an internal extraction request, and the app also reinforces local frequency signals such as repeated Python/Kotlin/code-language requests or detailed-explanation requests. Demo mode uses the same local signal reinforcement without real API calls.
- Working memory: branch-specific task state with `Status: PENDING`, `Status: PAUSED`, or `Status: DONE`. The status is changed locally before and after assistant requests or `/pause`, without using the model.

Personal memory items are stored as weighted constraints:

```md
- [strength: 1] Prefers Kotlin for code-related tasks
- [strength: 3] Prefers detailed explanations
```

Legacy plain bullets such as `- Prefers Kotlin` are still accepted as `strength: 1`. When the same constraint appears again, AIChatApp increments its strength instead of duplicating the bullet. Higher strength means the preference has been observed more often and should be applied more readily when relevant to a future answer.

For example, three prompts asking for Python code will reinforce:

```md
- [strength: 3] Works with Python for code-related tasks
```

Memory system blocks are inserted after the base system prompt and before summary, sticky facts, and recent dialog messages. The base system prompt remains first, including after `/settings set systemPrompt ...`.

Use these commands inside chat:

```text
/memory
/memory show permanent
/memory show personal
/memory show work
/memory status
/memory done
/memory pending
/memory path
/memory reload
```

Markdown memory belongs to the orchestrator and the main chat flow. Stage agents do not read permanent or personal memory directly and do not update user memory.

Markdown memory is separate from sticky facts. Sticky facts are the existing key-value memory stored in `chat-history.json` and controlled by the `facts` context strategy. Markdown memory lives only in `.md` files and is used regardless of the selected context strategy.

Token usage and cost from the internal personal-memory extraction request are intentionally not saved in chat history, not shown by `/summary`, and not included in `totalUsage()`. Only normal assistant, summary, and sticky-facts usage remain part of user-visible accounting.

## Assistant Invariants

Assistant invariants are non-negotiable rules stored separately from dialog, sticky facts, summaries, branches, task state, and Markdown memory:

```text
<app-dir>/invariants.md
```

Use `/edit invariants` inside chat to create and open the file in the operating system's default text application. Invariants are included as a dedicated system block after the base system prompt and before Markdown memory for both `sliding` and `facts` context strategies. In orchestrated tasks, only `PlanningAgent` and `ValidationAgent` see invariants through the working context prepared by the orchestrator.

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
