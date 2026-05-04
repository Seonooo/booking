package com.booking.infrastructure.scheduler;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock {@link LockProvider} Bean 등록 (ADR-010 / ADR-011).
 *
 * <p>{@code shedlock} 테이블 (V1__init.sql) 위에서 분산 락 제공. Outbox 폴러 (feature-005) /
 * TTL sweeper (feature-006) / Reconciliation worker (feature-007) 등 모든
 * {@code @Scheduled + @SchedulerLock} 컴포넌트가 본 LockProvider 사용.
 *
 * <p>{@code .usingDbTime()} 옵션 — DB 시간 기준 락 만료 (인스턴스 간 시계 sync 무관).
 */
@Configuration
public class ShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
