# JR NetBeans AI Brain

An AI coding agent for **Apache NetBeans 21+**, in the spirit of Claude Code: a chat window where the AI
looks through your project by itself (lists folders, finds files, searches and reads code) before it
answers. It works with **Ollama Cloud** (default model `glm-5.3-flash`), a local Ollama, the jrbtbrain
phone bridge, **Claude**, **OpenAI**, **Google Gemini**, any OpenAI-compatible server, or the Microsoft
Copilot app.

Developer: **Jose Rodriguez Arroyo** - https://microrepair.net - https://github.com/jorodriguezpr
License: Apache 2.0

## What it does

- **Tools > AI Brain** menu: open the chat, switch Chat / Plan / Agent mode, New Project with AI, AI
  Providers, Settings, Ask about the selection.
- **Explicit modes**: **Chat** answers and changes nothing; **Plan** explores the project and writes a
  plan (findings, steps with files and commands, checks, risks) that you approve with one click
  ("Carry out this plan in Agent mode"); **Agent** does the work.
- **AI providers** (Tools > AI Brain > AI Providers, or the **AI** list in the chat): Ollama Cloud,
  Ollama (local), jrbtbrain bridge, Claude (Anthropic, with prompt caching), OpenAI, Google Gemini,
  OpenAI-compatible (LM Studio, OpenRouter, Groq, vLLM...), Copilot app bridge (experimental, Windows).
  Each has its own address, model ("Load models" lists the account's models), key source and a
  connection test. Switching provider keeps the conversation.
- **Chat window** (Window > AI Brain, `Ctrl+Alt+Shift+B`): streamed answers, the model's thinking, its
  plan as a checklist, and every step it takes ("Read routes/web.php", "Edit app/Models/Post.php",
  "Run php artisan migrate") with the result or diff one click away. File names in answers open the file.
- **Explores the whole project**: `list_dir`, `glob`, `grep`, `read_file`, `project_info`,
  `editor_state` (vendor/ and node_modules/ on request).
- **Changes the project**: `write_file`, `edit_file`, `create_folder`, `move_file`, `delete_file`,
  `run_command` (php artisan, composer, npm, tests), `open_in_editor`.
- **You stay in control** in Agent mode with the permission list: ask before every change (diff preview,
  Accept, Accept all in this chat, Reject with a note for the AI) / accept edits / accept edits and
  commands. Dangerous commands always ask.
- **New project...**: describe an application and it creates it: Laravel (Blade + JavaScript + Vite),
  Laravel with Breeze login, plain PHP or an empty folder. The skeleton is installed by the official
  tools (composer create-project, npm install), saved in a first git commit, opened in NetBeans, and then
  the agent builds the app: migrations, models, Form Requests, controllers, routes, Blade layout and
  pages, JavaScript, seed data, build and tests.
- **Detects the project type**: Laravel (exact version, Livewire, Breeze, Inertia, Sanctum, Pest...),
  Symfony, WordPress, plain PHP, Node, Maven/Gradle, Python; Vite, Tailwind, Bootstrap, Alpine, Vue, React.
- **Project notes**: a `JRBRAIN.md` (or `AGENTS.md`) in the project folder with your own rules.
- **Insert at cursor** / **Copy** on code blocks, and "Ask AI Brain about the Selection" in the editor.

### Built to fail less

- A file must be read before it is changed, and must not have changed since. Edits replace exact text;
  copied line numbers and indentation differences are forgiven; ambiguous edits are refused with the
  line numbers. Line endings and BOM are kept. Open files are edited through the editor (Undo works).
- After every change `php -l`, a JSON check and a Blade directive check run; errors go back to the model.
- Tool calls written as text are recognised; broken JSON arguments are repaired; unknown tools, missing
  arguments, bad paths and invalid regexes get an explanation; repeated identical calls are stopped.
- Servers/watchers are refused; commands have no input, a timeout, and Stop kills the process tree.
- Network errors, HTTP 429 and 5xx are retried; a stalled stream is cut; long chats drop old tool output.
- The agent never leaves the project folder and never sees secret values (`.env` shows names only).

## Install

1. Build: `powershell -ExecutionPolicy Bypass -File build.ps1` (needs JDK 17+ and Maven), or take the
   `.nbm` from the GitHub release.
2. NetBeans > Tools > Plugins > Downloaded > Add Plugins... > `jrnetbeansaibrain-<version>.nbm` > Install
   (for an update: uninstall the old version first, or install over it and restart NetBeans).
3. Tools > AI Brain > **AI Providers...** (also in Tools > Options > Advanced > AI Brain): pick a
   provider, check its address and model, choose where its key comes from (saved in NetBeans (keyring), a
   credentials file (JSON with an `apiKey` field), its environment variable such as `OLLAMA_API_KEY`,
   `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, or no key), **Test connection**, then
   **Use in the chat**.

   | Provider | Address | Key |
   |---|---|---|
   | Ollama Cloud | `https://ollama.com` | ollama.com > Settings > Keys |
   | Ollama (local) | `http://127.0.0.1:11434` | none |
   | jrbtbrain bridge | `http://127.0.0.1:11435` | none (the phone holds it) |
   | Claude | `https://api.anthropic.com` | console.anthropic.com |
   | OpenAI | `https://api.openai.com/v1` | platform.openai.com |
   | Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | aistudio.google.com |
   | OpenAI-compatible | your server's `/v1` address | as the server needs |
   | Copilot app | path of `copilotapp.exe` | none (your Microsoft account) |
4. Tools > AI Brain > Open AI Brain Chat (or Window > AI Brain), pick the project and the mode, and ask.

## Tests

`mvn test` runs the unit tests. The live test talks to the real provider on a real project:

```
mvn test -Dtest=LiveAgentTest -Djrbrain.keyfile=<json with apiKey> -Djrbrain.project=<project folder>
mvn test -Dtest=LiveCreateTest -Djrbrain.keyfile=<json with apiKey> -Djrbrain.parent=<folder> -Djrbrain.name=<new>
mvn test -Dtest=LiveAgentTest ... -Djrbrain.api=openai -Djrbrain.work=plan   (OpenAI API, Plan mode)
```

Needs PHP, Composer, Node/npm and Git on the PATH for commands and new projects.

## Developer and license

Developed by **Jose Rodriguez Arroyo** - https://microrepair.net - https://github.com/jorodriguezpr

Copyright 2026 Jose Rodriguez Arroyo. Licensed under the **Apache License, Version 2.0**: see
[LICENSE](LICENSE) and [NOTICE](NOTICE). You may use, change and share it under the license terms.
