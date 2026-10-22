'use strict';
(() => {
    const el = id => document.getElementById(id);
    const account = el('history-account');
    const refresh = el('history-refresh');
    const previous = el('history-previous');
    const next = el('history-next');
    const params = new URLSearchParams(location.search);
    let page = /^\d+$/.test(params.get('page') || '') ? Number(params.get('page')) : 0;
    if (!Number.isSafeInteger(page) || page > 1000000) page = 0;
    let hasAccounts = false;
    let busy = false;
    const size = 20;

    async function read(path) {
        const response = await fetch(path, {headers: {'Accept': 'application/json'}, credentials: 'same-origin'});
        if (response.status === 401 || response.status === 403) throw new Error('Your session has ended. Sign in again to view your history.');
        if (!response.ok) {
            let body;
            try { body = await response.json(); } catch { /* Return a safe message below. */ }
            throw new Error(response.status === 404 ? 'This account is not available for your login.' :
                response.status === 400 && body?.message ? body.message : 'Transaction history is temporarily unavailable. Please try again.');
        }
        if (!(response.headers.get('content-type') || '').includes('application/json')) throw new Error('Sign in again to view your history.');
        return response.json();
    }

    // Format Oracle decimal strings directly: never convert financial values to binary floating point.
    function amount(value) {
        const match = /^(\d+)(?:\.(\d+))?$/.exec(String(value));
        if (!match) return 'Unavailable';
        const whole = match[1].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        let fraction = (match[2] || '').padEnd(2, '0');
        while (fraction.length > 2 && fraction.endsWith('0')) fraction = fraction.slice(0, -1);
        return whole + '.' + fraction;
    }
    const zero = value => /^0+(?:\.0+)?$/.test(String(value));
    function cell(row, value, css) {
        const td = document.createElement('td');
        td.textContent = value;
        if (css) td.className = css;
        row.append(td);
        return td;
    }
    function showRows(entries) {
        const rows = entries.map(entry => {
            const row = document.createElement('tr');
            const date = new Date(entry.transactionDate);
            const dateCell = cell(row, '', 'history-date');
            const time = document.createElement('time');
            time.dateTime = entry.transactionDate;
            time.textContent = date.toLocaleDateString(undefined, {day:'2-digit',month:'short',year:'numeric'});
            time.title = entry.transactionDate;
            const clock = document.createElement('small');
            clock.textContent = date.toLocaleTimeString(undefined, {hour:'2-digit',minute:'2-digit',timeZoneName:'short'});
            dateCell.append(time, clock);
            cell(row, entry.customerName);
            cell(row, 'Wallet ' + entry.walletId);
            const currency = document.createElement('span');currency.className = 'history-currency';currency.textContent = entry.currency;
            cell(row, '').append(currency);
            cell(row, zero(entry.amountCredited) ? '—' : amount(entry.amountCredited), 'amount credit');
            cell(row, zero(entry.amountDebited) ? '—' : amount(entry.amountDebited), 'amount debit');
            cell(row, amount(entry.updatedBalance), 'amount balance');
            cell(row, entry.settlementId, 'history-reference');
            return row;
        });
        el('history-rows').replaceChildren(...rows);
    }
    function failure(error) {
        el('history-message').textContent = error.message;
        el('history-message').className = 'error';
        el('history-table-wrap').hidden = true;
        el('history-empty').hidden = true;
        el('history-pagination').hidden = true;
        el('history-count').textContent = '';
    }
    async function load() {
        if (busy) return;
        busy = true; account.disabled = true; refresh.disabled = true; previous.disabled = true; next.disabled = true;
        el('history-message').className = '';
        el('history-message').textContent = 'Loading transaction history…';
        el('history-table-wrap').hidden = true;
        el('history-empty').hidden = true;
        el('history-pagination').hidden = true;
        try {
            const query = new URLSearchParams({page: String(page), size: String(size)});
            if (account.value) query.set('walletId', account.value);
            const result = await read('/api/transaction-history?' + query);
            showRows(result.entries);
            el('history-count').textContent = result.total + (result.total === 1 ? ' entry' : ' entries');
            el('history-message').textContent = result.entries.length ? 'Newest entries first. Times are shown in your local time zone.' : '';
            el('history-table-wrap').hidden = result.entries.length === 0;
            el('history-empty').hidden = result.entries.length !== 0;
            el('history-empty').querySelector('h3').textContent = result.total === 0 ? 'No transactions yet' : 'No entries on this page';
            el('history-empty').querySelector('p').textContent = result.total === 0 ?
                'This account has no completed wallet transactions. Creating or cancelling a quote does not move money.' :
                'Use Previous or refresh the history to return to the available entries.';
            el('history-pagination').hidden = result.total <= size && page === 0;
            el('history-page-label').textContent = result.entries.length ?
                'Showing ' + (page * size + 1) + '–' + (page * size + result.entries.length) + ' of ' + result.total + ' entries' : 'Page ' + (page + 1);
            previous.disabled = page === 0;
            next.disabled = !result.hasNext;
            const url = new URL(location.href);url.search = '';
            if (account.value) url.searchParams.set('walletId', account.value);
            if (page) url.searchParams.set('page', String(page));
            history.replaceState(null, '', url);
        } catch (error) { failure(error); }
        finally { busy = false; account.disabled = !hasAccounts; refresh.disabled = false; }
    }
    account.addEventListener('change', () => { page = 0; load(); });
    el('history-filters').addEventListener('submit', event => { event.preventDefault(); load(); });
    previous.addEventListener('click', () => { if (!busy && page > 0) { page--; load(); } });
    next.addEventListener('click', () => { if (!busy) { page++; load(); } });
    async function start() {
        try {
            const accounts = await read('/api/transaction-history/accounts');
            account.replaceChildren(new Option('All my accounts', ''));
            accounts.forEach(wallet => account.add(new Option(wallet.currency + ' wallet · ' + wallet.walletId +
                (wallet.status === 'ACTIVE' ? '' : ' · ' + wallet.status.toLowerCase()), wallet.walletId)));
            hasAccounts = accounts.length > 0;
            const selected = params.get('walletId');
            if (selected && !accounts.some(wallet => wallet.walletId === selected)) throw new Error('This account is not available for your login.');
            account.value = selected || '';
            await load();
        } catch (error) { failure(error); refresh.disabled = false; }
    }
    start();
})();
