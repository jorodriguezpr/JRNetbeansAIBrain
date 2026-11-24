# Changelog - JR NetBeans AI Brain

## 0.3.0 (2026-11-24)
- Explicit modes in the chat and in Tools > AI Brain: **Chat** (answers, no changes), **Plan** (explores
  and writes a plan: goal, findings, steps with files and commands, checks, risks; then one click
  "Carry out this plan in Agent mode"), **Agent** (does the work; the permission list applies here).
- **Tools > AI Brain** menu: Open AI Brain Chat, Chat / Plan / Agent Mode, New Project with AI,
  Ask AI Brain about the Selection, AI Providers, Settings.
- **AI providers** form (Tools > AI Brain > AI Providers, and Tools > Options > Advanced > AI Brain):
  Ollama Cloud, Ollama (local), jrbtbrain bridge, Claude (Anthropic Messages API, prompt caching on the
  system prompt, tools and newest message), OpenAI, Google Gemini (OpenAI-compatible API),
  OpenAI-compatible servers, and the Copilot app bridge (experimental: UI Automation of the Microsoft
  Copilot app, tools as JSON blocks). Per provider: address, model with "Load models", key source
  (keyring, credentials file, environment variable, none), Test connection, Use in the chat.
- **AI** list in the chat to switch provider/model at any time; the conversation continues.
- Every tool call now has an id (needed by Claude and OpenAI); local Ollama models without thinking
  are retried without it; OpenAI reasoning models get no temperature; servers that refuse
  `stream_options` or `temperature` are retried without them.
- Your 0.1/0.2 Ollama Cloud settings and saved key keep working.

## 0.2.0 (2026-11-24)
- The agent now changes the project: `write_file`, `edit_file` (exact-text replace), `create_folder`,
  `move_file`, `delete_file`, `run_command` (artisan, composer, npm, tests), `open_in_editor`, and `plan`
  (a checklist shown in the chat).
- Permission modes (the Mode list in the chat): read only; ask before every change (diff preview with
  Accept / Accept all in this chat / Reject + a note for the AI); accept edits; accept edits and commands.
  Dangerous commands (git reset/push, migrate:fresh, db:wipe, rm/del, --force...) always ask.
- Safer edits: a file must be read before it is changed and must not have changed since; line numbers
  copied from read_file and indentation differences are forgiven; an ambiguous edit is refused with the
  line numbers; CRLF and BOM are kept. Files open in the editor are changed through the editor (one undo
  step, unsaved typing kept) and saved.
- Checks after every change: `php -l` for PHP, JSON parse, Blade directive balance; the result goes back
  to the model so it fixes its own mistakes.
- Servers and watchers (artisan serve, npm run dev) are refused with a hint; commands get no input, run
  with a timeout and are stopped by the Stop button (whole process tree).
- **New project...**: create a whole application from a description. Templates: Laravel (Blade +
  JavaScript + Vite), Laravel with Breeze login, plain PHP, empty folder. The plugin installs the skeleton
  (composer create-project, npm install), writes the NetBeans PHP project, makes a first git commit,
  opens the project in NetBeans, then the agent builds the application (up to 150 steps).
- Whole-project navigation: glob/grep can include vendor/ and node_modules/; file names in answers are
  links that open the file at the line.
- Paths with stray spaces or invalid characters are refused.

## 0.1.0 (2026-11-24)
- Chat window (Window > AI Brain, Ctrl+Alt+Shift+B) with streamed answers, thinking and tool steps.
- Ollama chat API client with native tool calling, retries and a stall watchdog. Default Ollama Cloud
  and `glm-5.3-flash`. API key from the NetBeans keyring, a credentials file or `OLLAMA_API_KEY`.
- Agent loop with read-only tools: list_dir, glob, grep, read_file, project_info, editor_state.
- Project detection (Laravel, Symfony, WordPress, PHP, Node, Maven, Gradle, Python) and Laravel rules.
- JRBRAIN.md / AGENTS.md project notes.
- Insert at cursor / Copy on code blocks; "Ask AI Brain about the Selection" in the editor menu.

## Planned
- Conversation compression by summary, saved chats, model switch per question, test-driven fix loop.
