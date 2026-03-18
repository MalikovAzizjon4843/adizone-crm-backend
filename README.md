# Education CRM System — Backend

Production-level Spring Boot backend for managing an education center with 3000+ students.

---

## Tech Stack

| Layer       | Technology                        |
|-------------|-----------------------------------|
| Framework   | Spring Boot 3.2, Java 17          |
| Database    | PostgreSQL 15+                    |
| ORM         | Spring Data JPA / Hibernate       |
| Security    | Spring Security + JWT             |
| Migration   | Flyway                            |
| Build       | Maven                             |

---

## Quick Start

### 1. PostgreSQL Setup

```sql
CREATE DATABASE crm_db;
CREATE USER crm_user WITH PASSWORD 'crm_password';
GRANT ALL PRIVILEGES ON DATABASE crm_db TO crm_user;
```

### 2. Configure `application.yml`

Edit `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/crm_db
    username: crm_user
    password: crm_password
jwt:
  secret: YOUR_256_BIT_HEX_SECRET
```

### 3. Build & Run

```bash
mvn clean package -DskipTests
java -jar target/crm-system.jar
```

Default port: **8080**

---

## Default Credentials

| Username    | Password   | Role        |
|-------------|------------|-------------|
| superadmin  | Admin@123  | SUPER_ADMIN |

> Change the password immediately after first login!

---

## API Endpoints

### Authentication
| Method | Endpoint              | Access      | Description        |
|--------|-----------------------|-------------|--------------------|
| POST   | `/api/auth/login`     | Public      | Login, get JWT     |
| POST   | `/api/auth/register`  | SUPER_ADMIN | Create new user    |
| GET    | `/api/auth/me`        | Authenticated | Current user     |

### Students
| Method | Endpoint              | Access         | Description         |
|--------|-----------------------|----------------|---------------------|
| GET    | `/api/students`       | All roles      | List (paginated, search, filter) |
| GET    | `/api/students/{id}`  | All roles      | Student detail + groups + payments |
| POST   | `/api/students`       | ADMIN+         | Create student      |
| PUT    | `/api/students/{id}`  | ADMIN+         | Update student      |
| DELETE | `/api/students/{id}`  | ADMIN+         | Soft delete (status=LEFT) |

**Query params for GET /api/students:**
- `page` (default: 0), `size` (default: 20)
- `search` — searches firstName, lastName, phone
- `status` — ACTIVE | FROZEN | FINISHED | LEFT

### Courses
| Method | Endpoint              | Description     |
|--------|-----------------------|-----------------|
| GET    | `/api/courses`        | List courses    |
| GET    | `/api/courses/{id}`   | Course detail   |
| POST   | `/api/courses`        | Create course   |
| PUT    | `/api/courses/{id}`   | Update course   |
| DELETE | `/api/courses/{id}`   | Deactivate      |

### Groups
| Method | Endpoint                              | Description             |
|--------|---------------------------------------|-------------------------|
| GET    | `/api/groups`                         | List groups (filter by status) |
| GET    | `/api/groups/{id}`                    | Group detail            |
| POST   | `/api/groups`                         | Create group            |
| PUT    | `/api/groups/{id}`                    | Update group            |
| DELETE | `/api/groups/{id}`                    | Cancel group            |
| POST   | `/api/groups/students`                | Add student to group    |
| DELETE | `/api/groups/{groupId}/students/{studentId}` | Remove student |

### Teachers
| Method | Endpoint              | Description     |
|--------|-----------------------|-----------------|
| GET    | `/api/teachers`       | List teachers   |
| GET    | `/api/teachers/{id}`  | Teacher detail  |
| POST   | `/api/teachers`       | Create teacher  |
| PUT    | `/api/teachers/{id}`  | Update teacher  |
| DELETE | `/api/teachers/{id}`  | Deactivate      |

### Attendance
| Method | Endpoint                        | Description             |
|--------|---------------------------------|-------------------------|
| POST   | `/api/attendance/mark`          | Mark attendance (bulk)  |
| GET    | `/api/attendance/group/{id}`    | Group attendance by date |
| GET    | `/api/attendance/student/{id}`  | Student attendance log  |

