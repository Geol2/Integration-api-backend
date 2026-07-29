# 통합 API 백엔드 (integration-api-backend)

여러 프론트엔드에 계정별 데이터를 제공하는 **통합 REST API 백엔드**.
현재 첫 소비자는 `momentum_v3`(달빛 서랍) 프론트엔드입니다 — 계정별 일기/설정/할일/메모에서
시작해 지금은 플레이리스트·통합 검색·Web Push 알림·회의록 녹음 업로드까지 제공합니다.

- Java 17 · Spring Boot 3.4 · Spring Security · Spring Data JPA
- DB: PostgreSQL (개발은 로컬 5433, 운영은 `prod` 프로파일의 5432)
- 인증: stateless JWT (`Authorization: Bearer <token>`) + 슬라이딩 만료
- 메일: Gmail SMTP (가입 이메일 인증코드 전송)
- Web Push: VAPID + aes128gcm (BouncyCastle)

## 실행

`DB_PASSWORD`는 기본값이 없습니다 (커밋되는 파일에 비밀번호를 넣지 않기 위함) — 실행 전에 셸에서 export 해주세요.

```bash
export DB_PASSWORD=...   # 로컬 5433 Postgres의 integration 계정 비밀번호
./gradlew bootRun
```

- API: **http://localhost:9090**  (프론트 Vite dev 프록시 대상)

운영(PostgreSQL):

```bash
DB_URL=jdbc:postgresql://localhost:5432/integration DB_USER=integration DB_PASSWORD=... \
JWT_SECRET=... \
  ./gradlew bootRun --args='--spring.profiles.active=prod'
```

> `prod`에서 `JWT_SECRET`은 기본값이 없어 미설정 시 기동이 실패합니다(fail-fast).

### 선택 환경 변수

| 변수 | 용도 |
|------|------|
| `MAIL_USERNAME` / `MAIL_APP_PASSWORD` | Gmail 주소 + 앱 비밀번호. 가입 인증코드 메일 발송에 사용 |
| `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` | Web Push 키쌍. `./gradlew -q vapidKeys`로 1회 생성. 미설정 시 알림만 비활성화되고 API는 정상 기동 |
| `N8N_RECORDING_WEBHOOK_URL` / `N8N_RECORDING_KEY` | 회의록 녹음 전달용 n8n 웹훅. 미설정 시 업로드가 503, API는 정상 기동 |
| `JWT_EXPIRATION_MINUTES` | 세션 만료(분). dev 기본 1440(24h), prod 기본 10080(7일) |

## 데이터 모델

| 리소스 | 테이블 | 관계 |
|--------|--------|------|
| 사용자 | `users` | — |
| 설정 | `settings` | User와 1:1 |
| 일기 | `diaries` | User와 1:N |
| 할일 | `todos` | User와 1:N |
| 메모 | `notes` | User와 1:N |
| 플레이리스트 트랙 | `tracks` | User와 1:N |
| Web Push 구독 | `push_subscriptions` | User와 1:N (브라우저당 1행) |
| 이메일 인증코드 | `email_verification` | 이메일별(가입 전) |

모든 조회/수정은 로그인된 사용자의 `userId`로 필터링되어, 남의 데이터에 접근할 수 없습니다.

## API

### 인증 `/api/auth` (인증 불필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/auth/request-code` | 가입 1단계: `{email}` → 6자리 인증코드 메일 발송 |
| POST | `/api/auth/signup` | 가입 2단계: `{email,password,name,code}` → 코드 검증 후 계정 생성, `{token,user}` (자동 로그인) |
| POST | `/api/auth/login` | 로그인 `{email,password}` → `{token,user}` |
| GET | `/api/auth/me` | 현재 로그인 사용자 (Bearer 토큰 필요) |

### 리소스 (로그인 필요)
| 리소스 | 엔드포인트 |
|--------|-----------|
| settings | `GET/PUT /api/settings` |
| diaries | `GET /api/diaries` · `PUT/DELETE /api/diaries/{dateKey}` |
| todos | `GET/POST /api/todos` · `PATCH/DELETE /api/todos/{id}` |
| notes | `GET/POST /api/notes` · `PATCH/DELETE /api/notes/{id}` |
| tracks | `GET/POST /api/tracks` · `PATCH/DELETE /api/tracks/{id}` |
| search | `GET /api/search?q=&from=&to=&types=` — 일기·할일·메모 통합 검색 |
| push | `GET /api/push/public-key` · `POST/DELETE /api/push/subscriptions` · `POST /api/push/test` |
| recordings | `POST /api/recordings` (multipart) — 회의록 녹음 → n8n 전달 |

## 프론트엔드 연동

- 인증은 stateless JWT: `/api/auth/login`·`/signup` 응답의 `token`을 저장해두고, 이후 요청에 `Authorization: Bearer <token>` 헤더로 전송.
- **슬라이딩 만료**: 수명이 절반 이하로 남은 토큰을 서버가 갱신해 `X-Renewed-Token` 응답 헤더로 돌려줍니다(사용 중에는 세션이 끊기지 않음). CORS `exposedHeaders`에 이 헤더가 등록되어 있어야 cross-origin에서 프론트가 읽을 수 있습니다.
- CORS 허용 origin은 `application.yml`의 `app.cors.allowed-origin` (dev 기본 `http://localhost:5173`).
- 로컬 개발 시 Vite `server.proxy`로 `/api`를 9090에 프록시하면 CORS 걱정 없이 상대경로로 호출 가능.
- n8n 웹훅 주소·키, VAPID 키 등 비밀 값은 프론트가 아니라 이 백엔드에만 둡니다.

## 운영 주의

- 두 프로파일 모두 JPA `ddl-auto: update`를 씁니다. 학습/개발에는 편하지만, 운영 스키마를
  Hibernate가 자동 변경하는 위험이 있어 이후 **Flyway/Liquibase 마이그레이션**으로 옮기는 것을 권장합니다.
