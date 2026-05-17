# Outreach templates

Short, copy-paste-ready messages for promoting **MultiView** to Minecraft content creators and modding communities. Adapt the bracketed parts to each target; never send the placeholder verbatim.

> Rule of thumb: one message + one visual + one link. Anything longer gets ignored.

---

## 1. Direct message to a content creator (Twitter / Discord / YouTube comment reply)

> Hey **\[NAME\]**, big fan of **\[SPECIFIC SERIES / VIDEO\]**.
>
> I built a Fabric add-on for Flashback that merges several players' replays of the same session into a single unified replay — so a SMP recap can be cut from one timeline with everyone visible and every chunk loaded.
>
> Looks like this in 20 seconds: **\[GIF/VIDEO LINK\]**.
>
> Free, MIT, runs on 1.21.9 / 1.21.11 / 26.1.x → https://modrinth.com/mod/multiview.
>
> Happy to help set it up on a session you already recorded if you ever want to try.

**Why this works**
- Specific opener (proves you actually watch them).
- Value in one sentence.
- Visual upfront, link after.
- Open invitation, no pressure.

**Targets worth investigating first** (multi-POV recap territory):
- Empires SMP: fWhip, Pearlescent Moon, Smajor, Joel, LDShadowLady.
- Hermitcraft: Grian, Pearl, Mumbo, Etho, Iskall.
- Lifesteal SMP: Vitalasy, Spoke, Subz, Mapicc.
- FR : Inoxtag, Solary crew, Squeezie's Minecraft event teams.

---

## 2. Reddit post — r/feedthebeast or r/fabricmc

> **\[Mod\] MultiView 0.3.x — merge several Flashback replays into one omniscient-observer recording (Fabric)**
>
> If you've ever recorded a multi-player session and ended up with N separate `.flashback` files that you had to switch between by hand, this is what I built to stop doing that.
>
> What it does:
> - Takes 2+ replays from the same session, aligns them tick-perfect via `ClientboundSetTimePacket`.
> - Outputs a single replay containing the union of every chunk explored, every entity seen and every event recorded, with all the recording players visible at once.
> - Has a free camera or per-player Spectate on the merged result.
>
> Demo: **\[GIF link\]**
>
> Modrinth: https://modrinth.com/mod/multiview
> Source: https://github.com/Zeffut/MultiView (MIT)
>
> MC 1.21.9 / 1.21.10 / 1.21.11 / 26.1.x supported. Fabric only, requires Flashback.
>
> Feedback and bug reports welcome — I'm aware secondary POVs still show as player entities (Flashback only supports one local player) and 4+ POV merges can produce some chunk artefacts.

**Why this works**
- "Stop doing that" framing — the reader recognises the pain.
- Limitations stated up-front — builds trust on a modding subreddit.
- Source + Modrinth both linked.

**One-shot rule**: cross-posting the same body across many subreddits looks like spam. Pick the one that fits best and post there only.

---

## 3. Discord — Flashback server #addons or similar

> Hey — built a Fabric add-on for Flashback that fuses several players' replays into one unified replay. Aligns by `SetTime`, merges chunks/entities/events, lets you free-cam or spectate any of the recorders on the result. Free, MIT.
>
> 20 s demo: **\[GIF\]**
> Modrinth: https://modrinth.com/mod/multiview
> Source: https://github.com/Zeffut/MultiView
>
> Specifically tagging Moulberry in case there's something I should be doing differently to play nice with Flashback internals.

**Why this works**
- Short and concrete.
- Names the Flashback maintainer for visibility (don't @-spam — mention once, politely).

---

## 4. Twitter / X reply to a recap-style video

When a creator posts a multi-POV recap teaser, reply once with:

> If you ever want to do this from a single merged replay (free cam over the whole session, every player visible at once), this Fabric add-on for Flashback does exactly that: https://modrinth.com/mod/multiview — happy to help set it up.

Do **not** lead with the link. Always lead with the value sentence.

---

## 5. Cold email to an esports / tournament organiser

Subject: **One-replay multi-angle review for your \[EVENT NAME\] casters**

> Hi **\[NAME\]**,
>
> I built a free, open-source Fabric add-on that takes several players' Flashback recordings of the same Minecraft session and merges them into a single replay containing every player's POV at the same time. The result has a free camera so a caster or commentator can review any incident from any angle without juggling N separate files.
>
> 20-second demo: **\[GIF/VIDEO\]**
>
> If that's useful for **\[EVENT / SHOW\]**, I'm happy to walk one of your editors through a real session — no obligation.
>
> All the technical details and the source code are at https://github.com/Zeffut/MultiView (MIT).
>
> — **\[YOUR NAME\]**

---

## What **not** to do

- Don't DM the same message to 30 people in one day. Personalise the first sentence each time.
- Don't post the same link across half a dozen subreddits — reddit's anti-spam will shadowban.
- Don't argue with someone who passes or ignores you. One follow-up after two weeks max is fine; more than that is harassment.
- Don't oversell. Mention the known limitations (secondary POVs are entities, 4+ POVs can have artefacts) up-front when talking to technical audiences — it builds far more trust than hiding them.
