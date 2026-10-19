package com.fxlock.quote;
import com.fxlock.quote.Models.SettlementResult;
public interface SettlementGateway { SettlementResult settle(String quoteId,String customerId,String idempotencyKey); }
