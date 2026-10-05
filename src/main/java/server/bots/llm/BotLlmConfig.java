package server.bots.llm;

public final class BotLlmConfig {
    public static volatile boolean enabled = true;
    public static volatile boolean typoSuggesterEnabled = false; // recommended off if LLM on, too many false positive and block LLM chat sometimes

    public static volatile String endpoint = "http://localhost:11434";
    public static volatile String model = "qwen2.5:7b";
    public static volatile int requestTimeoutMs = 30_000;
    public static volatile int maxConcurrentGlobal = 4;
    // Hard ceiling on the FULL reply (sum across split messages). Computed
    // dynamically as maxReplyMessages * maxReplyCharsPerMessage so changing
    // either knob below auto-resizes the cap.
    public static int maxReplyChars() {
        return Math.max(1, maxReplyMessages) * Math.max(1, maxReplyCharsPerMessage);
    }

    // CPU cap: how many threads Ollama may use per inference. 0 = let Ollama
    // decide (uses all cores → 100% spike). Default to half the available
    // logical cores so the game server keeps room to breathe.
    public static volatile int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    // Keep the model resident in Ollama for this long after each call so the
    // next reply doesn't pay a cold-load cost. Examples: "5m", "30m", "-1" (forever).
    public static volatile String keepAlive = "30m";

    // Speed knobs for CPU-only hosts. All can be tuned at runtime.

    // Qwen3/3.5 emit <think>...</think> tokens by default — pure waste for
    // one-line chat. Disabling roughly halves wall time on short replies.
    public static volatile boolean disableThinking = true;

    // Hard cap on tokens generated. Reply is split across up to
    // maxReplyMessages chat messages of maxReplyCharsPerMessage chars each,
    // so size this to roughly cover the multi-message budget plus a bit.
    // 2*120=240 chars ~= 60 tokens; 24 was truncating replies to ~1/3 of the
    // intended length (a big part of why chat read as "too basic").
    public static volatile int maxPredictTokens = 60;

    // Context window size. Our prompts stay under ~300 tokens; the default
    // 4096 wastes memory bandwidth. Lower = faster on CPU.
    public static volatile int numCtx = 2048;

    // How many recent turns from memory to inline into the prompt. Lower =
    // shorter prompt = faster prompt eval on CPU. Long-term context still
    // survives via the compacted .summary.txt.
    public static volatile int recentTurnsInPrompt = 5;

    // Gemma4 official preset is temp=1.0 / top_p=0.95 / top_k=64 — tuned for general
    // assistant use. For very short MMO chatter we tighten the tail slightly so the
    // long-tail "weird picks" that hurt 50-token replies get clipped. Keep temperature
    // at 1.0 since instruction-tuning was done there.
    public static volatile double temperature = 1.0;
    public static volatile double topP = 0.9;
    public static volatile int topK = 40;
    public static volatile double minP = 0.0;
    // presence_penalty was inherited from the Qwen3.5 non-thinking preset; Gemma doesn't
    // recommend it and >0 on short replies causes "talks-around-the-word" outputs because
    // common tokens (you/the/it) get pushed away after their first use.
    public static volatile double presencePenalty = 0.0;
    public static volatile double repeatPenalty = 1.0;

    // Multi-message reply: long replies are split on sentence boundaries and
    // sent as up to maxReplyMessages chat messages with multiMessageDelayMs
    // between each. Set to 1 to disable splitting.
    public static volatile int maxReplyMessages = 2;
    public static volatile int multiMessageDelayMs = 1800;
    public static volatile int maxReplyCharsPerMessage = 120;

    // When true, every LLM call prints the full prompt + raw response + timing
    // to the server log. Use to diagnose nonsense / sampling issues, then turn off.
    public static volatile boolean debugLog = true;

    public static volatile String memoryDir = "bots/llm-memory";
    // Compaction is gap-free + history-preserving. The jsonl is append-only forever.
    // A cursor sidecar tracks how many leading turns have been folded into the rolling
    // summary. Compaction fires when (total - cursor) > recentTurnsInPrompt + compactBatchSize:
    // the oldest compactBatchSize turns get summarized into summary.txt and the cursor
    // advances. Bigger batch = fewer LLM calls per chat-active bot but more verbatim
    // turns in the prompt. ONE LLM compact call per compactBatchSize turns.
    public static volatile int compactBatchSize = 8;
    // Token budget specifically for the summary-rewrite call. Larger than maxPredictTokens
    // (which is sized for chat replies) so the rolling memory can actually carry detail.
    // ~300 tokens ≈ 4-8 short sentences ≈ what fits comfortably in 800 chars.
    public static volatile int summaryMaxPredictTokens = 300;

