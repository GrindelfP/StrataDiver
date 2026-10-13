# 2. Sounds are synthesized at runtime, behind OpenAL

Date: 2026-10-13
Status: accepted

## Context

The game had no audio at all: no dependency, no code, no asset file, no vocabulary. The
first sounds it needs are explicitly placeholders — synthesized approximations standing in
for recordings that do not exist yet, and which are expected to be replaced by real audio
later.

Two things follow from "placeholder", and they pull in opposite directions.

The obvious approach is to synthesize buffers at runtime: a few milliseconds of float
math per sound, no files in the repository, and nothing to load. The alternative is to
generate audio files once and commit them, which buys inspectable and diffable assets and
lets real recordings be dropped in later without touching code — at the cost of writing a
file format parser, and of committing to that format for sounds nobody intends to keep.

The backend has to be chosen at the same time. `javax.sound.sampled` ships with the JDK
and needs no dependency, but offers no positional audio at all and pulls AWT into a game
that otherwise uses only GLFW and OpenGL.

## Decision

**OpenAL**, via `lwjgl-openal`, matching the existing LWJGL modules and version. It gives
3D positional audio natively, which is the point: a block broken to the player's left is
heard from the left. The cost is a device that must be opened at startup and can fail, so
the engine treats audio as optional — if the device will not open it logs and disables
itself, and the game runs silently rather than refusing to start.

**Synthesis at runtime, with no audio files in the repository.** There is deliberately no
WAV reader in the codebase, because writing one to load sounds that are placeholders would
be building for a future that has not been chosen yet.

The shape that makes this safe is that **synthesis sits behind a buffer interface**. Every
sound is generated once into an immutable PCM buffer, keyed by what caused it and what it
is made of. No call site knows how a buffer was produced. Swapping in real recordings
therefore means replacing one class, not editing the game's event code.

## Consequences

- **The repository contains no binary audio.** Anyone expecting to hear something by
  looking at the files will find none; the sounds are described in source.
- **Adding a Block Type means adding one Material mapping, not a sound.** Many Block Types
  share a Material — grass and dirt are both earth — so the sound set grows by material,
  not by block.
- **The buffers are deterministic.** Synthesis is seeded, so the same launch produces
  byte-identical audio. That is what makes the headless checks in `tools/Check.kt`
  possible: they assert things like "no buffer clips", "no buffer is silent", and "different
  Materials produce measurably different spectra". Audio is unusually verifiable for
  something you cannot hear.
- **Synthesized audio is easy to get wrong in silence.** A filter that cancels to zero or
  an envelope that never opens produces a buffer that is perfectly valid and inaudible.
  This is the specific reason for the RMS assertions.
- **Positional sounds cost a listener update per frame**, and the voice pool needs a cap:
  clipping is painful in a way that dropped sounds during a burst are not.

## Alternatives considered

- **`javax.sound.sampled`.** No new dependency, but no positional audio, and AWT in a GLFW
  game. Positional audio is most of what a first-person game's sound is *for*, and adding
  it later means replacing the backend.
- **Committed WAV assets, loaded through a parser.** Better for a final game with real
  recordings. Wrong for placeholders: it adds a file format to the codebase to serve audio
  that exists to be thrown away.
- **Synthesize and commit both** — a generator that writes files the game then loads.
  Defers the parser question, but builds two subsystems for sounds that have one obvious
  next step.
- **Sound in the shaders.** Not possible; audio is not a GPU concern, and this would be
  worth saying out loud given that the minimap's failure was a shader pretending to have
  data it could not access.
