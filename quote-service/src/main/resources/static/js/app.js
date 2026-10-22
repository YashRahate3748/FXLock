'use strict';

const el = id =>
    document.getElementById(id);

let active = null;

let remainingAtLoad = 0;

let loadedAt = 0;

let busy = false;

let walletOptions = [];

const confirmedSettlements =
    new Map();


function selectedDirection() {

    const value =
        el('direction').value;

    if (value === 'USD-EUR') {

        return {
            sourceCurrency: 'USD',
            targetCurrency: 'EUR'
        };
    }

    return {
        sourceCurrency: 'EUR',
        targetCurrency: 'USD'
    };
}


function updateDirectionUi() {

    const {
        sourceCurrency,
        targetCurrency
    } = selectedDirection();

    el('direction-eyebrow').textContent =
        sourceCurrency
        + ' → '
        + targetCurrency;

    el('amount-label').textContent =
        'Amount in '
        + sourceCurrency;

    el('limit-currency').textContent =
        sourceCurrency;

    el('source-wallet-label').textContent =
        'Sender '
        + sourceCurrency
        + ' wallet';

    el('target-wallet-label').textContent =
        'Destination '
        + targetCurrency
        + ' wallet';

    populateWalletSelectors();
}


function populateWalletSelectors() {

    if (!walletOptions.length) {
        return;
    }

    const {
        sourceCurrency,
        targetCurrency
    } = selectedDirection();


    /*
     * Source:
     *
     * must match source currency
     * AND belong to logged-in customer.
     *
     * canDebit=true is supplied by backend.
     */
    const sourceWallets =
        walletOptions.filter(
            wallet =>
                wallet.currency === sourceCurrency
                && wallet.canDebit
        );


    /*
     * Destination:
     *
     * may belong to:
     *
     * - the logged-in customer
     * - another customer
     *
     * It only needs to match target currency.
     */
    const targetWallets =
        walletOptions.filter(
            wallet =>
                wallet.currency === targetCurrency
        );


    const sourceSelect =
        el('source-wallet');

    const targetSelect =
        el('target-wallet');


    sourceSelect.replaceChildren(
        ...sourceWallets.map(
            wallet => {

                const option =
                    document.createElement(
                        'option'
                    );

                option.value =
                    wallet.walletId;

                option.textContent =
                    wallet.label;

                return option;
            }
        )
    );


    targetSelect.replaceChildren(
        ...targetWallets.map(
            wallet => {

                const option =
                    document.createElement(
                        'option'
                    );

                option.value =
                    wallet.walletId;

                option.textContent =
                    wallet.label;

                return option;
            }
        )
    );


    if (!sourceWallets.length) {

        message(
            'No eligible '
            + sourceCurrency
            + ' source wallet is available.'
        );
    }


    if (!targetWallets.length) {

        message(
            'No eligible '
            + targetCurrency
            + ' destination wallet is available.'
        );
    }


    el('create').disabled =
        !sourceWallets.length
        || !targetWallets.length;
}


function showSettlementConfirmation(
    result
) {

    const sourceCurrency =
        active?.sourceCurrency
        || '';

    const targetCurrency =
        active?.targetCurrency
        || '';

    el('result-text').textContent =
        'M3 confirmed settlement '
        + result.settlementId
        + ': '
        + result.sourceAmount
        + ' '
        + sourceCurrency
        + ' converted to '
        + result.targetAmount
        + ' '
        + targetCurrency
        + '.';


    const baseUrl =
        document.querySelector(
            'meta[name="settlement-ui-base-url"]'
        ).content;


    const detailsUrl =
        new URL(
            '/settlement.html',
            baseUrl
        );


    detailsUrl.searchParams.set(
        'settlementId',
        result.settlementId
    );


    const link =
        el('settlement-details-link');

    link.href =
        detailsUrl.toString();

    link.hidden =
        false;


    el('result').hidden =
        false;
}


