2026-10-11, 17:03, Created AGENTS.md with project overview, build commands, architecture, and conventions
2026-10-11, 17:12, Renamed project from Minecraft to Kubold for legal safety; added README.md, non-commercial LICENSE; updated pom.xml, Main.kt, Entity.kt, AGENTS.md
2026-10-11, 18:20, Renamed project from Kubold to StrataDiver; restructured packages and directories
2026-10-11, 18:30, Implemented control routing: GAMEPLAY / INTERACTIVE modes; fixed inventory mouse interaction (cursor pos readback)
2026-10-11, 18:40, User confirmed: inventory interaction works, digging down to bedrock works; next: visible inventory slots, remove hotbar duplication (always-on hotbar + inventory above it)
2026-10-11, 18:45, [FEATURE] 8ce4ae4 Minecraft-style inventory: beveled slot frames + selected-slot white frame (new atlas tiles (3,1)/(0,2)), dark panel (3,2); 3x9 grid sits above the always-on hotbar; hotbar no longer duplicated inside inventory
2026-10-11, 17:21, Fixed build for JDK 23 (Kotlin 1.9.0 incompatible with Java 27); committed all changes
2026-10-11, 17:30, Build verified with JDK 23; updating docs before runtime debugging
2026-10-11, 17:35, Fixed macOS GLFW startup crash (-XstartOnFirstThread via exec:exec); game launches and runs without crashing
2026-10-11, 17:40, Debugging complete — game runs stably; ready for feature work
2026-10-11, 17:45, Connected remote git@github.com:GrindelfP/kubold.git and pushed main branch
2026-10-11, 18:32, Updated remote to git@github.com:GrindelfP/StrataDiver.git (repo moved on GitHub); commits: rename [UPDATE] cb1d080, control routing [FEATURE] 17ca5df
2026-10-11, 17:50, Initialized agent skills filesystem: docs/agents/issue-tracker.md, triage-labels.md, domain.md; updated AGENTS.md
2026-10-11, 18:00, User explored game for ~7 min — no crashes, core loop works (terrain, physics, breaking/placing, item pickup)
2026-10-11, 18:15, Fixed 4 bugs: spawn collision push-up, input blocked when inventory open, GUI rewritten from 3D cubes to 2D screen-space quads, hotbar rendering added
