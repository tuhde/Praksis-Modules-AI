# Praksis-Modules-AI

Model access for [Praksis](https://github.com/tuhde/Praksis): a module `ai` that lets rules and
commands ask a language model, with the limits that make that safe to do with a person's mail.

| Capability | What it does |
|---|---|
| `ai.complete` | an instruction (written by whoever builds the rule) and, apart from it, text that may come from outside; the answer is text |
| `ai.classify` | which of the given labels fits a text best; the answer is checked against the list, so a rule can branch on it without trusting the model |
| `ai.extract` | the values of named fields (date, amount, person) found in a text; a field that is not there is empty |
| `ai.embed` | a vector for a text, for searching by meaning |
| `ai.calls` | the log of the model calls, newest first |

All of them except `ai.calls` have the effect class `external`: they speak over the network, so a
rule that uses them needs a person's approval like any rule that sends something out.

## Build and install

Build the core first (`./mvnw install` in `../Praksis`), then `./mvnw verify` here. The archive is
`praksis-module-ai/target/ai.praksis-module`:

```sh
praksis modules install praksis-module-ai/target/ai.praksis-module
```

## Settings

A **profile** is a model on a server, with where the text goes. Profiles are named tables below
`[module.ai]` in `praksis.toml`:

```toml
enabled_modules = ["maildir", "ai"]

[module.ai]
default = "local"            # used when a call names no profile
# retention_days = 14        # the log of calls is kept this long
# log_content = true         # false: the log keeps only hashes of what was sent and came back

[module.ai.local]
api = "ollama"               # ollama, or openai (the OpenAI interface of llama.cpp, vLLM, LM Studio, providers)
url = "http://127.0.0.1:11434"
model = "qwen3.5:latest"
# embed_model = "nomic-embed-text"
data = "local"               # local: the text stays on this machine; cloud: anything else
max_tokens_per_day = 200000  # 0 (the default): no limit

[module.ai.cloud]
api = "openai"
url = "https://api.example.org/v1"
model = "some-model"
data = "cloud"
credential = "ai:cloud"      # a binding in policy.toml; the key is never stored in Praksis
```

Further keys of a profile: `timeout_ms` (120000), `max_input_chars` (20000), `max_output_tokens`
(1024), `temperature` (0).

**`data` is what you say, and Praksis holds you to it.** `data = "local"` is accepted only for a
server on this machine (`localhost`, `127.x.x.x`), and a server on another machine must be
reached with https. With Ollama the module also asks the server which models it forwards to a
cloud (the `:cloud` models, which go to ollama.com): a profile that says `local` and names such a
model is refused before any text is sent.

## What a grant can do

The `profile` argument can be limited by a grant, so that a mail may go to the local model and not
to a cloud one:

```toml
[[grant]]
id = "ai-local"
layer = "mine"
capability = "ai.classify"
[grant.args]
profile = { one_of = ["local"] }
```

A grant limits only the arguments it lists, and a call without one of them is not covered: a rule
that is meant to use this grant must pass `profile` itself. The answers say where the text went
(`data`), which `model` and what it cost (`tokens_in`, `tokens_out`).

## Safety notes

- **A model has no rights.** It gets no tools and cannot do anything: it is asked for text, and
  the text goes back to the rule or person that asked. What a rule does with it passes the same
  grants and approvals as everything else.
- **Outside text is data.** The instruction of the caller and the text from outside are kept apart:
  the text is put between two marker lines with a random id that changes with every call, and the
  model is told that nothing between them is an instruction. That lowers the risk of prompt
  injection; it does not remove it. **Never let a rule do something that matters because of free
  text that a model produced from a mail**: `ai.classify` returns only labels from your list, and
  `ai.extract` only the fields you name, for this reason.
- **Limits.** The size of the text, the tokens per day (counted from what the server reports) and
  the output length are bounded per profile; a refused call says why and whether to try again.
- **A log of every call** (`ai.calls`, and `ai.db` in the data directory of the module): when, which
  capability, profile, model, whether the text left the machine, tokens, how long, the outcome, and
  with `content = true` what was sent and what came back. The text of a mail can be in it, so
  `log_content = false` keeps hashes only; entries are deleted after `retention_days`.
- **Redirects are not followed**, so a key goes only to the address that you wrote down.
- **No secrets in prompts:** the key is used for the connection only and is never part of what is
  sent. What a person writes into a prompt, or a mail contains, is not scanned.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
