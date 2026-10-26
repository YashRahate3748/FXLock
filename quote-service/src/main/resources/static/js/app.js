'use strict';
const el = id => document.getElementById(id);
let active = null;
let remainingAtLoad = 0;
let loadedAt = 0;
let busy = false;
let walletOptions = [];
let walletsLoaded = false;

function selectedDirection() {
  return el('direction').value === 'USD-EUR'
    ? {sourceCurrency:'USD',targetCurrency:'EUR'}
    : {sourceCurrency:'EUR',targetCurrency:'USD'};
}
function populateWallets() {
  const {sourceCurrency,targetCurrency}=selectedDirection();
  let eligible=true;
  for (const [id,currency,debit] of [['source-wallet',sourceCurrency,true],['target-wallet',targetCurrency,false]]) {
    const selected=el(id).value;
    const matching=walletOptions.filter(w => w.currency===currency && (!debit || w.canDebit));
    el(id).replaceChildren(...matching.map(w => { const option=document.createElement('option'); option.value=w.walletId; option.textContent=w.label; return option; }));
    if (matching.some(w => w.walletId===selected)) el(id).value=selected;
    if (!matching.length) eligible=false;
  }
  el('create').disabled=busy || !walletsLoaded || !eligible;
}
function updateDirectionUi() {
  const {sourceCurrency,targetCurrency}=selectedDirection();
  el('direction-eyebrow').textContent=sourceCurrency+' → '+targetCurrency;
  el('amount-label').textContent='Amount in '+sourceCurrency;
  el('limit-currency').textContent=sourceCurrency;
  el('source-wallet-label').textContent='Sender '+sourceCurrency+' wallet';
  el('target-wallet-label').textContent='Destination '+targetCurrency+' wallet';
  populateWallets();
}
el('direction').addEventListener('change',updateDirectionUi);

function showQuotedWallets() {
  if (!active) return;
  for (const [field,id,currency] of [['review-source-wallet',active.sourceWalletId,active.sourceCurrency],['review-target-wallet',active.targetWalletId,active.targetCurrency]]) {
    const wallet=walletOptions.find(option => option.walletId===id && option.currency===currency);
    el(field).textContent=wallet ? wallet.label+' (#'+id+')' : 'Wallet #'+id+' · '+currency;
  }
}

const confirmedSettlements = new Map();

function showSettlementConfirmation(result) {
    el('result-text').textContent =
        'M3 confirmed settlement ' + result.settlementId +
        ': ' + result.sourceAmount +
        ' ' + active.sourceCurrency + ' converted to ' + result.targetAmount + ' ' + active.targetCurrency + '.';

    const baseUrl = document.querySelector(
        'meta[name="settlement-ui-base-url"]'
    ).content;

    const detailsUrl = new URL('/settlement.html', baseUrl || window.location.origin);
    detailsUrl.searchParams.set('settlementId', result.settlementId);

    const link = el('settlement-details-link');
    link.href = detailsUrl.toString();
    link.hidden = false;

    el('result').hidden = false;
}

