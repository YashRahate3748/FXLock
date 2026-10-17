package com.ofss.fxlock.rate.repository;

import com.ofss.fxlock.rate.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, Long> {
}