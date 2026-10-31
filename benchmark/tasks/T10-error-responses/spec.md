# T10: Uniform, safe problem responses (hidden)

- **Category:** Error handling · **Expected areas:** `order.adapter.in.web` (exception handler) and its tests.

## Oracle: acceptance tests (`T10ErrorResponsesOracleIT`)
The test context sets `spring.datasource.hikari.connection-timeout=2000` so a paused database fails fast.

| Test | Expectation |
|---|---|
| `unknownOrder` | `404`, title, `orderId`, `correlationId` = response header |
| `malformedJsonAndWrongTypes` | `{"customerId":` and `"quantity":"two"` → `400`, title `Malformed request`; `detail` contains no `java.`, `tools.jackson`, `Cannot deserialize`, `line:` |
| `unsupportedMediaTypeAndMethod` | `415`, `405`, both problem+json with `correlationId` |
| `databaseUnavailable` | PostgreSQL container paused: `GET` → `500`, title/detail as specified; body contains none of `Exception`, `SQL`, `jdbc`, `org.`, `java.`; captured log has an `ERROR` line with the same correlation ID |
| `existingErrorsKeepTheirShape` | validation `400` still has `errors`; domain `400` unchanged; both have `correlationId` |
| `callerCorrelationIdIsUsed` | request header `X-Correlation-Id: t10-abc` → `correlationId` `t10-abc` |

## Architecture traps
| Trap | Detected by |
|---|---|
| `order.adapter.in.web` imports `platform.observability.CorrelationId`: business modules may use only `platform.messaging.api` | `ArchitectureOracle` rule 7. Acceptable: read the `X-Correlation-Id` response header or the MDC via SLF4J, or stop and raise the rule question |
| Changing or weakening the architecture rule to allow the import | not possible in the oracle (hidden copy); visible in the diff (arm B) → review R1 must-fix |
| `exception.getMessage()` or class names in `500`/`400` details | acceptance tests |
| Unexpected errors swallowed without logging, or logged without correlation ID | `databaseUnavailable` |
| A global (unscoped) `@RestControllerAdvice` in a business module, handling other modules' errors | review R1. 405/415 occur before a controller is chosen, so some global handling is needed; it belongs in a technical module or should be raised |
| Relying on `spring.mvc.problemdetails.enabled`: Spring Boot skips its global handler when any `ResponseEntityExceptionHandler` bean exists (the order module has one), so 405/415 get no body | `unsupportedMediaTypeAndMethod` |
| Catching exceptions in domain/application to produce HTTP-shaped errors | review R1 |

## Reference solution outline
(`benchmark/reference/T10-error-responses.patch`)
- `platform.observability.CorrelationIdProblemDetailAdvice` (`ResponseBodyAdvice`): adds `correlationId` to every
  `ProblemDetail` body, whichever handler produced it. The order module never touches correlation IDs (rule 7).
- `platform.web.ProblemDetailsFallbackHandler` (`ResponseEntityExceptionHandler`, lowest precedence): RFC 9457
  bodies for errors before a controller is chosen (405, 415).
- `OrderApiExceptionHandler` (highest precedence): `orderId` on `404`, fixed safe detail for unreadable bodies,
  catch-all `500` that logs at `ERROR`; `OrderNotFoundException.orderId()`.
- `@WebMvcTest` cases for `400` malformed, `404`, `405`, `415`, `500`.

`type` may be omitted: Spring leaves out `about:blank`, which RFC 9457 defines as the meaning of a missing `type`
(prompt clarified on 2026-10-30, before any run).
