# Capsule MUSIC repository guidance

When editing Jetpack Compose transitions, settings, dialog animations, preference controls or reorderable priority lists, **read and follow** [Capsule Motion Skill](.agents/skills/capsule-motion/SKILL.md).

For changes to Android animations:
- Match movement to the user's explicit visual description and existing player motion.
- Keep one owner per transform; do not mix independent route and screen-internal transforms.
- Preserve existing audio/video logic, UI color extraction and the original palette.
- Run Gradle CI, Android lint, focused tests and the Universal Debug APK workflow.
- Keep visual adjustments in review branches and describe exactly what has/has not been verified on a physical device.
