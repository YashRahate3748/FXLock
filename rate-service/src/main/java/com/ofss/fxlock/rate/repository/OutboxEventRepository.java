package com.ofss.fxlock.rate.repository;

import com.ofss.fxlock.rate.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, String> {
}