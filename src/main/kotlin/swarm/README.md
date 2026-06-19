# Planning Swarm

This package contains an optional implementation detail for `TaskStage.PLANNING`.

The swarm is stage-local. `TaskOrchestrator` still owns the FSM, task state, stage input preparation, stage result recording, public chat history, pause lifecycle, and validation flow. To the orchestrator, the swarm is just another `StageAgent` called `PlanningSwarmStageAgent`.

The feature is experimental and disabled by default with `planningSwarmEnabled=false`. `AI_CHAT_PLANNING_SWARM_ENABLED=true` or `/settings set planningSwarmEnabled true` enables it for later tasks. When the flag is false, planning falls back to the original `PromptedStageAgent` and the existing PlanningAgent prompt.

Each new task creates a fresh `SwarmDialogue`. Swarm agents do not have persistent chat histories of their own. They receive `StageInput`, including `workingContext`, and the dialogue accumulated during that one planning execution. The dialogue is not summarized and is not added to the normal USER or ASSISTANT API context.

While a planning swarm is active, the dialogue is checkpointed after each agent response in `SwarmSessionStore`, keyed by the current task id. If the app is paused or exits during PLANNING, `/resume` restores that dialogue and continues with the next missing role or round. When PLANNING completes, the stored session is cleared.

The planning swarm can accept consensus after one complete round, but only when every role explicitly returns `approve` without required changes or blockers. Role prompts require independent role-specific contributions rather than simple agreement. The default maximum is three rounds.

The dialogue is exposed to the user as an EVENT through `SwarmEventSink`, normally using `ChatHistoryRepository.addEvent`. EVENT messages are already excluded from `apiContextMessages`, so the public log can show the planning discussion without feeding it back into the regular model context.

Memory and invariants enter the swarm only through `StageInput.workingContext`, which is prepared by the orchestrator context provider. The swarm package does not depend on `MemoryRepository` or `InvariantRepository`.

To connect a swarm to another stage, implement a new `StageAgent` wrapper for that stage, create a fresh dialogue inside each `execute(input)`, and select it in the composition root or `StageAgentFactory` for that stage.
