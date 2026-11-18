# Kirana backend — rules for Claude

Read PLAN.md before any task. It is the source of truth for data model, API and statuses.

- Java 21, Spring Boot 3, Maven, PostgreSQL, Flyway, Lombok.
- Package by feature (auth, store, request, quote, parchi, common), as in PLAN.md.
- Controllers return DTOs, never JPA entities. Validate input with jakarta.validation.
- All errors go through GlobalExceptionHandler as {code, message} JSON.
- Schema changes only through a new Flyway migration; never edit an applied one.
- Accepting a quote must be transactional and safe against double-accept (conditional update or @Version), returning 409 on conflict.
- total_price is always computed on the server.
- Write a short test for every service method that changes status.
- Build only the current phase. Do not add features that are not in PLAN.md; ask first.
- After each task, list the files changed and how to test it with curl.
