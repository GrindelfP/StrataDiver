2026-10-11, 17:03, Created AGENTS.md with project overview, build commands, architecture, and conventions
2026-10-11, 17:12, Renamed project from Minecraft to Kubold for legal safety; added README.md, non-commercial LICENSE; updated pom.xml, Main.kt, Entity.kt, AGENTS.md
2026-10-11, 18:20, Renamed project from Kubold to StrataDiver; restructured packages and directories
2026-10-11, 18:30, Implemented control routing: GAMEPLAY / INTERACTIVE modes; fixed inventory mouse interaction (cursor pos readback)
2026-10-11, 17:21, Fixed build for JDK 23 (Kotlin 1.9.0 incompatible with Java 27); committed all changes
2026-10-11, 17:30, Build verified with JDK 23; updating docs before runtime debugging
2026-10-11, 17:35, Fixed macOS GLFW startup crash (-XstartOnFirstThread via exec:exec); game launches and runs without crashing
2026-10-11, 17:40, Debugging complete — game runs stably; ready for feature work
2026-10-11, 17:45, Connected remote git@github.com:GrindelfP/kubold.git and pushed main branch
2026-10-11, 17:50, Initialized agent skills filesystem: docs/agents/issue-tracker.md, triage-labels.md, domain.md; updated AGENTS.md
2026-10-11, 18:00, User explored game for ~7 min — no crashes, core loop works (terrain, physics, breaking/placing, item pickup)
2026-10-11, 18:15, Fixed 4 bugs: spawn collision push-up, input blocked when inventory open, GUI rewritten from 3D cubes to 2D screen-space quads, hotbar rendering added
