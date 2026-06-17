# PromptBundler

> Get your codebase into any AI chat, without the copy-paste hell.

Asking a web AI about your code means copying one file, pasting it, copying the next,
losing track, and rebuilding your project's structure by hand in a chat box.

PromptBundler does it for you: pick the files, get one clean, structured prompt, paste it
wherever you want.

## Works with any chat

ChatGPT, Claude, Gemini, Mistral, Microsoft 365 Copilot, or your company's self-hosted
LLM. The output is plain text on your clipboard, so it goes anywhere.

## What it does

- Bundles the files and snippets you pick into one structured prompt, with their tree.
- Tells you the token count up front, so you know it fits the chat's context window.
- Reuses a template so every prompt looks the same.
- Keeps a history of past prompts.

## Private by design

No API calls, no account, no network. PromptBundler reads the files you pick and writes
text to your clipboard. Your code never leaves your machine until you paste it, yourself,
into the chat your company approves.

## One good reason to bother

Your IDE's AI agent is capped, and getting pricier. The chat in a subscription your
company already pays for often isn't. Send the big context questions there, and keep your
limited agent credits for the work that truly needs them.

## Status

Early. Built module by module, no release yet. Star it to follow along.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE).
