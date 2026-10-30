# OrderFlow

Order processing service.

## Prerequisites
- JDK 21
- Docker (tests start PostgreSQL and Kafka via Testcontainers)

## Build and test
```bash
./mvnw verify
```

## Run locally
```bash
docker compose up -d          # PostgreSQL on localhost:5432, Kafka on localhost:9092
./mvnw spring-boot:run        # http://localhost:8080
```

## API
| Method | Path |
|---|---|
| `POST` | `/api/v1/orders` |
| `GET` | `/api/v1/orders/{id}` |
