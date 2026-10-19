"use strict";

const elements = {
    walletList: document.getElementById("walletList"),
    walletMessage: document.getElementById("walletMessage"),
    refreshButton: document.getElementById("refreshButton"),

    lookupForm: document.getElementById("lookupForm"),
    lookupInput: document.getElementById("settlementId"),
    lookupButton: document.getElementById("lookupButton"),
    settlementMessage: document.getElementById("settlementMessage"),
    settlementDetails: document.getElementById("settlementDetails"),

    acceptForm: document.getElementById("acceptForm"),
    quoteInput: document.getElementById("acceptQuoteId"),
    acceptButton: document.getElementById("acceptButton"),
    acceptMessage: document.getElementById("acceptMessage"),
    acceptResult: document.getElementById("acceptResult"),
    newAcceptanceButton: document.getElementById("newAcceptanceButton")
};

const STORAGE_KEY = "fxlock.local.CUST001.acceptance.v1";
const REQUEST_TIMEOUT_MS = 15000;

let acceptance = null;
let acceptanceBusy = false;
let storageReady = true;

function showMessage(element, text, isError = false) {
    element.textContent = text;
    element.classList.toggle("error", isError);
}

function formatMoney(amount, currency) {
    return new Intl.NumberFormat("en-US", {
        style: "currency",
        currency,
        minimumFractionDigits: 2,
        maximumFractionDigits: 4
    }).format(amount);
}

async function requestJson(url, options = {}) {
    const controller = new AbortController();

    const timer = setTimeout(
        () => controller.abort(),
        REQUEST_TIMEOUT_MS
    );

    try {
        const response = await fetch(url, {
            ...options,
            signal: controller.signal,
            cache: "no-store",

            headers: {
                Accept: "application/json",
                ...options.headers
            }
        });

        if (!response.ok) {
            let body = {};

            try {
                body = await response.json();
            } catch {
                // A proxy or server may return a non-JSON error.
            }

            const error = new Error(
                body.message || `Request failed: HTTP ${response.status}`
            );

            error.status = response.status;
            error.code = body.code || "HTTP_ERROR";

            throw error;
        }

        return await response.json();

    } finally {
        clearTimeout(timer);
    }
}

async function loadWallets() {
    elements.refreshButton.disabled = true;
    showMessage(elements.walletMessage, "Loading balances…");

    try {
        const wallets = await requestJson("/api/v1/wallets");

        elements.walletList.replaceChildren();

        for (const wallet of wallets) {
            const card = document.createElement("article");
            card.className = "wallet";

            const currency = document.createElement("h3");
            currency.textContent = wallet.currencyCode;

            const balance = document.createElement("p");
            balance.className = "balance";
            balance.textContent = formatMoney(
                wallet.balance,
                wallet.currencyCode
            );

            const id = document.createElement("p");
            id.className = "muted";
            id.textContent = "Wallet ID: " + wallet.walletId;

            card.append(currency, balance, id);
            elements.walletList.append(card);
        }

        showMessage(
            elements.walletMessage,
            wallets.length
                ? "Balances refreshed at " + new Date().toLocaleTimeString()
                : "No wallets found."
        );
    } catch (error) {
        elements.walletList.replaceChildren();

        showMessage(
            elements.walletMessage,
            "Could not load balances. Check the service and try Refresh.",
            true
        );
    } finally {
        elements.refreshButton.disabled = false;
    }
}

function addSettlementDetail(label, value) {
    const term = document.createElement("dt");
    term.textContent = label;

    const description = document.createElement("dd");
    description.textContent = value;

    elements.settlementDetails.append(term, description);
}

async function lookupSettlement(event) {
    event.preventDefault();

    const id = elements.lookupInput.value.trim();

    if (!id) {
        showMessage(
            elements.settlementMessage,
            "Enter a settlement ID.",
            true
        );
        return;
    }

    elements.lookupButton.disabled = true;
    elements.settlementDetails.hidden = true;
    elements.settlementDetails.replaceChildren();

    showMessage(elements.settlementMessage, "Loading settlement…");

    try {
        const settlement = await requestJson(
            "/api/v1/settlements/" + encodeURIComponent(id)
        );

        addSettlementDetail("Settlement ID", settlement.settlementId);
        addSettlementDetail("Quote ID", settlement.quoteId);
        addSettlementDetail("Status", settlement.status);

        addSettlementDetail(
            "Source amount",
            formatMoney(
                settlement.sourceAmount,
                settlement.sourceCurrency
            )
        );

        addSettlementDetail(
            "Target amount",
            formatMoney(
                settlement.targetAmount,
                settlement.targetCurrency
            )
        );

        elements.settlementDetails.hidden = false;

        showMessage(
            elements.settlementMessage,
            "Settlement details loaded."
        );
    } catch (error) {
        showMessage(
            elements.settlementMessage,
            error.status === 404
                ? "Settlement not found for the current customer."
                : "Could not load settlement details. Please try again.",
            true
        );
    } finally {
        elements.lookupButton.disabled = false;
    }
}

function isTerminalAcceptance() {
    return acceptance !== null &&
        ["completed", "rejected"].includes(acceptance.status);
}

function renderAcceptance() {
    elements.quoteInput.disabled =
        acceptanceBusy || acceptance !== null;

    if (acceptance) {
        elements.quoteInput.value = acceptance.quoteId;
    }

    elements.acceptButton.disabled =
        !storageReady || acceptanceBusy || isTerminalAcceptance();

    elements.acceptButton.textContent = acceptanceBusy
        ? "Processing…"
        : acceptance
            ? "Retry same acceptance"
            : "Accept quote";

    elements.newAcceptanceButton.hidden = !isTerminalAcceptance();
    elements.newAcceptanceButton.disabled = acceptanceBusy;

    elements.acceptResult.textContent =
        acceptance?.status === "completed"
            ? "Settlement ID: " + acceptance.settlementId
            : "";
}

