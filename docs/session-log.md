2026-10-11, 17:03, Created AGENTS.md with project overview, build commands, architecture, and conventions
2026-10-11, 17:12, Renamed project from Minecraft to Kubold for legal safety; added README.md, non-commercial LICENSE; updated pom.xml, Main.kt, Entity.kt, AGENTS.md
2026-10-11, 18:20, Renamed project from Kubold to StrataDiver; restructured packages and directories
2026-10-11, 18:30, Implemented control routing: GAMEPLAY / INTERACTIVE modes; fixed inventory mouse interaction (cursor pos readback)
2026-10-11, 18:40, User confirmed: inventory interaction works, digging down to bedrock works; next: visible inventory slots, remove hotbar duplication (always-on hotbar + inventory above it)
2026-10-11, 18:45, [FEATURE] 8ce4ae4 Minecraft-style inventory: beveled slot frames + selected-slot white frame (new atlas tiles (3,1)/(0,2)), dark panel (3,2); 3x9 grid sits above the always-on hotbar; hotbar no longer duplicated inside inventory
2026-10-11, 18:55, [DELETE] 5f89929 removed leftover shaders/orig-art, orig-art-p, copy-art, copy-art-p junk files (still exist in git history; full purge via filter-repo possible on request); [BUGFIX] f4319ef inventory panel stopped covering the always-on hotbar; brightened hotbar/inventory colors (slot interior 55→115, panel 35→~90)
2026-10-11, 19:00, Session wrap-up: user confirmed hotbar stays visible with inventory open, art files gone from working tree/remote main, brightness acceptable; head to final commit dbb751c
2026-10-11, 17:21, Fixed build for JDK 23 (Kotlin 1.9.0 incompatible with Java 27); committed all changes
2026-10-11, 17:30, Build verified with JDK 23; updating docs before runtime debugging
2026-10-11, 17:35, Fixed macOS GLFW startup crash (-XstartOnFirstThread via exec:exec); game launches and runs without crashing
2026-10-11, 17:40, Debugging complete — game runs stably; ready for feature work
2026-10-11, 17:45, Connected remote git@github.com:GrindelfP/kubold.git and pushed main branch
2026-10-11, 18:32, Updated remote to git@github.com:GrindelfP/StrataDiver.git (repo moved on GitHub); commits: rename [UPDATE] cb1d080, control routing [FEATURE] 17ca5df
2026-10-11, 17:50, Initialized agent skills filesystem: docs/agents/issue-tracker.md, triage-labels.md, domain.md; updated AGENTS.md
2026-10-11, 18:00, User explored game for ~7 min — no crashes, core loop works (terrain, physics, breaking/placing, item pickup)
2026-10-11, 18:15, Fixed 4 bugs: spawn collision push-up, input blocked when inventory open, GUI rewritten from 3D cubes to 2D screen-space quads, hotbar rendering added

2026-10-11, 22:20, Design session (grill-with-docs). Settled: 16x16 Art upscaled 4x with mipmap padding (Q1/Q9); Icons composited at draw time from three Tiles with a tint uniform, Minecraft face brightness 1.0/0.72/0.55 (Q2/Q10); leaves are Cutout + alpha discard (Q3); oak-shaped canopy (Q4); GUI fully unlit via isGui uniform (Q5); per-face Tiles as a general BlockType capability incl. grass-side fringe and wood-top rings (Q12); panel 198 / slot 139, inverting current values (Q11); leaves Drop nothing (Q8). Split CONTEXT.md into a domain glossary + docs/session-log.md; added Opaque, Cutout, Icon, Silhouette, Panel terms.

2026-10-11, 23:10, [FEATURE] New art pipeline: authored 16x16 ASCII art per tile (Art.kt) enlarged 4x with edge-bleed guard ring; LEAVES BlockType (id 6, non-opaque) with dense cutout alpha; per-face tiles (grass-top/side, wood-top rings); oak canopy with trimmed corners and varied trunk height; opacity-based face culling; GUI unlit via isGui + tint uniform; isometric Icons composited at draw time; panel 198/slot 139; leaves drop nothing; crosshair gets a real cutout tile. Atlas generated with mipmaps.