    // ---- Autonomous planner (BotAutonomousPlanner) ----
    // Master switch for the off-tick decision layer. When false the planner never
    // fires and bots behave exactly as before (owner-command driven only). Independent
    // of `enabled` (chat) so chat and autonomy can be toggled separately.
    public static volatile boolean plannerEnabled = true;
    // How often the planner re-evaluates each idle bot. 5-10s keeps decisions responsive
    // without hammering the SLM. Critical events can force an immediate re-eval out of band.
    public static volatile int plannerIntervalMs = 8_000;
    // Heartbeat: how often to FORCE a fresh planner decision for each ownerless bot,
    // even mid-action (hunting), so it periodically reassesses and the SLM stays warm.
    // Owner-driven bots are unaffected (idle-gated sweep only). Min clamp 10s.
    public static volatile int plannerHeartbeatMs = 60_000;
    // Token budget for one planner decision. A JSON object {"action","reason","params"}
    // is small; ~96 tokens covers a one-line reason plus the structure comfortably.
    public static volatile int plannerMaxTokens = 96;
    // judge_completion (read->judge->mark->emit loop): a goal must be judged complete
    // on this many CONSECUTIVE ticks before its GOAL.md status flag is flipped done
    // (two-tick confirmation guards against a single false-positive judgment).
    public static volatile int plannerConfirmTicks = 2;
    // Stuck-detector K: if no goal completes for this many ticks while goals remain
    // open, signal the Tier-1 planner to review (escape a stubborn false negative).
    public static volatile int plannerStuckTicks = 5;
    // Temperature for the judge_completion call. Completion is a CLASSIFICATION, not
    // creative chat — it must be near-deterministic so the same state yields the same
    // verdict run-to-run (the chat default of 1.0 makes the judge flip-flop randomly).
    public static volatile double plannerJudgeTemperature = 0.0;
    // Directory holding per-bot personality/goal context files (SOUL.md, GOAL.md).
    // Layout: <plannerDataDir>/<botName>/SOUL.md etc.
    public static volatile String plannerDataDir = "bots/agent-data";

    // ---- Tier-1 orchestrator (Claude-backed GOAL.md authoring) ----
    // Master switch. OFF by default: Tier-1 calls the PAID Anthropic API, so it must be
    // explicitly enabled. When off, the escalate/stuck signals stay log-only (as before).
    // The API key is read from the ANTHROPIC_API_KEY env var, never stored here.
    public static volatile boolean tier1Enabled = false;
    // Anthropic Messages API base URL + model for the goal-authoring call.
    public static volatile String anthropicEndpoint = "https://api.anthropic.com";
    public static volatile String tier1Model = "claude-sonnet-4-6";
    // Token budget for one authored GOAL.md (markdown + adaptive thinking). 4096 keeps the
    // call non-streamed and well clear of HTTP-timeout territory.
    public static volatile int tier1MaxTokens = 4096;
    // Opus authoring with adaptive thinking can take 10-30s; give it room.
    public static volatile int tier1TimeoutMs = 60_000;
    // Per-bot cooldown: minimum ms between Tier-1 re-authors for ONE bot. The stuck-detector
    // re-fires every K ticks, so a permanently-stuck bot would otherwise re-trigger a PAID
    // Claude call every ~40s. This caps it to one call per window per bot (applies to both
    // the auto escalate/stuck signals AND the manual panel trigger). 10 min default.
    public static volatile int tier1MinIntervalMs = 600_000;
    // External world-index grounding harness (Python + SQLite). The server runs from
    // cosmic/, the harness is a sibling dir, so the default is one level up.
    public static volatile String worldIndexDir = "../world-index";
    public static volatile String worldIndexPython = "python3";
    // Directory holding swappable global system-prompt templates for the planner.
    // active.txt names the active template (basename); <name>.md is the template,
    // <name>.fewshot.md an optional few-shot fragment. When no active template is
    // set, the planner uses its built-in hardcoded system prompt. See PlannerPromptLibrary.
    public static volatile String plannerPromptsDir = "bots/planner-prompts";

    // ---- Bot Control HTTP API ----
    // Lightweight HTTP API on 127.0.0.1 only for the external debug panel to spawn/despawn
    // ownerless bots and read planner decisions without a human logged in.
    public static volatile boolean botControlApiEnabled = true;
    public static volatile int botControlApiPort = 8485;
    // Optional shared secret. If non-empty, callers must send X-Panel-Token: <token>.
    // If empty, the API is open to localhost.
    public static volatile String botControlApiToken = "";

    private BotLlmConfig() {}
}
