# 통합 API 백엔드 (integration-api-backend)

여러 프론트엔드에 계정별 데이터를 제공하는 **통합 REST API 백엔드**.
현재 첫 소비자는 `momentum_v3`(별빛 투두) 프론트엔드의 **계정별 일기/설정/할일/메모**.

- Java 17 · Spring Boot 3.4 · Spring Security · Spring Data JPA
- DB: PostgreSQL (개발은 로컬 5433, 운영은 `prod` 프로파일)
- 인증: stateless JWT (`Authorization: Bearer <token>`)

## 실행

`DB_PASSWORD`는 기본값이 없습니다 (커밋되는 파일에 비밀번호를 넣지 않기 위함) — 실행 전에 셸에서 export 해주세요.

```bash
export DB_PASSWORD=...   # 로컬 5433 Postgres의 integration 계정 비밀번호
./gradlew bootRun
```

- API: http://localhost:8081

운영(PostgreSQL):

```bash
DB_URL=jdbc:postgresql://localhost:5432/integration DB_USER=integration DB_PASSWORD=... \
  ./gradlew bootRun --args='--spring.profiles.active=prod'
```

## 데이터 모델 (momentum_v3 프론트 localStorage → 테이블)

| 프론트 localStorage 키   | 테이블      | 관계        |
|--------------------------|-------------|-------------|
| `byeolbit_settings`      | `settings`  | User와 1:1  |
| `byeolbit_diaries`       | `diaries`   | User와 1:N  |
| `byeolbit_todos`         | `todos`     | User와 1:N  |
| `byeolbit_notes`         | `notes`     | User와 1:N  |

> 참고: `byeolbit_*`는 프론트엔드가 실제로 쓰는 localStorage 키 이름이라 그대로 둡니다.

모든 조회/수정은 로그인된 사용자의 `userId`로 필터링되어, 남의 데이터에 접근할 수 없습니다.

## API

### 인증 `/api/auth`
| 메서드 | 경로              | 설명                         |
|--------|-------------------|------------------------------|
| POST   | `/api/auth/signup`| 회원가입 `{email,password,name}` → `{token,user}` |
| POST   | `/api/auth/login` | 로그인 `{email,password}` → `{token,user}` |
| GET    | `/api/auth/me`    | 현재 로그인 사용자 (Bearer 토큰 필요) |

### 리소스 (로그인 필요)
| 리소스   | 엔드포인트                                                        |
|----------|------------------------------------------------------------------|
| settings | `GET/PUT /api/settings`                                          |
| diaries  | `GET /api/diaries` · `PUT/DELETE /api/diaries/{dateKey}`         |
| todos    | `GET/POST /api/todos` · `PATCH/DELETE /api/todos/{id}`           |
| notes    | `GET/POST /api/notes` · `PATCH/DELETE /api/notes/{id}`           |

## 프론트엔드 연동

- 인증은 stateless JWT: `/api/auth/login`·`/signup` 응답의 `token`을 저장해두고, 이후 요청에 `Authorization: Bearer <token>` 헤더로 전송.
- CORS 허용 origin은 `application.yml`의 `app.cors.allowed-origin` (기본 `http://localhost:5173`).
- 로컬 개발 시 Vite `server.proxy`로 `/api`를 8081에 프록시하면 CORS 걱정 없이 상대경로로 호출 가능.

## 다음 단계 (선택 · 학습용 이벤트 흐름)

핵심 CRUD는 REST로 두고, 일기 저장 이후의 비동기 부수효과(통계 집계/알림 등)를
`diary.created` 이벤트 → Kafka 컨슈머로 분리하는 구성을 이후 단계에서 얹을 수 있음.
"통합" 백엔드답게 새 프론트/서비스가 붙을 때 이 이벤트 계층이 확장 지점이 됩니다.