function message(text) {

    el('message').textContent =
        text;

    el('message').hidden =
        false;
}


async function api(
    path,
    options = {}
) {

    const headers = {
        'Content-Type': 'application/json',
        ...(options.headers || {})
    };


    const csrfHeader =
        document.querySelector(
            'meta[name="csrf-header"]'
        ).content;

    const csrfToken =
        document.querySelector(
            'meta[name="csrf-token"]'
        ).content;


    headers[csrfHeader] =
        csrfToken;


    const response =
        await fetch(
            path,
            {
                ...options,
                headers
            }
        );


    if (
        response.status === 401
        || response.status === 403
    ) {

        throw new Error(
            'Your session has ended or the security token changed. '
            + 'Sign in again and load your quote.'
        );
    }


    let body;

    try {

        body =
            await response.json();

    } catch {

        throw new Error(
            'The server did not return a result. '
            + 'Retry the same quote; do not assume settlement failed.'
        );
    }


    if (!response.ok) {

        throw new Error(
            (
                body.message
                || 'The request could not be completed.'
            )
            + (
                body.code
                    ? ' (' + body.code + ')'
                    : ''
            )
        );
    }


    return body;
}


function tick() {

    if (!active) {
        return;
    }


    const ms =
        Math.max(
            0,
            remainingAtLoad
            - (
                performance.now()
                - loadedAt
            )
        );


    const open =
        active.status === 'OPEN'
        && ms > 0;


    el('countdown').textContent =
        active.status === 'OPEN'
            ? (
                ms > 0
                    ? Math.ceil(ms / 1000)
                        + ' seconds'
                    : 'Expired'
            )
            : 'Closed';


    el('quote-status').textContent =
        active.status === 'OPEN'
        && ms <= 0
            ? 'EXPIRED'
            : active.status;


    /*
     * After expiry an uncertain previous acceptance
     * may still be retried.
     *
     * M3 determines whether it was committed.
     */
    const hasRetry =
        sessionStorage.getItem(
            'fxlock:'
            + active.customerId
            + ':'
            + active.quoteId
        );


    el('accept').disabled =
        busy
        || active.status === 'SETTLED'
        || active.status === 'CANCELLED'
        || (
            !open
            && !hasRetry
        );


    el('accept').textContent =
        hasRetry
            ? 'Retry / check acceptance'
            : 'Accept quote';


    el('cancel').disabled =
        busy
        || !open;
}


function show(view) {

    active =
        view.quote;


    remainingAtLoad =
        new Date(
            active.expiresAt
        ).getTime()
        -
        new Date(
            view.serverTime
        ).getTime();


    loadedAt =
        performance.now();


    el('empty').hidden =
        true;

    el('review').hidden =
        false;

    el('result').hidden =
        true;


    el('target-amount').textContent =
        active.targetAmount;


    el('target-currency').textContent =
        active.targetCurrency;


    el('source-amount').textContent =
        active.sourceAmount
        + ' '
        + active.sourceCurrency;


    el('quote-direction').textContent =
        active.sourceCurrency
        + ' → '
        + active.targetCurrency;


    el('offered-rate').textContent =
        '1 '
        + active.sourceCurrency
        + ' = '
        + active.offeredRate
        + ' '
        + active.targetCurrency;


    el('confidence').textContent =
        active.confidence.label
        + ' · '
        + active.confidence.score
        + '/100';


    el('quote-id').textContent =
        active.quoteId;


    el('lookup-id').value =
        active.quoteId;


    el('reasons').replaceChildren(
        ...active.confidence.reasons.map(
            reason => {

                const li =
                    document.createElement(
                        'li'
                    );

                li.textContent =
                    reason;

                return li;
            }
        )
    );


    history.replaceState(
        null,
        '',
        '/?quote='
        + encodeURIComponent(
            active.quoteId
        )
    );


    tick();


    const confirmed =
        confirmedSettlements.get(
            active.quoteId
        );


    if (confirmed) {

        showSettlementConfirmation(
            confirmed
        );
    }
}


