package com.fxlock.dashboard;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FundingRequest(String operation, String walletId, String amount,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    @Override public String toString() { return "FundingRequest[credentials redacted]"; }
}
