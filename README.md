# Dice Chess Bot — Aggressive + Book (Scala GraalVM Native Image for Azure Functions)

[![CI](https://github.com/fortemate/dicechess-bot-azure/actions/workflows/ci.yml/badge.svg)](https://github.com/fortemate/dicechess-bot-azure/actions/workflows/ci.yml)
[![Play Live](https://img.shields.io/badge/Play-Live-success)](https://fortemate.com/)
[![Leaderboard](https://img.shields.io/badge/Ladder-Leaderboard-1E90FF)](https://fortemate.com/leaderboard)
[![Engine](https://img.shields.io/badge/Engine-dicechess--engine-8A2BE2)](https://github.com/fortemate/dicechess-engine)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

The live [`azure/scala-aggressive-book`](https://fortemate.com/leaderboard) ladder bot: a Dice Chess webhook bot in **Scala 3** that links
the **real game engine** as a dependency —
[`dicechess-engine`](https://github.com/fortemate/dicechess-engine) — and plays its
**aggressive** king-hunt search behind the exported **opening book**
(`OpeningBookBot.decorate(AggressiveSearch, book)`). Compiled to a **GraalVM native image**, it
runs as an Azure Functions **custom handler**: cold starts in the same league as Node, none of
the JVM's 5–20 s serverless startup pain.

Built from [`dicechess-bot-scala`](https://github.com/fortemate/dicechess-bot-scala) — that
repo is the minimal, no-engine, MIT starter (swap its `Strategy.scala` for your own algorithm);
this one exists because linking the real engine needs an actual dependency and a licensing
choice (see below), which a "maximally simple" template shouldn't carry by default. Start here
directly if you specifically want the engine already wired in.

## Licensing

**AGPL-3.0**, because it links the AGPL engine — this is the trade-off that
[`dicechess-bot-scala`](https://github.com/fortemate/dicechess-bot-scala) deliberately avoids.
Forks and experiments are welcome — derived bots stay AGPL. If you want a **closed-source** bot,
fork the MIT template instead: the legal moves are already on the wire, so no engine linkage is
ever required.

## Layout

| Path | Role |
| --- | --- |
| `src/main/scala/dicechess/bot/Strategy.scala` | Engine-backed decision brain: implements runtime v2 `BotStrategy` for turns, draw offers/responses, and stake doubling. **Swap the algorithm here.** |
| `src/main/scala/dicechess/bot/Main.scala` | Wires `Strategy` directly into [`dicechess-bot-runtime`](https://github.com/fortemate/dicechess-bot-runtime)'s `WebhookHandler`/`CustomHandlerServer`. |
| `opening_book.tsv` | The exported opening book (a file on disk — swap without rebuilding). |
| `host.json` · `webhook/function.json` | Azure Functions custom-handler wiring (`enableForwardingHttpRequest`). |

HMAC verification, signature handshakes, decision routing, and the JDK `HttpServer` itself are managed by
[`dicechess-bot-runtime`](https://github.com/fortemate/dicechess-bot-runtime) v2 (`com.fortemate:dicechess-bot-runtime` 2.0.0). `Main.scala` supplies the typed `BotStrategy` directly to `WebhookHandler`.

## Runtime v2 & Webhook Capabilities

This bot supports runtime v2 decision events by bridging `Strategy` to `AggressiveSearch`'s underlying policy hooks:
- **Turn Actions (`onTurn`)**: Selects legal turn paths, consulting the opening book and search evaluation. Offers a draw when permitted by server and recommended by engine policy.
- **Draw Decisions (`onDrawDecision`)**: Evaluates incoming draw offers from the bot's perspective (`shouldAcceptDraw`).
- **Doubling Opportunities (`onDoubleOpportunity`)**: Evaluates stake-doubling offer opportunities (`shouldOfferDouble`) with active-color perspective and current stake multiplier.
- **Doubling Decisions (`onDoubleDecision`)**: Evaluates incoming double offers (`shouldAcceptDouble`) with active-color perspective and proposed stake multiplier.

### Required Webhook Capabilities
When registering or updating this bot on the Dice Chess platform, enable the following webhook capabilities:
- `turn` — Normal turn move selection and draw offering.
- `draw` — Dice-free draw decision evaluation.
- `double` — Stake doubling opportunity and response decision evaluation.

> **Production Deployment Note**: Code readiness for runtime v2 decision handling is independent of production registration and capability enablement. Webhook registration, secret rotation, and capability flags on the live platform are administrative operational tasks managed separately from code changes.

## Local development

Requires JDK 25+ and sbt; resolving the engine needs a GitHub token with `read:packages`
(`gh auth login` is enough — the build reads `gh auth token`).

```bash
mise run check   # Runs scalafmt check and sbt clean test
mise run test    # Runs hermetic tests
mise run format  # Formats code with scalafmt
```

## Deploy to Azure Functions

The binary is **linux-x64** and is built by CI —
grab the `dicechess-bot-linux-x64` artifact from the latest [Actions](../../actions) run.
