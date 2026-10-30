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
| Global `@RestControllerAdvice` outside the module (shared web package) | review R1 (architecture decision, should be raised) |
| Catching exceptions in domain/application to produce HTTP-shaped errors | review R1 |

## Reference solution outline
Extend `OrderApiExceptionHandler`: override `handleHttpMessageNotReadable` (fixed safe detail),
`handleExceptionInternal` to add `correlationId` to every `ProblemDetail` (from `MDC.get("correlationId")` or the
response header), a catch-all `Exception` handler logging at `ERROR` and returning the fixed `500` body;
`OrderNotFoundException` exposes the order ID for the `orderId` property. `@WebMvcTest` cases for each status.
