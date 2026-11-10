# Stage 1 change list
Fixed (static review, unverified by compiler):
- `catch (_: Exception)` x5 (Audio.kt, AssistService.kt): Kotlin 1.9 reserves `_` there -> renamed.
- `ScheduledExecutorService.schedule { }` lambda ambiguity (Runnable vs Callable) -> explicit `Runnable {}`.
- `vibrateOnce`: annotation-on-expression after `else` -> restructured.
- Build: model download no longer hooked to `preBuild` (broke offline unit tests); now only before `merge*Assets`; `-PskipModel` added; JUnit added.
- `I18n.S` made `internal` so tests can check language parity.
New: Metrics.kt (FrameStats, DeviceVitals, RunLogger), Replay.kt (ReplayRunner), FrameDetector interface, replay UI, 7 test files, CI workflow.
Findings NOT fixed yet (belong to later stages):
- Ground-hazard labels (stairs, pothole, ...) get no range in estimateRange -> Pipeline drops them (Stage 2).
- No roll correction, no step/sway compensation, pitch from raw gravity sensor (Stage 4).
- Pipeline/AlertEngine language is fixed at start; `Track.nextId` is global state.
- Rotation: a full bitmap copy per frame (GC churn); candidate optimisation in Stage 2.
- Stop notification action when service is not running would start it without foreground (edge case).