function prepareAcceptance(attempt) {
    // Persist the key before sending the financial request.
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt));
    acceptance = attempt;
}

function rememberAcceptance(attempt) {
    acceptance = attempt;

    try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt));
    } catch (error) {
        // The original key was already saved before submission.
        console.warn("Could not update acceptance state.", error);
    }
}

function restoreAcceptance() {
    try {
        const saved = sessionStorage.getItem(STORAGE_KEY);

        if (!saved) {
            return;
        }

        const attempt = JSON.parse(saved);

        if (
            typeof attempt.quoteId !== "string" ||
            !attempt.quoteId.trim() ||
            attempt.quoteId.length > 36 ||
            typeof attempt.key !== "string" ||
            !attempt.key.trim() ||
            attempt.key.length > 200 ||
            !["pending", "uncertain", "completed", "rejected"]
                .includes(attempt.status) ||
            (
                attempt.status === "completed" &&
                typeof attempt.settlementId !== "string"
            )
        ) {
            throw new Error("Invalid saved acceptance");
        }

        acceptance = attempt;

        if (["pending", "uncertain"].includes(attempt.status)) {
            acceptance.status = "uncertain";

            showMessage(
                elements.acceptMessage,
                "A previous acceptance has an unconfirmed outcome. " +
                "Retry using the saved quote and key."
            );
        } else if (attempt.status === "completed") {
            showMessage(
                elements.acceptMessage,
                "Previous acceptance completed successfully."
            );

            elements.lookupInput.value = attempt.settlementId;
        } else {
            showMessage(
                elements.acceptMessage,
                "The previous request was rejected. " +
                "You can start another acceptance.",
                true
            );
        }
    } catch (error) {
        storageReady = false;

        showMessage(
            elements.acceptMessage,
            "Could not restore acceptance state. Resolve any previous " +
            "request before starting another acceptance.",
            true
        );
    }
}

async function submitAcceptance(event) {
    event.preventDefault();

    if (acceptanceBusy || !storageReady || isTerminalAcceptance()) {
        return;
    }

    if (!acceptance) {
        const quoteId = elements.quoteInput.value.trim();

        if (!quoteId || quoteId.length > 36) {
            showMessage(
                elements.acceptMessage,
                "Enter a valid quote ID.",
                true
            );
            return;
        }

        if (!window.confirm("Accept the stored offer for this quote?")) {
            return;
        }

        try {
            prepareAcceptance({
                quoteId,
                key: crypto.randomUUID(),
                status: "pending"
            });
        } catch (error) {
            showMessage(
                elements.acceptMessage,
                "Could not save a retry key. No request was sent.",
                true
            );
            return;
        }
    }

    acceptanceBusy = true;
    renderAcceptance();

    showMessage(elements.acceptMessage, "Submitting acceptance…");

    try {
        const result = await requestJson("/api/v1/settlements", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Idempotency-Key": acceptance.key
            },
            body: JSON.stringify({
                quoteId: acceptance.quoteId
            })
        });

        if (
            result.status !== "COMPLETED" ||
            result.quoteId !== acceptance.quoteId ||
            typeof result.settlementId !== "string"
        ) {
            throw new Error("Unexpected settlement response");
        }

        rememberAcceptance({
            ...acceptance,
            status: "completed",
            settlementId: result.settlementId
        });

        showMessage(
            elements.acceptMessage,
            "Conversion completed. Debited " +
            formatMoney(result.sourceAmount, result.sourceCurrency) +
            " and credited " +
            formatMoney(result.targetAmount, result.targetCurrency) +
            "."
        );

        elements.lookupInput.value = result.settlementId;
        elements.settlementDetails.hidden = true;
        elements.settlementDetails.replaceChildren();

        showMessage(
            elements.settlementMessage,
            "Click View details to inspect this settlement."
        );
    } catch (error) {
        if ([400, 401, 403, 404, 409, 422].includes(error.status)) {
            rememberAcceptance({
                ...acceptance,
                status: "rejected"
            });

			showMessage(
			    elements.acceptMessage,
			    error.message,
			    true
			);
        } else {
            rememberAcceptance({
                ...acceptance,
                status: "uncertain"
            });

            showMessage(
                elements.acceptMessage,
                "Outcome unconfirmed. The conversion may have committed. " +
                "Use Retry same acceptance to reuse the original key.",
                true
            );
        }
    } finally {
        acceptanceBusy = false;
        renderAcceptance();
    }

    await loadWallets();
}

function startAnotherAcceptance() {
    if (acceptanceBusy || !isTerminalAcceptance()) {
        return;
    }

    try {
        sessionStorage.removeItem(STORAGE_KEY);
    } catch (error) {
        showMessage(
            elements.acceptMessage,
            "Could not clear the previous acceptance state.",
            true
        );
        return;
    }

    acceptance = null;
    elements.quoteInput.value = "";

    showMessage(elements.acceptMessage, "");
    renderAcceptance();
    elements.quoteInput.focus();
}

elements.refreshButton.addEventListener("click", loadWallets);
elements.lookupForm.addEventListener("submit", lookupSettlement);
elements.acceptForm.addEventListener("submit", submitAcceptance);
elements.newAcceptanceButton.addEventListener(
    "click",
    startAnotherAcceptance
);

restoreAcceptance();
renderAcceptance();
loadWallets();