el('direction')
    .addEventListener(
        'change',
        () => {

            updateDirectionUi();

            /*
             * Don't display a previous quote as though
             * it belongs to the newly selected direction.
             */
            el('message').hidden =
                true;
        }
    );


el('quote-form')
    .addEventListener(
        'submit',
        async event => {

            event.preventDefault();

            if (busy) {
                return;
            }


            el('create').disabled =
                true;

            el('message').hidden =
                true;


            const {
                sourceCurrency,
                targetCurrency
            } = selectedDirection();


            try {

                const view =
                    await api(
                        '/quotes',
                        {
                            method: 'POST',

                            body: JSON.stringify(
                                {
                                    sourceCurrency,
                                    targetCurrency,

                                    sourceAmount:
                                        el('amount').value,

                                    sourceWalletId:
                                        el(
                                            'source-wallet'
                                        )
                                        .value
                                        .trim(),

                                    targetWalletId:
                                        el(
                                            'target-wallet'
                                        )
                                        .value
                                        .trim()
                                }
                            )
                        }
                    );


                show(view);

            } catch (error) {

                message(
                    error.message
                );

            } finally {

                el('create').disabled =
                    false;
            }
        }
    );


async function load(id) {

    if (busy) {
        return;
    }


    try {

        show(
            await api(
                '/quotes/'
                + encodeURIComponent(id)
            )
        );

    } catch (error) {

        message(
            error.message
        );
    }
}


el('lookup-form')
    .addEventListener(
        'submit',
        event => {

            event.preventDefault();

            load(
                el('lookup-id')
                    .value
                    .trim()
            );
        }
    );


el('cancel')
    .addEventListener(
        'click',
        async () => {

            if (!active || busy) {
                return;
            }


            busy =
                true;

            tick();


            try {

                show(
                    await api(
                        '/quotes/'
                        + active.quoteId
                        + '/cancel',
                        {
                            method: 'POST'
                        }
                    )
                );


                message(
                    'Quote cancelled. '
                    + 'No money has been moved by this action.'
                );

            } catch (error) {

                message(
                    error.message
                );

            } finally {

                busy =
                    false;

                tick();
            }
        }
    );


el('accept')
    .addEventListener(
        'click',
        async () => {

            if (!active || busy) {
                return;
            }


            const quote =
                active;


            const storageKey =
                'fxlock:'
                + quote.customerId
                + ':'
                + quote.quoteId;


            let key =
                sessionStorage.getItem(
                    storageKey
                );


            if (!key) {

                key =
                    crypto.randomUUID();

                sessionStorage.setItem(
                    storageKey,
                    key
                );
            }


            busy =
                true;

            el('create').disabled =
                true;

            tick();

            el('message').hidden =
                true;


            try {

                const result =
                    await api(
                        '/quotes/'
                        + quote.quoteId
                        + '/accept',
                        {
                            method: 'POST',

                            headers: {
                                'Idempotency-Key':
                                    key
                            }
                        }
                    );


                active.status =
                    'SETTLED';


                confirmedSettlements.set(
                    quote.quoteId,
                    result
                );


                showSettlementConfirmation(
                    result
                );

            } catch (error) {

                message(
                    error.message
                );

            } finally {

                busy =
                    false;

                el('create').disabled =
                    false;

                tick();
            }
        }
    );


async function loadWallets() {

    try {

        walletOptions =
            await api(
                '/wallets/quote-options'
            );


        updateDirectionUi();

    } catch (error) {

        message(
            error.message
        );

        el('create').disabled =
            true;
    }
}


loadWallets();


setInterval(
    tick,
    250
);


document.addEventListener(
    'visibilitychange',
    () => {

        if (
            !document.hidden
            && active
            && !busy
        ) {

            load(
                active.quoteId
            );
        }
    }
);


const restored =
    new URLSearchParams(
        location.search
    ).get('quote');


if (restored) {

    load(restored);
}