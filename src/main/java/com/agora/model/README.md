# model package guideline

This package is reserved for persistent domain models.

## What belongs here
- JPA persistence classes annotated with `@Entity`, `@Embeddable`, or `@MappedSuperclass`.
- Domain objects that are part of database mapping and repository access.

## What should NOT be placed here
- In-memory conversation/session state for Telegram or bot flows.
- Service-specific runtime models used only inside one service.
- Request/response transport objects (use `dto`).

## Current structure note
- Most business entities are in this package.
- Some telemetry/game entities are in `com.agora.entity`.
- New non-persistent runtime models should be placed under feature-specific packages, e.g.:
  - `com.agora.bot.conversation`
  - `com.agora.service.auth.model`

## Future cleanup (optional)
Choose one long-term convention and migrate incrementally:
1. Keep all persistence classes under `com.agora.model`.
2. Or move all persistence classes to `com.agora.entity`.

Avoid mixing conventions for newly added files.