function message(text) { el('message').textContent = text; el('message').hidden = false; }
async function api(path, options = {}) {
  const headers = {'Content-Type':'application/json', ...(options.headers || {})};
  headers[document.querySelector('meta[name="csrf-header"]').content] = document.querySelector('meta[name="csrf-token"]').content;
  const response = await fetch(path, {...options, headers});
  if (response.status === 401 || response.status === 403) throw new Error('Your session has ended or the security token changed. Sign in again and load your quote.');
  let body; try { body = await response.json(); } catch { throw new Error('The server did not return a result. Retry the same quote; do not assume settlement failed.'); }
  if (!response.ok) throw new Error((body.message || 'The request could not be completed.') + (body.code ? ' (' + body.code + ')' : ''));
  return body;
}
function tick() {
  if (!active) return;
  const ms = Math.max(0,remainingAtLoad - (performance.now()-loadedAt));
  const open = active.status === 'OPEN' && ms > 0;
  el('countdown').textContent = active.status === 'OPEN' ? (ms > 0 ? Math.ceil(ms/1000)+' seconds' : 'Expired') : 'Closed';
  el('quote-status').textContent = active.status === 'OPEN' && ms <= 0 ? 'EXPIRED' : active.status;
  // After expiry a previous uncertain request can still be retried: M3 decides whether it committed.
  const hasRetry = sessionStorage.getItem('fxlock:' + active.customerId + ':' + active.quoteId);
  el('accept').disabled = busy || active.status === 'SETTLED' || active.status === 'CANCELLED' || (!open && !hasRetry);
  el('accept').textContent = hasRetry ? 'Retry / check acceptance' : 'Accept quote';
  el('cancel').disabled = busy || !open;
  el('direction').disabled=busy;
  el('source-wallet').disabled=busy;
  el('target-wallet').disabled=busy;
}
function show(view) {
  active = view.quote;
  remainingAtLoad = new Date(active.expiresAt).getTime() - new Date(view.serverTime).getTime();
  loadedAt = performance.now();
  el('empty').hidden = true; el('review').hidden = false; el('result').hidden = true;
  el('target-amount').textContent = active.targetAmount;
  el('source-amount').textContent = active.sourceAmount + ' ' + active.sourceCurrency;
  el('offered-rate').textContent = '1 ' + active.sourceCurrency + ' = ' + active.offeredRate + ' ' + active.targetCurrency;
  el('review-target-currency').textContent=active.targetCurrency;
  el('quote-direction').textContent=active.sourceCurrency+' → '+active.targetCurrency;
  showQuotedWallets();
  el('confidence').textContent = active.confidence.label + ' · ' + active.confidence.score + '/100';
  el('quote-id').textContent = active.quoteId; el('lookup-id').value = active.quoteId;
  el('reasons').replaceChildren(...active.confidence.reasons.map(reason => { const li = document.createElement('li'); li.textContent = reason; return li; }));
  history.replaceState(null, '', '/?quote=' + encodeURIComponent(active.quoteId)); tick();
  const confirmed = confirmedSettlements.get(active.quoteId);

  if (confirmed) {
    showSettlementConfirmation(confirmed);
  }
}
el('quote-form').addEventListener('submit', async event => {
  event.preventDefault(); if(busy) return; busy=true; el('create').disabled = true; el('message').hidden = true;
  try {
    show(await api('/quotes', {method:'POST', body:JSON.stringify({...selectedDirection(),sourceAmount:el('amount').value,sourceWalletId:el('source-wallet').value.trim(),targetWalletId:el('target-wallet').value.trim()})}));
  } catch(error) { message(error.message); } finally { busy=false; populateWallets(); tick(); }
});
async function load(id) { if(busy) return; try { show(await api('/quotes/' + encodeURIComponent(id))); } catch(error) { message(error.message); } }
el('lookup-form').addEventListener('submit',event => { event.preventDefault(); load(el('lookup-id').value.trim()); });
el('cancel').addEventListener('click',async () => {
  if (!active || busy) return; busy = true; tick();
  try { show(await api('/quotes/' + active.quoteId + '/cancel',{method:'POST'})); message('Quote cancelled. No money has been moved by this action.'); }
  catch(error) { message(error.message); } finally { busy=false; tick(); }
});
el('accept').addEventListener('click',async () => {
  if (!active || busy) return;
  const quote = active, storageKey = 'fxlock:' + quote.customerId + ':' + quote.quoteId;
  let key = sessionStorage.getItem(storageKey);
  if (!key) { key=crypto.randomUUID(); sessionStorage.setItem(storageKey,key); }
  busy=true; el('create').disabled=true; tick(); el('message').hidden=true;
  try {
    const result=await api('/quotes/' + quote.quoteId + '/accept',{method:'POST',headers:{'Idempotency-Key':key}});
    active.status = 'SETTLED';

    confirmedSettlements.set(quote.quoteId, result);

    showSettlementConfirmation(result);
  } catch(error) { message(error.message); } finally { busy=false; populateWallets(); tick(); }
});
async function loadWallets() {
  try {
    walletOptions=await api('/wallets/quote-options');
    walletsLoaded=true;
    updateDirectionUi();
    showQuotedWallets();
  } catch(error) { message(error.message); el('create').disabled=true; }
}
loadWallets();
setInterval(tick,250);
document.addEventListener('visibilitychange',() => { if (!document.hidden && active && !busy) load(active.quoteId); });
const restored = new URLSearchParams(location.search).get('quote'); if (restored) load(restored);
