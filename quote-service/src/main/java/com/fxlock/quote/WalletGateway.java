package com.fxlock.quote;

import java.util.List;
import com.fxlock.quote.Models.WalletOption;

/** M3 supplies eligible accounts; M2 checks selection and M3 rechecks under lock. */
public interface WalletGateway {
    List<WalletOption> options(String customerId);
}
