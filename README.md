# Sunrise Dental Clinic — Patient Appointment & Management System

A Java web application built for CIS6003 Advanced Programming (Cardiff Metropolitan
University, delivered via ICBT Campus), replacing Sunrise Dental Clinic's paper-based
appointment and billing process with a role-based, database-backed system.

## Technology stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 |
| Web tier | Jakarta Servlet API 6.1 (annotation-routed, no `web.xml` mappings) |
| Application server | Apache Tomcat (any Jakarta EE 10-compatible servlet container) |
| Database | MySQL 8.x |
| Connection pooling | HikariCP 6.3 |
| Password hashing | jBCrypt 0.4 |
| Build | Maven (WAR packaging) |
| Testing | JUnit 5 (Jupiter), integration-style against a real database |
| Frontend | Static HTML/CSS/vanilla JavaScript served from `src/main/webapp`, calling the JSON API over `fetch` |

## Architecture

Three logical tiers, each with a single responsibility:

```
Browser (HTML/CSS/JS)
        │  fetch() → JSON over HTTP
        ▼
Servlet layer   (com.sunrisedental.servlet)   — HTTP, routing, session, JSON (de)serialisation
        ▼
Service layer   (com.sunrisedental.service)   — validation, business rules, transactions
        ▼
DAO layer       (com.sunrisedental.dao)       — SQL, JDBC, result-set mapping
        ▼
MySQL 8 (HikariCP pool)
```

`AuthFilter` gates every `/api/appointments/*`, `/api/bills`, `/api/dentists`,
`/api/treatments/*`, `/api/staff/*`, `/api/patients/*`, `/api/reports`,
`/api/notifications/*` and `/api/profile/*` request behind an active session; role checks
inside each servlet (via `AuthorizationUtil`) then apply the fine-grained rule for that
action. See [`docs/RBAC.md`](docs/RBAC.md) for the full role model and its rationale.

## Getting started

1. Install MySQL 8.x, Java 21, Maven, and a Jakarta EE 10 servlet container (e.g. Tomcat 10.1+).
2. Create the database and seed data:
   ```
   mysql -u root -p < database/schema.sql
   ```
   Then replace every `$2a$10$REPLACE_WITH_REAL_BCRYPT_HASH` placeholder in `users` with a
   real BCrypt hash (see `PasswordUtil.hash`) before attempting to log in.
3. Copy `src/main/resources/db.properties.example` to `db.properties` and fill in your local
   credentials (this file is gitignored and never committed), **or** set the `DB_HOST`,
   `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` environment variables — `DatabaseConfig`
   prefers environment variables and falls back to `db.properties`.
4. Build and run the tests:
   ```
   mvn test
   ```
   Note: the test suite in `src/test/java` runs against a real MySQL database (see
   `BackendVerificationTest`, `RbacVerificationTest`, etc.) rather than mocks, so a
   reachable, seeded `sunrise_dental` database is required before running `mvn test`.
5. Package and deploy the WAR:
   ```
   mvn package
   ```
   Deploy the resulting `target/sunrise-dental.war` to Tomcat's `webapps/` directory.

## Project structure

```
src/main/java/com/sunrisedental/
  db/          DatabaseConfig       — HikariCP pool, env/properties resolution
  model/       Patient, Dentist, Treatment, Appointment, Bill, Notification, User, ...
  dao/         one DAO per table — raw JDBC, PreparedStatement, no ORM
  service/     business rules, validation, transaction boundaries
  servlet/     HTTP endpoints, session handling, JSON responses
  util/        AuthorizationUtil, PasswordUtil, JsonUtil
  exception/   BusinessException (400/404), ForbiddenException (403)
src/main/webapp/
  pages/       one HTML page per feature area
  js/          one script per page, plus shared api.js/shell.js/toast.js
  css/         style.css, login.css, print.css
src/test/java/com/sunrisedental/   6 JUnit 5 integration test classes (107 test methods)
database/schema.sql                 reverse-engineered MySQL schema + seed data
docs/RBAC.md                        role-based access control design rationale
```

## Testing

The test suite is integration-style: every class connects to a real MySQL database via
`DatabaseConfig` and cleans up its own rows in `@AfterAll`. Run with:

```
mvn test
```

See `docs/REPORT.md` §5 for the full testing strategy, traceability matrix, and test
case tables.

## Continuous Integration

`.github/workflows/ci.yml` builds the project and runs the test suite against a MySQL
service container on every push and pull request targeting `main`. See `docs/REPORT.md`
§6 for a full explanation of the workflow.
