---
name: adminifender-ui-review
description: Review Adminifender's Android UI and accessibility with device evidence.
---

# UI review

Read `AGENTS.md`, the relevant SRS/UI contracts and actual Compose source. Today there is no UI implementation to capture. Do not invent screenshots or delegate without explicit user request.

When the app exists, exercise phone-size Compose previews/emulators at minimum API and current owner API. Inspect screenshots plus interaction: semantics, TalkBack labels, keyboard navigation, touch targets, font scaling, contrast, loading/error/offline states, notification privacy and exact action review. Use synthetic archive content. For device-dependent passkeys, FCM, OCR and inference, record separate S24+ evidence. A screenshot is an observation, not proof that interaction or accessibility passes. Report scenario, device/viewport, exact checks, findings and evidence paths.