### Payments
| Method | Endpoint                      | Description          |
|--------|-------------------------------|----------------------|
| POST   | `/api/payments`               | Record payment       |
| GET    | `/api/payments/student/{id}`  | Student payment history |
| GET    | `/api/payments/debtors`       | List all debtors     |

### Finance
| Method | Endpoint                 | Description           |
|--------|--------------------------|-----------------------|
| GET    | `/api/finance/expenses`  | List expenses         |
| POST   | `/api/finance/expenses`  | Add expense           |
| GET    | `/api/finance/report`    | Income/expense report |

### Analytics
| Method | Endpoint                    | Description         |
|--------|-----------------------------|---------------------|
| GET    | `/api/analytics/dashboard`  | Full dashboard data |
| GET    | `/api/analytics/revenue`    | Revenue analytics   |
| GET    | `/api/analytics/students`   | Student analytics   |

### Marketing
| Method | Endpoint                  | Description              |
|--------|---------------------------|--------------------------|
| GET    | `/api/marketing/sources`  | Students by source count |

---

## Authentication Flow

```
POST /api/auth/login
Body: { "username": "superadmin", "password": "Admin@123" }

Response:
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "role": "SUPER_ADMIN"
  }
}
```

Use the token in all subsequent requests:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

---

## Role Permissions

| Endpoint Group     | SUPER_ADMIN | ADMIN | TEACHER | ACCOUNTANT |
|--------------------|:-----------:|:-----:|:-------:|:----------:|
| Auth               | ✅          | ✅    | ✅      | ✅         |
| Students (read)    | ✅          | ✅    | ✅      | ✅         |
| Students (write)   | ✅          | ✅    | ❌      | ❌         |
| Groups (read)      | ✅          | ✅    | ✅      | ✅         |
| Groups (write)     | ✅          | ✅    | ❌      | ❌         |
| Attendance         | ✅          | ✅    | ✅      | ❌         |
| Payments           | ✅          | ✅    | ❌      | ✅         |
| Finance            | ✅          | ✅    | ❌      | ✅         |
| Analytics          | ✅          | ✅    | ❌      | ❌         |
| Register users     | ✅          | ❌    | ❌      | ❌         |

---

## Payment Logic

When a student joins a group:
- `paymentStartDate` = join date
- `nextPaymentDate` = join date + 30 days

After each payment:
- `nextPaymentDate` += 30 days

**Debtor detection:** `today > nextPaymentDate` → student is a debtor.

---

## Linux Server Deployment

```bash
# 1. Build
mvn clean package -DskipTests

# 2. Upload to server
scp target/crm-system.jar user@your-server:/opt/crm/

# 3. Create systemd service
sudo nano /etc/systemd/system/crm.service
```

`/etc/systemd/system/crm.service`:
```ini
[Unit]
Description=Education CRM System
After=network.target postgresql.service

[Service]
User=crm
WorkingDirectory=/opt/crm
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/crm/crm-system.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable crm
sudo systemctl start crm
```

### Nginx Reverse Proxy

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location /api/ {
        proxy_pass http://localhost:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## Project Structure

```
src/main/java/com/crm/
├── config/           # SecurityConfig, JpaConfig, WebConfig
├── controller/       # REST controllers (AuthController, StudentController, ...)
├── service/          # Business logic
├── repository/       # Spring Data JPA repositories
├── entity/           # JPA entities
│   └── enums/        # Enums (UserRole, StudentStatus, ...)
├── dto/
│   ├── request/      # Input DTOs with validation
│   └── response/     # Output DTOs
├── security/
│   ├── jwt/          # JwtUtils, JwtAuthenticationFilter
│   └── CustomUserDetailsService.java
└── exception/        # GlobalExceptionHandler, custom exceptions
```

---

## Environment Variables (Production)

Override in production via env vars or external config:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/crm_db
SPRING_DATASOURCE_USERNAME=crm_user
SPRING_DATASOURCE_PASSWORD=secure_password
JWT_SECRET=your_256bit_hex_secret_here
SERVER_PORT=8080
```
