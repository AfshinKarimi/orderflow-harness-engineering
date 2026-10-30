# ArchUnit violation demo: controller depending on a repository

Date: 2026-10-30. Shows that the architecture tests catch a controller that bypasses the use case layer.
All output below is copied from real runs (`./mvnw test -Dtest=ArchitectureTest`, JDK 21).
The temporary classes were deleted afterwards; nothing intentionally broken remains in `main`.

## Violated rule

**Rule 4** ([architecture.md](architecture.md#inside-a-module-dependencies-point-inward)): inbound adapters call the
application only through `port.in`. They must never use `port.out`, outbound adapters or Spring Data repositories.

Purpose: a controller that talks to a repository skips the domain (invariants such as "an order needs at least
one line") and the use case's transaction boundary, and business logic starts leaking into the web layer.

ArchUnit test: `ArchitectureTest.rule4_inboundAdaptersDoNotAccessRepositories`.

## The violation

Temporary class `com.orderflow.order.adapter.in.web.TempViolatingOrderController`:

```java
@RestController
class TempViolatingOrderController {

    private final OrderRepository orderRepository;   // application.port.out

    TempViolatingOrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }
}
```

## Finding: the original rule set missed it

With the original 9 rules the violation was **not** detected:

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Existing rules only blocked `adapter.in → application.service` and adapter-to-adapter dependencies;
`adapter.in → application.port.out` was allowed. We added `rule4_inboundAdaptersDoNotAccessRepositories`:

```java
noClasses().that().resideInAPackage("com.orderflow.*.adapter.in..")
        .should().dependOnClassesThat().resideInAnyPackage(
                "com.orderflow.*.application.port.out..", "com.orderflow.*.adapter.out..")
        .orShould().dependOnClassesThat().areAssignableTo(Repository.class)   // Spring Data, anywhere
```

## Failure output (with the new rule)

```
[ERROR] com.orderflow.architecture.ArchitectureTest.rule4_inboundAdaptersDoNotAccessRepositories <<< FAILURE!
Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside in a package 'com.orderflow.*.adapter.in..'
should depend on classes that reside in any package ['com.orderflow.*.application.port.out..',
'com.orderflow.*.adapter.out..'] or should depend on classes that are assignable to
org.springframework.data.repository.Repository, because rule 4: controllers and listeners go through use cases,
never repositories, so domain rules and transaction boundaries cannot be bypassed' was violated (2 times):
Constructor <com.orderflow.order.adapter.in.web.TempViolatingOrderController.<init>(com.orderflow.order.application.port.out.OrderRepository)> has parameter of type <com.orderflow.order.application.port.out.OrderRepository> in (TempViolatingOrderController.java:0)
Field <com.orderflow.order.adapter.in.web.TempViolatingOrderController.orderRepository> has type <com.orderflow.order.application.port.out.OrderRepository> in (TempViolatingOrderController.java:0)

[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

The Spring Data branch was verified the same way: a temporary `TempSpringDataRepository extends Repository<Object, Long>`
placed in `com.orderflow.order` (outside any adapter package) and injected into the controller also failed
`rule4_inboundAdaptersDoNotAccessRepositories` (2 violations: constructor parameter and field).

## Corrected design

The controller depends only on the inbound port; the use case owns validation, the domain call and persistence:

```java
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final CreateOrderUseCase createOrderUseCase;   // application.port.in

    OrderController(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }
}
```

```
adapter.in.web ──▶ application.port.in ◀── application.service ──▶ application.port.out ◀── adapter.out.persistence
```

## Successful result after removing the violation

`./mvnw clean verify`:

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in com.orderflow.architecture.ArchitectureTest
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
