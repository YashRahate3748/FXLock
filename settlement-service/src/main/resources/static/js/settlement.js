"use strict";

const elements = {
    form: document.getElementById("lookupForm"),
    input: document.getElementById("settlementId"),
    refresh: document.getElementById("refreshButton"),
    message: document.getElementById("message"),
    receipt: document.getElementById("receipt"),
    ledgerRows: document.getElementById("ledgerRows"),
    ledgerMessage: document.getElementById("ledgerMessage")
};

const moneyFormatter = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4
});

const rateFormatter = new Intl.NumberFormat(undefined, {
    maximumFractionDigits: 10
});

let loadedSettlementId = "";
let loading = false;

function setText(id, value) {
    document.getElementById(id).textContent = value;
}

function money(value, currency) {
    if (value === null || value === undefined
            || !Number.isFinite(Number(value))) {
        return "Unavailable";
    }

    return `${moneyFormatter.format(Number(value))} ${currency}`;
}

function dateTime(value) {
    if (!value) {
        return "Not recorded";
    }

    const date = new Date(value);

    return Number.isNaN(date.getTime())
        ? "Unavailable"
        : date.toLocaleString(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            timeZoneName: "short"
        });
}

function rate(value, source, target) {
    if (value === null || value === undefined
            || !Number.isFinite(Number(value))) {
        return "Unavailable";
    }

    return `1 ${source} = ${rateFormatter.format(Number(value))} ${target}`;
}

function showMessage(text, isError = false) {
    elements.message.textContent = text;
    elements.message.classList.toggle("error", isError);
}

function addCell(row, text, className = "") {
    const cell = document.createElement("td");
    cell.textContent = text;

    if (className) {
        cell.className = className;
    }

    row.appendChild(cell);
}

function renderLedger(entries) {
    elements.ledgerRows.replaceChildren();

    for (const entry of entries) {
        const row = document.createElement("tr");

        const entryClass = entry.entryType === "DEBIT"
            ? "entry-debit"
            : entry.entryType === "CREDIT"
                ? "entry-credit"
                : "";

        addCell(row, entry.entryType, entryClass);
        addCell(row, String(entry.walletId));
        addCell(row, money(entry.amount, entry.currencyCode));
        addCell(row, money(entry.balanceBefore, entry.currencyCode));
        addCell(row, money(entry.balanceAfter, entry.currencyCode));
        addCell(row, money(
            entry.currentWalletBalance,
            entry.currencyCode
        ));
        addCell(row, dateTime(entry.recordedAt));

        elements.ledgerRows.appendChild(row);
    }

    elements.ledgerMessage.textContent = entries.length === 0
        ? "No ledger entries were returned for this settlement."
        : `${entries.length} recorded wallet movements.`;
}

function renderReceipt(data) {
    setText("status", data.status);

    document.getElementById("status").classList.toggle(
        "completed",
        data.status === "COMPLETED"
    );

    setText("sourceAmount", money(
        data.sourceAmount,
        data.sourceCurrency
    ));

    setText("targetAmount", money(
        data.targetAmount,
        data.targetCurrency
    ));

    setText("receiptSettlementId", data.settlementId);
    setText("quoteId", data.quoteId);
    setText("completedAt", dateTime(data.completedAt));

    setText("currencyPair",
        `${data.sourceCurrency} → ${data.targetCurrency}`);

    setText("marketRate", rate(
        data.marketRateWhenQuoted,
        data.sourceCurrency,
        data.targetCurrency
    ));

    setText("customerRate", rate(
        data.lockedCustomerRate,
        data.sourceCurrency,
        data.targetCurrency
    ));

    setText("spread",
        data.appliedSpreadBps == null
            ? "Unavailable"
            : `${rateFormatter.format(Number(data.appliedSpreadBps))} bps`);

    setText("quoteCreatedAt", dateTime(data.quoteCreatedAt));
    setText("quoteExpiresAt", dateTime(data.quoteExpiresAt));

    const debitEntry = data.ledgerEntries.find(
    entry => entry.entryType === "DEBIT"
);

const creditEntry = data.ledgerEntries.find(
    entry => entry.entryType === "CREDIT"
);

setText(
    "sourceWalletLabel",
    `Source wallet · ${data.sourceCurrency}`
);

setText(
    "targetWalletLabel",
    `Target wallet · ${data.targetCurrency}`
);

setText(
    "sourceWalletBalance",
    money(debitEntry?.currentWalletBalance, data.sourceCurrency)
);

setText(
    "targetWalletBalance",
    money(creditEntry?.currentWalletBalance, data.targetCurrency)
);

    renderLedger(data.ledgerEntries);
    elements.receipt.hidden = false;
}

async function loadSettlement(rawId) {
    if (loading) {
        return;
    }

    const id = rawId.trim();

    if (!id || id.length > 36) {
        showMessage("Enter a settlement ID of up to 36 characters.", true);
        return;
    }

    loading = true;
    loadedSettlementId = "";
    elements.receipt.hidden = true;
    elements.refresh.disabled = true;
    elements.input.disabled = true;
    elements.form.querySelector("button").disabled = true;

    showMessage("Loading settlement details…");

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);

    try {
        const response = await fetch(
            `/api/v1/settlements/${encodeURIComponent(id)}/details`,
            {
                method: "GET",
                headers: { "Accept": "application/json" },
                cache: "no-store",
                signal: controller.signal
            }
        );

        let data;

        try {
            data = await response.json();
        } catch {
            throw new Error("The service returned an unreadable response.");
        }

        if (!response.ok) {
            throw new Error(
                data.message || `Could not load settlement (${response.status}).`
            );
        }

        if (data.settlementId !== id
                || !Array.isArray(data.ledgerEntries)) {
            throw new Error("The service returned unexpected settlement details.");
        }

        renderReceipt(data);
        loadedSettlementId = id;
        elements.input.value = id;

        const url = new URL(window.location.href);
        url.searchParams.set("settlementId", id);
        window.history.replaceState(null, "", url);

        showMessage(
            `Details loaded. Current wallet balances checked at ${
                new Date().toLocaleTimeString()
            }.`
        );
    } catch (error) {
        showMessage(
            error.name === "AbortError"
                ? "Loading timed out. Try again."
                : error.message || "Unable to reach the settlement service.",
            true
        );
    } finally {
        clearTimeout(timeout);
        loading = false;
        elements.input.disabled = false;
        elements.form.querySelector("button").disabled = false;
        elements.refresh.disabled = !loadedSettlementId;
    }
}

elements.form.addEventListener("submit", event => {
    event.preventDefault();
    loadSettlement(elements.input.value);
});

elements.refresh.addEventListener("click", () => {
    if (loadedSettlementId) {
        loadSettlement(loadedSettlementId);
    }
});

const initialId = new URL(window.location.href)
    .searchParams.get("settlementId");

if (initialId) {
    elements.input.value = initialId;
    loadSettlement(initialId);
} else {
    showMessage("Enter a settlement ID to view its financial receipt.");
}