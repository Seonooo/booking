이 프로젝트의 변경 사항을 리뷰하라.

먼저 다음 문서들을 읽어라:
- `/CLAUDE.md`
- `/docs/adr/DECISIONS.md`
- 관련 ADR 파일들 (`/docs/adr/ADR-00N-*.md`)

그런 다음 변경된 파일들을 확인하고, 아래 체크리스트로 검증하라.

## 체크리스트

1. **ADR 준수**: 각 구현이 해당 ADR의 결정(Accepted 상태)을 따르고 있는가? Deprecated된 ADR-001 패턴(큐 ZSET)을 사용하지 않았는가?
2. **기술 스택 준수**: Spring Boot, Redis (Lua Script), PostgreSQL/MySQL, Resilience4j — ADR에 정의된 스택을 벗어나지 않았는가?
3. **CRITICAL 규칙 준수** (CLAUDE.md 기준):
   - PG 호출이 DB 트랜잭션 **밖**에 있는가? (ADR-009)
   - Redis 원자 연산에 Lua Script를 사용했는가? WATCH/MULTI/EXEC를 쓰지 않았는가? (ADR-002)
   - 멱등성 키가 UUID이고, body 검증은 SHA256 해시로 분리되어 있는가? (ADR-006)
   - Redis 장애 시 Fail-Closed (503) 처리가 되어 있는가? Fail-Open으로 처리하지 않았는가? (ADR-007)
   - @Scheduled Outbox 폴러에 분산 락이 적용되어 있는가? (ADR-010)
4. **테스트 존재**: 동시성 테스트가 필요한 영역(재고 카운터, 멱등성, Lua atomic)에 단순 단위 테스트만 있지 않은가?
5. **빌드 가능**: 빌드 명령어가 에러 없이 통과하는가?

## 출력 형식

| 항목 | 결과 | 비고 |
|------|------|------|
| ADR 준수 | ✅/❌ | {위반된 ADR 번호와 내용} |
| 기술 스택 준수 | ✅/❌ | {상세} |
| CRITICAL 규칙 준수 | ✅/❌ | {위반 항목} |
| 테스트 존재 | ✅/❌ | {누락된 테스트 영역} |
| 빌드 가능 | ✅/❌ | {에러 내용} |

위반 사항이 있으면 해당 ADR 번호를 명시하고 수정 방안을 구체적으로 제시하라.
