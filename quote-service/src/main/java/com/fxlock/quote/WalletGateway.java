package com.fxlock.quote;

import java.util.List;
import com.fxlock.quote.Models.WalletOption;

/** M2 reads eligible wallets from the shared schema; M3 rechecks them under lock. */
public interface WalletGateway {
    List<WalletOption> options(String customerId);
}
